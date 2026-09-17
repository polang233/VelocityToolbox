package io.github.polang233.velocitytoolbox.pack.host;

import io.github.polang233.velocitytoolbox.pack.delivery.PackSender;

import io.github.polang233.velocitytoolbox.pack.config.PackRules;

import io.github.polang233.velocitytoolbox.pack.config.PackConfig;

import com.velocitypowered.api.command.*;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.scheduler.*;
import com.mojang.brigadier.CommandDispatcher;
import io.github.polang233.velocitytoolbox.command.VelocityToolboxCommand;
import io.github.polang233.velocitytoolbox.command.ModuleStatus;
import io.github.polang233.velocitytoolbox.plugins.PluginLoadService;
import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import io.github.polang233.velocitytoolbox.lang.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.ConfigurationNode;

import java.io.IOException;
import java.lang.reflect.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/**
 * Real parser, HTTP listener and event handlers; a deterministic clock replaces the proxy scheduler.
 */
public final class PackDeliveryTest {
    private static Path dir;
    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";
    private static final Logger LOG = LoggerFactory.getLogger(PackDeliveryTest.class);
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String YAML = """
            enabled: true
            settings:
              delay: 0
              timeout: 2
            packs:
              base:
                - url: https://example.com/base.zip
                  hash: HASH
              extra:
                - url: https://example.com/new.zip
                  hash: HASH
                  conditions:
                    versions:
                      min: "1.20.3"
                - url: https://example.com/old.zip
                  hash: HASH
                  conditions:
                    versions:
                      max: "1.20.2"
              vip:
                - url: https://example.com/vip.zip
                  hash: HASH
                  required: true
                  conditions:
                    permission: pack.vip
            servers:
              default:
                packs: [base]
              survival:
                packs: [base, extra]
              empty:
                packs: []
              vip:
                packs: [vip]
            """.replace("HASH", HASH);

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("vtb-delivery-");
        try {
            hostOptions();
            rules();
            redesignedRules();
            noPack();
            limitedRetry();
            http();
            events();
            globalAssignments();
            legacyRequiredReplies();
            mixedOffers();
            commands();
            System.out.println("Pack delivery tests passed: config, selection, real HTTP, reload, events, stale replies, timeout, commands.");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void hostOptions() throws Exception {
        for (String origin : List.of("https://packs.example.com?token=abc", "https://packs.example.com#section",
                "ftp://packs.example.com", "https://user:secret@packs.example.com", "https://packs.example.com:0")) {
            try {
                PackConfig.from((org.spongepowered.configurate.CommentedConfigurationNode) yaml("enabled: true\npublic-url: '" + origin + "'\n"));
                throw new AssertionError("invalid hosting origin accepted: " + origin);
            } catch (IOException expected) {
                check(expected.getMessage().contains("pack-host.public-url"), "origin validation path");
            }
        }
        check(PackConfig.from((org.spongepowered.configurate.CommentedConfigurationNode) yaml(
                "enabled: true\npublic-url: 'https://packs.example.com/downloads'\n")).publicUrl().endsWith("/downloads"), "reverse-proxy prefix");
        check(PackConfig.from((org.spongepowered.configurate.CommentedConfigurationNode) yaml(
                "enabled: true\npublic-url: ''\n")).publicUrl().isEmpty(), "automatic LAN origin remains supported");
    }

    private static ConfigurationNode yaml(String text) throws IOException {
        Path file = dir.resolve("test.yml");
        Files.writeString(file, text);
        return ResourceFiles.loadYaml(file);
    }

    private static PackRules rules(String text) throws IOException {
        return PackRules.read(yaml(text), List.of());
    }

    private static void rules() throws Exception {
        PackRules rules = rules(YAML);
        var modern = rules.select("survival", ProtocolVersion.MINECRAFT_1_20_3, x -> true);
        check(modern.packs().stream().map(PackRules.Choice::name).toList().equals(List.of("base", "extra")), "stack order");
        check(modern.packs().get(1).file().url().endsWith("new.zip"), "modern variant");
        var legacy = rules.select("survival", ProtocolVersion.MINECRAFT_1_12_2, x -> true);
        check(legacy.packs().size() == 1 && legacy.packs().getFirst().name().equals("base"), "legacy first");
        check(rules.select("missing", ProtocolVersion.MINECRAFT_1_20_3, x -> true).packs().size() == 1, "default");
        check(rules.select("empty", ProtocolVersion.MINECRAFT_1_20_3, x -> true).packs().isEmpty(), "empty replaces default");
        var denied = rules.select("vip", ProtocolVersion.MINECRAFT_1_20_3, x -> false);
        check(denied.packs().isEmpty() && denied.skipped().equals(List.of("vip")), "required permission skips");
        for (String bad : List.of(YAML.replace(HASH, "bad"), YAML.replace("packs: [base]", "packs: [missing]"),
                YAML.replace("delay: 0", "delay: -1"), YAML.replace("timeout: 2", "timeout: 0"),
                YAML.replace("min: \"1.20.3\"", "min: 999999"), YAML.replace("enabled: true", "enabled: \"yes\""),
                YAML.replace("url: https://example.com/base.zip", "url: \"@missing.zip\""),
                YAML.replace("packs: [base]", "packs: [base, base]"))) {
            try {
                rules(bad);
                throw new AssertionError("accepted bad config");
            } catch (IOException expected) {
            }
        }
        check(!PackRules.read(yaml("{}").node("resource-packs"), List.of()).enabled(), "missing disabled");
        check(rules("enabled: true\npacks: {}\nservers: {}\n").packs().isEmpty(), "empty enabled config");
    }

    private static void redesignedRules() throws Exception {
        var defaults = rules("enabled: true\npacks: {}\n");
        check(defaults.delay() == 3000 && defaults.timeout() == 60000 && defaults.defaults().packs().isEmpty(), "settings defaults");
        String inherited = YAML.replace("settings:", "settings:\n  required: true\n  prompt: global");
        var selection = rules(inherited).select("survival", ProtocolVersion.MINECRAFT_1_20_3, x -> true);
        check(selection.packs().stream().allMatch(PackRules.Choice::required), "global required");
        check(PLAIN.serialize(selection.packs().getFirst().prompt()).equals("global"), "global prompt");
        String override = inherited.replace("- url: https://example.com/new.zip",
                "- required: false\n      prompt: local\n      url: https://example.com/new.zip");
        selection = rules(override).select("survival", ProtocolVersion.MINECRAFT_1_20_3, x -> true);
        check(selection.packs().getFirst().required() && !selection.packs().getLast().required(), "per-variant override");
        check(PLAIN.serialize(selection.packs().getLast().prompt()).equals("local"), "variant prompt");
        String fallback = YAML.replace("min: \"1.20.3\"", "min: \"1.20.3\"\n          deny: [\"1.20.3\"]")
                .replace("max: \"1.20.2\"", "max: max");
        check(rules(fallback).select("survival", ProtocolVersion.MINECRAFT_1_20_3, x -> true)
                .packs().getLast().file().url().endsWith("old.zip"), "deny selects next variant");
        fallback = YAML.replace("min: \"1.20.3\"", "min: \"1.20.3\"\n        permission: modern")
                .replace("max: \"1.20.2\"", "max: max");
        check(rules(fallback).select("survival", ProtocolVersion.MINECRAFT_1_20_3, x -> false)
                .packs().getLast().file().url().endsWith("old.zip"), "permission fallback");
        check(rules(fallback).select("survival", ProtocolVersion.MINECRAFT_1_20_3, x -> true)
                .packs().getLast().file().url().endsWith("new.zip"), "first match wins");
        for (String old : List.of("delay: 1", "default: [base]", "required-prompt: hello")) {
            try {
                rules(YAML + old + "\n");
                throw new AssertionError("obsolete root option");
            } catch (IOException expected) {
                check(expected.getMessage().contains("resource-packs.") && expected.getMessage().contains("docs/RESOURCE_PACKS.md"), "migration path");
            }
        }
        for (String bad : List.of(
                YAML.replace("  survival:\n    packs: [base, extra]", "  survival:\n    required: true\n    packs: [base, extra]"),
                YAML.replace("hash: " + HASH, "hash: \"@\""),
                YAML.replace("hash: " + HASH, ""),
                YAML.replace("conditions:", "versions:"),
                YAML.replace("https://example.com/base.zip", "@../base.zip"))) {
            try { rules(bad); throw new AssertionError("invalid new schema accepted"); }
            catch (IOException expected) { }
        }
        Path directory = dir.resolve("hash-check");
        var hosted = new HostedPack("base.zip", directory.resolve("base.zip"), HASH, "https://example.com/base.zip");
        String local = "enabled: true\npacks:\n  base:\n    - url: \"@base.zip\"\n";
        for (String suffix : List.of("", "      hash: \"@\"\n", "      hash: " + HASH + "\n"))
            check(PackRules.read(yaml(local + suffix), List.of(hosted), directory).packs()
                    .get("base").variants().getFirst().file().sha1().equals(HASH), "local hash resolution");
        try {
            PackRules.read(yaml(local + "      hash: " + "a".repeat(40)), List.of(hosted), directory);
            throw new AssertionError("local mismatched hash");
        } catch (IOException expected) { check(expected.getMessage().contains(".hash"), "hash error path"); }
        try {
            PackRules.read(yaml(local), List.of(), directory);
            throw new AssertionError("missing file");
        } catch (IOException expected) {
            check(expected.getMessage().contains(directory.toString()) && expected.getMessage().contains("packs.base[0].url"), "missing file diagnostic");
        }
    }

    private static void noPack() throws Exception {
        String empty = "enabled: true\nsettings:\n  required: true\npacks:\n  default:\n    - url: \"@\"\nservers:\n  default:\n    packs: [default]\n";
        PackRules rules = PackRules.read(yaml(empty), List.of(), dir, false);
        check(rules.select("lobby", ProtocolVersion.MINECRAFT_1_20_3, permission -> true).packs().isEmpty(),
                "@ needs neither hosting nor an offer even when required");
        Harness h = new Harness(dir.resolve("no-pack"));
        try (PackSender sender = h.sender()) {
            sender.apply(rules(YAML));
            h.run(0);
            ResourcePackInfo old = h.sent.getFirst();
            sender.apply(rules);
            h.run(4000);
            check(h.sent.size() == 1 && h.removed.contains(old.getId()) && h.kicked == null, "@ clears owned modern packs");
            check(sender.resend(h.player) == PackSender.ResendResult.EMPTY, "@ resend sends nothing");
        }
        try {
            PackRules.read(yaml(empty.replace("url: \"@\"", "url: \"@absent.zip\"")), List.of(), dir, false);
            throw new AssertionError("disabled hosting reference");
        } catch (IOException expected) { check(expected.getMessage().contains("self-hosting is disabled"), "distinct disabled message"); }
        try {
            PackRules.read(yaml(empty.replace("url: \"@\"", "url: \"@absent.zip\"")), List.of(), dir, true);
            throw new AssertionError("missing hosted file");
        } catch (IOException expected) { check(expected.getMessage().contains("hosted file not found"), "distinct missing message"); }
    }

    private static void limitedRetry() throws Exception {
        Path root = Files.createDirectories(dir.resolve("limited-retry"));
        Files.createDirectories(root.resolve("packs"));
        zip(root.resolve("packs/base.zip"), "content");
        Harness h = new Harness(root);
        int port = freePort();
        PackConfig config = PackConfig.from((org.spongepowered.configurate.CommentedConfigurationNode) yaml(
                "enabled: true\nbind: 127.0.0.1\nport: " + port + "\npublic-url: http://127.0.0.1:" + port
                + "\npacks-directory: packs\nsecurity:\n  burst-per-ip: 1\n  requests-per-minute-per-ip: 1\n"));
        try (PackService host = h.host; HttpClient client = HttpClient.newHttpClient(); PackSender sender = h.sender()) {
            host.apply(host.prepare(config));
            sender.apply(PackRules.read(yaml("enabled: true\nsettings:\n  delay: 0\n  timeout: 60\npacks:\n  local:\n"
                    + "    - url: \"@base.zip\"\n      required: true\nservers:\n  default:\n    packs: [local]\n"), host.packs(), root.resolve("packs"), true));
            h.run(0);
            var first = h.sent.getLast();
            check(!first.getShouldForce(), "VTB enforces required during local retry");
            var request = HttpRequest.newBuilder(URI.create(first.getUrl())).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            check(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200, "initial local request");
            for (int i = 0; i < 20; i++) client.send(request, HttpResponse.BodyHandlers.discarding());
            for (int i = 0; i < 3; i++) {
                var offer = h.sent.getLast();
                var limited = client.send(HttpRequest.newBuilder(URI.create(offer.getUrl())).method("HEAD",
                        HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
                check(limited.statusCode() == 429, "real host records limiting");
                sender.status(reply(h, offer, "FAILED_DOWNLOAD"));
                if (i < 2) {
                    check(h.kicked == null, "overload waits before enforcing required");
                    h.run(5000);
                    check(!h.sent.getLast().getId().equals(offer.getId()), "retry gets new offer");
                    sender.status(reply(h, offer, "FAILED_DOWNLOAD"));
                    check(h.kicked == null, "old retry response ignored");
                }
            }
            check(h.kicked != null && PLAIN.serialize(h.kicked).contains("busy"), "bounded retries retain required policy");
        }
    }

    private static void mixedOffers() throws Exception {
        String mixed = YAML.replace("packs: [base]", "packs: [base, vip]");
        for (String failure : List.of("DECLINED", "FAILED_DOWNLOAD", "TIMEOUT", "THROW")) {
            Harness h = new Harness(dir.resolve("mixed-" + failure));
            h.permissions.add("pack.vip");
            h.throwUrl = failure.equals("THROW") ? "base.zip" : "";
            try (PackSender sender = h.sender()) {
                sender.apply(rules(mixed));
                h.run(0);
                ResourcePackInfo optional = failure.equals("THROW") ? null : h.sent.getFirst();
                ResourcePackInfo required = h.sent.getLast();
                check(h.status(sender).contains("optional") && h.status(sender).contains("required"), "mixed status");
                sender.status(reply(h, required, "SUCCESSFUL"));
                if (failure.equals("TIMEOUT")) h.run(3000);
                else if (!failure.equals("THROW")) sender.status(reply(h, optional, failure));
                check(h.kicked == null, "optional failure in mixed assignment: " + failure);
            }
            Harness required = new Harness(dir.resolve("mixed-required-" + failure));
            required.permissions.add("pack.vip");
            required.throwUrl = failure.equals("THROW") ? "vip.zip" : "";
            try (PackSender sender = required.sender()) {
                sender.apply(rules(mixed));
                required.run(0);
                if (failure.equals("TIMEOUT")) required.run(3000);
                else if (!failure.equals("THROW")) sender.status(reply(required, required.sent.getLast(), failure));
                check(required.kicked != null, "required failure in mixed assignment: " + failure);
            }
        }
        Harness h = new Harness(dir.resolve("changed-variant"));
        h.server = "survival";
        try (PackSender sender = h.sender()) {
            sender.apply(rules(YAML));
            h.run(0);
            ResourcePackInfo base = h.sent.getFirst();
            String changed = YAML;
            for (String replacement : List.of("newer.zip", "required: true\n      url: https://example.com/newer.zip",
                    "prompt: changed\n      required: true\n      url: https://example.com/newer.zip")) {
                changed = replacement.equals("newer.zip") ? YAML.replace("new.zip", replacement)
                        : YAML.replace("url: https://example.com/new.zip", replacement);
                int before = h.sent.size();
                sender.apply(rules(changed));
                h.run(0);
                check(h.sent.size() == before + 1 && !h.removed.contains(base.getId()), "changed variant only resends suffix");
            }
            PackRules previous = sender.rules();
            try { sender.apply(rules(changed.replace("hash: " + HASH, "hash: invalid"))); }
            catch (IOException expected) { }
            check(sender.rules() == previous, "invalid reload preserves applied rules");
            h.server = "empty";
            check(sender.resend(h.player) == PackSender.ResendResult.EMPTY, "empty resend result");
            h.run(0);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static PackConfig hostConfig(int port, String bind) throws IOException {
        return PackConfig.from((org.spongepowered.configurate.CommentedConfigurationNode) yaml(
                "enabled: true\nbind: " + bind + "\nport: " + port + "\npublic-url: http://127.0.0.1:" + port + "\npacks-directory: packs\n"));
    }

    private static void zip(Path file, String value) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("pack.mcmeta"));
            out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static void http() throws Exception {
        Path root = dir.resolve("host");
        Files.createDirectories(root.resolve("packs"));
        zip(root.resolve("packs/base.zip"), "first");
        for (String example : List.of("survival.zip", "rpg-modern.zip", "rpg-legacy.zip", "ui.zip", "vip.zip")) {
            zip(root.resolve("packs").resolve(example), "example");
        }
        Harness h = new Harness(root);
        int port = freePort();
        try (PackService host = h.host; HttpClient client = HttpClient.newHttpClient()) {
            PackConfig config = hostConfig(port, "127.0.0.1");
            var staged = host.prepare(config);
            var bundled = ResourceFiles.loadBundledYaml("config.yml").node("resource-packs");
            check(!PackRules.read(bundled, List.of()).enabled(), "bundled delivery disabled");
            bundled.node("enabled").set(true);
            var enabledDefaults = PackRules.read(bundled, staged.list(), staged.directory());
            check(enabledDefaults.select("unlisted", ProtocolVersion.MINECRAFT_1_20_3, x -> true).packs().isEmpty(),
                    "default @ is an empty selection");
            check(enabledDefaults.packs().size() == 6, "all reference packs parsed");
            check(enabledDefaults.select("lobby", ProtocolVersion.MINECRAFT_1_20_3, permission -> true).packs().isEmpty(),
                    "lobby clears packs");
            check(enabledDefaults.select("rpg", ProtocolVersion.MINECRAFT_1_20_3, permission -> true).packs().size() == 2,
                    "modern rpg layers");
            check(enabledDefaults.select("rpg", ProtocolVersion.MINECRAFT_1_15, permission -> true)
                    .packs().getFirst().file().url().endsWith("/packs/rpg-legacy.zip"), "legacy complete pack");
            check(enabledDefaults.select("vip", ProtocolVersion.MINECRAFT_1_20_3, permission -> false).skipped().contains("vip"),
                    "vip permission example");
            PackRules local = PackRules.read(yaml("enabled: true\npacks:\n  base:\n    - url: \"@base.zip\"\nservers:\n  default:\n    packs: [base]\n"), staged.list());
            check(local.packs().get("base").variants().getFirst().file().local(), "resolved local file");
            host.apply(staged);
            check(host.httpStatus().contains("active downloads") && !host.httpStatus().contains("<downloads>"),
                    "HTTP statistics are localized with real values");
            URI uri = URI.create("http://127.0.0.1:" + port + "/packs/base.zip");
            var get = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            check(get.statusCode() == 200 && Arrays.equals(get.body(), Files.readAllBytes(root.resolve("packs/base.zip"))), "HTTP GET bytes");
            var head = client.send(HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            check(head.statusCode() == 200 && head.headers().firstValue("Content-Length").isPresent(), "HTTP HEAD");
            String hash = host.packs().getFirst().sha1();
            zip(root.resolve("packs/base.zip"), "updated");
            host.apply(host.prepare(config));
            check(!host.packs().getFirst().sha1().equals(hash), "changed hash");
            try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                try {
                    host.apply(host.prepare(hostConfig(occupied.getLocalPort(), "127.0.0.1")));
                    throw new AssertionError("occupied port");
                } catch (IOException expected) {
                }
            }
            check(host.enabled() && client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200, "old listener survives");
            try {
                host.apply(host.prepare(hostConfig(port, "203.0.113.254")));
                throw new AssertionError("nonlocal bind");
            } catch (IOException expected) {
            }
            check(host.enabled() && client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200, "same-port restoration");
            try {
                PackRules.read(yaml("enabled: true\npacks:\n  broken:\n    - url: \"@absent.zip\"\n"), host.prepare(config).list());
                throw new AssertionError("missing local");
            } catch (IOException expected) {
            }
            check(host.enabled(), "bad rule does not stop host");
        }
    }

    private static void events() throws Exception {
        Harness h = new Harness(dir.resolve("events"));
        try (PackSender sender = h.sender()) {
            sender.apply(rules(YAML));
            h.run(0);
            check(h.sent.size() == 1, "initial login offer");
            ResourcePackInfo first = h.sent.getFirst();
            sender.status(reply(h, first, "ACCEPTED"));
            check(h.status(sender).contains("accepted"), "accept is not loaded");
            sender.status(reply(h, first, "SUCCESSFUL"));
            sender.apply(rules(YAML));
            h.run(0);
            check(h.sent.size() == 1, "unchanged reload does not resend");
            h.server = "survival";
            sender.connected(new ServerPostConnectEvent(h.player, null));
            h.run(0);
            check(h.sent.size() == 2, "keep common prefix");
            ResourcePackInfo extra = h.sent.getLast();
            h.server = "empty";
            sender.connected(new ServerPostConnectEvent(h.player, null));
            sender.status(reply(h, extra, "DECLINED"));
            h.run(3000);
            check(h.kicked == null && h.removed.contains(extra.getId()), "stale reply and timer cannot kick after switch");
            h.server = "lobby";
            sender.connected(new ServerPostConnectEvent(h.player, null));
            h.run(0);
            ResourcePackInfo before = h.sent.getLast();
            sender.resend(h.player);
            h.run(0);
            ResourcePackInfo after = h.sent.getLast();
            check(!before.getId().equals(after.getId()), "resend has new offer id");
            sender.status(reply(h, before, "SUCCESSFUL"));
            check(!h.status(sender).contains(" · loaded"), "late success ignored");
            sender.status(reply(h, after, "FAILED_DOWNLOAD"));
            check(h.kicked == null && h.status(sender).contains("failed"), "optional failure");
            sender.resend(h.player);
            h.run(0);
            h.run(3000);
            check(h.kicked == null && h.status(sender).contains("timed out"), "optional timeout");
            h.server = "vip";
            sender.connected(new ServerPostConnectEvent(h.player, null));
            h.run(0);
            check(h.kicked == null && h.status(sender).contains(h.lang.plain("pack.delivery.skipped", Lang.ph("packs", "vip"))), "required missing permission skips");
        }
        Harness required = new Harness(dir.resolve("required"));
        try (PackSender sender = required.sender()) {
            sender.apply(rules(YAML.replace("settings:", "settings:\n  required: true")));
            required.run(0);
            sender.status(reply(required, required.sent.getLast(), "DECLINED"));
            check(required.kicked != null, "required declined");
        }
        Harness timed = new Harness(dir.resolve("timed"));
        try (PackSender sender = timed.sender()) {
            sender.apply(rules(YAML.replace("settings:", "settings:\n  required: true")));
            timed.run(0);
            timed.run(3000);
            check(timed.kicked != null, "required timeout");
        }
        Harness quick = new Harness(dir.resolve("quick"));
        try (PackSender sender = quick.sender()) {
            sender.apply(rules(YAML.replace("delay: 0", "delay: 1")));
            quick.server = "survival";
            sender.connected(new ServerPostConnectEvent(quick.player, null));
            quick.server = "empty";
            sender.connected(new ServerPostConnectEvent(quick.player, null));
            quick.run(1000);
            check(quick.sent.isEmpty(), "quick switches cancel old sends");
        }
        Harness old = new Harness(dir.resolve("old"));
        old.version = ProtocolVersion.MINECRAFT_1_12_2;
        try (PackSender sender = old.sender()) {
            sender.apply(rules(YAML));
            old.run(0);
            int count = old.sent.size();
            sender.backend(new ServerResourcePackSendEvent(old.sent.getLast(), old.connection));
            sender.apply(rules(YAML));
            old.run(0);
            check(old.sent.size() == count && old.removed.isEmpty(), "legacy backend wins across reload");
            sender.connected(new ServerPostConnectEvent(old.player, null));
            old.run(0);
            check(old.sent.size() == count, "postconnect does not overwrite backend offer from same server");
        }
        Harness rejoin = new Harness(dir.resolve("rejoin"));
        PackSender sender = rejoin.sender();
        sender.apply(rules(YAML));
        rejoin.run(0);
        ResourcePackInfo departed = rejoin.sent.getLast();
        sender.disconnected(new DisconnectEvent(rejoin.player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        sender.connected(new ServerPostConnectEvent(rejoin.player, null));
        rejoin.run(0);
        sender.status(reply(rejoin, departed, "SUCCESSFUL"));
        check(!rejoin.status(sender).contains(" · loaded"), "disconnected session response ignored");
        ResourcePackInfo active = rejoin.sent.getLast();
        sender.backend(new ServerResourcePackSendEvent(active, rejoin.connection));
        check(!rejoin.removed.contains(active.getId()), "modern backend does not remove VTB pack");
        sender.apply(PackRules.disabled());
        check(rejoin.removed.contains(active.getId()), "disable removes owned modern packs");
        int sent = rejoin.sent.size();
        rejoin.run(5000);
        check(rejoin.sent.size() == sent && rejoin.kicked == null, "disable cancels scheduled work");
        sender.close();
    }

    /** 全局规则同时更新多个玩家；各子服分配、客户端版本和回执互不影响。 */
    private static void globalAssignments() throws Exception {
        Harness modern = new Harness(dir.resolve("global-modern"));
        Harness legacy = new Harness(dir.resolve("global-legacy"));
        Harness custom = new Harness(dir.resolve("global-custom"));
        modern.server = "unlisted-a";
        legacy.server = "unlisted-b";
        legacy.version = ProtocolVersion.MINECRAFT_1_15;
        custom.server = "vip";
        modern.online.addAll(List.of(legacy.player, custom.player));
        String config = YAML.replace("packs: [base]", "packs: [base, extra]");
        try (PackSender sender = modern.sender()) {
            sender.apply(rules(config));
            modern.run(0);
            check(modern.sent.size() == 2, "global stack reaches modern online player");
            check(legacy.sent.size() == 1 && legacy.sent.getFirst().getUrl().endsWith("base.zip"),
                    "global stack gives legacy player the first complete pack");
            check(custom.sent.isEmpty() && custom.kicked == null,
                    "unmatched custom assignment does not fall back to global packs or kick");

            sender.status(reply(modern, legacy.sent.getFirst(), "SUCCESSFUL"));
            check(!modern.status(sender).contains(" · loaded") && !legacy.status(sender).contains(" · loaded"),
                    "a reply cannot update another player's offers");
            for (ResourcePackInfo offer : modern.sent) sender.status(reply(modern, offer, "SUCCESSFUL"));
            sender.status(reply(legacy, legacy.sent.getFirst(), "SUCCESSFUL"));

            List<UUID> original = modern.sent.stream().map(ResourcePackInfo::getId).toList();
            sender.apply(rules(config));
            modern.server = "unlisted-c";
            legacy.server = "unlisted-d";
            sender.connected(new ServerPostConnectEvent(modern.player, null));
            sender.connected(new ServerPostConnectEvent(legacy.player, null));
            modern.run(0);
            check(modern.sent.size() == 2 && legacy.sent.size() == 1 && modern.removed.isEmpty(),
                    "reload and switches between default servers retain unchanged packs");

            custom.permissions.add("pack.vip");
            check(sender.resend(custom.player) == PackSender.ResendResult.SCHEDULED, "permission change can be resent");
            modern.run(0);
            check(custom.sent.size() == 1 && custom.sent.getFirst().getUrl().endsWith("vip.zip"),
                    "custom assignment replaces the entire global list");
            ResourcePackInfo required = custom.sent.getFirst();
            custom.server = "empty";
            sender.connected(new ServerPostConnectEvent(custom.player, null));
            sender.status(reply(custom, required, "FAILED_DOWNLOAD"));
            modern.run(3000);
            check(custom.removed.contains(required.getId()) && custom.kicked == null && custom.sent.size() == 1,
                    "empty assignment removes owned packs and cancels old required deadlines");

            String reordered = config.replace("default:\n    packs: [base, extra]", "default:\n    packs: [extra, base]");
            sender.apply(rules(reordered));
            modern.run(0);
            check(modern.sent.size() == 4 && modern.sent.get(2).getUrl().endsWith("new.zip")
                    && modern.sent.get(3).getUrl().endsWith("base.zip") && modern.removed.containsAll(original),
                    "global reordering updates stack order on existing players");
            check(legacy.sent.size() == 2 && legacy.sent.getLast().getUrl().endsWith("old.zip")
                    && legacy.removed.isEmpty(), "global reordering respects legacy version selection");
            check(custom.sent.size() == 1, "global reload leaves explicitly empty servers empty");

            String withoutDefault = reordered.replace("  default:\n    packs: [extra, base]\n", "");
            ResourcePackInfo last = modern.sent.getLast();
            sender.apply(rules(withoutDefault));
            modern.run(3000);
            check(modern.removed.contains(last.getId()) && modern.sent.size() == 4 && legacy.sent.size() == 2,
                    "removing default stops allocation and removes modern owned packs");
            check(sender.resend(modern.player) == PackSender.ResendResult.EMPTY
                    && sender.resend(legacy.player) == PackSender.ResendResult.EMPTY,
                    "missing global assignment reports no packs for both client types");
        }
    }

    private static void legacyRequiredReplies() throws Exception {
        String requiredConfig = YAML.replace("settings:", "settings:\n  required: true");
        for (String change : List.of("switch", "resend", "backend", "disable")) {
            Harness h = new Harness(dir.resolve("legacy-required-" + change));
            h.version = ProtocolVersion.MINECRAFT_1_15;
            try (PackSender sender = h.sender()) {
                sender.apply(rules(requiredConfig));
                h.run(0);
                ResourcePackInfo old = h.sent.getFirst();
                switch (change) {
                    case "switch" -> {
                        h.server = "empty";
                        sender.connected(new ServerPostConnectEvent(h.player, null));
                    }
                    case "resend" -> sender.resend(h.player);
                    case "backend" -> sender.backend(new ServerResourcePackSendEvent(old, h.connection));
                    case "disable" -> sender.apply(PackRules.disabled());
                }
                sender.status(reply(h, old, "DECLINED"));
                // 代理还会在事件结束后处理必需标记，不能只检查 VTB 的事件处理结果。
                if (old.getShouldForce()) h.player.disconnect(Component.text("Required pack declined"));
                check(h.kicked == null, "legacy stale required decline must not disconnect after " + change);
            }
        }
        for (String failure : List.of("DECLINED", "FAILED_DOWNLOAD", "TIMEOUT")) {
            Harness h = new Harness(dir.resolve("legacy-active-required-" + failure));
            h.version = ProtocolVersion.MINECRAFT_1_15;
            try (PackSender sender = h.sender()) {
                sender.apply(rules(requiredConfig));
                h.run(0);
                ResourcePackInfo active = h.sent.getFirst();
                check(!active.getShouldForce(), "legacy enforcement belongs to the active VTB selection");
                if (failure.equals("TIMEOUT")) h.run(3000);
                else sender.status(reply(h, active, failure));
                check(h.kicked != null, "legacy active required pack still enforced on " + failure);
            }
        }
        for (ProtocolVersion version : List.of(ProtocolVersion.MINECRAFT_1_17, ProtocolVersion.MINECRAFT_1_20_3)) {
            Harness h = new Harness(dir.resolve("native-required-" + version.getProtocol()));
            h.version = version;
            try (PackSender sender = h.sender()) {
                sender.apply(rules(requiredConfig));
                h.run(0);
                check(h.sent.getFirst().getShouldForce(), "supported external packs keep the native required flag");
            }
        }
    }

    private static PlayerResourcePackStatusEvent reply(Harness h, ResourcePackInfo info, String name) {
        return new PlayerResourcePackStatusEvent(h.player, info.getId(), PlayerResourcePackStatusEvent.Status.valueOf(name), info);
    }

    private static void commands() throws Exception {
        Harness h = new Harness(dir.resolve("commands"));
        try (PackSender sender = h.sender()) {
            var modules = new ModuleStatus(h.proxy, h.lang, new PluginLoadService(h.proxy, LOG, h.lang, dir), h.host, sender);
            modules.show(h.player, Component.text("Version rules: disabled"), false, true);
            List<String> statusLines = h.messages.stream().map(PLAIN::serialize).toList();
            check(statusLines.indexOf("[VTB] Plugin management") < statusLines.indexOf("[VTB] Servers and connections")
                    && statusLines.indexOf("[VTB] Servers and connections") < statusLines.indexOf("[VTB] Resource packs"), "module order");
            check(statusLines.contains("[VTB]   HTTP hosting: disabled")
                    && statusLines.contains("[VTB]   Pack delivery: disabled"), "distinct indented hosting/delivery state");
            check(statusLines.stream().noneMatch(line -> line.contains("has not joined")), "server status is not player state");
            h.messages.clear();
            modules.show(h.player, Component.text("Version rules: disabled"), true, false);
            check(h.messages.stream().map(PLAIN::serialize).anyMatch(line -> line.contains(h.lang.plain("pack.info.load-failed"))), "failed reload is visible");
            var command = new VelocityToolboxCommand(null, h.proxy, null, h.host, h.lang, sender).build();
            var root = command.getNode();
            check(root.getChild("packs") == null && root.getChild("vhosts") == null, "old commands removed");
            check(root.getChild("plugin") != null && root.getChild("pack") != null && root.getChild("server") != null, "three modules");
            CommandDispatcher<CommandSource> dispatcher = new CommandDispatcher<>();
            dispatcher.getRoot().addChild(root);
            h.permissions.addAll(List.of("velocitytoolbox.command", "velocitytoolbox.command.pack", "velocitytoolbox.command.pack.list"));
            dispatcher.execute("vtoolbox pack list", h.player);
            try {
                dispatcher.execute("vtoolbox pack resend Tester", h.player);
                throw new AssertionError("resend permission");
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) {
            }
            var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("vtoolbox pack ", h.player)).get();
            check(suggestions.getList().stream().anyMatch(s -> s.getText().equals("list")), "list completion");
            check(suggestions.getList().stream().noneMatch(s -> s.getText().equals("resend")), "hidden resend completion");
            h.messages.clear();
            dispatcher.execute("vtoolbox pack", h.player);
            check(h.messages.stream().noneMatch(c -> PLAIN.serialize(c).contains("resend")), "help filters permissions");
            h.permissions.addAll(List.of("velocitytoolbox.command.server", "velocitytoolbox.command.server.hosts"));
            h.messages.clear();
            dispatcher.execute("vtoolbox server hosts", h.player);
            check(h.messages.stream().anyMatch(PackDeliveryTest::hostClick), "host click uses new command");
            sender.apply(rules(YAML));
            h.messages.clear();
            dispatcher.execute("vtoolbox pack list", h.player);
            String listing = h.messages.stream().map(PLAIN::serialize).reduce("", String::concat);
            check(listing.contains(" to ") && listing.contains("permission: pack.vip")
                    && listing.contains("external"), "list includes source and conditions");
            h.permissions.add("velocitytoolbox.command.pack.resend");
            h.server = "empty";
            h.messages.clear();
            dispatcher.execute("vtoolbox pack resend Tester", h.player);
            String empty = h.messages.stream().map(PLAIN::serialize).reduce("", String::concat);
            check(empty.contains(h.lang.plain("pack.delivery.resend-empty"))
                    && !empty.contains(h.lang.plain("pack.delivery.waiting"))
                    && !empty.contains(h.lang.plain("pack.delivery.resent")), "empty resend feedback");
            h.server = "vip";
            h.messages.clear();
            dispatcher.execute("vtoolbox pack resend Tester", h.player);
            check(h.messages.stream().map(PLAIN::serialize).anyMatch(m -> m.contains(h.lang.plain("pack.delivery.skipped", Lang.ph("packs", "vip")))),
                    "unmatched resend explains skipped packs");
            h.permissions.add("velocitytoolbox.command.plugin.inspect");
            check(!root.getChild("plugin").canUse(h.player), "plugin parent permission required");
            h.permissions.add("velocitytoolbox.command.plugin");
            check(root.getChild("plugin").getChild("inspect").canUse(h.player), "inspect permission retained");
        }
    }

    private static boolean hostClick(Component message) {
        return net.kyori.adventure.text.event.ClickEvent.runCommand("/vtb server hosts 1").equals(message.clickEvent())
                || message.children().stream().anyMatch(PackDeliveryTest::hostClick);
    }

    private static final class Job {
        final Runnable run;
        final long due;
        boolean cancelled;

        Job(Runnable run, long due) {
            this.run = run;
            this.due = due;
        }
    }

    private static final class Harness {
        long now;
        String throwUrl = "";
        String server = "lobby";
        ProtocolVersion version = ProtocolVersion.MINECRAFT_1_20_3;
        final UUID uuid = UUID.randomUUID();
        Component kicked;
        final Set<String> permissions = new HashSet<>();
        final List<ResourcePackInfo> sent = new ArrayList<>();
        final List<UUID> removed = new ArrayList<>();
        final List<Component> messages = new ArrayList<>();
        final List<Job> jobs = new ArrayList<>();
        final List<Player> online = new ArrayList<>();
        final Lang lang;
        final Player player;
        final ServerConnection connection;
        final ProxyServer proxy;
        final PackService host;

        Harness(Path root) throws Exception {
            Files.createDirectories(root);
            lang = new Lang(root);
            lang.load("en_us");
            connection = stub(ServerConnection.class, (o, m, a) -> switch (m.getName()) {
                case "getServerInfo" ->
                        new com.velocitypowered.api.proxy.server.ServerInfo(server, new InetSocketAddress("127.0.0.1", 25565));
                case "getPlayer" -> player();
                default -> throw unexpected(m);
            });
            player = stub(Player.class, (o, m, a) -> switch (m.getName()) {
                case "getUniqueId" -> uuid;
                case "getUsername" -> "Tester";
                case "getVirtualHost" -> Optional.of(new InetSocketAddress("play.example.com", 25565));
                case "getRemoteAddress" -> new InetSocketAddress("127.0.0.1", 12345);
                case "getPing" -> 12L;
                case "isOnlineMode" -> true;
                case "getProtocolVersion" -> version;
                case "getCurrentServer" -> Optional.of(connection);
                case "isActive" -> kicked == null;
                case "hasPermission" -> permissions.contains((String) a[0]);
                case "sendMessage" -> {
                    messages.add((Component) a[0]);
                    yield null;
                }
                case "disconnect" -> {
                    kicked = (Component) a[0];
                    yield null;
                }
                case "sendResourcePackOffer" -> {
                    if (!throwUrl.isEmpty() && ((ResourcePackInfo) a[0]).getUrl().endsWith(throwUrl))
                        throw new IllegalStateException("simulated send failure");
                    sent.add((ResourcePackInfo) a[0]);
                    yield null;
                }
                case "removeResourcePacks" -> {
                    if (a[0] instanceof UUID[] ids) removed.addAll(List.of(ids));
                    else if (a[0] instanceof Iterable<?> ids) for (Object id : ids) removed.add((UUID) id);
                    else removed.add((UUID) a[0]);
                    yield null;
                }
                default -> throw unexpected(m);
            });
            online.add(player);
            Scheduler scheduler = stub(Scheduler.class, (o, m, a) -> {
                if (!m.getName().equals("buildTask")) throw unexpected(m);
                Runnable runnable = (Runnable) a[1];
                long[] delay = {0};
                return stub(Scheduler.TaskBuilder.class, (builder, method, args) -> switch (method.getName()) {
                    case "delay" -> {
                        delay[0] = ((TimeUnit) args[1]).toMillis(((Number) args[0]).longValue());
                        yield builder;
                    }
                    case "schedule" -> {
                        Job job = new Job(runnable, now + delay[0]);
                        jobs.add(job);
                        yield stub(ScheduledTask.class, (task, tm, ta) -> {
                            if (tm.getName().equals("cancel")) {
                                job.cancelled = true;
                                return null;
                            }
                            throw unexpected(tm);
                        });
                    }
                    default -> throw unexpected(method);
                });
            });
            EventManager events = stub(EventManager.class, (o, m, a) -> {
                if (m.getName().equals("register") || m.getName().equals("unregisterListener")) return null;
                throw unexpected(m);
            });
            proxy = stub(ProxyServer.class, (o, m, a) -> switch (m.getName()) {
                case "getEventManager" -> events;
                case "getScheduler" -> scheduler;
                case "getAllPlayers" -> List.copyOf(online);
                case "getPlayer" -> Optional.of(player);
                case "getPluginManager" -> stub(com.velocitypowered.api.plugin.PluginManager.class, (pm, method, args) -> {
                    if (method.getName().equals("getPlugins")) return List.of();
                    throw unexpected(method);
                });
                case "createResourcePackBuilder" -> builder((String) a[0]);
                default -> throw unexpected(m);
            });
            host = new PackService(root, player, lang);
        }

        Player player() {
            return player;
        }

        PackSender sender() {
            return new PackSender(this, proxy, host, lang, LOG);
        }

        String status(PackSender sender) {
            messages.clear();
            sender.show(player, player);
            return messages.stream().map(PLAIN::serialize).reduce("", (a, b) -> a + "\n" + b);
        }

        void run(long advance) {
            now += advance;
            for (; ; ) {
                Job job = jobs.stream().filter(j -> !j.cancelled && j.due <= now).findFirst().orElse(null);
                if (job == null) return;
                job.cancelled = true;
                job.run.run();
            }
        }
    }

    private static ResourcePackInfo.Builder builder(String url) {
        Map<String, Object> fields = new HashMap<>();
        return stub(ResourcePackInfo.Builder.class, (o, m, a) -> {
            if (m.getName().startsWith("set")) {
                fields.put(m.getName().substring(3), a[0]);
                return o;
            }
            if (m.getName().equals("build")) return stub(ResourcePackInfo.class, (p, pm, pa) -> {
                if (pm.getName().equals("getUrl")) return url;
                if (pm.getName().startsWith("get")) return fields.get(pm.getName().substring(3));
                throw unexpected(pm);
            });
            throw unexpected(m);
        });
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (o, m, a) -> {
            if (m.getDeclaringClass() == Object.class) return switch (m.getName()) {
                case "hashCode" -> System.identityHashCode(o);
                case "equals" -> o == a[0];
                case "toString" -> type.getSimpleName();
                default -> throw unexpected(m);
            };
            return handler.invoke(o, m, a);
        }));
    }

    private static AssertionError unexpected(Method m) {
        return new AssertionError("Unexpected call: " + m);
    }

    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
}
