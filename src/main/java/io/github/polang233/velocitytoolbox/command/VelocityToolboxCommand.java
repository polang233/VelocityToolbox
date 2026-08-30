package io.github.polang233.velocitytoolbox.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.polang233.velocitytoolbox.VelocityToolboxPlugin;
import io.github.polang233.velocitytoolbox.plugins.CleanupReport;
import io.github.polang233.velocitytoolbox.plugins.PluginLoadService;
import io.github.polang233.velocitytoolbox.lang.Lang;
import io.github.polang233.velocitytoolbox.pack.HostedPack;
import io.github.polang233.velocitytoolbox.pack.PackService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static io.github.polang233.velocitytoolbox.lang.Lang.ph;

/**
 * {@code /vtoolbox}（别名 {@code /vtb}）。权限：{@code velocitytoolbox.admin}。
 *
 * <p>使用 Velocity 4.0 以上推荐的 {@link BrigadierCommand}，并通过 {@code CommandMeta.plugin(this)} 登记归属，
 * 这样本插件卸载时命令可以被拆掉。</p>
 */
public final class VelocityToolboxCommand {

    public static final String PERMISSION = "velocitytoolbox.admin";

    private final VelocityToolboxPlugin plugin;
    private final ProxyServer proxy;
    private final PluginLoadService plugins;
    private final PackService packService;
    private final Lang lang;

