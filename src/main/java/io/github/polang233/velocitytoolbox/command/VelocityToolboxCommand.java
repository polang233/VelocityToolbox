package io.github.polang233.velocitytoolbox.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import io.github.polang233.velocitytoolbox.plugins.PluginInspection;
import io.github.polang233.velocitytoolbox.plugins.PluginLoadService;
import io.github.polang233.velocitytoolbox.lang.Lang;
import io.github.polang233.velocitytoolbox.pack.HostedPack;
import io.github.polang233.velocitytoolbox.pack.PackService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.github.polang233.velocitytoolbox.lang.Lang.ph;

/**
 * {@code /vtoolbox}（别名 {@code /vtb}）。旧权限 {@code velocitytoolbox.admin} 保留为全部放行，
 * 新权限使用 {@code velocitytoolbox.command} + 对应子命令权限。
 *
 * <p>使用 Velocity 4.0 以上推荐的 {@link BrigadierCommand}，并通过 {@code CommandMeta.plugin(this)} 登记归属，
 * 这样本插件卸载时命令可以被拆掉。</p>
 */
public final class VelocityToolboxCommand {

    public static final String PERMISSION = "velocitytoolbox.admin";
    public static final String COMMAND_PERMISSION = "velocitytoolbox.command";
    public static final String INFO_PERMISSION = "velocitytoolbox.command.info";
    public static final String PACKS_PERMISSION = "velocitytoolbox.command.packs";
    public static final String VHOSTS_PERMISSION = "velocitytoolbox.command.vhosts";
    public static final String RELOAD_PERMISSION = "velocitytoolbox.command.reload";
    public static final String PLUGIN_PERMISSION = "velocitytoolbox.command.plugin";
    public static final String PLUGIN_LIST_PERMISSION = "velocitytoolbox.command.plugin.list";
    public static final String PLUGIN_INSPECT_PERMISSION = "velocitytoolbox.command.plugin.inspect";
    public static final String PLUGIN_LOAD_PERMISSION = "velocitytoolbox.command.plugin.load";
    public static final String PLUGIN_UNLOAD_PERMISSION = "velocitytoolbox.command.plugin.unload";
    public static final String PLUGIN_RELOAD_PERMISSION = "velocitytoolbox.command.plugin.reload";

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
                .requires(this::hasBasePermission)
                .executes(this::help);

        root.then(BrigadierCommand.literalArgumentBuilder("help").executes(this::help));
        root.then(BrigadierCommand.literalArgumentBuilder("info")
                .requires(source -> hasCommandPermission(source, INFO_PERMISSION))
                .executes(this::info));
        root.then(BrigadierCommand.literalArgumentBuilder("packs")
                .requires(source -> hasCommandPermission(source, PACKS_PERMISSION))
                .executes(this::packs));
        root.then(BrigadierCommand.literalArgumentBuilder("vhosts")
                .requires(source -> hasCommandPermission(source, VHOSTS_PERMISSION))
                .executes(this::vhosts)
                .then(BrigadierCommand.requiredArgumentBuilder("entry", IntegerArgumentType.integer(1))
                        .executes(this::expandedVhost)));
        root.then(BrigadierCommand.literalArgumentBuilder("reload")
                .requires(source -> hasCommandPermission(source, RELOAD_PERMISSION))
                .executes(this::reloadAll));
        root.then(pluginNode());

