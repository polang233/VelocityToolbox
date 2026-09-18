package io.github.polang233.velocitytoolbox.pack.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.polang233.velocitytoolbox.pack.config.HostLimits;
import io.github.polang233.velocitytoolbox.pack.host.HostedPack;
import io.github.polang233.velocitytoolbox.pack.host.PackScanner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 有界下载服务；连接建立和请求头解析之前的保护由反代或 JVM 启动参数提供。 */
public final class PackHttpServer implements AutoCloseable {
    public record Stats(int downloads, int clients, long limited, long busy, long bytes) {}
    public record Traffic(long limited, long busy, long timeouts) {}

    private record Snapshot(Path root, Map<String, HostedPack> packs, HostLimits limits, ClientAddress addresses) {
        static Snapshot create(Path root, Map<String, HostedPack> packs, HostLimits limits) throws IOException {
            return new Snapshot(root.toRealPath(), Map.copyOf(packs), limits, new ClientAddress(limits.trustedProxies()));
        }
    }
    private volatile Snapshot snapshot;
    private final RequestLimits requests;
    private final TransferControl transfers;
    private final DownloadTickets tickets;
    private final AtomicInteger handlers = new AtomicInteger();
    private volatile HttpServer httpServer;
    private ExecutorService executor;

    public PackHttpServer(Path directory, Map<String, HostedPack> packs, HostLimits limits,
                          DownloadTickets tickets, Consumer<Traffic> report) throws IOException {
        snapshot = Snapshot.create(directory, packs, limits);
        requests = new RequestLimits(limits);
        transfers = new TransferControl(report);
        this.tickets = tickets;
    }

    public void update(Path directory, Map<String, HostedPack> packs, HostLimits limits) throws IOException {
        Snapshot next = Snapshot.create(directory, packs, limits);
        requests.update(limits);
        snapshot = next;
    }

