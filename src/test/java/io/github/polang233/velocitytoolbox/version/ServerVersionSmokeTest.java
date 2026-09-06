package io.github.polang233.velocitytoolbox.version;

import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import io.github.polang233.velocitytoolbox.lang.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Runs real YAML parsing and the real Velocity event listener without a proxy or ViaVersion. */
public final class ServerVersionSmokeTest {

    private static final ProtocolVersion V112 = ProtocolVersion.MINECRAFT_1_12_2;
    private static final ProtocolVersion V118 = ProtocolVersion.MINECRAFT_1_18;
    private static final ProtocolVersion V120 = ProtocolVersion.MINECRAFT_1_20;
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public static void main(String[] args) throws Exception {
        require(ClassLoader.getSystemResource("com/viaversion/viaversion/api/Via.class") == null,
                "This test must run without ViaVersion on the classpath");
        for (String arg : args) {
            ServerVersionConfig supplied = ServerVersionConfig.from(ResourceFiles.loadYaml(Path.of(arg)).node("server-versions"));
            System.out.println("Validated supplied config: enabled=" + supplied.enabled() + ", servers=" + supplied.rules().size());
        }
        Path directory = Files.createTempDirectory("velocitytoolbox-versions-");
        try {
            require(!ServerVersionConfig.load(directory).enabled(), "fresh install must be disabled");
            String defaults = Files.readString(directory.resolve("config.yml"));
            String existingConfig = "language: en_us\npack-host:\n  enabled: false\n";
            Files.writeString(directory.resolve("config.yml"), existingConfig);
            require(!ServerVersionConfig.load(directory).enabled(), "missing section must default to disabled");
            require(Files.readString(directory.resolve("config.yml")).equals(existingConfig),
                    "loading version rules must preserve existing config settings");
            rules(directory);
            invalidConfigs(directory);
            lifecycleAndEvents(directory);
            Files.writeString(directory.resolve("config.yml"), defaults);
            require(!ServerVersionConfig.load(directory).enabled(), "default file must remain valid");
            System.out.println("Server version smoke tests passed: rules, aliases, validation, reload, lifecycle, connection events; no ViaVersion.");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void rules(Path directory) throws Exception {
        ServerVersionConfig config = read(directory, """
                enabled: true
                servers:
                  Lobby:
                    min: "1.18.1"
                    max: "1.20.1"
                  survival:
                    allow: [340, "1.18.1", "1.20.1"]
                    deny: ["1.18"]
                  future:
                    min: "1.12.2"
                    max: max
                  blocked:
                    deny: ["1.20"]
                """);
        var range = config.rules().get("lobby");
        require(!range.allows(V112), "range must reject older client");
        require(range.allows(V118) && range.allows(V120), "inclusive endpoints and aliases must match");
        require(!range.allows(ProtocolVersion.MINECRAFT_1_20_2), "range must reject newer client");
        var allow = config.rules().get("survival");
        require(allow.allows(V112) && allow.allows(V120), "numeric IDs and exact aliases must work");
        require(!allow.allows(V118), "deny must override allow, including aliases");
        require(!allow.allows(ProtocolVersion.MINECRAFT_1_19), "nonempty allow must reject unlisted clients");
        require(config.rules().get("future").allows(ProtocolVersion.MAXIMUM_VERSION), "max must track Velocity");
        require(!range.allows(ProtocolVersion.UNKNOWN) && !range.allows(ProtocolVersion.LEGACY),
                "restricted servers must reject unsupported protocol sentinels");
        require(config.rules().get("blocked").allows(V112) && !config.rules().get("blocked").allows(V120),
                "deny-only rule must allow other clients");
        require(read(directory, "enabled: true\nservers: {}\n").rules().isEmpty(), "empty map must work");
        var combined = read(directory, """
                enabled: true
                servers:
                  survival:
                    min: "1.18"
                    allow: ["1.12.2", "1.20.1"]
                    deny: []
                """).rules().get("survival");
        require(!combined.allows(V112) && combined.allows(V120), "range and allow must both match");
    }

    private static void invalidConfigs(Path directory) throws Exception {
        for (String rule : List.of(
                "min: \"1.20.1\"\nmax: \"1.12.2\"",
                "allow: [\"1.99.99\"]",
                "allow: \"1.12.2\"",
                "min: 1.20",
                "deny: [true]",
                "max: \"mxa\"",
                "allow: [-1]",
                "allowed: [\"1.12.2\"]")) {
            expectInvalid(directory, "enabled: true\nservers:\n  survival:\n    "
                    + rule.replace("\n", "\n    ") + "\n", "servers.survival");
        }
        expectInvalid(directory, "enabled: \"yes\"\nservers: {}\n", "enabled");
        expectInvalid(directory, "enabled: true\nservers: []\n", "servers");
        expectInvalid(directory, "enabled: true\nserver: {}\n", "server");
        expectInvalid(directory, "enabled: true\nservers:\n  survival: {}\n  Survival: {}\n", "duplicate");
        require(!read(directory, "enabled: false\nservers: unfinished\n").enabled(),
                "explicit disable should work while editing unfinished rules");
    }

    private static void lifecycleAndEvents(Path directory) throws Exception {
        List<Object> listeners = new ArrayList<>();
        EventManager eventManager = stub(EventManager.class, (instance, method, args) -> {
            switch (method.getName()) {
                case "register" -> { listeners.add(args[1]); return null; }
                case "unregisterListener" -> { listeners.remove(args[1]); return null; }
                default -> throw new AssertionError("Unexpected event manager call: " + method);
            }
        });
        RegisteredServer lobby = server("lobby");
        RegisteredServer survival = server("survival");
        ProxyServer proxy = stub(ProxyServer.class, (instance, method, args) -> switch (method.getName()) {
            case "getEventManager" -> eventManager;
            case "getServer" -> Optional.of(survival);
            default -> throw new AssertionError("Unexpected proxy call: " + method);
        });
        Logger logger = stub(Logger.class, (instance, method, args) -> null);
        Path oldLanguage = Files.createDirectories(directory.resolve("lang")).resolve("en_us.yml");
        Files.writeString(oldLanguage, "prefix: \"<gold>[CUSTOM] \"\n");
        Lang lang = new Lang(directory);
        lang.load("en_us");
        require(Files.readString(oldLanguage).contains("[CUSTOM]"), "existing language file must not be overwritten");
        String only112 = "enabled: true\nservers:\n  survival:\n    allow: [\"1.12.2\"]\n";
        read(directory, only112);
        ServerVersionService service = new ServerVersionService(new Object(), proxy, directory, lang, logger);
        service.start();
        require(listeners.size() == 1, "start must register exactly one listener");
        require(PLAIN.serialize(service.status()).contains("enabled for 1"), "enabled status must include rule count");
        String initialDetails = PLAIN.serialize(service.status(true));
        require(initialDetails.contains("survival") && initialDetails.contains("only allow 1.12.2"),
                "detailed status must identify the server and its allowed versions");
        require(!PLAIN.serialize(service.status()).contains("\n"), "startup status must remain one line");

        Client switching = new Client(V120, true);
        ServerPreConnectEvent denied = fire(service, switching, survival);
        require(!denied.getResult().isAllowed() && switching.disconnected == null && switching.messages.size() == 1,
                "denied switch must keep current server and send one message");
        String message = PLAIN.serialize(switching.messages.getFirst());
        require(message.contains("survival") && message.contains("1.12.2") && message.contains("1.20～1.20.1")
                && !message.contains("763") && !message.contains("<requirement>"), "denial must show compact versions without protocol IDs");
        require(hoverText(switching.messages.getFirst()).contains("Client protocol: 763")
                && hoverText(switching.messages.getFirst()).contains("only allow 340"),
                "denial hover must include the client protocol and rule protocols");

        Client initial = new Client(V120, false);
        require(!fire(service, initial, survival).getResult().isAllowed()
                && initial.disconnected != null && initial.messages.isEmpty(), "initial denial must disconnect with reason");
        require(fire(service, new Client(V112, false), survival).getResult().isAllowed(), "allowed initial join must pass");
        require(fire(service, new Client(V120, true), lobby).getResult().isAllowed(), "unconfigured server must pass");

        Client redirected = new Client(V120, true);
        ServerPreConnectEvent redirect = new ServerPreConnectEvent(redirected.player, lobby);
        redirect.setResult(ServerPreConnectEvent.ServerResult.allowed(survival));
        service.onServerPreConnect(redirect);
        require(!redirect.getResult().isAllowed(), "must check redirected destination");
        redirect = new ServerPreConnectEvent(new Client(V120, true).player, survival);
        redirect.setResult(ServerPreConnectEvent.ServerResult.allowed(lobby));
        service.onServerPreConnect(redirect);
        require(redirect.getResult().isAllowed(), "must not restrict the discarded original destination");

        Client alreadyDenied = new Client(V120, false);
        ServerPreConnectEvent cancelled = new ServerPreConnectEvent(alreadyDenied.player, survival);
        cancelled.setResult(ServerPreConnectEvent.ServerResult.denied());
        service.onServerPreConnect(cancelled);
        require(!cancelled.getResult().isAllowed() && alreadyDenied.disconnected == null && alreadyDenied.messages.isEmpty(),
                "must preserve another plugin's denial without sending another message");

        expectInvalid(directory, only112.replace("1.12.2", "typo"), "allow");
        try {
            service.reload();
            throw new AssertionError("reload should have failed");
        } catch (IOException expected) {
            require(!fire(service, new Client(V120, true), survival).getResult().isAllowed(),
                    "invalid reload must preserve previous rules");
            require(PLAIN.serialize(service.status(true)).equals(initialDetails),
                    "info must report the active rules after a failed reload");
        }
        read(directory, only112.replace("1.12.2", "1.20.1"));
        service.reload();
        require(fire(service, new Client(V120, true), survival).getResult().isAllowed(), "reload must install new rules");
        require(!fire(service, new Client(V112, true), survival).getResult().isAllowed(), "reload must replace old rules");
        read(directory, """
                enabled: true
                servers:
                  survival:
                    allow: ["1.7.2", "1.20.1"]
                  minigame:
                    min: "1.18"
                    max: "1.20.2"
                    deny: ["1.19"]
                  lobby:
                    min: "1.18.1"
                    max: "1.18"
                """);
        service.reload();
        String details = PLAIN.serialize(service.status(true));
        require(details.lines().count() == 4 && details.indexOf("lobby:") < details.indexOf("minigame:")
                && details.indexOf("minigame:") < details.indexOf("survival:"),
                "info must list every configured server in stable name order");
        require(details.contains("minigame: 1.18 to 1.20.2; block 1.19")
                && details.contains("lobby: 1.18 to 1.18.1")
                && details.contains("only allow 1.7.2～1.7.5, 1.20～1.20.1")
                && !details.contains("1.18/"),
                "info must show range endpoints, allowlists, and blocklists");
        String infoHover = hoverText(service.status(true));
        require(infoHover.contains("757 to 764; block 759") && infoHover.contains("only allow 4, 763"),
                "info hover must show the exact protocol bounds and lists");
        lang.load("zh_cn");
        String chineseDetails = PLAIN.serialize(service.status(true));
        require(chineseDetails.contains("minigame：") && chineseDetails.contains("禁止 1.19")
                && chineseDetails.contains("仅允许 1.7.2～1.7.5, 1.20～1.20.1"), "Chinese info must resolve the detailed rule messages");
        require(hoverText(service.status(true)).contains("协议号要求："), "Chinese protocol hover must resolve");
        lang.load("en_us");
        read(directory, "enabled: false\n");
        service.reload();
        require(fire(service, new Client(V112, true), survival).getResult().isAllowed(), "disabled module must pass");
        require(!PLAIN.serialize(service.status(true)).contains("\n"), "disabled status must not list inactive rules");
        require(listeners.size() == 1, "reload must not duplicate listeners");
        service.close();
        require(listeners.isEmpty(), "close must remove owned listener");

        Files.writeString(directory.resolve("config.yml"), "server-versions:\n  enabled: broken\n");
        ServerVersionService failed = new ServerVersionService(new Object(), proxy, directory, lang, logger);
        failed.start();
        require(!fire(failed, new Client(V112, false), lobby).getResult().isAllowed(), "initial config failure must not silently bypass rules");
        require(PLAIN.serialize(failed.status()).contains("configuration failed"), "startup failure must be visible");
        require(!PLAIN.serialize(failed.status(true)).contains("\n"), "failed initial load must not list rules");
        read(directory, "enabled: false\n");
        failed.reload();
        require(fire(failed, new Client(V112, false), lobby).getResult().isAllowed(), "reload must recover from startup failure");
        failed.close();

        lang.load("zh_cn");
        require(lang.plain("server-versions.status.enabled", Lang.ph("count", 4)).contains("4 个子服"),
                "bundled Chinese translations must load");
    }

    private static String hoverText(Component component) {
        StringBuilder text = new StringBuilder();
        if (component.hoverEvent() != null && component.hoverEvent().value() instanceof Component hover) {
            text.append(PLAIN.serialize(hover));
        }
        for (Component child : component.children()) {
            text.append(hoverText(child));
        }
        return text.toString();
    }

    private static ServerPreConnectEvent fire(ServerVersionService service, Client client, RegisteredServer target) {
        ServerPreConnectEvent event = new ServerPreConnectEvent(client.player, target);
        service.onServerPreConnect(event);
        return event;
    }

    private static ServerVersionConfig read(Path directory, String yaml) throws IOException {
        Files.writeString(directory.resolve("config.yml"),
                "language: en_us\npack-host:\n  enabled: false\nserver-versions:\n" + yaml.indent(2));
        return ServerVersionConfig.load(directory);
    }

    private static void expectInvalid(Path directory, String yaml, String detail) throws IOException {
        try {
            read(directory, yaml);
            throw new AssertionError("Accepted invalid YAML: " + yaml);
        } catch (IOException expected) {
            require(expected.getMessage().contains(detail), "validation must identify the broken option: " + expected);
        }
    }

    private static RegisteredServer server(String name) {
        ServerInfo info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
        return stub(RegisteredServer.class, (instance, method, args) -> {
            if (method.getName().equals("getServerInfo")) return info;
            throw new AssertionError("Unexpected registered server call: " + method);
        });
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class Client {
        private final List<Component> messages = new ArrayList<>();
        private Component disconnected;
        private final Player player;

        private Client(ProtocolVersion version, boolean connected) {
            ServerConnection connection = stub(ServerConnection.class, (instance, method, args) -> null);
            player = stub(Player.class, (instance, method, args) -> switch (method.getName()) {
                case "getProtocolVersion" -> version;
                case "getCurrentServer" -> connected ? Optional.of(connection) : Optional.empty();
                case "disconnect" -> { disconnected = (Component) args[0]; yield null; }
                case "sendMessage" -> { messages.add((Component) args[0]); yield null; }
                default -> throw new AssertionError("Unexpected player call: " + method);
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
