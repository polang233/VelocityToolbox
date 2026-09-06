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
import net.kyori.adventure.text.event.HoverEvent;
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
        return status(false);
    }

    public Component status(boolean includeRules) {
        Optional<ServerVersionConfig> current = config;
        if (current.isEmpty()) {
            return lang.get("server-versions.status.failed");
        }
        Component result = current.get().enabled()
                ? lang.get("server-versions.status.enabled", Lang.ph("count", current.get().rules().size()))
                : lang.get("server-versions.status.disabled");
        if (includeRules && current.get().enabled()) {
            for (String server : current.get().rules().keySet().stream().sorted().toList()) {
                ServerVersionConfig.Rule rule = current.get().rules().get(server);
                result = result.append(Component.newline()).append(lang.get("server-versions.status.rule",
                        Lang.ph("server", server),
                        Lang.ph("requirement", requirement(rule, false)))
                        .hoverEvent(HoverEvent.showText(protocolDetails(rule))));
            }
        }
        return result;
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
                Lang.ph("requirement", requirement(rule, false)))
                .hoverEvent(HoverEvent.showText(protocolDetails(rule).append(Component.newline())
                        .append(lang.get("server-versions.client-protocol", Lang.ph("protocol", version.getProtocol()))))));
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

    private Component protocolDetails(ServerVersionConfig.Rule rule) {
        return lang.get("server-versions.protocol-details", Lang.ph("requirement", requirement(rule, true)));
    }

    private String requirement(ServerVersionConfig.Rule rule, boolean protocolIds) {
        String result = lang.plain("server-versions.requirement.range",
                Lang.ph("min", protocolIds ? rule.min().getProtocol() : rule.min().getVersionIntroducedIn()),
                Lang.ph("max", protocolIds ? rule.max().getProtocol() : rule.max().getMostRecentSupportedVersion()));
        if (!rule.allow().isEmpty()) {
            result += lang.plain("server-versions.requirement.allow", Lang.ph("versions", labels(rule.allow(), protocolIds)));
        }
        if (!rule.deny().isEmpty()) {
            result += lang.plain("server-versions.requirement.deny", Lang.ph("versions", labels(rule.deny(), protocolIds)));
        }
        return result;
    }

    private static String labels(Set<ProtocolVersion> versions, boolean protocolIds) {
        return versions.stream().sorted()
                .map(version -> protocolIds ? Integer.toString(version.getProtocol()) : label(version))
                .collect(Collectors.joining(", "));
    }

    private static String label(ProtocolVersion version) {
        String first = version.getVersionIntroducedIn();
        String last = version.getMostRecentSupportedVersion();
        return first.equals(last) ? first : first + "～" + last;
    }

    @Override
    public void close() {
        config = Optional.of(ServerVersionConfig.disabled());
        proxy.getEventManager().unregisterListener(owner, this);
    }
}
