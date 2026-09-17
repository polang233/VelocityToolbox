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
import java.io.IOException;
import java.util.Locale;

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
        node.then(literal("check")
                .requires(source -> action(source, "pack", "check")).executes(this::check));
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
        for (String action : List.of("list", "check", "status", "resend"))
            if (action(ctx.getSource(), "pack", action))
                helpLine(ctx.getSource(), "/vtb pack " + action + (List.of("list", "check").contains(action) ? "" : " <player>"),
                        "pack.help." + action);
        return 1;
    }

    private int check(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        try {
            var result = host.check();
            lang.send(source, "pack.check.ok");
            lang.send(source, "pack.check.summary",
                    Lang.ph("host", lang.plain(result.host().config().enabled() ? "common.enabled" : "common.disabled")),
                    Lang.ph("delivery", lang.plain(result.rules().enabled() ? "common.enabled" : "common.disabled")),
                    Lang.ph("files", result.host().packs().size()), Lang.ph("packs", result.rules().packs().size()));
            if (!result.rules().enabled()) lang.send(source, "pack.check.disabled");
            if (result.host().config().enabled()) {
                lang.send(source, "pack.host.url", Lang.ph("url", result.host().origin()));
                if (result.host().config().publicUrl().isEmpty()) lang.send(source, "pack.check.auto-url");
            }
            lang.send(source, "pack.check.scope");
            return 1;
        } catch (IOException | RuntimeException error) {
            String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            lang.send(source, "pack.check.failed", Lang.ph("detail", detail));
            if (source != proxy.getConsoleCommandSource())
                lang.send(proxy.getConsoleCommandSource(), "pack.check.log-failed",
                        Lang.ph("source", sourceName(source)), Lang.ph("detail", detail));
            return 0;
        }
    }

    private int player(CommandContext<CommandSource> ctx, String action) {
        Player player = proxy.getPlayer(StringArgumentType.getString(ctx, "player")).orElse(null);
        if (player == null) {
            lang.send(ctx.getSource(), "pack.delivery.offline");
            return 0;
        }
        if (action.equals("status")) {
            sender.show(player, ctx.getSource());
            explain(player, ctx.getSource());
        }
        else {
            var result = sender.resend(player);
            lang.send(ctx.getSource(), switch (result) {
                case SCHEDULED -> "pack.delivery.resent";
                case EMPTY -> "pack.delivery.resend-empty";
                case DISABLED -> "pack.delivery.unavailable";
            });
            if (result == PackSender.ResendResult.EMPTY) {
                sender.show(player, ctx.getSource());
                explain(player, ctx.getSource());
            }
            return result == PackSender.ResendResult.SCHEDULED ? 1 : 0;
        }
        return 1;
    }

    private void explain(Player player, CommandSource source) {
        PackRules rules = sender.rules();
        if (!rules.enabled() || player.getCurrentServer().isEmpty()) return;
        String server = player.getCurrentServer().orElseThrow().getServerInfo().getName();
        var explanation = rules.explain(server, player.getProtocolVersion(), player::hasPermission);
        lang.send(source, "pack.explain.title");
        lang.send(source, "pack.explain.assignment", Lang.ph("path", "resource-packs.servers." + explanation.assignment() + ".packs"));
        if (explanation.matches().isEmpty()) lang.send(source, "pack.explain.empty");
        for (var match : explanation.matches()) {
            String reason = "pack.explain.reason." + match.reason().name().toLowerCase(Locale.ROOT).replace('_', '-');
            lang.send(source, "pack.explain.variant", Lang.ph("pack", match.pack()), Lang.ph("index", match.index()),
                    Lang.ph("reason", lang.plain(reason)), Lang.ph("conditions", conditions(match.variant())));
        }
        if (player.getProtocolVersion().compareTo(com.velocitypowered.api.network.ProtocolVersion.MINECRAFT_1_20_3) < 0)
            lang.send(source, "pack.explain.legacy");
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