    public VelocityToolboxCommand(
            VelocityToolboxPlugin plugin,
            ProxyServer proxy,
            PluginLoadService plugins,
            PackService packService,
            Lang lang
    ) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.plugins = plugins;
        this.packService = packService;
        this.lang = lang;
    }

    public BrigadierCommand build() {
        LiteralArgumentBuilder<CommandSource> root = BrigadierCommand.literalArgumentBuilder("vtoolbox")
                .requires(source -> source.hasPermission(PERMISSION))
                .executes(this::help);

        root.then(BrigadierCommand.literalArgumentBuilder("help").executes(this::help));
        root.then(BrigadierCommand.literalArgumentBuilder("version").executes(this::version));
        root.then(BrigadierCommand.literalArgumentBuilder("status").executes(this::status));
        root.then(BrigadierCommand.literalArgumentBuilder("packs").executes(this::packs));
        root.then(BrigadierCommand.literalArgumentBuilder("vhosts").executes(this::vhosts));
        root.then(BrigadierCommand.literalArgumentBuilder("reload").executes(this::reloadAll));
        root.then(pluginNode());

        return new BrigadierCommand(root);
    }

    private LiteralArgumentBuilder<CommandSource> pluginNode() {
        return BrigadierCommand.literalArgumentBuilder("plugin")
                .executes(this::pluginHelp)
                .then(BrigadierCommand.literalArgumentBuilder("list").executes(this::pluginList))
                .then(BrigadierCommand.literalArgumentBuilder("load")
                        .executes(ctx -> usage(ctx, "/vtoolbox plugin load <file.jar>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("file", StringArgumentType.greedyString())
                                .suggests(this::suggestJars)
                                .executes(this::pluginLoad)))
                .then(BrigadierCommand.literalArgumentBuilder("unload")
                        .executes(ctx -> usage(ctx, "/vtoolbox plugin unload <plugin-id>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .suggests(this::suggestPluginIds)
                                .executes(this::pluginUnload)))
                .then(BrigadierCommand.literalArgumentBuilder("reload")
                        .executes(ctx -> usage(ctx, "/vtoolbox plugin reload <plugin-id>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .suggests(this::suggestPluginIds)
                                .executes(this::pluginReload)));
    }

    private int help(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        lang.send(source, "command.help.title");
        helpLine(source, "/vtoolbox version", "command.help.version");
        helpLine(source, "/vtoolbox status", "command.help.status");
        helpLine(source, "/vtoolbox packs", "command.help.packs");
        helpLine(source, "/vtoolbox vhosts", "command.help.vhosts");
        helpLine(source, "/vtoolbox reload", "command.help.reload");
        helpLine(source, "/vtoolbox plugin", "command.help.plugin");
        return Command.SINGLE_SUCCESS;
    }

    private int pluginHelp(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        lang.send(source, "command.plugin.title");
        helpLine(source, "/vtoolbox plugin list", "command.plugin.list");
        helpLine(source, "/vtoolbox plugin load <file.jar>", "command.plugin.load");
        helpLine(source, "/vtoolbox plugin unload <plugin-id>", "command.plugin.unload");
        helpLine(source, "/vtoolbox plugin reload <plugin-id>", "command.plugin.reload");
        lang.send(source, "command.plugin.limit");
        return Command.SINGLE_SUCCESS;
    }

    private int version(CommandContext<CommandSource> ctx) {
        lang.send(ctx.getSource(), "command.version",
                ph("version", plugin.version()),
                ph("plugins", plugins.loadedIds().size()));
        lang.send(ctx.getSource(), packHostStatus());
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        lang.send(source, "command.status.proxy",
                ph("version", proxy.getVersion().getVersion()),
                ph("java", System.getProperty("java.version")),
                ph("plugins", plugins.loadedIds().size()));
        lang.send(source, packHostStatus());
        if (packService.enabled()) {
            lang.send(source, "command.status.origin", ph("origin", packService.publicOrigin()));
            lang.send(source, "command.status.packs-dir", ph("path", packService.packsDirectory()));
        }
        return pluginList(ctx);
    }

    private int packs(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        if (!packService.enabled()) {
            lang.send(source, "command.packs.disabled");
            return 0;
        }
        List<HostedPack> hosted = packService.packs();
        if (hosted.isEmpty()) {
            lang.send(source, "command.packs.empty", ph("path", packService.packsDirectory()));
            return Command.SINGLE_SUCCESS;
        }
        lang.send(source, "command.packs.title", ph("count", hosted.size()));
        for (HostedPack pack : hosted) {
            lang.send(source, Component.text(pack.fileName(), Lang.ACCENT));
            lang.send(source, Component.text(pack.url(), Lang.BODY));
            lang.send(source, Component.text()
                    .append(Component.text("sha1 ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(pack.sha1(), Lang.BODY))
                    .build());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int vhosts(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        List<Player> players = new ArrayList<>(proxy.getAllPlayers());
        if (players.isEmpty()) {
            lang.send(source, "command.vhosts.empty");
            return Command.SINGLE_SUCCESS;
        }
        players.sort(Comparator
                .comparing((Player player) -> player.getVirtualHost().map(InetSocketAddress::getHostString).orElse(""))
                .thenComparing(Player::getUsername));
        lang.send(source, "command.vhosts.title", ph("count", players.size()));
        for (Player player : players) {
            String host = player.getVirtualHost()
                    .map(InetSocketAddress::getHostString)
                    .orElseGet(() -> lang.plain("command.vhosts.unknown"));
            InetSocketAddress remote = player.getRemoteAddress();
            String remoteIp = remote.getAddress() != null
                    ? remote.getAddress().getHostAddress()
                    : remote.getHostString();
            lang.send(source, Component.text()
                    .append(Component.text(player.getUsername(), Lang.ACCENT))
                    .append(Component.text("  " + host, Lang.BODY))
                    .append(Component.text("  (" + remoteIp + ")", NamedTextColor.DARK_GRAY))
                    .build());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reloadAll(CommandContext<CommandSource> ctx) {
        boolean ok = plugin.reloadAll();
        lang.send(ctx.getSource(), ok ? "command.reload.ok" : "command.reload.fail");
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

    private int pluginList(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        List<PluginLoadService.PluginInfo> infos = plugins.pluginInfos();
        lang.send(source, "command.plugin.list-title", ph("count", infos.size()));
        for (PluginLoadService.PluginInfo info : infos) {
            lang.send(source, pluginLine(info));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int pluginLoad(CommandContext<CommandSource> ctx) {
        return report(ctx, plugins.loadByFileName(StringArgumentType.getString(ctx, "file")));
    }

    private int pluginUnload(CommandContext<CommandSource> ctx) {
        return report(ctx, plugins.unload(StringArgumentType.getString(ctx, "id")));
    }

    private int pluginReload(CommandContext<CommandSource> ctx) {
        return report(ctx, plugins.reload(StringArgumentType.getString(ctx, "id")));
    }

    private int report(CommandContext<CommandSource> ctx, PluginLoadService.OperationResult result) {
        CommandSource source = ctx.getSource();
        lang.send(source, result.messageKey(), Lang.placeholders(result.placeholders()));
        CleanupReport cleanup = result.cleanup();
        if (cleanup != null) {
            if (cleanup.shutdownEventFailed()) {
                lang.send(source, "plugins.shutdown-error");
            }
            lang.send(source, "plugins.cleanup.summary",
                    ph("commands", cleanup.commands()),
                    ph("tasks", cleanup.tasks()),
                    ph("listeners", cleanup.extraListeners()),
                    ph("channels", cleanup.channels()));
            for (CleanupReport.Leftover leftover : cleanup.leftovers()) {
                lang.send(source, leftover.key(), Lang.placeholders(leftover.placeholders()));
            }
        }
        if (result.error() != null) {
            lang.send(source, "plugins.error.detail",
                    ph("type", result.error().getClass().getSimpleName()),
                    ph("message", PluginLoadService.rootMessage(result.error())));
            lang.send(source, "plugins.error.see-log");
        }
        return result.success() ? Command.SINGLE_SUCCESS : 0;
    }

    private int usage(CommandContext<CommandSource> ctx, String command) {
        lang.send(ctx.getSource(), "command.usage", ph("usage", command));
        return 0;
    }

    private CompletableFuture<Suggestions> suggestJars(CommandContext<CommandSource> ctx, SuggestionsBuilder builder) {
        return suggest(builder, plugins.jarFileNames());
    }

    private CompletableFuture<Suggestions> suggestPluginIds(CommandContext<CommandSource> ctx, SuggestionsBuilder builder) {
        return suggest(builder, plugins.unmanagedIds());
    }

    private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, List<String> options) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(option);
            }
        }
        return builder.buildFuture();
    }

    private Component packHostStatus() {
        return Component.text()
                .append(Component.text("pack-host=", NamedTextColor.DARK_GRAY))
                .append(packService.enabled()
                        ? lang.get("command.pack-host.on")
                        : lang.get("command.pack-host.off"))
                .build();
    }

    private static Component pluginLine(PluginLoadService.PluginInfo info) {
        return Component.text()
                .append(Component.text(info.id(), Lang.ACCENT))
                .append(Component.text(" " + info.version(), Lang.BODY))
                .append(Component.text(" -> ", NamedTextColor.DARK_GRAY))
                .append(Component.text(info.jar(), Lang.BODY))
                .build();
    }

    private void helpLine(CommandSource source, String command, String descriptionKey) {
        lang.send(source, Component.text()
                .append(Component.text(command, Lang.ACCENT))
                .append(Component.text("  "))
                .append(lang.get(descriptionKey))
                .build());
    }
}
