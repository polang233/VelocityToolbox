package io.github.polang233.velocitytoolbox.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.polang233.velocitytoolbox.lang.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.polang233.velocitytoolbox.lang.Lang.ph;

final class ServerCommand extends CommandView {
    ServerCommand(ProxyServer proxy, Lang lang) {
        super(proxy, lang);
    }

    LiteralArgumentBuilder<CommandSource> build() {
        return literal("server")
                .requires(source -> module(source, "server"))
                .executes(ctx -> {
                    sectionLine(ctx.getSource(), "server.title");
                    if (action(ctx.getSource(), "server", "hosts"))
                        helpLine(ctx.getSource(), "/vtb server hosts [index]", "server.help.hosts");
                    return 1;
                })
                .then(literal("hosts")
                        .requires(source -> action(source, "server", "hosts"))
                        .executes(this::vhosts)
                        .then(BrigadierCommand.requiredArgumentBuilder("entry", IntegerArgumentType.integer(1))
                                .executes(this::expandedVhost)));
    }

    private int vhosts(CommandContext<CommandSource> ctx) {
        return vhosts(ctx, null);
    }

    private int vhosts(CommandContext<CommandSource> ctx, Integer selectedEntry) {
        CommandSource source = ctx.getSource();
        List<Player> players = new ArrayList<>(proxy.getAllPlayers());
        if (players.isEmpty()) {
            lang.send(source, "server.hosts.empty");
            return Command.SINGLE_SUCCESS;
        }
        players.sort(Comparator
                .comparing((Player player) -> entryPoint(player).sortKey())
                .thenComparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER));

        Map<EntryPoint, List<Player>> groups = new LinkedHashMap<>();
        for (Player player : players) {
            groups.computeIfAbsent(entryPoint(player), ignored -> new ArrayList<>()).add(player);
        }

        lang.send(source, "server.hosts.title",
                ph("entries", groups.size()),
                ph("players", players.size()));
        int index = 1;
        String unknown = lang.plain("common.unknown");
        for (Map.Entry<EntryPoint, List<Player>> group : groups.entrySet()) {
            EntryPoint entry = group.getKey();
            int currentIndex = index++;
            Component entryLine = lang.get("server.hosts.entry",
                            ph("index", currentIndex),
                            ph("domain", entry.domain().isEmpty() ? unknown : entry.domain()),
                            ph("port", entry.port() <= 0 ? unknown : entry.port()),
                            ph("count", group.getValue().size()))
                    .hoverEvent(HoverEvent.showText(lang.get(selectedEntry != null && selectedEntry == currentIndex
                            ? "server.hosts.collapse-hint"
                            : "server.hosts.expand-hint")))
                    .clickEvent(ClickEvent.runCommand(selectedEntry != null && selectedEntry == currentIndex
                            ? "/vtb server hosts"
                            : "/vtb server hosts " + currentIndex));
            lang.send(source, entryLine);
            if (selectedEntry != null && selectedEntry == currentIndex) {
                for (Player player : group.getValue()) {
                    lang.send(source, playerLine(player, unknown));
                }
            }
        }
        if (selectedEntry != null && selectedEntry > groups.size()) {
            lang.send(source, "server.hosts.invalid-entry", ph("index", selectedEntry));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int expandedVhost(CommandContext<CommandSource> ctx) {
        return vhosts(ctx, IntegerArgumentType.getInteger(ctx, "entry"));
    }

    private Component playerLine(Player player, String unknown) {
        String ping = player.getPing() < 0 ? unknown : player.getPing() + "ms";
        return lang.get("server.hosts.player", ph("name", player.getUsername()), ph("ping", ping))
                .hoverEvent(HoverEvent.showText(playerHover(player, unknown)));
    }

    private Component playerHover(Player player, String unknown) {
        String backend = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(unknown);
        String mode = lang.plain(player.isOnlineMode()
                ? "server.player.online-mode"
                : "server.player.offline-mode");
        return Component.text()
                .append(lang.get("server.hosts.player-hover-title", ph("name", player.getUsername())))
                .append(Component.newline())
                .append(hoverField("server.hosts.player-uuid", String.valueOf(player.getUniqueId())))
                .append(Component.newline())
                .append(hoverField("server.hosts.player-ip", remoteIp(player)))
                .append(Component.newline())
                .append(hoverField("server.hosts.player-server", backend))
                .append(Component.newline())
                .append(hoverField("server.hosts.player-mode", mode))
                .build();
    }

    private static EntryPoint entryPoint(Player player) {
        InetSocketAddress address = player.getVirtualHost().orElse(null);
        if (address == null) {
            return new EntryPoint("", -1);
        }
        String domain = address.getHostString().toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        return new EntryPoint(domain, address.getPort());
    }

    private static String remoteIp(Player player) {
        InetSocketAddress remote = player.getRemoteAddress();
        return remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
    }

    private record EntryPoint(String domain, int port) {
        String sortKey() {
            return domain.toLowerCase(Locale.ROOT) + ':' + port;
        }
    }
}