    public void bind(String host, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 128);
        httpServer = server;
        server.createContext("/", this::handle);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.start();
    }

    public boolean running() { return httpServer != null; }
    public Stats stats() {
        return new Stats(requests.active(), requests.clientCount(), transfers.rejected.sum(), transfers.busy.sum(), transfers.bytes.sum());
    }

    private void handle(HttpExchange exchange) throws IOException {
        Snapshot current = snapshot;
        int count = handlers.incrementAndGet();
        try {
            if (!running() || count > current.limits().downloads() + 64) {
                reject(exchange, 503);
                return;
            }
            try (var transfer = transfers.begin(exchange)) {
                if (requestBytes(exchange) > current.limits().requestBytes()) {
                    int rejection = requests.request(exchange.getRemoteAddress().getAddress().getHostAddress());
                    if (rejection != 0) { reject(exchange, rejection); return; }
                    respond(exchange, 431, "Request too large\n"); return;
                }
                String ip = current.addresses().resolve(exchange);
                int rejection = requests.request(ip);
                if (rejection != 0) { reject(exchange, rejection); return; }
                String method = exchange.getRequestMethod();
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                    respond(exchange, 405, "Method not allowed\n"); return;
                }
                if (exchange.getRequestHeaders().containsKey("Transfer-Encoding")
                        || exchange.getRequestHeaders().getOrDefault("Content-Length", java.util.List.of("0"))
                        .stream().anyMatch(value -> !value.equals("0"))) {
                    respond(exchange, 400, "Request body not accepted\n"); return;
                }
                String path = exchange.getRequestURI().getPath();
                if ("/".equals(path)) {
                    if (!current.limits().index()) { respond(exchange, 404, "Not found\n"); return; }
                    StringBuilder body = new StringBuilder("VelocityToolBox packs\n");
                    current.packs().values().stream().sorted(Comparator.comparing(HostedPack::fileName))
                            .forEach(pack -> body.append(pack.fileName()).append('\n'));
                    respond(exchange, 200, body.toString()); return;
                }
                String name = requestedFileName(exchange.getRequestURI());
                HostedPack pack = name == null ? null : current.packs().get(name.toLowerCase(Locale.ROOT));
                if (pack == null) { respond(exchange, 404, "Not found\n"); return; }
                if (!valid(current.root(), pack)) {
                    respond(exchange, 409, "Pack changed or unavailable; reload required\n"); return;
                }
                String tag = "\"" + pack.sha1() + "\"";
                exchange.getResponseHeaders().set("ETag", tag);
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                if (matchesTag(exchange.getRequestHeaders().getFirst("If-None-Match"), tag)) {
                    exchange.sendResponseHeaders(304, -1); return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Content-Length", Long.toString(pack.size()));
                if (method.equals("HEAD")) { exchange.sendResponseHeaders(200, -1); return; }
                try (var lease = requests.download(ip)) {
                    if (lease == null) { reject(exchange, 503); return; }
                    transfer.downloading(current.limits());
                    try (SeekableByteChannel input = Files.newByteChannel(pack.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                        if (input.size() != pack.size() || !valid(current.root(), pack)) {
                            respond(exchange, 409, "Pack changed; reload required\n"); return;
                        }
                        if (pack.size() == 0) {
                            exchange.sendResponseHeaders(200, -1);
                            return;
                        }
                        exchange.sendResponseHeaders(200, pack.size());
                        // 由 exchange 统一关闭响应；部分发送时先关闭流可能使 JDK 保留连接。
                        OutputStream output = exchange.getResponseBody();
                        ByteBuffer buffer = ByteBuffer.allocate(32768);
                        while (input.read(buffer) != -1) {
                            int bytes = buffer.position();
                            transfer.pause(bytes, current.limits());
                            output.write(buffer.array(), 0, bytes);
                            transfers.bytes.add(bytes);
                            buffer.clear();
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // 客户端断开或期限到达，统一在 finally 中关闭；不逐请求刷错误日志。
        } finally {
            handlers.decrementAndGet();
            exchange.close();
        }
    }

    private void reject(HttpExchange exchange, int status) throws IOException {
        if (status == 429) transfers.rejected.increment(); else transfers.busy.increment();
        tickets.limited(exchange.getRequestURI());
        exchange.getResponseHeaders().set("Retry-After", Integer.toString(snapshot.limits().retrySeconds()));
        respond(exchange, status, status == 429 ? "Too many requests\n" : "Server busy\n");
    }

    private static boolean valid(Path root, HostedPack pack) {
        try {
            Path file = pack.path().toAbsolutePath().normalize();
            return file.getParent().equals(root) && file.toRealPath().getParent().equals(root)
                    && pack.matches(Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException failure) { return false; }
    }

    static boolean matchesTag(String value, String tag) {
        if (value == null) return false;
        for (String entry : value.split(",")) {
            String candidate = entry.trim();
            if (candidate.equals("*") || candidate.equals(tag) || candidate.equals("W/" + tag)) return true;
        }
        return false;
    }

    private static long requestBytes(HttpExchange exchange) {
        long count = exchange.getRequestURI().toASCIIString().length() + exchange.getRequestMethod().length();
        for (var header : exchange.getRequestHeaders().entrySet()) {
            count += header.getKey().length();
            for (String value : header.getValue()) count += value.getBytes(StandardCharsets.UTF_8).length + 4;
        }
        return count;
    }

    private static String requestedFileName(URI uri) {
        String path = uri.getPath();
        if (path == null || !path.startsWith("/packs/")) return null;
        String name = path.substring("/packs/".length());
        return PackScanner.isSafeZipFileName(name) ? name : null;
    }

    private static void respond(HttpExchange exchange, int status, String message) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.remove("ETag");
        headers.set("Connection", "close");
        headers.set("Content-Type", "text/plain; charset=utf-8");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "no-store");
        headers.set("Content-Length", Integer.toString(bytes.length));
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override public void close() {
        HttpServer server = httpServer;
        httpServer = null;
        transfers.close();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }
}
