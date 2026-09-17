package io.github.polang233.velocitytoolbox.pack.config;

import io.github.polang233.velocitytoolbox.pack.host.HostedPack;
import io.github.polang233.velocitytoolbox.pack.host.PackScanner;
import io.github.polang233.velocitytoolbox.pack.host.PackArchive;
import io.github.polang233.velocitytoolbox.version.VersionRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

import static io.github.polang233.velocitytoolbox.pack.config.PackRules.*;

/**
 * 读取配置并校验文件、哈希和分配引用；全部有效后才交给下发器。
 */
final class PackParser {
    private PackParser() {
    }

    static PackRules read(ConfigurationNode root, List<HostedPack> hosted, Path directory, boolean hostEnabled) throws IOException {
        if (root.virtual()) return disabled();
        keys(root, "", "enabled", "settings", "packs", "servers");
        if (!bool(root.node("enabled"), false, "enabled")) return disabled();
        ConfigurationNode settings = root.node("settings");
        if (!settings.virtual()) keys(settings, "settings", "delay", "timeout", "required", "prompt");
        long delay = seconds(settings.node("delay"), 3, true, "settings.delay");
        long timeout = seconds(settings.node("timeout"), 60, false, "settings.timeout");
        boolean required = bool(settings.node("required"), false, "settings.required");
        Component prompt = prompt(settings.node("prompt"), Component.empty(), "settings.prompt");
        Map<String, HostedPack> files = new HashMap<>();
        for (HostedPack pack : hosted) files.put(pack.fileName(), pack);
        Map<String, Pack> packs = new LinkedHashMap<>();
        Set<Path> checkedArchives = new HashSet<>();
        ConfigurationNode entries = root.node("packs");
        if (!entries.isMap()) throw error("packs", "expected a map of named variant lists");
        for (var entry : entries.childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!name.matches("[A-Za-z0-9_-]+")) throw error("packs", "invalid name: " + name);
            String path = "packs." + name;
            ConfigurationNode node = entry.getValue();
            if (!node.isList() || node.childrenList().isEmpty())
                throw error(path, "expected a non-empty variant list (- url: ...); see docs/RESOURCE_PACKS.md");
            List<Variant> variants = new ArrayList<>();
            int i = 0;
            for (ConfigurationNode variant : node.childrenList())
                variants.add(variant(variant, files, path + "[" + i++ + "]", required, prompt, directory, hostEnabled, checkedArchives));
            packs.put(name, new Pack(variants));
        }
        Map<String, Assignment> servers = new LinkedHashMap<>();
        ConfigurationNode serverNode = root.node("servers");
        if (!serverNode.virtual() && !serverNode.isMap()) throw error("servers", "expected a map");
        for (var entry : serverNode.childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey());
            String path = "servers." + name;
            if (name.isBlank() || !name.equals(name.trim())) throw error(path, "invalid server name");
            ConfigurationNode node = entry.getValue();
            keys(node, path, "packs");
            if (node.node("packs").virtual()) throw error(path + ".packs", "expected a list");
            Assignment rule = new Assignment(names(node.node("packs"), packs, path + ".packs"));
            if (servers.putIfAbsent(name.toLowerCase(Locale.ROOT), rule) != null) throw error(path, "duplicate server");
        }
        Assignment defaults = servers.remove("default");
        return new PackRules(true, delay, timeout, packs,
                defaults == null ? new Assignment(List.of()) : defaults, servers);
    }

    private static Variant variant(ConfigurationNode node, Map<String, HostedPack> files, String path,
                                   boolean required, Component prompt, Path directory, boolean hostEnabled, Set<Path> checkedArchives) throws IOException {
        keys(node, path, "url", "hash", "conditions", "required", "prompt");
        String url = string(node.node("url"), "", path + ".url");
        String hash = string(node.node("hash"), "", path + ".hash").toLowerCase(Locale.ROOT);
        File source;
        if (url.equals("@")) {
            if (!node.node("hash").virtual() && !hash.equals("@"))
                throw error(path + ".hash", "no hash is needed for url: '@'");
            source = new File("@", "", false);
        } else if (url.startsWith("@")) {
            String file = url.substring(1);
            if (!hostEnabled) throw error(path + ".url",
                    "self-hosting is disabled; enable pack-host.enabled to use " + url);
            if (!PackScanner.isSafeZipFileName(file))
                throw error(path + ".url", "expected @ or @filename.zip without a directory");
            HostedPack hosted = files.get(file);
            if (hosted == null) throw error(path + ".url", "hosted file not found: " + file
                    + "; check filename and packs-directory: " + (directory == null ? "(not supplied)" : directory));
            if (checkedArchives.add(hosted.path())) {
                try { PackArchive.validate(hosted.path()); }
                catch (IOException invalid) { throw error(path + ".url", invalid.getMessage()); }
            }
            if (!(node.node("hash").virtual() || hash.equals("@"))) {
                if (!hash.matches("[0-9a-f]{40}"))
                    throw error(path + ".hash", "expected @ or 40 hexadecimal characters");
                if (!hash.equalsIgnoreCase(hosted.sha1()))
                    throw error(path + ".hash", "hash does not match hosted file: " + file);
            }
            source = new File(hosted.url(), hosted.sha1(), true);
        } else {
            if (!hash.matches("[0-9a-f]{40}"))
                throw error(path + ".hash", "external URLs require 40 hexadecimal characters; automatic hashing is only available for @filename.zip");
            source = new File(url, hash, false);
        }
        try {
            URI uri = source.empty() ? null : URI.create(source.url());
            if (uri != null && (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw error(path + ".url", "expected an HTTP(S) download URL with port 1-65535 and no credentials or fragment");
        }
        ConfigurationNode conditions = node.node("conditions");
        if (!conditions.virtual()) keys(conditions, path + ".conditions", "versions", "permission");
        return new Variant(source, VersionRule.read(conditions.node("versions"), "resource-packs." + path + ".conditions.versions"),
                string(conditions.node("permission"), "", path + ".conditions.permission"),
                bool(node.node("required"), required, path + ".required"), prompt(node.node("prompt"), prompt, path + ".prompt"));
    }

    private static List<String> names(ConfigurationNode node, Map<String, Pack> packs, String path) throws IOException {
        if (node.virtual()) return List.of();
        if (!node.isList()) throw error(path, "expected a list");
        List<String> names = new ArrayList<>();
        for (ConfigurationNode child : node.childrenList()) {
            String name = string(child, "", path);
            if (!packs.containsKey(name) || names.contains(name))
                throw error(path, "unknown or duplicate pack: " + name);
            names.add(name);
        }
        return names;
    }

    private static void keys(ConfigurationNode node, String path, String... names) throws IOException {
        if (!node.isMap()) throw error(path, "expected a map");
        Set<String> allowed = Set.of(names);
        for (Object rawKey : node.childrenMap().keySet()) {
            String key = String.valueOf(rawKey);
            if (allowed.contains(key)) continue;
            String hint = switch (key) {
                case "delay", "timeout" -> "move this option to settings";
                case "required", "prompt" ->
                        path.isEmpty() ? "move this option to settings" : "set this option on a pack variant";
                case "required-prompt" -> "use prompt";
                case "file" -> "use url: '@filename.zip'";
                case "sha1" -> "use hash";
                case "default" -> "use servers.default.packs";
                case "legacy" -> "use version conditions and a complete pack";
                default -> "unknown option";
            };
            throw error(path.isEmpty() ? key : path + "." + key, hint + "; see docs/RESOURCE_PACKS.md");
        }
    }

    private static String string(ConfigurationNode node, String fallback, String path) throws IOException {
        if (node.virtual()) return fallback;
        if (!(node.raw() instanceof String value)) throw error(path, "expected text");
        return value.trim();
    }

    private static boolean bool(ConfigurationNode node, boolean fallback, String path) throws IOException {
        if (node.virtual()) return fallback;
        if (!(node.raw() instanceof Boolean value)) throw error(path, "expected true/false");
        return value;
    }

    private static long seconds(ConfigurationNode node, double fallback, boolean zero, String path) throws IOException {
        Object raw = node.virtual() ? fallback : node.raw();
        if (!(raw instanceof Number n)) throw error(path, "expected seconds");
        double seconds = n.doubleValue();
        if (!Double.isFinite(seconds) || seconds < (zero ? 0 : 0.001) || seconds > 86400)
            throw error(path, "seconds out of range");
        return Math.round(seconds * 1000);
    }

    private static Component prompt(ConfigurationNode node, Component fallback, String path) throws IOException {
        if (node.virtual()) return fallback;
        try {
            return MiniMessage.miniMessage().deserialize(string(node, "", path));
        } catch (RuntimeException e) {
            throw error(path, "invalid MiniMessage");
        }
    }

    private static IOException error(String path, String detail) {
        return new IOException("resource-packs" + (path.isEmpty() ? "" : "." + path) + ": " + detail);
    }
}
