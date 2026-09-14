package io.github.polang233.velocitytoolbox.pack;

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
            delay: 0
            timeout: 2
            packs:
              base:
                url: https://example.com/base.zip
                sha1: HASH
              extra:
                variants:
                  - url: https://example.com/new.zip
                    sha1: HASH
                    versions:
                      min: "1.20.3"
                  - url: https://example.com/old.zip
                    sha1: HASH
                    versions:
                      max: "1.20.2"
              old:
                url: https://example.com/full.zip
                sha1: HASH
              vip:
                url: https://example.com/vip.zip
                sha1: HASH
                permission: pack.vip
            default: [base]
            servers:
              prison:
                packs: [base, extra]
                legacy: old
              empty:
                packs: []
              vip:
                packs: [vip]
                required: true
            """.replace("HASH", HASH);

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("vtb-delivery-");
        try {
            rules();
            http();
            events();
            commands();
            System.out.println("Pack delivery tests passed: config, selection, real HTTP, reload, events, stale replies, timeout, commands.");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
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
        var modern = rules.select("prison", ProtocolVersion.MINECRAFT_1_20_3, x -> true);
        check(modern.packs().stream().map(PackRules.Choice::name).toList().equals(List.of("base", "extra")), "stack order");
        check(modern.packs().get(1).file().url().endsWith("new.zip"), "modern variant");
        check(rules.select("prison", ProtocolVersion.MINECRAFT_1_12_2, x -> true).packs().getFirst().name().equals("old"), "explicit legacy");
        var fallback = rules(YAML.replace("    legacy: old\n", "")).select("prison", ProtocolVersion.MINECRAFT_1_12_2, x -> true);
        check(fallback.packs().size() == 1 && fallback.packs().getFirst().name().equals("base"), "legacy first");
        check(rules.select("missing", ProtocolVersion.MINECRAFT_1_20_3, x -> true).packs().size() == 1, "default");
        check(rules.select("empty", ProtocolVersion.MINECRAFT_1_20_3, x -> true).packs().isEmpty(), "empty replaces default");
        check(rules.select("vip", ProtocolVersion.MINECRAFT_1_20_3, x -> false).blocked(), "required permission");
        check(!rules(YAML.replace("required: true", "required: false")).select("vip", ProtocolVersion.MINECRAFT_1_20_3, x -> false).blocked(), "optional skip");
        for (String bad : List.of(YAML.replace(HASH, "bad"), YAML.replace("default: [base]", "default: [missing]"),
                YAML.replace("delay: 0", "delay: -1"), YAML.replace("timeout: 2", "timeout: 0"),
                YAML.replace("min: \"1.20.3\"", "min: 999999"), YAML.replace("enabled: true", "enabled: \"yes\""),
                YAML.replace("url: https://example.com/base.zip", "file: missing.zip"),
                YAML.replace("default: [base]", "default: [base, base]"))) {
            try {
                rules(bad);
                throw new AssertionError("accepted bad config");
            } catch (IOException expected) {
            }
        }
        check(!PackRules.read(yaml("{}").node("resource-packs"), List.of()).enabled(), "missing disabled");
        check(rules("enabled: true\npacks: {}\ndefault: []\nservers: {}\n").packs().isEmpty(), "empty enabled config");
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
        Harness h = new Harness(root);
        int port = freePort();
        try (PackService host = h.host; HttpClient client = HttpClient.newHttpClient()) {
            PackConfig config = hostConfig(port, "127.0.0.1");
            var staged = host.prepare(config);
            PackRules local = PackRules.read(yaml("enabled: true\npacks:\n  base:\n    file: base.zip\ndefault: [base]\n"), staged.list());
            check(local.packs().get("base").variants().getFirst().file().local(), "resolved local file");
            host.apply(staged);
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
                PackRules.read(yaml("enabled: true\npacks:\n  broken:\n    file: absent.zip\n"), host.prepare(config).list());
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
            h.server = "prison";
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
            check(h.kicked != null, "required missing permission disconnects");
        }
        Harness required = new Harness(dir.resolve("required"));
        try (PackSender sender = required.sender()) {
            sender.apply(rules(YAML.replace("enabled: true", "enabled: true\nrequired: true")));
            required.run(0);
            sender.status(reply(required, required.sent.getLast(), "DECLINED"));
            check(required.kicked != null, "required declined");
        }
        Harness timed = new Harness(dir.resolve("timed"));
        try (PackSender sender = timed.sender()) {
            sender.apply(rules(YAML.replace("enabled: true", "enabled: true\nrequired: true")));
            timed.run(0);
            timed.run(3000);
            check(timed.kicked != null, "required timeout");
        }
        Harness quick = new Harness(dir.resolve("quick"));
        try (PackSender sender = quick.sender()) {
            sender.apply(rules(YAML.replace("delay: 0", "delay: 1")));
            quick.server = "prison";
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

    private static PlayerResourcePackStatusEvent reply(Harness h, ResourcePackInfo info, String name) {
        return new PlayerResourcePackStatusEvent(h.player, info.getId(), PlayerResourcePackStatusEvent.Status.valueOf(name), info);
    }

    private static void commands() throws Exception {
        Harness h = new Harness(dir.resolve("commands"));
        try (PackSender sender = h.sender()) {
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
        String server = "lobby";
        ProtocolVersion version = ProtocolVersion.MINECRAFT_1_20_3;
        final UUID uuid = UUID.randomUUID();
        Component kicked;
        final Set<String> permissions = new HashSet<>();
        final List<ResourcePackInfo> sent = new ArrayList<>();
        final List<UUID> removed = new ArrayList<>();
        final List<Component> messages = new ArrayList<>();
        final List<Job> jobs = new ArrayList<>();
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
                case "getAllPlayers" -> List.of(player);
                case "getPlayer" -> Optional.of(player);
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