        return new BrigadierCommand(root);
    }

    private LiteralArgumentBuilder<CommandSource> pluginNode() {
        return BrigadierCommand.literalArgumentBuilder("plugin")
                .requires(source -> hasPluginPermission(source, PLUGIN_PERMISSION))
                .executes(this::pluginHelp)
                .then(BrigadierCommand.literalArgumentBuilder("list")
                        .requires(source -> hasPluginPermission(source, PLUGIN_LIST_PERMISSION))
                        .executes(this::pluginList))
                .then(BrigadierCommand.literalArgumentBuilder("inspect")
                        .requires(source -> hasPluginPermission(source, PLUGIN_INSPECT_PERMISSION))
                        .executes(ctx -> usage(ctx, "/vtoolbox plugin inspect <plugin-id>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .suggests(this::suggestPluginIds)
                                .executes(this::pluginInspect)))
                .then(BrigadierCommand.literalArgumentBuilder("load")
                        .requires(source -> hasPluginPermission(source, PLUGIN_LOAD_PERMISSION))
                        .executes(ctx -> usage(ctx, "/vtoolbox plugin load <file.jar>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("file", StringArgumentType.greedyString())
                                .suggests(this::suggestJars)
                                .executes(this::pluginLoad)))
                .then(BrigadierCommand.literalArgumentBuilder("unload")
                        .requires(source -> hasPluginPermission(source, PLUGIN_UNLOAD_PERMISSION))
                        .executes(ctx -> usage(ctx, "/vtoolbox plugin unload <plugin-id>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .suggests(this::suggestPluginIds)
                                .executes(this::pluginUnload)))
                .then(BrigadierCommand.literalArgumentBuilder("reload")
                        .requires(source -> hasPluginPermission(source, PLUGIN_RELOAD_PERMISSION))
                        .executes(ctx -> usage(ctx, "/vtoolbox plugin reload <plugin-id>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .suggests(this::suggestPluginIds)
                                .executes(this::pluginReload)));
    }

    private int help(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        lang.send(source, "command.help.title");
        helpLineIfAllowed(source, INFO_PERMISSION, "/vtoolbox info", "command.help.info");
        helpLineIfAllowed(source, PACKS_PERMISSION, "/vtoolbox packs", "command.help.packs");
        helpLineIfAllowed(source, VHOSTS_PERMISSION, "/vtoolbox vhosts", "command.help.vhosts");
        helpLineIfAllowed(source, RELOAD_PERMISSION, "/vtoolbox reload", "command.help.reload");
        if (hasPluginPermission(source, PLUGIN_PERMISSION)) {
            helpLine(source, "/vtoolbox plugin", "command.help.plugin");
        }
        return Command.SINGLE_SUCCESS;
    }

    private int pluginHelp(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        lang.send(source, "command.plugin.title");
        pluginHelpLineIfAllowed(source, PLUGIN_LIST_PERMISSION,
                "/vtoolbox plugin list", "command.plugin.list");
        pluginHelpLineIfAllowed(source, PLUGIN_INSPECT_PERMISSION,
                "/vtoolbox plugin inspect <plugin-id>", "command.plugin.inspect");
        pluginHelpLineIfAllowed(source, PLUGIN_LOAD_PERMISSION,
                "/vtoolbox plugin load <file.jar>", "command.plugin.load");
        pluginHelpLineIfAllowed(source, PLUGIN_UNLOAD_PERMISSION,
                "/vtoolbox plugin unload <plugin-id>", "command.plugin.unload");
        pluginHelpLineIfAllowed(source, PLUGIN_RELOAD_PERMISSION,
                "/vtoolbox plugin reload <plugin-id>", "command.plugin.reload");
        lang.send(source, "command.plugin.limit");
        return Command.SINGLE_SUCCESS;
    }

    private int info(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        lang.send(source, "command.info.title");
        fieldLine(source, "command.field.toolbox-version", plugin.version(), NamedTextColor.GREEN);
        fieldLine(source, "command.field.proxy-version", proxy.getVersion().getVersion(), Lang.BODY);
        fieldLine(source, "command.field.java-version", System.getProperty("java.version"), Lang.BODY);
        fieldLine(source, "command.field.loaded-plugins", plugins.loadedIds().size(), Lang.BODY);
        fieldLine(source, "command.field.pack-host",
                packService.enabled()
                        ? lang.plain("command.pack-host.enabled")
                        : lang.plain("command.pack-host.disabled"),
                packService.enabled() ? NamedTextColor.GREEN : NamedTextColor.RED);
        if (packService.enabled()) {
            fieldLine(source, "command.field.pack-origin", packService.publicOrigin(), Lang.BODY);
            fieldLine(source, "command.field.pack-directory", packService.packsDirectory(), Lang.BODY);
            fieldLine(source, "command.field.hosted-packs", packService.packs().size(), Lang.BODY);
        }
        return Command.SINGLE_SUCCESS;
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
        return vhosts(ctx, null);
    }

    private int expandedVhost(CommandContext<CommandSource> ctx) {
        return vhosts(ctx, IntegerArgumentType.getInteger(ctx, "entry"));
    }

    private int vhosts(CommandContext<CommandSource> ctx, Integer selectedEntry) {
        CommandSource source = ctx.getSource();
        List<Player> players = new ArrayList<>(proxy.getAllPlayers());
        if (players.isEmpty()) {
            lang.send(source, "command.vhosts.empty");
            return Command.SINGLE_SUCCESS;
        }
        players.sort(Comparator
                .comparing((Player player) -> entryPoint(player).sortKey())
                .thenComparing(Player::getUsername, String.CASE_INSENSITIVE_ORDER));

        Map<EntryPoint, List<Player>> groups = new LinkedHashMap<>();
        for (Player player : players) {
            groups.computeIfAbsent(entryPoint(player), ignored -> new ArrayList<>()).add(player);
        }

        lang.send(source, "command.vhosts.title",
                ph("entries", groups.size()),
                ph("players", players.size()));
        int index = 1;
        String unknown = lang.plain("command.common.unknown");
        for (Map.Entry<EntryPoint, List<Player>> group : groups.entrySet()) {
            EntryPoint entry = group.getKey();
            int currentIndex = index++;
            Component entryLine = lang.get("command.vhosts.entry",
                    ph("index", currentIndex),
                    ph("domain", entry.domain().isEmpty() ? unknown : entry.domain()),
                    ph("port", entry.port() <= 0 ? unknown : entry.port()),
                    ph("count", group.getValue().size()))
                    .hoverEvent(HoverEvent.showText(lang.get(selectedEntry != null && selectedEntry == currentIndex
                            ? "command.vhosts.collapse-hint"
                            : "command.vhosts.expand-hint")))
                    .clickEvent(ClickEvent.runCommand(selectedEntry != null && selectedEntry == currentIndex
                            ? "/vtoolbox vhosts"
                            : "/vtoolbox vhosts " + currentIndex));
            lang.send(source, entryLine);
            if (selectedEntry != null && selectedEntry == currentIndex) {
                for (Player player : group.getValue()) {
                    lang.send(source, playerLine(player, unknown));
                }
            }
        }
        if (selectedEntry != null && selectedEntry > groups.size()) {
            lang.send(source, "command.vhosts.invalid-entry", ph("index", selectedEntry));
        }
        return Command.SINGLE_SUCCESS;
    }

    private Component playerLine(Player player, String unknown) {
        String ping = player.getPing() < 0 ? unknown : player.getPing() + "ms";
        return lang.get("command.vhosts.player", ph("name", player.getUsername()), ph("ping", ping))
                .hoverEvent(HoverEvent.showText(playerHover(player, unknown)));
    }

    private Component playerHover(Player player, String unknown) {
        String backend = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(unknown);
        String mode = lang.plain(player.isOnlineMode()
                ? "command.player.online-mode"
                : "command.player.offline-mode");
        return Component.text()
                .append(lang.get("command.vhosts.player-hover-title", ph("name", player.getUsername())))
                .append(Component.newline())
                .append(hoverField("command.vhosts.player-uuid", String.valueOf(player.getUniqueId())))
                .append(Component.newline())
                .append(hoverField("command.vhosts.player-ip", remoteIp(player)))
                .append(Component.newline())
                .append(hoverField("command.vhosts.player-server", backend))
                .append(Component.newline())
                .append(hoverField("command.vhosts.player-mode", mode))
                .build();
    }

    private int reloadAll(CommandContext<CommandSource> ctx) {
        boolean ok = plugin.reloadAll();
        lang.send(ctx.getSource(), ok ? "command.reload.ok" : "command.reload.fail");
        if (ok) {
            lang.send(proxy.getConsoleCommandSource(), "log.console.reloaded",
                    ph("source", sourceName(ctx.getSource())));
            lang.send(proxy.getConsoleCommandSource(), packService.enabled()
                    ? "log.console.pack-host-enabled"
                    : "log.console.pack-host-disabled");
        }
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

    private int pluginList(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        sendPluginList(source);
        return Command.SINGLE_SUCCESS;
    }

    private void sendPluginList(CommandSource source) {
        List<PluginLoadService.PluginInfo> infos = plugins.pluginInfos();
        lang.send(source, "command.plugin.list-title", ph("count", infos.size()));
        for (PluginLoadService.PluginInfo info : infos) {
            lang.send(source, pluginLine(info));
        }
    }

    private int pluginInspect(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "id");
        PluginInspection inspection = plugins.inspect(id);
        if (!inspection.found()) {
            lang.send(source, "plugins.inspect.not-loaded", ph("plugin", id));
            return 0;
        }

        lang.send(source, Component.text()
                .append(lang.get("command.plugin.inspect-title"))
                .append(Component.text("  " + inspection.name(), Lang.COMMAND))
                .append(Component.text(" " + inspection.version(), NamedTextColor.GRAY))
                .build());

        sectionLine(source, "command.plugin.section.basic");
        fieldLine(source, "command.field.id", inspection.id(), Lang.BODY);
        fieldLine(source, "command.field.name", inspection.name(), Lang.BODY);
        fieldLine(source, "command.field.version", inspection.version(), Lang.BODY);
        fieldLine(source, "command.field.authors", listOrNone(inspection.authors()), Lang.BODY);
        fieldLine(source, "command.field.description", valueOrNone(inspection.description()), Lang.BODY);
        fieldLine(source, "command.field.url", valueOrNone(inspection.url()), Lang.BODY);
        fieldLine(source, "command.field.jar", inspection.jar(), NamedTextColor.GRAY);
        fieldLine(source, "command.field.instance-class", inspection.instanceClass(), NamedTextColor.GRAY);
        fieldLine(source, "command.field.source-state", yesNo(inspection.sourceAvailable()),
                inspection.sourceAvailable() ? NamedTextColor.GREEN : NamedTextColor.RED);
        fieldLine(source, "command.field.instance-state", yesNo(inspection.instanceAvailable()),
                inspection.instanceAvailable() ? NamedTextColor.GREEN : NamedTextColor.RED);

        sectionLine(source, "command.plugin.section.dependencies");
        fieldLine(source, "command.field.required-dependencies",
                listOrNone(inspection.requiredDependencies()), Lang.BODY);
        fieldLine(source, "command.field.optional-dependencies",
                listOrNone(inspection.optionalDependencies()), Lang.BODY);
        fieldLine(source, "command.field.dependents", listOrNone(inspection.dependents()),
                inspection.dependents().isEmpty() ? Lang.BODY : NamedTextColor.RED);
        fieldLine(source, "command.field.provided-ids", listOrNone(inspection.providedIds()), Lang.BODY);

        sectionLine(source, "command.plugin.section.runtime");
        runtimeLine(source, inspection);

        sectionLine(source, "command.plugin.section.risk");
        fieldLine(source, "command.field.risk",
                lang.plain("command.plugin.risk." + inspection.risk().name().toLowerCase(Locale.ROOT)),
                riskColor(inspection.risk()));
        for (PluginInspection.Issue issue : inspection.issues()) {
            lang.send(source, Component.text()
                    .append(Component.text("  • ", riskColor(inspection.risk())))
                    .append(lang.get("plugins.inspect.issue." + issue.name().toLowerCase(Locale.ROOT)))
                    .build());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int pluginLoad(CommandContext<CommandSource> ctx) {
        String file = StringArgumentType.getString(ctx, "file");
        return report(ctx, plugins.loadByFileName(file));
    }

    private int pluginUnload(CommandContext<CommandSource> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        return report(ctx, plugins.unload(id));
    }

    private int pluginReload(CommandContext<CommandSource> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        return report(ctx, plugins.reload(id));
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

    private Component pluginLine(PluginLoadService.PluginInfo info) {
        String authors = listOrNone(info.authors());
        Component details = Component.text()
                .append(Component.text(info.name(), Lang.COMMAND))
                .append(Component.text(" " + info.version(), NamedTextColor.GRAY))
                .append(Component.text("  ·  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(authors, Lang.BODY))
                .hoverEvent(HoverEvent.showText(pluginHover(info)))
                .clickEvent(ClickEvent.suggestCommand("/vtoolbox plugin inspect " + info.id()))
                .build();
        return Component.text()
                .append(Component.text("• ", NamedTextColor.DARK_GRAY))
                .append(details)
                .build();
    }

    private Component pluginHover(PluginLoadService.PluginInfo info) {
        var hover = Component.text()
                .append(Component.text(info.name(), Lang.COMMAND))
                .append(Component.text(" " + info.version(), NamedTextColor.GRAY))
                .append(Component.newline())
                .append(hoverField("command.field.id", info.id()))
                .append(Component.newline())
                .append(hoverField("command.field.authors", listOrNone(info.authors())))
                .append(Component.newline())
                .append(hoverField("command.field.description", valueOrNone(info.description())))
                .append(Component.newline())
                .append(hoverField("command.field.url", valueOrNone(info.url())))
                .append(Component.newline())
                .append(hoverField("command.field.required-dependencies",
                        listOrNone(info.requiredDependencies())))
                .append(Component.newline())
                .append(hoverField("command.field.optional-dependencies",
                        listOrNone(info.optionalDependencies())))
                .append(Component.newline())
                .append(hoverField("command.field.provided-ids", listOrNone(info.providedIds())))
                .append(Component.newline())
                .append(Component.newline())
                .append(lang.get("command.plugin.hover-hint"));
        return hover.build();
    }

    private Component hoverField(String labelKey, String value) {
        return Component.text()
                .append(lang.get(labelKey))
                .append(Component.text("：", NamedTextColor.DARK_GRAY))
                .append(Component.text(value, Lang.BODY))
                .build();
    }

    private void helpLine(CommandSource source, String command, String descriptionKey) {
        lang.send(source, Component.text()
                .append(Component.text(command, Lang.COMMAND))
                .append(Component.text("  "))
                .append(lang.get(descriptionKey))
                .build());
    }

    private void helpLineIfAllowed(
            CommandSource source,
            String permission,
            String command,
            String descriptionKey
    ) {
        if (hasCommandPermission(source, permission)) {
            helpLine(source, command, descriptionKey);
        }
    }

    private void pluginHelpLineIfAllowed(
            CommandSource source,
            String permission,
            String command,
            String descriptionKey
    ) {
        if (hasPluginPermission(source, permission)) {
            helpLine(source, command, descriptionKey);
        }
    }

    private boolean hasBasePermission(CommandSource source) {
        return source.hasPermission(PERMISSION) || source.hasPermission(COMMAND_PERMISSION);
    }

    private boolean hasCommandPermission(CommandSource source, String permission) {
        return source.hasPermission(PERMISSION)
                || source.hasPermission(COMMAND_PERMISSION) && source.hasPermission(permission);
    }

    private boolean hasPluginPermission(CommandSource source, String permission) {
        return source.hasPermission(PERMISSION)
                || source.hasPermission(COMMAND_PERMISSION)
                && source.hasPermission(PLUGIN_PERMISSION)
                && source.hasPermission(permission);
    }

    private void sectionLine(CommandSource source, String key) {
        lang.send(source, Component.text()
                .append(Component.text("— ", NamedTextColor.DARK_GRAY))
                .append(lang.get(key))
                .append(Component.text(" —", NamedTextColor.DARK_GRAY))
                .build());
    }

    private void fieldLine(CommandSource source, String labelKey, Object value, TextColor valueColor) {
        lang.send(source, Component.text()
                .append(Component.text("  "))
                .append(lang.get(labelKey))
                .append(Component.text("：", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.valueOf(value), valueColor))
                .build());
    }

    private void runtimeLine(CommandSource source, PluginInspection inspection) {
        lang.send(source, Component.text()
                .append(Component.text("  "))
                .append(labelValue("command.field.commands", inspection.commands()))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(labelValue("command.field.tasks", inspection.tasks()))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(labelValue("command.field.listeners", inspection.listeners()))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(labelValue("command.field.channels", inspection.channels()))
                .build());
        fieldLine(source, "command.field.executor", yesNo(inspection.executorActive()),
                inspection.executorActive() ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
    }

    private Component labelValue(String labelKey, Object value) {
        return Component.text()
                .append(lang.get(labelKey))
                .append(Component.text(" ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.valueOf(value), Lang.BODY))
                .build();
    }

    private String listOrNone(List<String> values) {
        return values.isEmpty() ? lang.plain("command.common.none") : String.join(", ", values);
    }

    private String valueOrNone(String value) {
        return value == null || value.isBlank() ? lang.plain("command.common.none") : value;
    }

    private String yesNo(boolean value) {
        return lang.plain(value ? "command.common.yes" : "command.common.no");
    }

    private static TextColor riskColor(PluginInspection.Risk risk) {
        return switch (risk) {
            case LOW -> NamedTextColor.GREEN;
            case MEDIUM -> NamedTextColor.YELLOW;
            case HIGH -> NamedTextColor.GOLD;
            case BLOCKED -> NamedTextColor.RED;
        };
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

    private String sourceName(CommandSource source) {
        return source instanceof Player player
                ? player.getUsername()
                : lang.plain("command.common.console");
    }

    private record EntryPoint(String domain, int port) {
        String sortKey() {
            return domain.toLowerCase(Locale.ROOT) + ':' + port;
        }
    }
}
