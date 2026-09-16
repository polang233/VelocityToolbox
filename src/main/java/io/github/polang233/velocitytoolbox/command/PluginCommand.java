package io.github.polang233.velocitytoolbox.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.polang233.velocitytoolbox.lang.Lang;
import io.github.polang233.velocitytoolbox.plugins.CleanupReport;
import io.github.polang233.velocitytoolbox.plugins.PluginInspection;
import io.github.polang233.velocitytoolbox.plugins.PluginLoadService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static io.github.polang233.velocitytoolbox.lang.Lang.ph;

final class PluginCommand extends CommandView {
    private final PluginLoadService plugins;

    PluginCommand(ProxyServer proxy, Lang lang, PluginLoadService plugins) {
        super(proxy, lang);
        this.plugins = plugins;
    }

    LiteralArgumentBuilder<CommandSource> build() {
        return literal("plugin")
                .requires(source -> hasPluginPermission(source, PLUGIN_PERMISSION))
                .executes(this::pluginHelp)
                .then(literal("list")
                        .requires(source -> hasPluginPermission(source, PLUGIN_LIST_PERMISSION))
                        .executes(this::pluginList))
                .then(literal("inspect")
                        .requires(source -> hasPluginPermission(source, PLUGIN_INSPECT_PERMISSION))
                        .executes(ctx -> usage(ctx, "/vtoolbox plugin inspect <plugin-id>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .suggests(this::suggestPluginIds)
                                .executes(this::pluginInspect)))
                .then(literal("load")
                        .requires(source -> hasPluginPermission(source, PLUGIN_LOAD_PERMISSION))
                        .executes(ctx -> usage(ctx, "/vtoolbox plugin load <file.jar>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("file", StringArgumentType.greedyString())
                                .suggests(this::suggestJars)
                                .executes(this::pluginLoad)))
                .then(literal("unload")
                        .requires(source -> hasPluginPermission(source, PLUGIN_UNLOAD_PERMISSION))
                        .executes(ctx -> usage(ctx, "/vtoolbox plugin unload <plugin-id>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .suggests(this::suggestPluginIds)
                                .executes(this::pluginUnload)))
                .then(literal("reload")
                        .requires(source -> hasPluginPermission(source, PLUGIN_RELOAD_PERMISSION))
                        .executes(ctx -> usage(ctx, "/vtoolbox plugin reload <plugin-id>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .suggests(this::suggestPluginIds)
                                .executes(this::pluginReload)));
    }

    private int pluginHelp(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        lang.send(source, "plugin.title");
        pluginHelpLineIfAllowed(source, PLUGIN_LIST_PERMISSION,
                "/vtoolbox plugin list", "plugin.help.list");
        pluginHelpLineIfAllowed(source, PLUGIN_INSPECT_PERMISSION,
                "/vtoolbox plugin inspect <plugin-id>", "plugin.help.inspect");
        pluginHelpLineIfAllowed(source, PLUGIN_LOAD_PERMISSION,
                "/vtoolbox plugin load <file.jar>", "plugin.help.load");
        pluginHelpLineIfAllowed(source, PLUGIN_UNLOAD_PERMISSION,
                "/vtoolbox plugin unload <plugin-id>", "plugin.help.unload");
        pluginHelpLineIfAllowed(source, PLUGIN_RELOAD_PERMISSION,
                "/vtoolbox plugin reload <plugin-id>", "plugin.help.reload");
        lang.send(source, "plugin.help.limit");
        return Command.SINGLE_SUCCESS;
    }

    private int pluginList(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        sendPluginList(source);
        return Command.SINGLE_SUCCESS;
    }

    private void sendPluginList(CommandSource source) {
        List<PluginLoadService.PluginInfo> infos = plugins.pluginInfos();
        lang.send(source, "plugin.list.title", ph("count", infos.size()));
        for (PluginLoadService.PluginInfo info : infos) {
            lang.send(source, pluginLine(info));
        }
    }

    private int pluginInspect(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        String id = StringArgumentType.getString(ctx, "id");
        PluginInspection inspection = plugins.inspect(id);
        if (!inspection.found()) {
            lang.send(source, "plugin.inspect.not-loaded", ph("plugin", id));
            return 0;
        }

        lang.send(source, Component.text()
                .append(lang.get("plugin.inspect.title"))
                .append(Component.text("  " + inspection.name(), Lang.COMMAND))
                .append(Component.text(" " + inspection.version(), NamedTextColor.GRAY))
                .build());

        sectionLine(source, "plugin.inspect.section.basic");
        fieldLine(source, "plugin.inspect.field.id", inspection.id(), Lang.BODY);
        fieldLine(source, "plugin.inspect.field.name", inspection.name(), Lang.BODY);
        fieldLine(source, "plugin.inspect.field.version", inspection.version(), Lang.BODY);
        fieldLine(source, "plugin.inspect.field.authors", listOrNone(inspection.authors()), Lang.BODY);
        fieldLine(source, "plugin.inspect.field.description", valueOrNone(inspection.description()), Lang.BODY);
        fieldLine(source, "plugin.inspect.field.url", valueOrNone(inspection.url()), Lang.BODY);
        fieldLine(source, "plugin.inspect.field.jar", inspection.jar(), NamedTextColor.GRAY);
        fieldLine(source, "plugin.inspect.field.instance-class", inspection.instanceClass(), NamedTextColor.GRAY);
        fieldLine(source, "plugin.inspect.field.source-state", yesNo(inspection.sourceAvailable()),
                inspection.sourceAvailable() ? NamedTextColor.GREEN : NamedTextColor.RED);
        fieldLine(source, "plugin.inspect.field.instance-state", yesNo(inspection.instanceAvailable()),
                inspection.instanceAvailable() ? NamedTextColor.GREEN : NamedTextColor.RED);

        sectionLine(source, "plugin.inspect.section.dependencies");
        fieldLine(source, "plugin.inspect.field.required-dependencies",
                listOrNone(inspection.requiredDependencies()), Lang.BODY);
        fieldLine(source, "plugin.inspect.field.optional-dependencies",
                listOrNone(inspection.optionalDependencies()), Lang.BODY);
        fieldLine(source, "plugin.inspect.field.dependents", listOrNone(inspection.dependents()),
                inspection.dependents().isEmpty() ? Lang.BODY : NamedTextColor.RED);
        fieldLine(source, "plugin.inspect.field.provided-ids", listOrNone(inspection.providedIds()), Lang.BODY);

        sectionLine(source, "plugin.inspect.section.runtime");
        runtimeLine(source, inspection);

        sectionLine(source, "plugin.inspect.section.risk");
        fieldLine(source, "plugin.inspect.field.risk",
                lang.plain("plugin.inspect.risk." + inspection.risk().name().toLowerCase(Locale.ROOT)),
                riskColor(inspection.risk()));
        for (PluginInspection.Issue issue : inspection.issues()) {
            lang.send(source, Component.text()
                    .append(Component.text("  • ", riskColor(inspection.risk())))
                    .append(lang.get("plugin.inspect.issue." + issue.name().toLowerCase(Locale.ROOT).replace('_', '-')))
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
                lang.send(source, "plugin.shutdown-error");
            }
            lang.send(source, "plugin.cleanup.summary",
                    ph("commands", cleanup.commands()),
                    ph("tasks", cleanup.tasks()),
                    ph("listeners", cleanup.extraListeners()),
                    ph("channels", cleanup.channels()));
            for (CleanupReport.Leftover leftover : cleanup.leftovers()) {
                lang.send(source, leftover.key(), Lang.placeholders(leftover.placeholders()));
            }
        }
        if (result.error() != null) {
            lang.send(source, "plugin.error.detail",
                    ph("type", result.error().getClass().getSimpleName()),
                    ph("message", PluginLoadService.rootMessage(result.error())));
            lang.send(source, "plugin.error.see-log");
        }
        return result.success() ? Command.SINGLE_SUCCESS : 0;
    }

    private CompletableFuture<Suggestions> suggestJars(CommandContext<CommandSource> ctx, SuggestionsBuilder builder) {
        return suggest(builder, plugins.jarFileNames());
    }

    private CompletableFuture<Suggestions> suggestPluginIds(CommandContext<CommandSource> ctx, SuggestionsBuilder builder) {
        return suggest(builder, plugins.unmanagedIds());
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
                .append(hoverField("plugin.inspect.field.id", info.id()))
                .append(Component.newline())
                .append(hoverField("plugin.inspect.field.authors", listOrNone(info.authors())))
                .append(Component.newline())
                .append(hoverField("plugin.inspect.field.description", valueOrNone(info.description())))
                .append(Component.newline())
                .append(hoverField("plugin.inspect.field.url", valueOrNone(info.url())))
                .append(Component.newline())
                .append(hoverField("plugin.inspect.field.required-dependencies",
                        listOrNone(info.requiredDependencies())))
                .append(Component.newline())
                .append(hoverField("plugin.inspect.field.optional-dependencies",
                        listOrNone(info.optionalDependencies())))
                .append(Component.newline())
                .append(hoverField("plugin.inspect.field.provided-ids", listOrNone(info.providedIds())))
                .append(Component.newline())
                .append(Component.newline())
                .append(lang.get("plugin.list.hover"));
        return hover.build();
    }

    private void runtimeLine(CommandSource source, PluginInspection inspection) {
        lang.send(source, Component.text()
                .append(Component.text("  "))
                .append(labelValue("plugin.inspect.field.commands", inspection.commands()))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(labelValue("plugin.inspect.field.tasks", inspection.tasks()))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(labelValue("plugin.inspect.field.listeners", inspection.listeners()))
                .append(Component.text("  |  ", NamedTextColor.DARK_GRAY))
                .append(labelValue("plugin.inspect.field.channels", inspection.channels()))
                .build());
        fieldLine(source, "plugin.inspect.field.executor", yesNo(inspection.executorActive()),
                inspection.executorActive() ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
    }

    private static TextColor riskColor(PluginInspection.Risk risk) {
        return switch (risk) {
            case LOW -> NamedTextColor.GREEN;
            case MEDIUM -> NamedTextColor.YELLOW;
            case HIGH -> NamedTextColor.GOLD;
            case BLOCKED -> NamedTextColor.RED;
        };
    }

}
