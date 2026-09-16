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
import java.util.Locale;
import java.util.Set;

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
        copyIfMissing("lang/zh_cn.yml", langDirectory.resolve("zh_cn.yml"));
        copyIfMissing("lang/zh_tw.yml", langDirectory.resolve("zh_tw.yml"));
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
                throw new IOException("Missing bundled resource: " + resourcePath);
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
                throw new IOException("Missing bundled resource: " + resourcePath);
            }
            Files.copy(in, destination);
        }
    }

    /**
     * 规范化语言值：留空时自动跟随服务器系统语言；系统语言没有内置语言文件时，
     * 上层按缺失语言回退到中文。
     */
    public static String canonicalLanguage(String language) {
        if (language == null || language.isBlank()) {
            return systemLanguage();
        }
        String trimmed = language.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT).replace('-', '_');
        Locale locale = Locale.forLanguageTag(normalized.replace('_', '-'));
        if ("zh".equals(locale.getLanguage())) {
            if (traditionalChinese(locale)) return "zh_tw";
            if ("Hans".equals(locale.getScript()) || Set.of("zh", "zh_cn", "zh_sg").contains(normalized)) return "zh_cn";
        }
        return switch (normalized) {
            case "en", "en_us" -> "en_us";
            default -> trimmed;
        };
    }

    private static String systemLanguage() {
        Locale locale = Locale.getDefault();
        return switch (locale.getLanguage()) {
            case "zh" -> traditionalChinese(locale) ? "zh_tw" : "zh_cn";
            case "en" -> "en_us";
            default -> locale.toLanguageTag().toLowerCase(Locale.ROOT).replace('-', '_');
        };
    }

    private static boolean traditionalChinese(Locale locale) {
        if (!locale.getScript().isEmpty()) return locale.getScript().equals("Hant");
        return Set.of("TW", "HK", "MO").contains(locale.getCountry());
    }
}
