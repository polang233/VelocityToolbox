package io.github.polang233.velocitytoolbox.pack.config;

import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code plugins/VelocityToolbox/config.yml} 里 {@code pack-host} 一段。
 *
 * <p>{@code packs-directory} 可以是绝对路径，或相对于本插件数据目录的路径。
 * {@code warnings} 是可忽略的问题（如旧版选项），由调用方写入后台。</p>
 */
public record PackConfig(boolean enabled, String bind, int port, String publicUrl, String packsDirectory,
                         HostLimits limits, List<String> warnings) {
    public PackConfig {
        warnings = List.copyOf(warnings);
    }

    public static PackConfig from(ConfigurationNode node) throws IOException {
        boolean enabled = node.node("enabled").getBoolean(false);
        List<String> warnings = new ArrayList<>();
        PackConfig config = new PackConfig(
                enabled,
                text(node.node("bind"), "0.0.0.0"),
                node.node("port").getInt(8765),
                text(node.node("public-url"), ""),
                text(node.node("packs-directory"), "packs"),
                enabled ? HostLimits.read(node.node("security"), warnings::add) : HostLimits.defaults(),
                warnings
        );
        if (enabled) validateOrigin(config.publicUrl());
        return config;
    }

    private static void validateOrigin(String origin) throws IOException {
        if (origin.isEmpty()) return;
        try {
            URI uri = URI.create(origin);
            if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("pack-host.public-url: expected an HTTP(S) base URL without credentials, query or fragment", invalid);
        }
    }

    /** 读取去掉首尾空白的文本；缺失或空白时使用默认值。 */
    private static String text(ConfigurationNode node, String fallback) {
        String value = node.getString("");
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
