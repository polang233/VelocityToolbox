package io.github.polang233.velocitytoolbox.pack;

import com.velocitypowered.api.network.ProtocolVersion;
import io.github.polang233.velocitytoolbox.version.VersionRule;
import org.spongepowered.configurate.ConfigurationNode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.function.Predicate;

/**
 * Validated delivery rules. Local files are resolved against one host snapshot.
 */
public record PackRules(boolean enabled, long delay, long timeout, Map<String, Pack> packs,
                        Assignment defaults, Map<String, Assignment> servers) {
    public PackRules {
        packs = Collections.unmodifiableMap(new LinkedHashMap<>(packs));
        servers = Map.copyOf(servers);
    }

    public record File(String url, String sha1, boolean local) {
    }

    public record Variant(File file, VersionRule versions, String permission) {
    }

    public record Pack(String permission, List<Variant> variants) {
        public Pack {
            variants = List.copyOf(variants);
        }
    }

    public record Assignment(List<String> packs, String legacy, boolean required, Component prompt) {
        public Assignment {
            packs = List.copyOf(packs);
        }
    }

    public record Choice(String name, File file) {
    }

    public record Selection(List<Choice> packs, List<String> skipped, boolean required, Component prompt) {
        public Selection {
            packs = List.copyOf(packs);
            skipped = List.copyOf(skipped);
        }

        public boolean blocked() {
            return required && !skipped.isEmpty();
        }
    }

    public static PackRules disabled() {
        return new PackRules(false, 1000, 60000, Map.of(),
                new Assignment(List.of(), "", false, Component.empty()), Map.of());
    }

    public static PackRules read(ConfigurationNode root, List<HostedPack> hosted) throws IOException {
        if (root.virtual()) return disabled();
        keys(root, "resource-packs", "enabled", "delay", "timeout", "required", "prompt", "packs", "default", "servers");
        if (!bool(root.node("enabled"), false, "enabled")) return disabled();
        long delay = seconds(root.node("delay"), 1, true, "delay");
        long timeout = seconds(root.node("timeout"), 60, false, "timeout");
        boolean required = bool(root.node("required"), false, "required");
        Component prompt = prompt(root.node("prompt"), Component.empty(), "prompt");
        Map<String, HostedPack> files = new HashMap<>();
        for (HostedPack pack : hosted) files.put(pack.fileName(), pack);
        Map<String, Pack> packs = new LinkedHashMap<>();
        ConfigurationNode entries = root.node("packs");
        if (!entries.isMap()) throw error("packs", "expected a map");
        for (var entry : entries.childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!name.matches("[A-Za-z0-9_-]+")) throw error("packs", "invalid name: " + name);
            String path = "packs." + name;
            ConfigurationNode node = entry.getValue();
            keys(node, path, "file", "url", "sha1", "permission", "versions", "variants");
            String permission = string(node.node("permission"), "", path + ".permission");
            List<Variant> variants = new ArrayList<>();
            if (!node.node("variants").virtual()) {
                if (!node.node("file").virtual() || !node.node("url").virtual() || !node.node("sha1").virtual()
                        || !node.node("versions").virtual())
                    throw error(path, "variants cannot mix with file/url/sha1/versions");
                if (!node.node("variants").isList() || node.node("variants").childrenList().isEmpty())
                    throw error(path + ".variants", "expected a non-empty list");
                int i = 0;
                for (ConfigurationNode variant : node.node("variants").childrenList())
                    variants.add(variant(variant, files, path + ".variants[" + i++ + "]"));
            } else variants.add(variant(node, files, path));
            packs.put(name, new Pack(permission, variants));
        }
        Assignment defaults = new Assignment(names(root.node("default"), packs, "default"), "", required, prompt);
        Map<String, Assignment> servers = new LinkedHashMap<>();
        ConfigurationNode serverNode = root.node("servers");
        if (!serverNode.virtual() && !serverNode.isMap()) throw error("servers", "expected a map");
        for (var entry : serverNode.childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey());
            String path = "servers." + name;
            if (name.isBlank() || !name.equals(name.trim())) throw error(path, "invalid server name");
            ConfigurationNode node = entry.getValue();
            keys(node, path, "packs", "legacy", "required", "prompt");
            if (node.node("packs").virtual()) throw error(path + ".packs", "expected a list");
            String legacy = string(node.node("legacy"), "", path + ".legacy");
            if (!legacy.isEmpty() && !packs.containsKey(legacy))
                throw error(path + ".legacy", "unknown pack: " + legacy);
            Assignment rule = new Assignment(names(node.node("packs"), packs, path + ".packs"), legacy,
                    bool(node.node("required"), required, path + ".required"), prompt(node.node("prompt"), prompt, path + ".prompt"));
            if (servers.putIfAbsent(name.toLowerCase(Locale.ROOT), rule) != null) throw error(path, "duplicate server");
        }
        return new PackRules(true, delay, timeout, packs, defaults, servers);
    }

    public Selection select(String server, ProtocolVersion version, Predicate<String> permission) {
        Assignment rule = servers.getOrDefault(server.toLowerCase(Locale.ROOT), defaults);
        boolean modern = version.compareTo(ProtocolVersion.MINECRAFT_1_20_3) >= 0;
        List<String> names = !modern && !rule.legacy().isEmpty() ? List.of(rule.legacy()) : rule.packs();
        List<Choice> selected = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String name : names) {
            Pack pack = packs.get(name);
            Variant match = pack.permission().isEmpty() || permission.test(pack.permission())
                    ? pack.variants().stream().filter(v -> v.versions().allows(version))
                    .filter(v -> v.permission().isEmpty() || permission.test(v.permission())).findFirst().orElse(null)
                    : null;
            if (match == null) skipped.add(name);
            else {
                selected.add(new Choice(name, match.file()));
                if (!modern) {
                    // Legacy assignment requires one compatible full pack, not every modern layer.
                    skipped.clear();
                    break;
                }
            }
        }
        return new Selection(selected, skipped, rule.required(), rule.prompt());
    }

    private static Variant variant(ConfigurationNode node, Map<String, HostedPack> files, String path) throws IOException {
        keys(node, path, "file", "url", "sha1", "permission", "versions");
        String file = string(node.node("file"), "", path + ".file");
        String url = string(node.node("url"), "", path + ".url");
        if (file.isEmpty() == url.isEmpty()) throw error(path, "choose exactly one file or url");
        File source;
        if (!file.isEmpty()) {
            if (!node.node("sha1").virtual()) throw error(path + ".sha1", "local hashes are automatic");
            HostedPack hosted = files.get(file);
            if (hosted == null) throw error(path + ".file", "file is not hosted: " + file);
            source = new File(hosted.url(), hosted.sha1(), true);
        } else {
            String hash = string(node.node("sha1"), "", path + ".sha1").toLowerCase(Locale.ROOT);
            if (!hash.matches("[0-9a-f]{40}")) throw error(path + ".sha1", "expected 40 hexadecimal characters");
            source = new File(url, hash, false);
        }
        try {
            URI uri = URI.create(source.url());
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw error(path, "expected an http/https download URL");
        }
        return new Variant(source, VersionRule.read(node.node("versions"), "resource-packs." + path + ".versions"),
                string(node.node("permission"), "", path + ".permission"));
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
        for (Object key : node.childrenMap().keySet())
            if (!allowed.contains(String.valueOf(key))) throw error(path + "." + key, "unknown option");
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
        return new IOException("resource-packs." + path + ": " + detail);
    }
}
