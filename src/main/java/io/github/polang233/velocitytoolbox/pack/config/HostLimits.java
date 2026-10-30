package io.github.polang233.velocitytoolbox.pack.config;

import org.spongepowered.configurate.ConfigurationNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 托管防护配置；带宽为每秒字节数，时间为秒。
 * requestsPerSecond、burst、clients、idleSeconds 不开放配置，仅保留为测试注入点。
 */
public record HostLimits(int downloads, int downloadsPerIp, int requestsPerSecond,
                         int requestsPerMinute, int burst, int clients, int idleSeconds,
                         int downloadSeconds, long bandwidth, long perDownloadBandwidth,
                         List<String> trustedProxies) {
    public HostLimits { trustedProxies = List.copyOf(trustedProxies); }

    /** 单次请求头上限（字节）。 */
    public static final int REQUEST_BYTES = 16384;
    /** 自托管下载失败或过载时的重试次数。 */
    public static final int RETRIES = 2;
    /** 重试间隔基数，也用作 Retry-After（秒）。 */
    public static final int RETRY_SECONDS = 5;

    private static final int DEFAULT_DOWNLOADS = 64;
    private static final int DEFAULT_DOWNLOADS_PER_IP = 12;
    private static final int DEFAULT_REQUESTS_PER_MINUTE = 120;
    private static final int DEFAULT_DOWNLOAD_SECONDS = 60;
    private static final int GLOBAL_REQUESTS_PER_SECOND = 200;
    private static final int REQUEST_BURST_PER_IP = 40;
    private static final int MAX_CLIENTS = 10000;
    private static final int CLIENT_IDLE_SECONDS = 300;

    private static final Set<String> OPTIONS = Set.of(
            "max-downloads", "max-downloads-per-ip", "requests-per-minute-per-ip",
            "max-download-seconds", "bandwidth-mib", "per-download-mib", "trusted-proxies");
    private static final Set<String> OBSOLETE = Set.of(
            "requests-per-second", "burst-per-ip", "max-clients", "client-idle-seconds",
            "max-request-bytes", "show-index", "download-retries", "retry-delay");

    public static HostLimits defaults() {
        return new HostLimits(DEFAULT_DOWNLOADS, DEFAULT_DOWNLOADS_PER_IP, GLOBAL_REQUESTS_PER_SECOND,
                DEFAULT_REQUESTS_PER_MINUTE, REQUEST_BURST_PER_IP, MAX_CLIENTS, CLIENT_IDLE_SECONDS,
                DEFAULT_DOWNLOAD_SECONDS, 0, 0, List.of());
    }

    public static HostLimits read(ConfigurationNode node) throws IOException {
        return read(node, warning -> {});
    }

    /** 旧版内部选项只警告并忽略，避免升级后整段托管无法启动；拼错的新选项仍报错。 */
    public static HostLimits read(ConfigurationNode node, Consumer<String> warning) throws IOException {
        if (node.virtual()) return defaults();
        if (!node.isMap()) throw error("", "expected a map");
        for (Object key : node.childrenMap().keySet()) {
            String name = key.toString();
            if (OBSOLETE.contains(name))
                warning.accept("pack-host.security." + name + ": obsolete option ignored; it can be removed from config");
            else if (!OPTIONS.contains(name)) throw error(name, "unknown option");
        }
        List<String> proxies = new ArrayList<>();
        var proxyNode = node.node("trusted-proxies");
        if (!proxyNode.virtual()) {
            if (!proxyNode.isList() || proxyNode.childrenList().size() > 32) throw error("trusted-proxies", "expected up to 32 IP/CIDR entries");
            for (var child : proxyNode.childrenList()) {
                if (!(child.raw() instanceof String value) || value.length() > 64) throw error("trusted-proxies", "expected IP/CIDR text");
                proxies.add(value);
            }
        }
        return new HostLimits(number(node, "max-downloads", DEFAULT_DOWNLOADS, 1, 4096),
                number(node, "max-downloads-per-ip", DEFAULT_DOWNLOADS_PER_IP, 1, 4096),
                GLOBAL_REQUESTS_PER_SECOND,
                number(node, "requests-per-minute-per-ip", DEFAULT_REQUESTS_PER_MINUTE, 1, 100000),
                REQUEST_BURST_PER_IP, MAX_CLIENTS, CLIENT_IDLE_SECONDS,
                number(node, "max-download-seconds", DEFAULT_DOWNLOAD_SECONDS, 1, 3600),
                (long) number(node, "bandwidth-mib", 0, 0, 10000) * 1048576,
                (long) number(node, "per-download-mib", 0, 0, 10000) * 1048576,
                proxies);
    }

    private static int number(ConfigurationNode node, String key, int fallback, int min, int max) throws IOException {
        Object raw = node.node(key).virtual() ? fallback : node.node(key).raw();
        if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < min || ((Number) raw).longValue() > max)
            throw error(key, "expected an integer from " + min + " to " + max);
        return ((Number) raw).intValue();
    }
    private static IOException error(String key, String detail) {
        return new IOException("pack-host.security" + (key.isEmpty() ? "" : "." + key) + ": " + detail);
    }
}
