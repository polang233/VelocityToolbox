package io.github.polang233.velocitytoolbox.lang;

import io.github.polang233.velocitytoolbox.config.ResourceFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

/**
 * 验证 YAML 特殊键修复、系统语言自动检测，以及用户语言文件对随包键的覆盖。
 */
public final class LanguageResourceSmokeTest {

    private LanguageResourceSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("velocitytoolbox-lang-");
        try {
            Path langDirectory = Files.createDirectories(directory.resolve("lang"));
            Files.writeString(langDirectory.resolve("zh_cn.yml"), """
                    prefix: "<gold>[USER]</gold> "
                    command:
                      pack-host:
                        on: "旧开"
                        off: "旧关"
                    log:
                      started: "旧启动行 <version> <packhost> <dir>"
                    """);

            String auto = ResourceFiles.canonicalLanguage("");
            require(!auto.isBlank(), "留空语言没有解析出系统语言");
            require(auto.equals(ResourceFiles.canonicalLanguage(null)),
                    "null 语言没有解析出系统语言");
            require(auto.equals(ResourceFiles.canonicalLanguage("  ")),
                    "空白语言没有解析出系统语言");
            require("zh_cn".equals(ResourceFiles.canonicalLanguage("zh")),
                    "语言值 zh 没有规范化");
            require("en_us".equals(ResourceFiles.canonicalLanguage("en-US")),
                    "语言值 en-US 没有规范化");

            Lang lang = new Lang(directory);
            lang.load("");

            lang.load("zh_cn");
            require(lang.plain("prefix").startsWith("[USER]"),
                    "用户语言文件没有覆盖随包键");
            require("开".equals(lang.plain("command.pack-host.enabled")),
                    "enabled 键没有从随包语言回退");
            require("关".equals(lang.plain("command.pack-host.disabled")),
                    "disabled 键没有从随包语言回退");
            String started = lang.plain("log.console.started", Lang.ph("version", "1.1.0"));
            require(started.contains("VelocityToolbox 1.1.0 已启动"),
                    "新后台启动键被用户语言文件覆盖或未解析");
            require(lang.plain("command.help.info").contains("托管概要"),
                    "info 帮助语言键未解析");
            require("基本信息".equals(lang.plain("command.plugin.section.basic")),
                    "inspect 分段语言键未解析");
            require("作者".equals(lang.plain("command.field.authors")),
                    "插件字段语言键未解析");
            require(!Lang.COMMAND.equals(Lang.ACCENT), "命令颜色不应与前缀强调色相同");
            require(Locale.getDefault().toLanguageTag() != null, "系统语言标签不可用");
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
