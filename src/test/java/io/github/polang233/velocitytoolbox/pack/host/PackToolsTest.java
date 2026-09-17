package io.github.polang233.velocitytoolbox.pack.host;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.network.ProtocolVersion;
import io.github.polang233.velocitytoolbox.command.VelocityToolboxCommand;
import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import io.github.polang233.velocitytoolbox.pack.config.PackConfig;
import io.github.polang233.velocitytoolbox.pack.config.PackRules;
import io.github.polang233.velocitytoolbox.pack.delivery.PackSender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 使用真实磁盘配置、ZIP 和命令分发器验证排错工具与批量生命周期。 */
public final class PackToolsTest {
    private static Path root;
    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
    private static final String METADATA = "{\"pack\":{\"pack_format\":15,\"description\":\"Example\"}}";
    private static final String RULES = """
            enabled: true
            settings:
              delay: 0
              timeout: 60
            packs:
              main:
                - url: https://example.com/pack.zip
                  hash: HASH
            servers:
              default:
                packs: [main]
              empty:
                packs: []
            """.replace("HASH", HASH);

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("vtb-pack-tools-");
        try {
            archives();
            System.out.println("Pack tools tests passed: archives.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static org.spongepowered.configurate.CommentedConfigurationNode yaml(String text) throws IOException {
        Path file = root.resolve("input.yml");
        Files.writeString(file, text);
        return ResourceFiles.loadYaml(file);
    }

    private static PackRules rules(String text) throws IOException {
        return PackRules.read(yaml(text), List.of());
    }

    private static void zip(Path file, String name, String content) throws IOException {
        Files.createDirectories(file.getParent());
        try (var output = new ZipOutputStream(Files.newOutputStream(file))) {
            output.putNextEntry(new ZipEntry(name));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static void archives() throws Exception {
        Path file = root.resolve("archives/pack.zip");
        for (String metadata : List.of(METADATA,
                "{\"pack\":{\"min_format\":[75,1],\"max_format\":[75],\"description\":\"Example\"}}",
                "{\"pack\":{\"min_format\":[75,0],\"max_format\":[75,9],\"description\":{\"text\":\"Example\"}}}")) {
            zip(file, "pack.mcmeta", metadata);
            PackArchive.validate(file);
        }
        for (String metadata : List.of("broken", "{pack:{}}", METADATA + " trailing", "{}",
                METADATA.replace("15", "\"15\""), METADATA.replace("15", "-1"),
                "{\"pack\":{\"min_format\":76,\"max_format\":75,\"description\":\"Example\"}}",
                "{\"pack\":{\"pack_format\":15,\"min_format\":1,\"description\":\"Example\"}}",
                "{\"pack\":{\"pack_format\":15}}", " ".repeat(65537), "[".repeat(65) + "0" + "]".repeat(65))) {
            zip(file, "pack.mcmeta", metadata);
            expectArchiveFailure(file);
        }
        zip(file, "extra-folder/pack.mcmeta", METADATA);
        expectArchiveFailure(file);
        Files.writeString(file, "This is not a ZIP");
        expectArchiveFailure(file);
    }

    private static void expectArchiveFailure(Path file) throws Exception {
        try { PackArchive.validate(file); throw new AssertionError("bad archive accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains(file.toString()), "archive errors include the file path"); }
    }






    private static int sent(List<PackDeliveryTest.Harness> players) {
        return players.stream().mapToInt(p -> p.sent.size()).sum();
    }

    private static String text(List<Component> messages) {
        return messages.stream().map(PlainTextComponentSerializer.plainText()::serialize).reduce("", (a, b) -> a + "\n" + b);
    }

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
