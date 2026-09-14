package io.github.polang233.velocitytoolbox.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import io.github.polang233.velocitytoolbox.lang.Lang;
import io.github.polang233.velocitytoolbox.pack.*;

import java.util.List;

final class PackCommand extends CommandView {
    private final PackService host;
    private final PackSender sender;

    PackCommand(ProxyServer proxy, Lang lang, PackService host, PackSender sender) {
        super(proxy, lang);
        this.host = host;
        this.sender = sender;
    }

    LiteralArgumentBuilder<CommandSource> build() {
        var node = literal("pack")
                .requires(source -> module(source, "pack")).executes(this::help);
        node.then(literal("list")
                .requires(source -> action(source, "pack", "list")).executes(this::list));
        for (String action : List.of("status", "resend"))
            node.then(literal(action)
                    .requires(source -> action(source, "pack", action))
                    .executes(ctx -> usage(ctx, "/vtb pack " + action + " <player>"))
                    .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> suggest(builder,
                                    proxy.getAllPlayers().stream().map(Player::getUsername).sorted().toList()))
                            .executes(ctx -> player(ctx, action))));
        return node;
    }

    private int help(CommandContext<CommandSource> ctx) {
        for (String action : List.of("list", "status", "resend"))
            if (action(ctx.getSource(), "pack", action))
                helpLine(ctx.getSource(), "/vtb pack " + action + (action.equals("list") ? "" : " <player>"),
                        "delivery.help." + action);
        return 1;
    }

    private int player(CommandContext<CommandSource> ctx, String action) {
        Player player = proxy.getPlayer(StringArgumentType.getString(ctx, "player")).orElse(null);
        if (player == null) {
            lang.send(ctx.getSource(), "delivery.offline");
            return 0;
        }
        if (action.equals("status")) sender.show(player, ctx.getSource());
        else {
            boolean ok = sender.resend(player);
            lang.send(ctx.getSource(), ok ? "delivery.resent" : "delivery.disabled");
            return ok ? 1 : 0;
        }
        return 1;
    }

    private int list(CommandContext<CommandSource> ctx) {
        var source = ctx.getSource();
        lang.send(source, sender.rules().enabled() ? "delivery.enabled" : "delivery.disabled");
        for (var entry : sender.rules().packs().entrySet())
            for (var variant : entry.getValue().variants()) {
                String state = variant.file().local() && !host.enabled() ? "unavailable" : "ready";
                lang.send(source, "delivery.pack", Lang.ph("pack", entry.getKey()),
                        Lang.ph("state", lang.plain("delivery.state." + state)), Lang.ph("url", variant.file().url()));
            }
        if (!host.enabled()) {
            lang.send(source, "command.packs.disabled");
            return 1;
        }
        List<HostedPack> packs = host.packs();
        lang.send(source, "command.packs.title", Lang.ph("count", packs.size()));
        for (HostedPack pack : packs)
            lang.send(source, "delivery.file", Lang.ph("file", pack.fileName()),
                    Lang.ph("url", pack.url()), Lang.ph("sha1", pack.sha1()));
        return 1;
    }
}
