package io.github.polang233.velocitytoolbox.version;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.polang233.velocitytoolbox.lang.Lang;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Owns one listener and an atomic configuration snapshot. No ViaVersion API is used. */
public final class ServerVersionService implements AutoCloseable {

    private final Object owner;
    private final ProxyServer proxy;
    private final Path dataDirectory;
    private final Lang lang;
    private final Logger logger;
    // Empty means no configuration has loaded successfully; reject connections until repaired.
    private volatile Optional<ServerVersionConfig> config = Optional.empty();

    public ServerVersionService(Object owner, ProxyServer proxy, Path dataDirectory, Lang lang, Logger logger) {
        this.owner = owner;
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
        this.lang = lang;
        this.logger = logger;
    }

    public void start() {
        try {
            reload();
        } catch (IOException exception) {
            logger.error(lang.plain("server-versions.load-fail"), exception);
        }
        proxy.getEventManager().register(owner, this);
    }

    public void reload() throws IOException {
        ServerVersionConfig next = ServerVersionConfig.load(dataDirectory);
        for (String name : next.rules().keySet()) {
            if (proxy.getServer(name).isEmpty()) {
                logger.warn(lang.plain("server-versions.unknown-server", Lang.ph("server", name)));
            }
        }
        config = Optional.of(next);
    }

    public Component status() {
        Optional<ServerVersionConfig> current = config;
        if (current.isEmpty()) {
            return lang.get("server-versions.status.failed");
        }
        return current.get().enabled()
                ? lang.get("server-versions.status.enabled", Lang.ph("count", current.get().rules().size()))
                : lang.get("server-versions.status.disabled");
    }

    @Subscribe(order = PostOrder.LAST)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        // Respect an earlier denial and inspect the destination after earlier routing listeners.
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null) {
            return;
        }
        Optional<ServerVersionConfig> current = config;
        Player player = event.getPlayer();
        if (current.isEmpty()) {
            reject(event, lang.get("server-versions.unavailable"));
            return;
        }
        if (!current.get().enabled()) {
            return;
        }
        String name = target.getServerInfo().getName();
        ServerVersionConfig.Rule rule = current.get().rules().get(name.toLowerCase(Locale.ROOT));
        if (rule == null || rule.allows(player.getProtocolVersion())) {
            return;
        }
        ProtocolVersion version = player.getProtocolVersion();
        reject(event, lang.get("server-versions.denied", Lang.ph("server", name),
                Lang.ph("version", label(version)), Lang.ph("protocol", version.getProtocol()),
                Lang.ph("requirement", requirement(rule))));
    }

    private void reject(ServerPreConnectEvent event, Component reason) {
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        Player player = event.getPlayer();
        if (player.getCurrentServer().isEmpty()) {
            // Chat cannot explain an initial-join denial; close with a visible reason instead.
            player.disconnect(reason);
        } else {
            lang.send(player, reason);
        }
    }

    private String requirement(ServerVersionConfig.Rule rule) {
        String result = lang.plain("server-versions.requirement.range",
                Lang.ph("min", label(rule.min())), Lang.ph("max", label(rule.max())));
        if (!rule.allow().isEmpty()) {
            result += lang.plain("server-versions.requirement.allow", Lang.ph("versions", labels(rule.allow())));
        }
        if (!rule.deny().isEmpty()) {
            result += lang.plain("server-versions.requirement.deny", Lang.ph("versions", labels(rule.deny())));
        }
        return result;
    }

    private static String labels(Set<ProtocolVersion> versions) {
        return versions.stream().sorted().map(ServerVersionService::label).collect(Collectors.joining(", "));
    }

    private static String label(ProtocolVersion version) {
        return String.join("/", version.getVersionsSupportedBy());
    }

    @Override
    public void close() {
        config = Optional.of(ServerVersionConfig.disabled());
        proxy.getEventManager().unregisterListener(owner, this);
    }
}
