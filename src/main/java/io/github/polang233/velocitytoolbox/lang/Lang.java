package io.github.polang233.velocitytoolbox.lang;

import com.velocitypowered.api.command.CommandSource;
import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家/控制台可见文案。默认跟随服务器系统语言，没有对应语言文件时回退中文；
 * 也可在 {@code config.yml} 的 {@code language} 固定为 {@code en_us}
 * 或 {@code lang/} 下自定义文件名。
 *
 * <p>主题色：强调 {@code #FF6600}，正文 {@code #CCFFFF}。1.16+ 客户端能显示 RGB。</p>
 */
public final class Lang {

    public static final TextColor ACCENT = TextColor.color(0xFF6600);
    public static final TextColor COMMAND = TextColor.color(0xFFB366);
    public static final TextColor BODY = TextColor.color(0xCCFFFF);
    public static final TextColor ERROR = TextColor.color(0xFF5555);

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Path dataDirectory;
    private volatile Map<String, String> messages = Map.of();
    private volatile Component prefix = Component.text("[VTB] ", ACCENT);

    public Lang(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public void load(String language) throws IOException {
        language = ResourceFiles.canonicalLanguage(language);
        ResourceFiles.ensureDefaults(dataDirectory);
        Map<String, String> bundledZh = loadBundled("zh_cn");
        Map<String, String> bundled = "zh_cn".equals(language)
                ? bundledZh
                : loadBundled(language);
        Map<String, String> user = loadUser(language);
        Map<String, String> merged = new LinkedHashMap<>(bundledZh);
        merged.putAll(bundled);
        merged.putAll(user);
        this.messages = Map.copyOf(merged);
        this.prefix = get("prefix");
    }

    public Component prefix() {
        return prefix;
    }

    public Component get(String key, TagResolver... resolvers) {
        String template = messages.getOrDefault(key, key);
        try {
            return MINI.deserialize(template, TagResolver.resolver(resolvers));
        } catch (RuntimeException exception) {
            return Component.text(template, BODY);
        }
    }

    public String plain(String key, TagResolver... resolvers) {
        return PLAIN.serialize(get(key, resolvers));
    }

    public void send(CommandSource source, String key, TagResolver... resolvers) {
        source.sendMessage(prefix.append(get(key, resolvers)));
    }

    public void send(CommandSource source, Component message) {
        source.sendMessage(prefix.append(message));
    }

    public static TagResolver ph(String name, Object value) {
        return Placeholder.unparsed(name, String.valueOf(value));
    }

    public static TagResolver[] placeholders(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return new TagResolver[0];
        }
        TagResolver[] resolvers = new TagResolver[values.size()];
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            resolvers[index++] = ph(entry.getKey(), entry.getValue());
        }
        return resolvers;
    }

    private Map<String, String> loadUser(String language) throws IOException {
        Path file = dataDirectory.resolve("lang").resolve(language + ".yml");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        return flatten(ResourceFiles.loadYaml(file));
    }

    private static Map<String, String> loadBundled(String language) {
        try {
            return flatten(ResourceFiles.loadBundledYaml("lang/" + language + ".yml"));
        } catch (IOException ignored) {
            return Map.of();
        }
    }

    private static Map<String, String> flatten(ConfigurationNode root) {
        Map<String, String> out = new LinkedHashMap<>();
        flatten(root, "", out);
        return out;
    }

    private static void flatten(ConfigurationNode node, String path, Map<String, String> out) {
        if (node.isMap()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.childrenMap().entrySet()) {
                String next = path.isEmpty() ? String.valueOf(entry.getKey()) : path + "." + entry.getKey();
                flatten(entry.getValue(), next, out);
            }
            return;
        }
        if (!path.isEmpty()) {
            String value = node.getString();
            if (value != null) {
                out.put(path, value);
            }
        }
    }
}
