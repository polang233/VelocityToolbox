package io.github.polang233.velocitytoolbox.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用 JDK {@link HttpServer} 提供 {@code GET /packs/<file>.zip}。
 * Minecraft 客户端从这里下载，不从代理进程内部读文件。
 */
final class PackHttpServer implements AutoCloseable {

    private final Path packsDirectory;
    private final Map<String, HostedPack> packs;

    private HttpServer httpServer;
    private ExecutorService executor;

    PackHttpServer(Path packsDirectory, Map<String, HostedPack> packs) {
        this.packsDirectory = packsDirectory;
        this.packs = packs;
    }

    void bind(String host, int port) throws IOException {
        InetSocketAddress address = new InetSocketAddress(host, port);
        httpServer = HttpServer.create(address, 0);
        httpServer.createContext("/packs", this::handlePack);
        httpServer.createContext("/", this::handleIndex);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        httpServer.setExecutor(executor);
        httpServer.start();
    }

    boolean running() {
        return httpServer != null;
    }

    @Override
    public void close() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed\n");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path == null || !"/".equals(path)) {
            send(exchange, 404, "text/plain; charset=utf-8", "Not found\n");
            return;
        }
        StringBuilder body = new StringBuilder("VelocityToolbox pack host\n\n");
        if (packs.isEmpty()) {
            body.append("No zip files in ").append(packsDirectory).append('\n');
        } else {
            packs.values().stream()
                    .sorted(Comparator.comparing(HostedPack::fileName))
                    .forEach(pack -> body.append(pack.fileName()).append('\n')
                            .append("  url:  ").append(pack.url()).append('\n')
                            .append("  sha1: ").append(pack.sha1()).append('\n'));
        }
        send(exchange, 200, "text/plain; charset=utf-8", body.toString());
    }

    private void handlePack(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed\n");
            return;
        }

        String fileName = requestedFileName(exchange.getRequestURI());
        HostedPack pack = fileName == null ? null : packs.get(fileName.toLowerCase(Locale.ROOT));
        if (pack == null || !Files.isRegularFile(pack.path())) {
            send(exchange, 404, "text/plain; charset=utf-8", "Pack not found\n");
            return;
        }

        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if (ifNoneMatch != null && ifNoneMatch.contains(pack.sha1())) {
            exchange.getResponseHeaders().set("ETag", "\"" + pack.sha1() + "\"");
            exchange.sendResponseHeaders(304, -1);
            exchange.close();
            return;
        }

        long size = Files.size(pack.path());
        exchange.getResponseHeaders().set("Content-Type", "application/zip");
        exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
        exchange.getResponseHeaders().set("ETag", "\"" + pack.sha1() + "\"");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if ("HEAD".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(200, size);
        try (OutputStream out = exchange.getResponseBody();
             InputStream in = Files.newInputStream(pack.path())) {
            in.transferTo(out);
        }
    }

    private String requestedFileName(URI uri) {
        String path = uri.getPath();
        if (path == null || !path.startsWith("/packs/")) {
            return null;
        }
        String name = path.substring("/packs/".length());
        if (!PackScanner.isSafeZipFileName(name)) {
            return null;
        }
        Path root = packsDirectory.toAbsolutePath().normalize();
        Path resolved = root.resolve(name).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            return null;
        }
        return name;
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
