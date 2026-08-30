package io.github.polang233.velocitytoolbox.config;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 把随包默认文件写到数据目录（已存在则不覆盖）。
 */
public final class ResourceFiles {

    private ResourceFiles() {
    }

    public static void ensureDefaults(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        copyIfMissing("config.yml", dataDirectory.resolve("config.yml"));
        Path langDirectory = dataDirectory.resolve("lang");
        Files.createDirectories(langDirectory);
        migrateLegacyLanguage(langDirectory.resolve("zh.yml"), langDirectory.resolve("zh_cn.yml"));
        migrateLegacyLanguage(langDirectory.resolve("en.yml"), langDirectory.resolve("en_us.yml"));
        copyIfMissing("lang/zh_cn.yml", langDirectory.resolve("zh_cn.yml"));
        copyIfMissing("lang/en_us.yml", langDirectory.resolve("en_us.yml"));
    }

    public static CommentedConfigurationNode loadYaml(Path file) throws IOException {
        return YamlConfigurationLoader.builder()
                .path(file)
                .nodeStyle(NodeStyle.BLOCK)
                .build()
                .load();
    }

    public static CommentedConfigurationNode loadBundledYaml(String resourcePath) throws IOException {
        byte[] bytes;
        try (InputStream in = ResourceFiles.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("缺少随包资源 " + resourcePath);
            }
            bytes = in.readAllBytes();
        }
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new InputStreamReader(
                        new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)))
                .nodeStyle(NodeStyle.BLOCK)
                .build()
                .load();
    }

    public static void copyIfMissing(String resourcePath, Path destination) throws IOException {
        if (Files.exists(destination)) {
            return;
        }
        Files.createDirectories(destination.getParent());
        try (InputStream in = ResourceFiles.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("缺少随包资源 " + resourcePath);
            }
            Files.copy(in, destination);
        }
    }

    public static String canonicalLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "zh_cn";
        }
        String trimmed = language.trim();
        return switch (trimmed.toLowerCase(java.util.Locale.ROOT).replace('-', '_')) {
            case "zh", "zh_cn" -> "zh_cn";
            case "en", "en_us" -> "en_us";
            default -> trimmed;
        };
    }

    private static void migrateLegacyLanguage(Path legacy, Path canonical) throws IOException {
        if (!Files.exists(canonical) && Files.isRegularFile(legacy)) {
            Files.copy(legacy, canonical);
        }
    }
}
