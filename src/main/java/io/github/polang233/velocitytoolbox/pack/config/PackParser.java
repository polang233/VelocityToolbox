package io.github.polang233.velocitytoolbox.pack.config;

import io.github.polang233.velocitytoolbox.pack.host.HostedPack;
import io.github.polang233.velocitytoolbox.pack.host.PackScanner;
import io.github.polang233.velocitytoolbox.pack.host.PackArchive;
import io.github.polang233.velocitytoolbox.version.VersionRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static io.github.polang233.velocitytoolbox.pack.config.PackRules.*;

/**
 * 读取配置并校验文件、哈希和分配引用；缺失的托管文件跳过，其余规则校验通过后交给下发器。
 */
final class PackParser {
    private PackParser() {
    }

    static PackRules read(ConfigurationNode root, List<HostedPack> hosted, Path directory,
                          boolean hostEnabled, Consumer<String> warning) throws IOException {
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
        Set<String> configuredPacks = new HashSet<>();
        Set<Path> checkedArchives = new HashSet<>();
        ConfigurationNode entries = root.node("packs");
        if (!entries.isMap()) throw error("packs", "expected a map of named variant lists");
        for (var entry : entries.childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!name.matches("[A-Za-z0-9_-]+")) throw error("packs", "invalid name: " + name);
            configuredPacks.add(name);
            String path = "packs." + name;
            ConfigurationNode node = entry.getValue();
            List<Variant> variants = new ArrayList<>();
            if (node.raw() instanceof String url) {
                // 单文件简写：包名: "@文件.zip"，等同只有 url 的单个变体。
                Variant parsed = variant(single(url), files, path, required, prompt,
                        directory, hostEnabled, checkedArchives, warning);
                if (parsed != null) variants.add(parsed);
            } else {
                if (!node.isList() || node.childrenList().isEmpty())
                    throw error(path, "expected \"@file.zip\" or a non-empty variant list (- url: ...); see docs/RESOURCE_PACKS.md");
                int i = 0;
                for (ConfigurationNode variant : node.childrenList()) {
                    // 列表项也可只写来源，如 - none。
                    ConfigurationNode item = variant.raw() instanceof String url ? single(url) : variant;
                    Variant parsed = variant(item, files, path + "[" + i++ + "]", required, prompt,
                            directory, hostEnabled, checkedArchives, warning);
                    if (parsed != null) variants.add(parsed);
                }
            }
            if (!variants.isEmpty()) packs.put(name, new Pack(variants));
        }
        Map<String, Assignment> servers = new LinkedHashMap<>();
        ConfigurationNode serverNode = root.node("servers");
        if (!serverNode.virtual() && !serverNode.isMap()) throw error("servers", "expected a map");
        for (var entry : serverNode.childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey());
            String path = "servers." + name;
            if (name.isBlank() || !name.equals(name.trim())) throw error(path, "invalid server name");
            ConfigurationNode node = entry.getValue();
            Assignment rule;
            if (node.isList()) {
                rule = new Assignment(names(node, configuredPacks, packs, path));
            } else if (node.raw() instanceof String value) {
                if (!value.trim().equals("keep")) throw error(path, "expected keep, a pack list or a map");
                rule = new Assignment(List.of(), true);
            } else {
                keys(node, path, "packs", "keep-existing");
                boolean keep = bool(node.node("keep-existing"), false, path + ".keep-existing");
                if (!keep && node.node("packs").virtual()) throw error(path + ".packs", "expected a list");
                rule = new Assignment(names(node.node("packs"), configuredPacks, packs, path + ".packs"), keep);
            }
            if (servers.putIfAbsent(name.toLowerCase(Locale.ROOT), rule) != null) throw error(path, "duplicate server");
        }
        Assignment defaults = servers.remove("default");
        return new PackRules(true, delay, timeout, packs,
                defaults == null ? new Assignment(List.of()) : defaults, servers);
    }

    private static Variant variant(ConfigurationNode node, Map<String, HostedPack> files, String path,
                                   boolean required, Component prompt, Path directory, boolean hostEnabled,
                                   Set<Path> checkedArchives, Consumer<String> warning) throws IOException {
        keys(node, path, "url", "hash", "versions", "permission", "conditions", "required", "prompt");
        String url = string(node.node("url"), "", path + ".url");
        String hash = string(node.node("hash"), "", path + ".hash").toLowerCase(Locale.ROOT);
        // versions、permission 直接写在变体上；旧写法 conditions 继续解析。
        ConfigurationNode conditions = node;
        String conditionPath = path;
        if (!node.node("conditions").virtual()) {
            conditions = node.node("conditions");
            conditionPath = path + ".conditions";
            keys(conditions, conditionPath, "versions", "permission");
            if (!node.node("versions").virtual() || !node.node("permission").virtual())
                throw error(conditionPath, "cannot be combined with versions or permission on the variant");
        }
        VersionRule versions = VersionRule.read(conditions.node("versions"), "resource-packs." + conditionPath + ".versions");
        String permission = string(conditions.node("permission"), "", conditionPath + ".permission");
        boolean variantRequired = bool(node.node("required"), required, path + ".required");
        Component variantPrompt = prompt(node.node("prompt"), prompt, path + ".prompt");
        File source;
        if (url.equals("none") || url.equals("@")) {
            if (!node.node("hash").virtual() && !hash.equals("@"))
                throw error(path + ".hash", "no hash is needed for none");
            source = new File("@", "", false);
        } else if (url.startsWith("@")) {
            String file = url.substring(1);
            if (!hostEnabled) throw error(path + ".url",
                    "self-hosting is disabled; enable pack-host.enabled to use " + url);
            if (!PackScanner.isSafeZipFileName(file))
                throw error(path + ".url", "expected none or @filename.zip without a directory");
            if (!(node.node("hash").virtual() || hash.equals("@")) && !hash.matches("[0-9a-f]{40}"))
                throw error(path + ".hash", "expected @ or 40 hexadecimal characters");
            HostedPack hosted = files.get(file);
            if (hosted == null) {
                warning.accept("resource-packs." + path + ".url: hosted file not found: " + file
                        + "; skipping this variant; check packs-directory: "
                        + (directory == null ? "(not supplied)" : directory));
                return null;
            }
            if (checkedArchives.add(hosted.path())) {
                try { PackArchive.validate(hosted.path()); }
                catch (IOException invalid) { throw error(path + ".url", invalid.getMessage()); }
            }
            if (!(node.node("hash").virtual() || hash.equals("@"))) {
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
        return new Variant(source, versions, permission, variantRequired, variantPrompt);
    }

    /** 把只写来源的简写转成只有 url 的变体节点。 */
    private static ConfigurationNode single(String url) throws IOException {
        ConfigurationNode node = BasicConfigurationNode.root();
        node.node("url").set(url);
        return node;
    }

    private static List<String> names(ConfigurationNode node, Set<String> configuredPacks,
                                      Map<String, Pack> packs, String path) throws IOException {
        if (node.virtual()) return List.of();
        if (!node.isList()) throw error(path, "expected a list");
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ConfigurationNode child : node.childrenList()) {
            String name = string(child, "", path);
            if (!configuredPacks.contains(name) || !seen.add(name))
                throw error(path, "unknown or duplicate pack: " + name);
            if (packs.containsKey(name)) names.add(name);
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
