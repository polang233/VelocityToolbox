package io.github.polang233.velocitytoolbox.pack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 不引入测试框架的构建期冒烟测试：验证多包片段、顺序和重名 ID。
 */
public final class PackSnippetWriterSmokeTest {

    private PackSnippetWriterSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("velocitytoolbox-pack-snippet-");
        Path snippet = directory.resolve("snippet.yml");
        try {
            HostedPack first = new HostedPack(
                    "base.pack.zip",
                    directory.resolve("base.pack.zip"),
                    "1111111111111111111111111111111111111111",
                    "https://packs.example/base.pack.zip");
            HostedPack second = new HostedPack(
                    "base-pack.zip",
                    directory.resolve("base-pack.zip"),
                    "2222222222222222222222222222222222222222",
                    "https://packs.example/base-pack.zip");

            PackSnippetWriter.write(snippet, directory, List.of(first, second));
            String yaml = Files.readString(snippet);

            require(yaml.contains("\"base-pack\":"), "缺少第一个资源包定义");
            require(yaml.contains("\"base-pack-2\":"), "重名资源包 ID 未消歧");
            require(yaml.contains("  packs:\n    - \"base-pack\"\n    - \"base-pack-2\"\n"),
                    "global.packs 未按顺序包含全部资源包");
            require(!yaml.contains("  pack: "), "不应再生成单包 global.pack");
        } finally {
            Files.deleteIfExists(snippet);
            Files.deleteIfExists(directory);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
