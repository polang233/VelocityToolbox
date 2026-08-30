package io.github.polang233.velocitytoolbox.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 写出给 VelocityResourcepacks 合并用的片段。发不发包仍由那个插件决定。
 */
final class PackSnippetWriter {

    private PackSnippetWriter() {
    }

    static void write(Path snippetFile, Path packsDirectory, Collection<HostedPack> packs) throws IOException {
        List<NamedPack> namedPacks = uniqueNames(packs);
        StringBuilder yaml = new StringBuilder();
        yaml.append("# 由 VelocityToolbox 生成。把 packs / global 合并进 plugins/velocityresourcepacks/config.yml\n");
        yaml.append("# global.packs 需要 VelocityResourcepacks 1.9.0+；不同玩家组合可给 pack 增加 restricted / permission\n");
        yaml.append("# url 必须是玩家客户端能打开的直链。本插件只在本机起 HTTP，外网需自行放行端口后再改 public-url 并 /vtoolbox reload\n");
        yaml.append("packs:\n");
        if (namedPacks.isEmpty()) {
            yaml.append("  # 在 ").append(packsDirectory).append(" 里没有找到 zip\n");
        }
        for (NamedPack named : namedPacks) {
            HostedPack pack = named.pack();
            yaml.append("  ").append(yamlString(named.name())).append(":\n");
            yaml.append("    url: ").append(yamlString(pack.url())).append('\n');
            yaml.append("    hash: ").append(pack.sha1()).append('\n');
            yaml.append("    local-path: ")
                    .append(yamlString(pack.path().toAbsolutePath().toString().replace('\\', '/')))
                    .append('\n');
        }
        yaml.append("global:\n");
        if (!namedPacks.isEmpty()) {
            yaml.append("  # Minecraft 1.20.3+ 会按顺序叠加全部资源包；旧客户端只使用列表第一项\n");
            yaml.append("  packs:\n");
            for (NamedPack named : namedPacks) {
                yaml.append("    - ").append(yamlString(named.name())).append('\n');
            }
        }
        yaml.append("  send-delay: 20\n");
        Files.writeString(snippetFile, yaml.toString(), StandardCharsets.UTF_8);
    }

    private static List<NamedPack> uniqueNames(Collection<HostedPack> packs) {
        List<NamedPack> named = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (HostedPack pack : packs) {
            String base = PackScanner.packName(pack.fileName());
            String candidate = base;
            int suffix = 2;
            while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
                candidate = base + "-" + suffix++;
            }
            named.add(new NamedPack(candidate, pack));
        }
        return named;
    }

    private static String yamlString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n") + "\"";
    }

    private record NamedPack(String name, HostedPack pack) {
    }
}
