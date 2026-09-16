package io.github.polang233.velocitytoolbox.pack.config;

import org.spongepowered.configurate.ConfigurationNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 托管防护配置；带宽为每秒字节数，时间为秒。 */
public record HostLimits(int downloads, int downloadsPerIp, int requestsPerSecond,
                         int requestsPerMinute, int burst, int clients, int idleSeconds,
                         int requestBytes, int downloadSeconds, long bandwidth, long perDownloadBandwidth,
                         boolean index, List<String> trustedProxies, int retries, int retrySeconds) {
    public HostLimits { trustedProxies = List.copyOf(trustedProxies); }

    public static HostLimits defaults() {
        return new HostLimits(64, 8, 200, 120, 20, 10000, 300, 16384, 60, 0, 0, false, List.of(), 2, 5);
    }

    public static HostLimits read(ConfigurationNode node) throws IOException {
        if (node.virtual()) return defaults();
        // 已有预发布配置中的内部选项允许保留，但统一使用内置值。
        Set<String> keys = Set.of("max-downloads", "max-downloads-per-ip", "requests-per-second",
                "requests-per-minute-per-ip", "burst-per-ip", "max-clients", "client-idle-seconds",
                "max-request-bytes", "max-download-seconds", "bandwidth-mib", "per-download-mib",
                "show-index", "trusted-proxies", "download-retries", "retry-delay");
        if (!node.isMap()) throw error("", "expected a map");
        for (Object key : node.childrenMap().keySet())
            if (!keys.contains(key.toString())) throw error(key.toString(), "unknown option");
        List<String> proxies = new ArrayList<>();
        var proxyNode = node.node("trusted-proxies");
        if (!proxyNode.virtual()) {
            if (!proxyNode.isList() || proxyNode.childrenList().size() > 32) throw error("trusted-proxies", "expected up to 32 IP/CIDR entries");
            for (var child : proxyNode.childrenList()) {
                if (!(child.raw() instanceof String value) || value.length() > 64) throw error("trusted-proxies", "expected IP/CIDR text");
                proxies.add(value);
            }
        }
        return new HostLimits(number(node, "max-downloads", 64, 1, 4096),
                number(node, "max-downloads-per-ip", 8, 1, 4096),
                200,
                number(node, "requests-per-minute-per-ip", 120, 1, 100000),
                20,
                10000,
                300,
                16384,
                number(node, "max-download-seconds", 60, 1, 3600),
                (long) number(node, "bandwidth-mib", 0, 0, 10000) * 1048576,
                (long) number(node, "per-download-mib", 0, 0, 10000) * 1048576,
                false, proxies, 2, 5);
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
