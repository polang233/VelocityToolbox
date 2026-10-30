package io.github.polang233.velocitytoolbox.config;

import io.github.polang233.velocitytoolbox.pack.config.PackConfig;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code plugins/VelocityToolbox/config.yml} 根配置。
 */
public final class PluginConfig {

    private final String language;
    private final PackConfig packHost;
    private final CommentedConfigurationNode root;

    private PluginConfig(String language, PackConfig packHost, CommentedConfigurationNode root) {
        this.language = language;
        this.packHost = packHost;
        this.root = root;
    }

    public static PluginConfig load(Path dataDirectory) throws IOException {
        ResourceFiles.ensureDefaults(dataDirectory);
        CommentedConfigurationNode root = ResourceFiles.loadYaml(dataDirectory.resolve("config.yml"));
        String language = ResourceFiles.canonicalLanguage(
                root.node("language").getString(""));
        return new PluginConfig(language, PackConfig.from(root.node("pack-host")), root);
    }

    public String language() {
        return language;
    }

    public PackConfig packHost() {
        return packHost;
    }

    /** 本次读取的 {@code resource-packs} 节点，避免重载时再次读文件。 */
    public CommentedConfigurationNode resourcePacks() {
        return root.node("resource-packs");
    }
}
