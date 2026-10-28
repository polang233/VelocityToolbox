package io.github.polang233.velocitytoolbox.pack.http;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 关联本插件发出的下载、HTTP 诊断与限流结果；不是下载权限凭据。 */
public final class DownloadTickets {
    public enum Stage { NOT_REQUESTED, REQUESTED, RESPONDED, SENDING, SENT, TIMED_OUT, INTERRUPTED, FILE_ERROR, STOPPED }
    public record Diagnostic(int status, Stage stage, long bytes, long expectedBytes, long elapsedMs, String error) {}

    /** 每个 HTTP 请求独立记录；旧连接的迟到结果不覆盖同一票据的较新请求。 */
    static final class Request {
        private final long started = System.nanoTime();
        private volatile int status;
        private volatile Stage stage = Stage.REQUESTED;
        private volatile long bytes;
        private volatile long expectedBytes = -1;
        private volatile String error = "";
        private volatile long finished;

        synchronized void response(int status) { if (!failed()) { this.status = status; stage = Stage.RESPONDED; } }
        synchronized void sending(long expectedBytes) {
            if (!failed()) { this.status = 200; this.expectedBytes = expectedBytes; stage = Stage.SENDING; }
        }
        void wrote(int count) { bytes += count; }
        synchronized void complete() { if (stage == Stage.SENDING) stage = Stage.SENT; }
        synchronized void failed(Stage next, String detail) {
            if (stage == Stage.SENT || stage == Stage.TIMED_OUT || stage == Stage.STOPPED || stage == Stage.FILE_ERROR) return;
            error = detail.replace('\r', ' ').replace('\n', ' ');
            if (error.length() > 300) error = error.substring(0, 300);
            stage = next;
            finish();
        }
        synchronized void closing() {
            if (stage == Stage.SENDING) failed(Stage.INTERRUPTED, "HTTP transfer closed before completion");
        }
        private boolean failed() {
            return stage == Stage.TIMED_OUT || stage == Stage.INTERRUPTED || stage == Stage.FILE_ERROR || stage == Stage.STOPPED;
        }
        synchronized void finish() { if (finished == 0) finished = System.nanoTime(); }
        Diagnostic diagnostic() {
            return new Diagnostic(status, stage, bytes, expectedBytes,
                    Math.max(0, (finished == 0 ? System.nanoTime() : finished) - started) / 1_000_000, error);
        }
    }

    public final class Ticket implements AutoCloseable {
        private final String id = UUID.randomUUID().toString();
        private final String path;
        private final long expires;
        private final String url;
        private volatile boolean limited;
        private volatile Request request;
        private Ticket(String source, long milliseconds) {
            path = fileName(URI.create(source));
            expires = System.nanoTime() + Math.min(milliseconds, 86400000L) * 1_000_000L;
            url = source + (source.contains("?") ? "&" : "?") + "vtb=" + id;
        }
        public String url() { return url; }
        public boolean limited() { return limited; }
        public Diagnostic diagnostic() {
            Request current = request;
            return current == null ? new Diagnostic(0, Stage.NOT_REQUESTED, 0, -1, 0, "") : current.diagnostic();
        }
        @Override public void close() { synchronized (DownloadTickets.this) { tickets.remove(id); } }
    }

    private final Map<String, Ticket> tickets = new HashMap<>();
    synchronized Request begin(URI uri, boolean download) {
        Request request = new Request();
        if (download) {
            Ticket ticket = find(uri);
            if (ticket != null) ticket.request = request;
        }
        return request;
    }
    public synchronized Ticket issue(String url, long lifetimeMs) {
        long now = System.nanoTime();
        tickets.values().removeIf(ticket -> ticket.expires <= now);
        if (tickets.size() >= 4096) return null;
        Ticket ticket = new Ticket(url, lifetimeMs);
        tickets.put(ticket.id, ticket);
        return ticket;
    }
    public synchronized void limited(URI uri) {
        Ticket ticket = find(uri);
        if (ticket != null) ticket.limited = true;
    }
    private Ticket find(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.length() > 4096) return null;
        for (String part : query.split("&")) {
            if (!part.startsWith("vtb=")) continue;
            Ticket ticket = tickets.get(part.substring(4));
            if (ticket != null && ticket.expires > System.nanoTime() && ticket.path.equals(fileName(uri)))
                return ticket;
        }
        return null;
    }
    /** 签发 URL 与请求 URI 可能分别是解码/百分号编码路径，统一成文件名再比较。 */
    private static String fileName(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty()) path = uri.getRawPath();
        if (path == null) return "";
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.indexOf('%') < 0) return name;
        try {
            return URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            return name;
        }
    }
    public synchronized void clear() { tickets.clear(); }
}
