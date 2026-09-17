package io.github.polang233.velocitytoolbox.lang;

import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import io.github.polang233.velocitytoolbox.plugins.PluginInspection;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 语言结构、占位符、动态键和旧文件识别与重新生成。 */
public final class LanguageResourceSmokeTest {
    private static final Set<String> ROOTS = Set.of("common", "main", "plugin", "server", "pack");
    private static final Set<String> TAGS = Set.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
            "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white", "bold", "italic", "underlined", "strikethrough", "obfuscated", "reset", "newline");
    private static final Pattern TOKEN = Pattern.compile("<([a-z][a-z0-9_-]*)>");

    public static void main(String[] args) throws Exception {
        var config = ResourceFiles.loadBundledYaml("config.yml");
        require(config.node("pack-host", "security").childrenMap().keySet().equals(Set.of("max-downloads", "max-downloads-per-ip",
                "requests-per-minute-per-ip", "max-download-seconds", "bandwidth-mib", "per-download-mib", "trusted-proxies")),
                "public security options");
        var zh = ResourceFiles.loadBundledYaml("lang/zh_cn.yml");
        var en = ResourceFiles.loadBundledYaml("lang/en_us.yml");
        var tw = ResourceFiles.loadBundledYaml("lang/zh_tw.yml");
        require(zh.childrenMap().keySet().equals(ROOTS) && en.childrenMap().keySet().equals(ROOTS) && tw.childrenMap().keySet().equals(ROOTS), "module roots");
        Map<String, String> chinese = flatten(zh);
        Map<String, String> english = flatten(en);
        Map<String, String> traditional = flatten(tw);
        Map<String, Map<String, String>> locales = Map.of("zh_cn", chinese, "zh_tw", traditional, "en_us", english);
        for (var locale : locales.entrySet()) {
            require(chinese.keySet().equals(locale.getValue().keySet()), "locale key parity: " + locale.getKey());
            for (String key : chinese.keySet()) {
                require(tokens(chinese.get(key)).equals(tokens(locale.getValue().get(key))), "placeholder parity: " + key);
                require(key.matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*"), "key naming: " + key);
            }
        }
        for (var issue : PluginInspection.Issue.values())
            require(chinese.containsKey("plugin.inspect.issue." + issue.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')), "issue key");
        for (var risk : PluginInspection.Risk.values())
            require(chinese.containsKey("plugin.inspect.risk." + risk.name().toLowerCase(java.util.Locale.ROOT)), "risk key");
        for (String state : List.of("busy", "ready", "waiting", "accepted", "downloaded", "loaded", "failed", "declined", "timeout", "unavailable"))
            require(chinese.containsKey("pack.delivery.state." + state), "pack state key");
        for (var reason : io.github.polang233.velocitytoolbox.pack.config.PackRules.Reason.values())
            require(chinese.containsKey("pack.explain.reason." + reason.name().toLowerCase(Locale.ROOT).replace('_', '-')), "pack reason key");
        for (String module : List.of("plugin", "server", "pack"))
            require(chinese.containsKey(module + ".title") && chinese.containsKey(module + ".help.summary"), "module help key");

        String auto = ResourceFiles.canonicalLanguage("");
        require(!auto.isBlank() && auto.equals(ResourceFiles.canonicalLanguage(null))
                && auto.equals(ResourceFiles.canonicalLanguage("  ")), "system language");
        require(ResourceFiles.canonicalLanguage("zh").equals("zh_cn")
                && ResourceFiles.canonicalLanguage("en-US").equals("en_us"), "language aliases");
        Path directory = Files.createTempDirectory("vtb-lang-");
        try {
            Lang lang = new Lang(directory);
            // Exercise actual MiniMessage rendering for every message and placeholder.
            for (String locale : List.of("zh_cn", "zh_tw", "en_us")) {
                lang.load(locale);
                Map<String, String> messages = locales.get(locale);
                for (var entry : messages.entrySet()) {
                    Set<String> tokens = tokens(entry.getValue());
                    TagResolver[] resolvers = tokens.stream().map(token -> Lang.ph(token, "value-" + token)).toArray(TagResolver[]::new);
                    String rendered = lang.plain(entry.getKey(), resolvers);
                    require(!rendered.equals(entry.getKey()), "missing key: " + entry.getKey());
                    for (String token : tokens) require(rendered.contains("value-" + token), "missing rendered placeholder: " + entry.getKey() + "/" + token);
                }
                require(!lang.plain("common.yes").equals("common.yes") && !lang.plain("common.no").equals("common.no"), "quoted YAML boolean keys");
            }
            traditionalLocale(directory, lang);
            overridesAndRegeneration(directory, lang);
            malformedReload(directory, lang);
            require(!Lang.COMMAND.equals(Lang.ACCENT), "distinct command color");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("Language tests passed: module keys, placeholders, dynamic messages, regeneration and fallback.");
    }

    private static void traditionalLocale(Path directory, Lang lang) throws Exception {
        require(Files.isRegularFile(directory.resolve("lang/zh_tw.yml")), "bundled Traditional file generated");
        for (String tag : List.of("zh_TW", "zh-HK", "zh_mo", "zh-Hant", "zh-Hant-TW", "zh-Hant-CN")) {
            require(ResourceFiles.canonicalLanguage(tag).equals("zh_tw"), "Traditional language alias: " + tag);
        }
        for (String tag : List.of("zh", "zh_CN", "zh-SG", "zh-Hans", "zh-Hans-TW")) {
            require(ResourceFiles.canonicalLanguage(tag).equals("zh_cn"), "Simplified language alias: " + tag);
        }
        require(ResourceFiles.canonicalLanguage("zh_custom").equals("zh_custom"), "custom file name preserved");
        Locale original = Locale.getDefault();
        try {
            for (String tag : List.of("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-CN")) {
                Locale.setDefault(Locale.forLanguageTag(tag));
                lang.load("");
                require(lang.plain("plugin.title").equals("外掛管理"), "Traditional system locale: " + tag);
            }
            for (String tag : List.of("zh-CN", "zh-SG", "zh-Hans-TW")) {
                Locale.setDefault(Locale.forLanguageTag(tag));
                require(ResourceFiles.canonicalLanguage("").equals("zh_cn"), "Simplified system locale: " + tag);
            }
        } finally {
            Locale.setDefault(original);
        }
        Path file = directory.resolve("lang/zh_tw.yml");
        String custom = "common:\n  prefix: '[TW] '\n";
        Files.writeString(file, custom);
        lang.load("zh-HK");
        require(lang.plain("common.prefix").equals("[TW] ") && lang.plain("pack.source.local").equals("自行託管"),
                "partial Traditional override falls back to Traditional bundle");
        require(Files.readString(file).equals(custom), "existing Traditional file preserved");
    }

    private static void overridesAndRegeneration(Path directory, Lang lang) throws Exception {
        Path file = directory.resolve("lang/zh_cn.yml");
        String old = "prefix: '[OLD] '\ncommand:\n  help:\n    info: old text\n";
        Files.writeString(file, old);
        lang.load("zh_cn");
        require(lang.needsRegeneration(), "old structure requests regeneration");
        require(lang.plain("common.prefix").equals("[VTB] "), "old file uses current bundled text");
        require(Files.readString(file).equals(old), "old file is never overwritten");
        java.util.List<net.kyori.adventure.text.Component> notices = new java.util.ArrayList<>();
        var source = (com.velocitypowered.api.command.CommandSource) java.lang.reflect.Proxy.newProxyInstance(
                LanguageResourceSmokeTest.class.getClassLoader(),
                new Class<?>[]{com.velocitypowered.api.command.CommandSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage")) { notices.add((net.kyori.adventure.text.Component) args[0]); return null; }
                    throw new AssertionError(method);
                });
        lang.sendRegenerationNotice(source);
        String notice = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(notices.getFirst());
        require(notice.contains(file.toString()) && notice.contains("/vtb reload") && notice.contains("备份"), "actionable reload notice");
        Files.delete(file);
        lang.load("zh_cn");
        require(Files.isRegularFile(file) && !lang.needsRegeneration(), "missing bundled language regenerated");
        Files.writeString(file, "common:\n  prefix: '[CUSTOM] '\nmain:\n  help:\n    info: custom info\n");
        String current = Files.readString(file);
        lang.load("zh_cn");
        require(lang.plain("common.prefix").equals("[CUSTOM] ") && lang.plain("main.help.info").equals("custom info"), "current overrides");
        require(Files.readString(file).equals(current), "current custom file preserved");
        notices.clear();
        lang.sendRegenerationNotice(source);
        require(notices.isEmpty(), "no regeneration notice for current file");
        Files.writeString(directory.resolve("lang/custom.yml"), "common:\n  prefix: '[LOCAL] '\n");
        lang.load("custom");
        require(lang.plain("common.no").equals("否"), "custom language fallback");
        lang.load("en_us");
        require(lang.plain("common.separator").equals(": "), "English punctuation");
    }

    private static void malformedReload(Path directory, Lang lang) throws Exception {
        Files.writeString(directory.resolve("lang/broken.yml"), "common: [\n");
        String before = lang.plain("main.help.info");
        try {
            lang.load("broken");
            throw new AssertionError("malformed language accepted");
        } catch (IOException expected) {
            require(lang.plain("main.help.info").equals(before), "failed load preserves active language");
        }
    }

    private static Map<String, String> flatten(ConfigurationNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : node.childrenMap().entrySet()) {
            if (entry.getValue().isMap()) {
                flatten(entry.getValue()).forEach((key, value) -> result.put(entry.getKey() + "." + key, value));
            } else {
                require(entry.getValue().raw() instanceof String, "all messages must be quoted strings");
                result.put(entry.getKey().toString(), entry.getValue().getString());
            }
        }
        return result;
    }

    private static Set<String> tokens(String message) {
        Set<String> result = new LinkedHashSet<>();
        var matcher = TOKEN.matcher(message);
        while (matcher.find()) if (!TAGS.contains(matcher.group(1))) result.add(matcher.group(1));
        return result;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
