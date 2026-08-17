package io.github.velocitytoolbox.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

/**
 * 写出给 VelocityResourcepacks 合并用的片段。发不发包仍由那个插件决定。
 */
final class PackSnippetWriter {

    private PackSnippetWriter() {
    }

    static void write(Path snippetFile, Path packsDirectory, Collection<HostedPack> packs) throws IOException {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# 由 VelocityToolbox 生成。把 packs / global 合并进 plugins/velocityresourcepacks/config.yml\n");
        yaml.append("# url 必须是玩家客户端能打开的直链。本插件只在本机起 HTTP，外网需自行放行端口后再改 public-url 并 /vtoolbox reload\n");
        yaml.append("packs:\n");
        if (packs.isEmpty()) {
            yaml.append("  # 在 ").append(packsDirectory).append(" 里没有找到 zip\n");
        }
        HostedPack first = null;
        for (HostedPack pack : packs) {
            if (first == null) {
                first = pack;
            }
            String name = PackScanner.packName(pack.fileName());
            yaml.append("  ").append(name).append(":\n");
            yaml.append("    url: ").append(pack.url()).append('\n');
            yaml.append("    hash: ").append(pack.sha1()).append('\n');
            yaml.append("    local-path: \"")
                    .append(pack.path().toAbsolutePath().toString().replace('\\', '/'))
                    .append("\"\n");
        }
        yaml.append("global:\n");
        if (first != null) {
            yaml.append("  pack: ").append(PackScanner.packName(first.fileName())).append('\n');
        }
        yaml.append("  send-delay: 20\n");
        Files.writeString(snippetFile, yaml.toString(), StandardCharsets.UTF_8);
    }
}
