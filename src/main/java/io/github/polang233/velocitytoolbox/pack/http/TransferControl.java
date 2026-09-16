package io.github.polang233.velocitytoolbox.pack.http;

import com.sun.net.httpserver.HttpExchange;
import io.github.polang233.velocitytoolbox.pack.config.HostLimits;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** 统一下载期限和带宽预算；单个定时器清理超时请求。 */
final class TransferControl implements AutoCloseable {
    final class Transfer implements AutoCloseable {
        final HttpExchange exchange;
        final Thread thread = Thread.currentThread();
        final long started = System.nanoTime();
        volatile long deadline = started + TimeUnit.SECONDS.toNanos(10);
        volatile boolean closed;
        long localNext;
        Transfer(HttpExchange exchange) { this.exchange = exchange; }
        void downloading(HostLimits config) {
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.downloadSeconds());
        }
        void pause(int count, HostLimits config) throws IOException {
            long now = System.nanoTime();
            long due = reserve(count, config.bandwidth());
            if (config.perDownloadBandwidth() > 0) {
                localNext = Math.max(localNext, now) + count * 1_000_000_000L / config.perDownloadBandwidth();
                due = Math.max(due, localNext);
            }
            while (due > System.nanoTime()) {
                if (closed || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
                    throw new SocketTimeoutException("Download deadline exceeded");
                LockSupport.parkNanos(Math.min(due - System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(100)));
            }
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Transfer closed");
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            transfers.remove(exchange);
            exchange.close();
        }
    }
    private final Map<HttpExchange, Transfer> transfers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "vtb-http-watchdog"); thread.setDaemon(true); return thread;
    });
    final LongAdder rejected = new LongAdder();
    final LongAdder busy = new LongAdder();
    final LongAdder bytes = new LongAdder();
    private final LongAdder expired = new LongAdder();
    private long globalNext;
    private long lastRejected, lastBusy, lastExpired;

    TransferControl(Consumer<PackHttpServer.Traffic> report) {
        timer.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            for (Transfer transfer : transfers.values()) {
                if (!transfer.closed && transfer.deadline <= now) {
                    expired.increment();
                    transfer.thread.interrupt();
                    transfer.close();
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(() -> {
            long r = rejected.sum(), b = busy.sum(), e = expired.sum();
            if (r != lastRejected || b != lastBusy || e != lastExpired)
                report.accept(new PackHttpServer.Traffic(r - lastRejected, b - lastBusy, e - lastExpired));
            lastRejected = r; lastBusy = b; lastExpired = e;
        }, 60, 60, TimeUnit.SECONDS);
    }
    Transfer begin(HttpExchange exchange) {
        Transfer transfer = new Transfer(exchange);
        transfers.put(exchange, transfer);
        return transfer;
    }
    int active() { return transfers.size(); }
    private synchronized long reserve(int count, long rate) {
        if (rate == 0) return System.nanoTime();
        globalNext = Math.max(globalNext, System.nanoTime()) + count * 1_000_000_000L / rate;
        return globalNext;
    }
    @Override public void close() {
        timer.shutdownNow();
        for (Transfer transfer : transfers.values()) {
            transfer.thread.interrupt();
            transfer.close();
        }
    }
}
