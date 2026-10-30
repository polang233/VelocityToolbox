package io.github.polang233.velocitytoolbox.pack.config;

import com.velocitypowered.api.network.ProtocolVersion;
import io.github.polang233.velocitytoolbox.pack.host.HostedPack;
import io.github.polang233.velocitytoolbox.version.VersionRule;
import net.kyori.adventure.text.Component;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 保存有效配置，按玩家版本、权限和子服选择资源包。
 */
public record PackRules(boolean enabled, long delay, long timeout, Map<String, Pack> packs,
                        Assignment defaults, Map<String, Assignment> servers) {
    public PackRules {
        packs = Collections.unmodifiableMap(new LinkedHashMap<>(packs));
        // 保留书写顺序，多个通配符同时匹配时取第一个。
        servers = Collections.unmodifiableMap(new LinkedHashMap<>(servers));
    }

    public record File(String url, String sha1, boolean local) {
        public boolean empty() {
            return url.equals("@");
        }
    }

    public record Variant(File file, VersionRule versions, String permission, boolean required, Component prompt) {
    }

    public record Pack(List<Variant> variants) {
        public Pack {
            variants = List.copyOf(variants);
        }
    }

    public record Assignment(List<String> packs, boolean keepExisting) {
        public Assignment {
            packs = List.copyOf(packs);
        }

        public Assignment(List<String> packs) { this(packs, false); }
    }

    public record Choice(String name, File file, boolean required, Component prompt) {
    }

    public record Selection(List<Choice> packs, List<String> skipped) {
        public Selection {
            packs = List.copyOf(packs);
            skipped = List.copyOf(skipped);
        }
    }

    public enum Reason { SELECTED, NONE, VERSION, PERMISSION, VERSION_PERMISSION }

    public record Match(String pack, int index, Variant variant, Reason reason) {
    }

    public record Explanation(String assignment, Selection selection, List<Match> matches) {
        public Explanation {
            matches = List.copyOf(matches);
        }
    }

    public static PackRules disabled() {
        return new PackRules(false, 3000, 60000, Map.of(), new Assignment(List.of()), Map.of());
    }

    public static PackRules read(ConfigurationNode root, List<HostedPack> hosted) throws IOException {
        return read(root, hosted, null);
    }

    public static PackRules read(ConfigurationNode root, List<HostedPack> hosted, Path directory) throws IOException {
        return read(root, hosted, directory, true);
    }

    public static PackRules read(ConfigurationNode root, List<HostedPack> hosted, Path directory, boolean hostEnabled) throws IOException {
        return read(root, hosted, directory, hostEnabled, warning -> {});
    }

    public static PackRules read(ConfigurationNode root, List<HostedPack> hosted, Path directory,
                                 boolean hostEnabled, Consumer<String> warning) throws IOException {
        return PackParser.read(root, hosted, directory, hostEnabled, warning);
    }

    /**
     * 同一包取首个匹配变体；旧客户端只返回首个完整包。
     */
    public Selection select(String server, ProtocolVersion version, Predicate<String> permission) {
        return explain(server, version, permission).selection();
    }

    /** keep-existing 子服保留本次连接已下发的资源包；配了 packs 时，玩家没有 VTB 包才下发。 */
    public boolean keepExisting(String server) {
        return servers.getOrDefault(assignment(server), defaults).keepExisting();
    }

    /** 分配键：精确名称优先，其次按书写顺序取首个匹配的通配符，否则为 default。 */
    private String assignment(String server) {
        String name = server.toLowerCase(Locale.ROOT);
        if (servers.containsKey(name)) return name;
        for (String key : servers.keySet())
            if (key.contains("*") && glob(key, name)) return key;
        return "default";
    }

    private static boolean glob(String pattern, String name) {
        var regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) regex.append(Pattern.quote(part)).append(".*");
        regex.setLength(regex.length() - 2);
        return name.matches(regex.toString());
    }

    /** 选包与诊断共用一次判定，诊断信息不参与已发送包的变化比较。 */
    public Explanation explain(String server, ProtocolVersion version, Predicate<String> permission) {
        String assignment = assignment(server);
        Assignment rule = servers.getOrDefault(assignment, defaults);
        boolean modern = version.compareTo(ProtocolVersion.MINECRAFT_1_20_3) >= 0;
        List<Choice> selected = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<Match> matches = new ArrayList<>();
        for (String name : rule.packs()) {
            Variant match = null;
            int index = 0;
            for (Variant variant : packs.get(name).variants()) {
                boolean allowedVersion = variant.versions().allows(version);
                boolean allowedPermission = variant.permission().isEmpty() || permission.test(variant.permission());
                Reason reason = !allowedVersion && !allowedPermission ? Reason.VERSION_PERMISSION
                        : !allowedVersion ? Reason.VERSION : !allowedPermission ? Reason.PERMISSION
                        : variant.file().empty() ? Reason.NONE : Reason.SELECTED;
                matches.add(new Match(name, ++index, variant, reason));
                if (allowedVersion && allowedPermission) {
                    match = variant;
                    break;
                }
            }
            if (match == null) skipped.add(name);
            else if (!match.file().empty()) {
                selected.add(new Choice(name, match.file(), match.required(), match.prompt()));
                // 旧客户端只能使用一个完整包。
                if (!modern) break;
            }
        }
        return new Explanation(assignment, new Selection(selected, skipped), matches);
    }

}
