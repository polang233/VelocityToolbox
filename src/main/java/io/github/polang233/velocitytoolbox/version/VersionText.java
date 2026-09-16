package io.github.polang233.velocitytoolbox.version;

import com.velocitypowered.api.network.ProtocolVersion;
import io.github.polang233.velocitytoolbox.lang.Lang;

import java.util.Set;
import java.util.stream.Collectors;

/** 子服限制和资源包条件共用的版本显示。 */
public final class VersionText {
    private VersionText() {}

    public static String format(Lang lang, VersionRule rule, boolean protocolIds) {
        String result = lang.plain("common.versions.range",
                Lang.ph("min", protocolIds ? rule.min().getProtocol() : rule.min().getVersionIntroducedIn()),
                Lang.ph("max", protocolIds ? rule.max().getProtocol() : rule.max().getMostRecentSupportedVersion()));
        if (!rule.allow().isEmpty()) {
            result += lang.plain("common.versions.allow", Lang.ph("versions", labels(rule.allow(), protocolIds)));
        }
        if (!rule.deny().isEmpty()) {
            result += lang.plain("common.versions.deny", Lang.ph("versions", labels(rule.deny(), protocolIds)));
        }
        return result;
    }

    private static String labels(Set<ProtocolVersion> versions, boolean protocolIds) {
        return versions.stream().sorted()
                .map(version -> protocolIds ? Integer.toString(version.getProtocol()) : label(version))
                .collect(Collectors.joining(", "));
    }

    public static String label(ProtocolVersion version) {
        String first = version.getVersionIntroducedIn();
        String last = version.getMostRecentSupportedVersion();
        return first.equals(last) ? first : first + "～" + last;
    }

}
