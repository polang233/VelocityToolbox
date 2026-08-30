package io.github.polang233.velocitytoolbox.config;

import io.github.polang233.velocitytoolbox.pack.PackConfig;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.io.IOException;
import java.nio.file.Path;

/**
 * {@code plugins/VelocityToolbox/config.yml} 根配置。
 */
public final class PluginConfig {

    private final String language;
    private final PackConfig packHost;

    private PluginConfig(String language, PackConfig packHost) {
        this.language = language;
        this.packHost = packHost;
    }

    public static PluginConfig load(Path dataDirectory) throws IOException {
        ResourceFiles.ensureDefaults(dataDirectory);
        CommentedConfigurationNode root = ResourceFiles.loadYaml(dataDirectory.resolve("config.yml"));
        String language = ResourceFiles.canonicalLanguage(
                root.node("language").getString("zh_cn"));
        return new PluginConfig(language, PackConfig.from(root.node("pack-host")));
    }

    public String language() {
        return language;
    }

    public PackConfig packHost() {
        return packHost;
    }
}
