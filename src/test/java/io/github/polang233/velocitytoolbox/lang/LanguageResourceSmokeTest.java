package io.github.polang233.velocitytoolbox.lang;

import io.github.polang233.velocitytoolbox.config.ResourceFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 验证 YAML 特殊键修复，并模拟已有旧语言文件不会覆盖新的后台专用键。
 */
public final class LanguageResourceSmokeTest {

    private LanguageResourceSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("velocitytoolbox-lang-");
        try {
            Path langDirectory = Files.createDirectories(directory.resolve("lang"));
            Files.writeString(langDirectory.resolve("zh.yml"), """
                    prefix: "<gold>[OLD]</gold> "
                    command:
                      pack-host:
                        on: "旧开"
                        off: "旧关"
                    log:
                      started: "旧启动行 <version> <packhost> <dir>"
                    """);

            Lang lang = new Lang(directory);
            lang.load("zh");

            require(Files.isRegularFile(langDirectory.resolve("zh_cn.yml")),
                    "旧 zh.yml 没有迁移到规范文件名 zh_cn.yml");
            require("zh_cn".equals(ResourceFiles.canonicalLanguage("zh")),
                    "旧语言值 zh 没有规范化");
            require("en_us".equals(ResourceFiles.canonicalLanguage("en-US")),
                    "语言值 en-US 没有规范化");
            require("开".equals(lang.plain("command.pack-host.enabled")),
                    "enabled 键没有从随包语言回退");
            require("关".equals(lang.plain("command.pack-host.disabled")),
                    "disabled 键没有从随包语言回退");
            String started = lang.plain("log.console.started", Lang.ph("version", "1.1.0"));
            require(started.contains("VelocityToolbox 1.1.0 已启动"),
                    "新后台启动键被旧语言文件覆盖或未解析");
            require(lang.plain("command.help.info").contains("托管概要"),
                    "info 帮助语言键未解析");
            require("基本信息".equals(lang.plain("command.plugin.section.basic")),
                    "inspect 分段语言键未解析");
            require("作者".equals(lang.plain("command.field.authors")),
                    "插件字段语言键未解析");
            require(!Lang.COMMAND.equals(Lang.ACCENT), "命令颜色不应与前缀强调色相同");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
