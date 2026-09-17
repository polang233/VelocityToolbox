package io.github.polang233.velocitytoolbox.pack.config;

import com.velocitypowered.api.network.ProtocolVersion;
import io.github.polang233.velocitytoolbox.pack.host.HostedPack;
import io.github.polang233.velocitytoolbox.version.VersionRule;
import net.kyori.adventure.text.Component;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/**
 * 保存有效配置，按玩家版本、权限和子服选择资源包。
 */
public record PackRules(boolean enabled, long delay, long timeout, Map<String, Pack> packs,
                        Assignment defaults, Map<String, Assignment> servers) {
    public PackRules {
        packs = Collections.unmodifiableMap(new LinkedHashMap<>(packs));
        servers = Map.copyOf(servers);
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

    public record Assignment(List<String> packs) {
        public Assignment {
            packs = List.copyOf(packs);
        }
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
        return PackParser.read(root, hosted, directory, hostEnabled);
    }

    /**
     * 同一包取首个匹配变体；旧客户端只返回首个完整包。
     */
    public Selection select(String server, ProtocolVersion version, Predicate<String> permission) {
        return explain(server, version, permission).selection();
    }

    /** 选包与诊断共用一次判定，诊断信息不参与已发送包的变化比较。 */
    public Explanation explain(String server, ProtocolVersion version, Predicate<String> permission) {
        String assignment = servers.containsKey(server.toLowerCase(Locale.ROOT)) ? server.toLowerCase(Locale.ROOT) : "default";
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
                // Old clients can use only one complete pack.
                if (!modern) break;
            }
        }
        return new Explanation(assignment, new Selection(selected, skipped), matches);
    }

}
