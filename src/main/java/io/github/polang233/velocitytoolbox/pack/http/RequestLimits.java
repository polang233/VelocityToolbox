package io.github.polang233.velocitytoolbox.pack.http;

import io.github.polang233.velocitytoolbox.pack.config.HostLimits;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** 有容量和过期时间的 IP 令牌桶；下载名额不排队。 */
final class RequestLimits {
    private static final class Client {
        double tokens;
        long updated;
        int downloads;
        Client(int tokens, long now) { this.tokens = tokens; this.updated = now; }
    }
    final class Lease implements AutoCloseable {
        private Client client;
        Lease(Client client) { this.client = client; }
        @Override public void close() {
            synchronized (RequestLimits.this) {
                if (client != null) { client.downloads--; downloads--; client = null; }
            }
        }
    }
    private final Map<String, Client> clients = new HashMap<>();
    private final LongSupplier clock;
    private HostLimits limits;
    private double globalTokens;
    private long globalUpdated;
    private long swept;
    private int downloads;

    RequestLimits(HostLimits limits) { this(limits, System::nanoTime); }
    RequestLimits(HostLimits limits, LongSupplier clock) {
        this.limits = limits; this.clock = clock;
        globalTokens = limits.requestsPerSecond(); globalUpdated = clock.getAsLong();
    }
    synchronized void update(HostLimits next) { limits = next; }
    synchronized int request(String ip) {
        long now = clock.getAsLong();
        sweep(now);
        globalTokens = Math.min(limits.requestsPerSecond(), globalTokens
                + Math.max(0, now - globalUpdated) / 1e9 * limits.requestsPerSecond());
        globalUpdated = now;
        if (globalTokens < 1) return 503;
        globalTokens--;
        Client client = clients.get(ip);
        if (client == null) {
            if (clients.size() >= limits.clients()) return 503;
            client = new Client(limits.burst(), now);
            clients.put(ip, client);
        }
        client.tokens = Math.min(limits.burst(), client.tokens
                + Math.max(0, now - client.updated) / 60e9 * limits.requestsPerMinute());
        client.updated = now;
        if (client.tokens < 1) return 429;
        client.tokens--;
        return 0;
    }
    synchronized Lease download(String ip) {
        Client client = clients.get(ip);
        if (client == null || downloads >= limits.downloads() || client.downloads >= limits.downloadsPerIp()) return null;
        downloads++; client.downloads++;
        return new Lease(client);
    }
    synchronized int active() { return downloads; }
    synchronized int clientCount() { return clients.size(); }
    private void sweep(long now) {
        if (now - swept < 1_000_000_000L) return;
        swept = now;
        clients.values().removeIf(c -> c.downloads == 0 && now - c.updated > limits.idleSeconds() * 1_000_000_000L);
    }
}
