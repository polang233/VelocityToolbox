package io.github.polang233.velocitytoolbox.pack.http;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 关联本插件发出的下载与限流结果；不是下载权限凭据。 */
public final class DownloadTickets {
    public final class Ticket implements AutoCloseable {
        private final String id = UUID.randomUUID().toString();
        private final String path;
        private final long expires;
        private final String url;
        private volatile boolean limited;
        private Ticket(String source, long milliseconds) {
            path = fileName(URI.create(source));
            expires = System.nanoTime() + Math.min(milliseconds, 86400000L) * 1_000_000L;
            url = source + (source.contains("?") ? "&" : "?") + "vtb=" + id;
        }
        public String url() { return url; }
        public boolean limited() { return limited; }
        @Override public void close() { synchronized (DownloadTickets.this) { tickets.remove(id); } }
    }

    private final Map<String, Ticket> tickets = new HashMap<>();
    public synchronized Ticket issue(String url, long lifetimeMs) {
        long now = System.nanoTime();
        tickets.values().removeIf(ticket -> ticket.expires <= now);
        if (tickets.size() >= 4096) return null;
        Ticket ticket = new Ticket(url, lifetimeMs);
        tickets.put(ticket.id, ticket);
        return ticket;
    }
    public synchronized void limited(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.length() > 4096) return;
        for (String part : query.split("&")) {
            if (!part.startsWith("vtb=")) continue;
            Ticket ticket = tickets.get(part.substring(4));
            if (ticket != null && ticket.expires > System.nanoTime() && ticket.path.equals(fileName(uri)))
                ticket.limited = true;
        }
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
