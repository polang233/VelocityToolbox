package io.github.polang233.velocitytoolbox.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import io.github.polang233.velocitytoolbox.lang.Lang;
import io.github.polang233.velocitytoolbox.pack.config.PackRules;
import io.github.polang233.velocitytoolbox.pack.delivery.PackSender;
import io.github.polang233.velocitytoolbox.pack.host.HostedPack;
import io.github.polang233.velocitytoolbox.pack.host.PackService;
import io.github.polang233.velocitytoolbox.version.VersionText;

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
        sectionLine(ctx.getSource(), "pack.title");
        for (String action : List.of("list", "status", "resend"))
            if (action(ctx.getSource(), "pack", action))
                helpLine(ctx.getSource(), "/vtb pack " + action + (action.equals("list") ? "" : " <player>"),
                        "pack.help." + action);
        return 1;
    }

    private int player(CommandContext<CommandSource> ctx, String action) {
        Player player = proxy.getPlayer(StringArgumentType.getString(ctx, "player")).orElse(null);
        if (player == null) {
            lang.send(ctx.getSource(), "pack.delivery.offline");
            return 0;
        }
        if (action.equals("status")) sender.show(player, ctx.getSource());
        else {
            var result = sender.resend(player);
            lang.send(ctx.getSource(), switch (result) {
                case SCHEDULED -> "pack.delivery.resent";
                case EMPTY -> "pack.delivery.resend-empty";
                case DISABLED -> "pack.delivery.unavailable";
            });
            if (result == PackSender.ResendResult.EMPTY) sender.show(player, ctx.getSource());
            return result == PackSender.ResendResult.SCHEDULED ? 1 : 0;
        }
        return 1;
    }

    private String conditions(PackRules.Variant variant) {
        String result = VersionText.format(lang, variant.versions(), false);
        if (!variant.permission().isEmpty()) {
            result += lang.plain("pack.label.permission", Lang.ph("permission", variant.permission()));
        }
        return result;
    }

    private int list(CommandContext<CommandSource> ctx) {
        var source = ctx.getSource();
        sectionLine(source, "pack.title");
        lang.send(source, Component.text("  ").append(lang.get("pack.delivery.status", Lang.ph("state", lang.plain(sender.rules().enabled() ? "common.enabled" : "common.disabled")))));
        for (var entry : sender.rules().packs().entrySet())
            for (var variant : entry.getValue().variants()) {
                String state = variant.file().local() && !host.enabled() ? "unavailable" : "ready";
                lang.send(source, "pack.list.variant", Lang.ph("pack", entry.getKey()),
                        Lang.ph("state", lang.plain("pack.delivery.state." + state)), Lang.ph("url", variant.file().url()),
                        Lang.ph("required", lang.plain(variant.required() ? "pack.label.required" : "pack.label.optional")),
                        Lang.ph("source", lang.plain(variant.file().empty() ? "pack.source.none" : variant.file().local() ? "pack.source.local" : "pack.source.external")),
                        Lang.ph("conditions", conditions(variant)));
            }
        sectionLine(source, "pack.host.title");
        if (!host.enabled()) {
            lang.send(source, "pack.host.disabled");
            return 1;
        }
        lang.send(source, "pack.host.stats", Lang.ph("detail", host.httpStatus()));
        List<HostedPack> packs = host.packs();
        lang.send(source, "pack.host.files-title", Lang.ph("count", packs.size()));
        for (HostedPack pack : packs)
            lang.send(source, "pack.host.file", Lang.ph("file", pack.fileName()),
                    Lang.ph("url", pack.url()), Lang.ph("sha1", pack.sha1()));
        return 1;
    }
}
