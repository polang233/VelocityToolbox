package io.github.polang233.velocitytoolbox.pack.http;

import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import io.github.polang233.velocitytoolbox.pack.config.HostLimits;
import io.github.polang233.velocitytoolbox.pack.host.HostedPack;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 验证请求额度、文件边界和真实网络下的下载清理。 */
public final class HttpSecurityTest {
    private static Path directory;
    public static void main(String[] args) throws Exception {
        directory = Files.createTempDirectory("vtb-http-security-");
        try {
            limits();
            switchingLimits();
            addresses();
            tickets();
            http();
            switchingHttp();
            transfers();
            bandwidth();
            System.out.println("HTTP security tests passed: rate/concurrency, proxies, files, cache, deadlines and cleanup.");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static HostLimits config(String yaml) throws Exception {
        Path file = directory.resolve("security.yml");
        Files.writeString(file, yaml);
        return HostLimits.read(ResourceFiles.loadYaml(file));
    }

    private static HostLimits internal(int globalRate, int burst, int clients, int idle) {
        return new HostLimits(2, 1, globalRate, 120, burst, clients, idle, 16384, 60, 0, 0, false, List.of(), 2, 5);
    }

    private static void limits() throws Exception {
        var global = new RequestLimits(internal(1, 20, 10000, 300));
        check(global.request("first") == 0 && global.request("second") == 503, "global request rate covers different IPs");
        AtomicLong clock = new AtomicLong();
        var guard = new RequestLimits(internal(200, 2, 2, 1), clock::get);
        check(guard.request("a") == 0 && guard.request("a") == 0 && guard.request("a") == 429, "IP burst");
        var first = guard.download("a");
        check(first != null && guard.download("a") == null, "IP concurrency");
        check(guard.request("b") == 0, "independent IP");
        var second = guard.download("b");
        check(second != null && guard.active() == 2, "global concurrency");
        check(guard.request("c") == 503 && guard.clientCount() == 2, "bounded IP map");
        first.close(); first.close();
        check(guard.active() == 1, "idempotent release");
        clock.set(TimeUnit.SECONDS.toNanos(2));
        check(guard.request("c") == 0 && guard.clientCount() == 2, "expired idle entry reclaimed, active entry retained");
        guard.update(config("max-downloads: 1\nmax-downloads-per-ip: 1\n"));
        check(guard.download("c") == null, "reload retains active slots");
        second.close();
        check(guard.active() == 0, "all slots released");
        for (String invalid : List.of("max-downloads: 0", "bandwidth-mib: 1.5",
                "trusted-proxies: invalid", "unknown: true", "show-index: true",
                "burst-per-ip: 1", "requests-per-second: 50", "download-retries: 0")) {
            try { config(invalid); throw new AssertionError("invalid security config: " + invalid); }
            catch (java.io.IOException expected) { }
        }
        try {
            config("show-index: true\nmax-request-bytes: 999999\ndownload-retries: 0\n");
            throw new AssertionError("obsolete security keys must fail load");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("obsolete"), "obsolete keys named in load error");
        }
    }

    /** 切服时 HEAD、GET 和旧包下载可短时重叠，但同一 IP 仍受硬上限约束。 */
    private static void switchingLimits() throws Exception {
        HostLimits defaults = HostLimits.defaults();
        check(config("{}").equals(defaults), "parsed and programmatic defaults agree");
        AtomicLong clock = new AtomicLong();
        var guard = new RequestLimits(defaults, clock::get);
        for (int i = 0; i < 40; i++)
            check(guard.request("switching-player") == 0, "short server-switch burst: " + i);
        check(guard.request("switching-player") == 429, "sustained request flood still limited");
        var leases = new ArrayList<RequestLimits.Lease>();
        try {
            for (int i = 0; i < 12; i++) {
                var lease = guard.download("switching-player");
                check(lease != null, "old and new pack downloads may overlap: " + i);
                leases.add(lease);
            }
            check(guard.download("switching-player") == null, "parallel download flood still limited");
            check(guard.request("another-player") == 0, "another IP keeps its allowance");
            clock.set(TimeUnit.SECONDS.toNanos(1));
            check(guard.request("switching-player") == 0 && guard.request("switching-player") == 0
                    && guard.request("switching-player") == 429, "refill remains two requests per second");
        } finally {
            leases.forEach(RequestLimits.Lease::close);
        }
        check(guard.active() == 0, "switching slots released");
    }

    private static void addresses() throws Exception {
        InetAddress peer = InetAddress.getByName("127.0.0.1");
        check(new ClientAddress(List.of()).resolve(peer, "198.51.100.9").equals("127.0.0.1"), "untrusted forwarded ignored");
        ClientAddress trusted = new ClientAddress(List.of("127.0.0.1/32", "10.0.0.0/8"));
        check(trusted.resolve(peer, "203.0.113.99, 198.51.100.9, 10.0.0.1").equals("198.51.100.9"), "first untrusted hop");
        check(trusted.resolve(peer, "example.com").equals("127.0.0.1"), "no DNS from headers");
        check(trusted.resolve(peer, "1.2.3").equals("127.0.0.1"), "reject ambiguous IPv4");
        check(new ClientAddress(List.of("::1/128")).resolve(InetAddress.getByName("::1"), "2001:db8::1")
                .equals(InetAddress.getByName("2001:db8::1").getHostAddress()), "IPv6");
        for (String invalid : List.of("example.com", "127.0.0.1/33", "0.0.0.0/-1", "1.2.3")) {
            try { new ClientAddress(List.of(invalid)); throw new AssertionError("invalid CIDR"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    private static HostedPack pack(Path path, String url) throws Exception {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return new HostedPack(path.getFileName().toString(), path.toRealPath(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(path))),
                url, attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
    }

    private static int port() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); }
    }
    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6)).GET().build();
    }
    private static int status(HttpClient client, HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String encodeName(String name) {
        return java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String ticketId(DownloadTickets.Ticket ticket) {
        int index = ticket.url().indexOf("vtb=");
        return ticket.url().substring(index + 4);
    }

    /** 签发侧 URI.create 会解码路径；部分请求 URI 仍带着百分号编码，两边都要归一成文件名。 */
    private static void tickets() throws Exception {
        var tickets = new DownloadTickets();
        String name = "测试 pack.zip";
        String encoded = "http://127.0.0.1/packs/" + encodeName(name);
        try (var ticket = tickets.issue(encoded, 60000)) {
            URI request = new URI(null, null, "/packs/" + encodeName(name), "vtb=" + ticketId(ticket), null);
            tickets.limited(request);
            check(ticket.limited(), "percent-encoded request path matches issued Unicode/space zip");
        }
        String spaced = "http://127.0.0.1/packs/" + encodeName("my pack.zip");
        try (var ticket = tickets.issue(spaced, 60000)) {
            tickets.limited(URI.create(ticket.url()));
            check(ticket.limited(), "decoded issued URL still matches itself");
        }
        try (var ticket = tickets.issue(encoded, 60000)) {
            tickets.limited(URI.create("http://127.0.0.1/packs/" + encodeName("other.zip") + "?vtb=" + ticketId(ticket)));
            check(!ticket.limited(), "different filename does not mark the ticket");
            tickets.begin(URI.create("http://127.0.0.1/packs/other.zip?vtb=" + ticketId(ticket)), true).response(404);
            check(ticket.diagnostic().stage() == DownloadTickets.Stage.NOT_REQUESTED, "different file cannot set diagnostics");
        }
        try (var ticket = tickets.issue(encoded, 60000)) {
            var old = tickets.begin(URI.create(ticket.url()), true);
            var latest = tickets.begin(URI.create(ticket.url()), true);
            latest.sending(12);
            latest.wrote(12);
            latest.complete();
            old.failed(DownloadTickets.Stage.TIMED_OUT, "old connection timed out");
            check(ticket.diagnostic().stage() == DownloadTickets.Stage.SENT && ticket.diagnostic().bytes() == 12,
                    "old connection failure cannot overwrite latest request diagnostics");
        }
    }

    private static void http() throws Exception {
        Path root = Files.createDirectories(directory.resolve("files")).toRealPath();
        Path file = root.resolve("sample.zip");
        Files.writeString(file, "original bytes");
        String origin = "http://127.0.0.1:" + port();
        String url = origin + "/packs/sample.zip";
        HostedPack original = pack(file, url);
        HostLimits limits = config("requests-per-minute-per-ip: 600\n");
        var tickets = new DownloadTickets();
        try (HttpClient client = HttpClient.newHttpClient();
             var server = new PackHttpServer(root, Map.of("sample.zip", original), limits, tickets, message -> {})) {
            server.bind("127.0.0.1", URI.create(origin).getPort());
            check(status(client, request(origin + "/")) == 404, "index off");
            check(status(client, request(url)) == 200, "file download");
            try (var ticket = tickets.issue(url, 60000)) {
                check(status(client, request(ticket.url())) == 200, "tracked download succeeds");
                awaitStage(ticket, DownloadTickets.Stage.SENT);
                check(ticket.diagnostic().status() == 200 && ticket.diagnostic().bytes() == original.size()
                        && ticket.diagnostic().expectedBytes() == original.size(), "actual bytes and status recorded");
                check(status(client, HttpRequest.newBuilder(URI.create(ticket.url()))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build()) == 200, "tracked HEAD succeeds");
                check(ticket.diagnostic().stage() == DownloadTickets.Stage.SENT, "HEAD does not replace GET diagnostics");
                server.update(root, Map.of(), limits);
                check(status(client, request(ticket.url())) == 404 && ticket.diagnostic().status() == 404,
                        "file missing is recorded before returning failure");
                server.update(root, Map.of("sample.zip", original), limits);
            }
            String etag = "\"" + original.sha1() + "\"";
            check(status(client, HttpRequest.newBuilder(URI.create(url)).header("If-None-Match", etag).build()) == 304, "matching ETag");
            check(status(client, HttpRequest.newBuilder(URI.create(url)).header("If-None-Match", "\"prefix" + original.sha1() + "\"").build()) == 200,
                    "no substring ETag match");
            check(status(client, HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build()) == 200, "HEAD");
            for (String path : List.of("/packs/../secret.zip", "/packs/%2e%2e%2fsecret.zip", "/packs/sample.zip/extra", "/packs/%00.zip"))
                check(status(client, request(origin + path)) == 404, "invalid path: " + path);
            check(status(client, HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build()) == 405, "method");
            check(status(client, HttpRequest.newBuilder(URI.create(url)).header("X-Pad", "a".repeat(20000)).build()) == 431, "header size");
            Path outside = directory.resolve("outside.zip");
            Files.writeString(outside, "private");
            server.update(root, Map.of("sample.zip", original, "outside.zip", pack(outside, origin + "/packs/outside.zip")), limits);
            check(status(client, request(origin + "/packs/outside.zip")) == 409, "outside file rejected");
            Path link = root.resolve("link.zip");
            try {
                Files.createSymbolicLink(link, file);
                var linked = new HostedPack("link.zip", link, original.sha1(), origin + "/packs/link.zip",
                        original.size(), original.modified(), original.fileKey());
                server.update(root, Map.of("sample.zip", original, "link.zip", linked), limits);
                check(status(client, request(origin + "/packs/link.zip")) == 409, "symbolic link rejected even inside root");
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
                System.out.println("Symbolic-link fixture unavailable on this filesystem; direct boundary checks passed.");
            }
            Files.writeString(file, "changed content with new length");
            try (var ticket = tickets.issue(url, 60000)) {
                check(status(client, HttpRequest.newBuilder(URI.create(ticket.url())).header("If-None-Match", etag).build()) == 409,
                        "changed file rejects old hash");
                check(ticket.diagnostic().status() == 409, "changed-file rejection distinguished from network failure");
            }
            server.update(root, Map.of("sample.zip", pack(file, url)), limits);
            check(client.send(request(url), HttpResponse.BodyHandlers.ofString()).body().equals(Files.readString(file)), "reload applies file snapshot");
            Path unicodeFile = root.resolve("测试 pack.zip");
            Files.writeString(unicodeFile, "unicode zip bytes");
            String unicodeName = unicodeFile.getFileName().toString();
            String unicodeUrl = origin + "/packs/" + encodeName(unicodeName);
            HostedPack unicodePack = pack(unicodeFile, unicodeUrl);
            server.update(root, Map.of("sample.zip", pack(file, url),
                    unicodeName.toLowerCase(java.util.Locale.ROOT), unicodePack), limits);
            check(client.send(request(unicodeUrl), HttpResponse.BodyHandlers.ofString()).body().equals("unicode zip bytes"),
                    "unicode and space zip names download");
            server.update(root, Map.of("sample.zip", pack(file, url)), config("requests-per-minute-per-ip: 1\n"));
            try (var ticket = tickets.issue(url, 60000)) {
                HttpRequest limited = request(ticket.url());
                for (int i = 0; i <= limits.burst(); i++) status(client, limited);
                check(status(client, limited) == 429 && ticket.limited(), "ticket sees real rate limit");
                check(ticket.diagnostic().status() == 429, "real request-rate rejection recorded");
            }
            server.update(root, Map.of(unicodeName.toLowerCase(java.util.Locale.ROOT), unicodePack),
                    config("requests-per-minute-per-ip: 1\n"));
            try (var ticket = tickets.issue(unicodeUrl, 60000)) {
                HttpRequest limited = request(ticket.url());
                for (int i = 0; i <= limits.burst(); i++) status(client, limited);
                check(status(client, limited) == 429 && ticket.limited(), "unicode zip ticket sees real rate limit");
            }
        }
        check(PackHttpServer.matchesTag("W/\"abc\", \"def\"", "\"abc\""), "weak ETag");
        check(!PackHttpServer.matchesTag("\"xabc\"", "\"abc\""), "exact ETag");
    }

    private static void switchingHttp() throws Exception {
        Path root = Files.createDirectories(directory.resolve("switching")).toRealPath();
        Path a = root.resolve("a.zip"), b = root.resolve("b.zip");
        Files.write(a, new byte[8 * 1048576]);
        Files.write(b, new byte[8 * 1048576]);
        int port = port();
        String origin = "http://127.0.0.1:" + port + "/packs/";
        String aUrl = origin + "a.zip", bUrl = origin + "b.zip";
        HostLimits limits = config("per-download-mib: 1\nmax-download-seconds: 10\n");
        var responses = new ArrayList<HttpResponse<java.io.InputStream>>();
        var tickets = new DownloadTickets();
        try (HttpClient client = HttpClient.newHttpClient();
             var server = new PackHttpServer(root, Map.of("a.zip", pack(a, aUrl), "b.zip", pack(b, bUrl)),
                     limits, tickets, message -> {})) {
            server.bind("127.0.0.1", port);
            try {
                // 使用公开直链，不靠查询票据绕过限流；旧包保留连接，模拟切服时的重叠。
                for (int i = 0; i < 12; i++) {
                    String url = i % 2 == 0 ? aUrl : bUrl;
                    check(status(client, HttpRequest.newBuilder(URI.create(url))
                            .method("HEAD", HttpRequest.BodyPublishers.noBody()).build()) == 200,
                            "switching HEAD remains available: " + i);
                    var response = client.send(request(url), HttpResponse.BodyHandlers.ofInputStream());
                    responses.add(response);
                    check(response.statusCode() == 200, "switching GET remains available: " + i);
                }
                check(server.stats().downloads() == 12, "old and new downloads occupy bounded slots");
                try (var ticket = tickets.issue(bUrl, 60000)) {
                    check(status(client, request(ticket.url())) == 503 && ticket.limited(),
                            "shared download link and ticket still obey concurrency limit");
                    check(ticket.diagnostic().status() == 503, "concurrency rejection distinguished from request-rate rejection");
                }
            } finally {
                for (var response : responses) response.body().close();
            }
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (server.stats().downloads() != 0 && System.nanoTime() < until) Thread.sleep(10);
            check(server.stats().downloads() == 0, "abandoned switch downloads release slots");
            server.update(root, Map.of("a.zip", pack(a, aUrl), "b.zip", pack(b, bUrl)), HostLimits.defaults());
            check(status(client, request(bUrl + "?vtb=made-up")) == 200,
                    "ordinary download recovers; fabricated ticket gives no bypass");
        }
    }

    private static void transfers() throws Exception {
        Path root = Files.createDirectories(directory.resolve("transfers")).toRealPath();
        Path file = root.resolve("large.zip");
        Files.write(file, new byte[8 * 1048576]);
        int port = port();
        String url = "http://127.0.0.1:" + port + "/packs/large.zip";
        HostLimits limits = config("max-downloads: 1\nmax-downloads-per-ip: 1\nper-download-mib: 1\n"
                + "max-download-seconds: 1\n");
        var tickets = new DownloadTickets();
        try (HttpClient client = HttpClient.newHttpClient();
             var server = new PackHttpServer(root, Map.of("large.zip", pack(file, url)), limits, tickets, message -> {});
             var ticket = tickets.issue(url, 60000)) {
            server.bind("127.0.0.1", port);
            CompletableFuture<HttpResponse<byte[]>> slow = client.sendAsync(request(ticket.url()), HttpResponse.BodyHandlers.ofByteArray());
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (server.stats().downloads() != 1 && System.nanoTime() < until) Thread.sleep(10);
            check(server.stats().downloads() == 1, "download slot occupied");
            check(status(client, request(url)) == 503, "concurrent download rejected without queue");
            try { slow.get(5, TimeUnit.SECONDS); throw new AssertionError("expected transfer deadline"); }
            catch (ExecutionException expected) { }
            catch (java.util.concurrent.TimeoutException failure) { slow.cancel(true); client.shutdownNow(); throw failure; }
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (server.stats().downloads() != 0 && System.nanoTime() < until) Thread.sleep(10);
            check(server.stats().downloads() == 0, "timeout releases slot");
            awaitStage(ticket, DownloadTickets.Stage.TIMED_OUT);
            check(ticket.diagnostic().status() == 200 && ticket.diagnostic().bytes() < ticket.diagnostic().expectedBytes()
                    && !ticket.limited(), "HTTP deadline recorded separately from rate limits and incomplete byte count retained");
            server.update(root, Map.of("large.zip", pack(file, url)), config("per-download-mib: 1\nmax-download-seconds: 10\n"));
            try (var interrupted = tickets.issue(url, 60000)) {
                var aborted = client.send(request(interrupted.url()), HttpResponse.BodyHandlers.ofInputStream());
                aborted.body().close();
                until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (server.stats().downloads() != 0 && System.nanoTime() < until) Thread.sleep(10);
                check(server.stats().downloads() == 0, "client abort releases slot");
                awaitStage(interrupted, DownloadTickets.Stage.INTERRUPTED);
                check(!interrupted.diagnostic().error().isEmpty(), "connection interruption retains server-side error");
            }
            server.update(root, Map.of("large.zip", pack(file, url)), config("requests-per-minute-per-ip: 600\n"));
            check(status(client, request(url)) == 200, "service recovers after timeout");
        }
    }

    private static void awaitStage(DownloadTickets.Ticket ticket, DownloadTickets.Stage stage) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ticket.diagnostic().stage() != stage && System.nanoTime() < until) Thread.sleep(10);
        check(ticket.diagnostic().stage() == stage, "expected download stage " + stage + ", got " + ticket.diagnostic());
    }

    private static void bandwidth() throws Exception {
        Path root = Files.createDirectories(directory.resolve("bandwidth")).toRealPath();
        Path file = root.resolve("one.zip");
        Files.write(file, new byte[1048576]);
        int port = port();
        String url = "http://127.0.0.1:" + port + "/packs/one.zip";
        HostLimits limits = config("bandwidth-mib: 1\nmax-download-seconds: 10\n");
        try (HttpClient client = HttpClient.newHttpClient();
             var server = new PackHttpServer(root, Map.of("one.zip", pack(file, url)), limits, new DownloadTickets(), message -> {})) {
            server.bind("127.0.0.1", port);
            long start = System.nanoTime();
            var first = client.sendAsync(request(url), HttpResponse.BodyHandlers.discarding());
            var second = client.sendAsync(request(url), HttpResponse.BodyHandlers.discarding());
            try {
                check(first.get(6, TimeUnit.SECONDS).statusCode() == 200 && second.get(6, TimeUnit.SECONDS).statusCode() == 200,
                        "concurrent transfers finish under shared budget");
                check(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(1800), "global bandwidth is shared, not per transfer");
            } finally {
                first.cancel(true); second.cancel(true);
            }
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
