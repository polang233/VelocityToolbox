package io.github.velocitytoolbox.pack;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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

    private PackConfig(
            boolean enabled,
            String bind,
            int port,
            String publicUrl,
            String packsDirectory
    ) {
        this.enabled = enabled;
        this.bind = bind;
        this.port = port;
        this.publicUrl = publicUrl;
        this.packsDirectory = packsDirectory;
    }

    public static PackConfig load(Path dataDirectory) throws IOException {
        Path configFile = dataDirectory.resolve("config.yml");
        if (!Files.exists(configFile)) {
            try (InputStream in = PackConfig.class.getClassLoader().getResourceAsStream("config.yml")) {
                if (in == null) {
                    throw new IOException("缺少随包提供的 config.yml");
                }
                Files.createDirectories(dataDirectory);
                Files.copy(in, configFile);
            }
        }

        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(configFile)
                .nodeStyle(NodeStyle.BLOCK)
                .build();
        CommentedConfigurationNode root = loader.load();
        CommentedConfigurationNode node = root.node("pack-host");
        return new PackConfig(
                node.node("enabled").getBoolean(true),
                node.node("bind").getString("0.0.0.0"),
                node.node("port").getInt(8765),
                blankToEmpty(node.node("public-url").getString("")),
                blankToEmpty(node.node("packs-directory").getString("packs"))
        );
    }

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
