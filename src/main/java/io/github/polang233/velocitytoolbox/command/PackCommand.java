package io.github.polang233.velocitytoolbox.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.network.ProtocolVersion;
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
import java.util.ArrayList;
import java.util.Locale;
import java.io.IOException;

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
        for (String action : List.of("status", "resend", "remove")) {
            boolean supportsAll = !action.equals("status");
            var command = literal(action)
                    .requires(source -> action(source, "pack", action))
                    .executes(ctx -> usage(ctx, syntax(action)))
                    .then(BrigadierCommand.requiredArgumentBuilder("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                List<String> names = new ArrayList<>(proxy.getAllPlayers().stream().map(Player::getUsername).sorted().toList());
                                if (supportsAll && bulk(ctx.getSource(), action)) names.add("all");
                                return suggest(builder, names);
                            })
                            .executes(ctx -> player(ctx, action)));
            if (supportsAll)
                command.then(literal("all").requires(source -> bulk(source, action))
                        .executes(ctx -> all(ctx, action)));
            node.then(command);
        }
        return node;
    }

    private static String syntax(String action) {
        return "/vtb pack " + action + switch (action) {
            case "list", "check" -> "";
            case "status" -> " <player>";
            default -> " <player|all>";
        };
    }

    private boolean bulk(CommandSource source, String action) {
        return action(source, "pack", action) && (source.hasPermission(PERMISSION)
                || source.hasPermission("velocitytoolbox.command.pack." + action + ".all"));
    }

    private int all(CommandContext<CommandSource> ctx, String action) {
        if (action.equals("resend")) return sender.resendAll(ctx.getSource()) ? 1 : 0;
        var result = sender.removeAll();
        lang.send(ctx.getSource(), "pack.delivery.remove-all", Lang.ph("removed", result.removed()),
                Lang.ph("empty", result.empty()), Lang.ph("legacy", result.legacy()), Lang.ph("skipped", result.skipped()));
        return 1;
    }

    private int help(CommandContext<CommandSource> ctx) {
        sectionLine(ctx.getSource(), "pack.title");
        for (String action : List.of("list", "check", "status", "resend", "remove"))
            if (action(ctx.getSource(), "pack", action))
                helpLine(ctx.getSource(), syntax(action), "pack.help." + action);
        for (String action : List.of("resend", "remove"))
            if (bulk(ctx.getSource(), action)) helpLine(ctx.getSource(), "/vtb pack " + action + " all", "pack.help." + action + "-all");
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

    private int player(CommandContext<CommandSource> ctx, String action) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "player");
        // all 是批量操作关键字，不得在权限不足时回落为玩家名。
        if (!action.equals("status") && name.equalsIgnoreCase("all")) {
            if (!bulk(ctx.getSource(), action)) throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
            return all(ctx, action);
        }
        Player player = proxy.getPlayer(name).orElse(null);
        if (player == null) {
            lang.send(ctx.getSource(), "pack.delivery.offline");
            return 0;
        }
        CommandSource source = ctx.getSource();
        switch (action) {
            case "remove" -> {
                var result = sender.remove(player);
                lang.send(source, switch (result) {
                    case REMOVED -> "pack.delivery.removed";
                    case EMPTY -> "pack.delivery.remove-empty";
                    case LEGACY -> "pack.delivery.remove-legacy";
                    case OFFLINE -> "pack.delivery.offline";
                }, Lang.ph("player", player.getUsername()));
                return result == PackSender.RemoveResult.OFFLINE ? 0 : 1;
            }
            case "status" -> {
                status(player, source);
                return 1;
            }
            default -> {
                var result = sender.resend(player);
                lang.send(source, switch (result) {
                    case SCHEDULED -> "pack.delivery.resent";
                    case EMPTY -> "pack.delivery.resend-empty";
                    case DISABLED -> "pack.delivery.unavailable";
                });
                // 没有可发的包时顺带显示原因，方便排查分配和条件。
                if (result == PackSender.ResendResult.EMPTY) status(player, source);
                return result == PackSender.ResendResult.SCHEDULED ? 1 : 0;
            }
        }
    }

    private void status(Player player, CommandSource source) {
        sender.show(player, source);
        explain(player, source);
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
        if (player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_3) < 0)
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
