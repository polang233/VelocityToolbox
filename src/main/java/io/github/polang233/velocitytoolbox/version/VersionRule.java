package io.github.polang233.velocitytoolbox.version;

import com.velocitypowered.api.network.ProtocolVersion;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/** 统一解析版本名、协议号和 min/max/allow/deny，deny 优先。 */
public record VersionRule(ProtocolVersion min, ProtocolVersion max,
                          Set<ProtocolVersion> allow, Set<ProtocolVersion> deny) {
    public VersionRule {
        allow = Set.copyOf(allow);
        deny = Set.copyOf(deny);
    }

    public static VersionRule read(ConfigurationNode node, String path) throws IOException {
        if (!node.virtual() && !node.isMap()) throw invalid(path, "expected a version map");
        for (Object key : node.childrenMap().keySet())
            if (!Set.of("min", "max", "allow", "deny").contains(String.valueOf(key)))
                throw invalid(path + "." + key, "unknown option");
        ProtocolVersion min = bound(node.node("min"), path + ".min", ProtocolVersion.MINIMUM_VERSION, "min");
        ProtocolVersion max = bound(node.node("max"), path + ".max", ProtocolVersion.MAXIMUM_VERSION, "max");
        if (min.compareTo(max) > 0) throw invalid(path, "min is newer than max");
        return new VersionRule(min, max, versions(node.node("allow"), path + ".allow"),
                versions(node.node("deny"), path + ".deny"));
    }

    private static ProtocolVersion bound(ConfigurationNode node, String path,
                                         ProtocolVersion fallback, String keyword) throws IOException {
        if (node.virtual()) {
            return fallback;
        }
        if (keyword.equalsIgnoreCase(node.getString())) {
            return fallback;
        }
        return version(node, path);
    }

    private static Set<ProtocolVersion> versions(ConfigurationNode node, String path) throws IOException {
        if (node.virtual()) {
            return Set.of();
        }
        if (!node.isList()) {
            throw invalid(path, "expected a list such as [\"1.12.2\", \"1.20.1\"]");
        }
        Set<ProtocolVersion> result = new LinkedHashSet<>();
        int index = 0;
        for (ConfigurationNode child : node.childrenList()) {
            result.add(version(child, path + "[" + index++ + "]"));
        }
        return Set.copyOf(result);
    }

    private static ProtocolVersion version(ConfigurationNode node, String path) throws IOException {
        Object raw = node.raw();
        // 版本名必须是字符串，防止 YAML 将 1.20 读成小数 1.2。
        if (!(raw instanceof String || raw instanceof Integer || raw instanceof Long)) {
            throw invalid(path, "quote Minecraft version names, or use an integer protocol ID");
        }
        String value = String.valueOf(raw).trim();
        for (ProtocolVersion version : ProtocolVersion.SUPPORTED_VERSIONS) {
            if (version.getVersionsSupportedBy().contains(value)
                    || Integer.toString(version.getProtocol()).equals(value)) {
                return version;
            }
        }
        throw invalid(path, "unknown version '" + value + "' in this Velocity build");
    }

    private static IOException invalid(String path, String detail) {
        return new IOException(path + ": " + detail);
    }

    public boolean allows(ProtocolVersion version) {
        return version.isSupported() && version.compareTo(min) >= 0 && version.compareTo(max) <= 0
                && (allow.isEmpty() || allow.contains(version)) && !deny.contains(version);
    }
}
