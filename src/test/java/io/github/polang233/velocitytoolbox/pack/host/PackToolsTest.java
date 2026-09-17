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
            urlsAndReasons();
            readOnlyCheck();
            System.out.println("Pack tools tests passed: archives, urlsAndReasons, readOnlyCheck.");
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

    private static void urlsAndReasons() throws Exception {
        for (String url : List.of("https://example.com:0/pack.zip", "https://example.com:65536/pack.zip",
                "https://example.com/pack.zip#fragment", "https://user:secret@example.com/pack.zip")) {
            try { rules(RULES.replace("https://example.com/pack.zip", url)); throw new AssertionError("invalid URL accepted"); }
            catch (IOException expected) { check(expected.getMessage().contains("packs.main[0].url"), "URL configuration path"); }
        }
        rules(RULES.replace("https://example.com/pack.zip", "https://example.com:443/pack.zip?token=example"));
        String conditional = RULES.replace("  main:\n", "  main:\n    - url: https://example.com/modern.zip\n"
                + "      hash: " + HASH + "\n      conditions:\n        versions:\n          min: '1.20.3'\n        permission: pack.vip\n");
        PackRules config = rules(conditional);
        for (var version : List.of(ProtocolVersion.MINECRAFT_1_15, ProtocolVersion.MINECRAFT_1_20_3)) {
            for (boolean permission : List.of(false, true)) {
                var explanation = config.explain("lobby", version, ignored -> permission);
                check(explanation.selection().equals(config.select("lobby", version, ignored -> permission)), "explanation uses actual selection");
                check(explanation.assignment().equals("default"), "default assignment source");
                PackRules.Reason expected = version == ProtocolVersion.MINECRAFT_1_15
                        ? permission ? PackRules.Reason.VERSION : PackRules.Reason.VERSION_PERMISSION
                        : permission ? PackRules.Reason.SELECTED : PackRules.Reason.PERMISSION;
                check(explanation.matches().getFirst().reason() == expected, "distinct mismatch reasons");
                check(explanation.matches().getLast().reason() == PackRules.Reason.SELECTED, "fallback selection is visible");
            }
        }
        check(config.explain("EMPTY", ProtocolVersion.MINECRAFT_1_20_3, p -> true).assignment().equals("empty"), "server source");
        check(config.explain("empty", ProtocolVersion.MINECRAFT_1_20_3, p -> true).matches().isEmpty(), "empty assignment diagnostic");
        var none = rules(RULES.replace("url: https://example.com/pack.zip\n      hash: " + HASH, "url: '@'"))
                .explain("lobby", ProtocolVersion.MINECRAFT_1_20_3, p -> true);
        check(none.matches().getFirst().reason() == PackRules.Reason.NONE && none.selection().packs().isEmpty(), "@ diagnostic");
    }

    private static void readOnlyCheck() throws Exception {
        Path directory = root.resolve("check");
        var h = new PackDeliveryTest.Harness(directory);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        String host = "enabled: true\nbind: 127.0.0.1\nport: " + port + "\npublic-url: http://127.0.0.1:" + port + "\npacks-directory: live\n";
        zip(directory.resolve("live/live.zip"), "pack.mcmeta", METADATA);
        try (var service = h.host; PackSender sender = h.sender()) {
            service.start(PackConfig.from(yaml(host)));
            sender.apply(rules(RULES));
            h.run(0);
            var liveRules = sender.rules();
            var liveFiles = service.packs();
            String liveOrigin = service.publicOrigin();
            zip(directory.resolve("candidate/candidate.zip"), "pack.mcmeta", METADATA);
            String localRules = RULES.replace("url: https://example.com/pack.zip\n      hash: " + HASH, "url: '@candidate.zip'");
            String file = "pack-host:\n" + host.replace("packs-directory: live", "packs-directory: candidate").indent(2)
                    + "resource-packs:\n" + localRules.indent(2);
            Files.writeString(directory.resolve("config.yml"), file);
            List<Path> before;
            try (var paths = Files.walk(directory)) { before = paths.sorted().toList(); }
            int messages = h.messages.size();
            var result = service.check();
            check(result.rules().enabled() && result.host().packs().containsKey("candidate.zip"), "candidate configuration checked");
            check(service.enabled() && service.publicOrigin().equals(liveOrigin) && service.packs().equals(liveFiles), "check preserves listener and live catalog");
            check(sender.rules() == liveRules && h.sent.size() == 1 && h.messages.size() == messages, "check does not apply or log quietly scanned state");
            try (var paths = Files.walk(directory)) { check(paths.sorted().toList().equals(before), "check creates no files"); }
            check(Files.readString(directory.resolve("config.yml")).equals(file), "check preserves config bytes");

            zip(directory.resolve("candidate/candidate.zip"), "pack.mcmeta", "bad json");
            try { service.check(); throw new AssertionError("invalid candidate ZIP accepted"); }
            catch (IOException expected) {
                check(expected.getMessage().contains("resource-packs.packs.main[0].url")
                        && expected.getMessage().contains("candidate.zip"), "invalid ZIP reports config and file paths");
            }
            check(service.packs().equals(liveFiles) && sender.rules() == liveRules, "failed check preserves active state");

            Files.writeString(directory.resolve("config.yml"), file.replace("packs-directory: candidate", "packs-directory: absent"));
            try { service.check(); throw new AssertionError("missing directory accepted"); }
            catch (IOException expected) { check(!Files.exists(directory.resolve("absent")), "check does not create missing directory"); }
            Files.writeString(directory.resolve("config.yml"), "pack-host:\n  enabled: false\nresource-packs:\n  enabled: false\n  packs:\n    demo:\n      - url: '@missing.zip'\n");
            check(!service.check().rules().enabled(), "disabled example rules are left inactive");
            Files.delete(directory.resolve("config.yml"));
            try { service.check(); throw new AssertionError("missing config accepted"); }
            catch (IOException expected) { check(!Files.exists(directory.resolve("config.yml")), "check does not regenerate missing config"); }
        }
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
