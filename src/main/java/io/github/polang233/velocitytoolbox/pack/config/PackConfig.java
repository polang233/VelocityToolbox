package io.github.polang233.velocitytoolbox.pack.config;

import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.nio.file.Path;

/**
 * {@code plugins/VelocityToolbox/config.yml} 里 {@code pack-host} 一段。
 *
 * <p>{@code packs-directory} 可以是绝对路径，或相对于本插件数据目录的路径。</p>
 */
public final class PackConfig {

    private final boolean enabled;
    private final String bind;
    private final int port;
    private final String publicUrl;
    private final String packsDirectory;
    private final HostLimits limits;

    private PackConfig(
            boolean enabled,
            String bind,
            int port,
            String publicUrl,
            String packsDirectory,
            HostLimits limits
    ) {
        this.enabled = enabled;
        this.bind = bind;
        this.port = port;
        this.publicUrl = publicUrl;
        this.packsDirectory = packsDirectory;
        this.limits = limits;
    }

    public static PackConfig load(Path dataDirectory) throws IOException {
        ResourceFiles.ensureDefaults(dataDirectory);
        return from(ResourceFiles.loadYaml(dataDirectory.resolve("config.yml")).node("pack-host"));
    }

    public static PackConfig from(CommentedConfigurationNode node) throws IOException {
        PackConfig config = new PackConfig(
                node.node("enabled").getBoolean(false),
                node.node("bind").getString("0.0.0.0"),
                node.node("port").getInt(8765),
                blankToEmpty(node.node("public-url").getString("")),
                blankToEmpty(node.node("packs-directory").getString("packs")),
                node.node("enabled").getBoolean(false) ? HostLimits.read(node.node("security")) : HostLimits.defaults()
        );
        if (config.enabled()) validateOrigin(config.publicUrl());
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

    public HostLimits limits() { return limits; }

    public boolean enabled() {
        return enabled;
    }

    public String bind() {
        return bind == null || bind.isBlank() ? "0.0.0.0" : bind.trim();
    }

    public int port() {
        return port;
    }

    public String publicUrl() {
        return publicUrl;
    }

    public String packsDirectory() {
        return packsDirectory.isBlank() ? "packs" : packsDirectory;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
