package io.github.polang233.velocitytoolbox.version;

import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 子服版本配置；与资源包条件共用 VersionRule。
 */
public record ServerVersionConfig(boolean enabled, Map<String, VersionRule> rules) {

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
        Map<String, VersionRule> rules = new LinkedHashMap<>();
        for (var entry : servers.childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey());
            String path = "servers." + name;
            if (name.isBlank() || !name.equals(name.trim())) {
                throw invalid(path, "server name must not be blank or have surrounding spaces");
            }
            ConfigurationNode node = entry.getValue();
            checkKeys(node, path, Set.of("min", "max", "allow", "deny"));
            VersionRule range = VersionRule.read(node, "config.yml: server-versions." + path);
            if (rules.putIfAbsent(name.toLowerCase(Locale.ROOT), range) != null) {
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

    private static IOException invalid(String path, String detail) {
        return new IOException("config.yml: server-versions" + (path.isEmpty() ? "" : "." + path) + ": " + detail);
    }

}
