package io.github.polang233.velocitytoolbox.version;

import com.velocitypowered.api.network.ProtocolVersion;
import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable rules, parsed once when the module loads or reloads. */
public record ServerVersionConfig(boolean enabled, Map<String, Rule> rules) {

    public ServerVersionConfig {
        rules = Map.copyOf(rules);
    }

    public static ServerVersionConfig disabled() {
        return new ServerVersionConfig(false, Map.of());
    }

    public static ServerVersionConfig load(Path dataDirectory) throws IOException {
        Path file = dataDirectory.resolve("config.yml");
        ResourceFiles.copyIfMissing("config.yml", file);
        return from(ResourceFiles.loadYaml(file).node("server-versions"));
    }

    static ServerVersionConfig from(ConfigurationNode root) throws IOException {
        if (root.virtual()) {
            return disabled();
        }
        checkKeys(root, "", Set.of("enabled", "servers"));
        Object enabled = root.node("enabled").raw();
        if (!(enabled instanceof Boolean)) {
            throw invalid("enabled", "expected true or false");
        }
        if (!Boolean.TRUE.equals(enabled)) {
            return disabled();
        }

        ConfigurationNode servers = root.node("servers");
        if (servers.virtual() || !servers.isMap()) {
            throw invalid("servers", "expected a server-name map, or {} for no restrictions");
        }
        Map<String, Rule> rules = new LinkedHashMap<>();
        for (var entry : servers.childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey());
            String path = "servers." + name;
            if (name.isBlank() || !name.equals(name.trim())) {
                throw invalid(path, "server name must not be blank or have surrounding spaces");
            }
            ConfigurationNode node = entry.getValue();
            checkKeys(node, path, Set.of("min", "max", "allow", "deny"));
            ProtocolVersion min = bound(node.node("min"), path + ".min", ProtocolVersion.MINIMUM_VERSION, "min");
            ProtocolVersion max = bound(node.node("max"), path + ".max", ProtocolVersion.MAXIMUM_VERSION, "max");
            if (min.compareTo(max) > 0) {
                throw invalid(path, "min must not be newer than max");
            }
            Rule rule = new Rule(min, max, versions(node.node("allow"), path + ".allow"),
                    versions(node.node("deny"), path + ".deny"));
            if (rules.putIfAbsent(name.toLowerCase(Locale.ROOT), rule) != null) {
                throw invalid(path, "duplicate server name ignoring case");
            }
        }
        return new ServerVersionConfig(true, rules);
    }

    private static void checkKeys(ConfigurationNode node, String path, Set<String> keys) throws IOException {
        if (!node.isMap()) {
            throw invalid(path, "expected a map");
        }
        for (Object key : node.childrenMap().keySet()) {
            if (!keys.contains(String.valueOf(key))) {
                throw invalid(path.isEmpty() ? String.valueOf(key) : path + "." + key, "unknown option");
            }
        }
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
        // Reject YAML decimal numbers: unquoted 1.20 would otherwise silently become 1.2.
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
        return new IOException("config.yml: server-versions" + (path.isEmpty() ? "" : "." + path) + ": " + detail);
    }

    public record Rule(ProtocolVersion min, ProtocolVersion max,
                       Set<ProtocolVersion> allow, Set<ProtocolVersion> deny) {
        public Rule {
            allow = Set.copyOf(allow);
            deny = Set.copyOf(deny);
        }

        public boolean allows(ProtocolVersion version) {
            return version.isSupported()
                    && version.compareTo(min) >= 0 && version.compareTo(max) <= 0
                    && (allow.isEmpty() || allow.contains(version))
                    && !deny.contains(version);
        }
    }
}
