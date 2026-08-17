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
import io.github.polang233.velocitytoolbox.VelocityToolboxPlugin;
import io.github.polang233.velocitytoolbox.hotload.PluginLoadService;
import io.github.polang233.velocitytoolbox.pack.HostedPack;
import io.github.polang233.velocitytoolbox.pack.PackService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

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

    public VelocityToolboxCommand(
            VelocityToolboxPlugin plugin,
            ProxyServer proxy,
            PluginLoadService plugins,
            PackService packService
    ) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.plugins = plugins;
        this.packService = packService;
    }

    public BrigadierCommand build() {
        LiteralArgumentBuilder<CommandSource> root = BrigadierCommand.literalArgumentBuilder("vtoolbox")
                .requires(source -> source.hasPermission(PERMISSION))
                .executes(this::help);

        root.then(BrigadierCommand.literalArgumentBuilder("help").executes(this::help));
        root.then(BrigadierCommand.literalArgumentBuilder("version").executes(this::version));
        root.then(BrigadierCommand.literalArgumentBuilder("status").executes(this::status));
        root.then(BrigadierCommand.literalArgumentBuilder("packs").executes(this::packs));
        root.then(BrigadierCommand.literalArgumentBuilder("reload").executes(this::reloadPacks));
        root.then(pluginNode("plugin"));
        root.then(pluginNode("plugins"));

        return new BrigadierCommand(root);
    }

    private LiteralArgumentBuilder<CommandSource> pluginNode(String name) {
        return BrigadierCommand.literalArgumentBuilder(name)
                .executes(this::pluginList)
                .then(BrigadierCommand.literalArgumentBuilder("list").executes(this::pluginList))
                .then(BrigadierCommand.literalArgumentBuilder("load")
                        .executes(ctx -> usage(ctx, "用法: /vtoolbox plugin load <file.jar>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("file", StringArgumentType.greedyString())
                                .suggests(this::suggestJars)
                                .executes(this::pluginLoad)))
                .then(BrigadierCommand.literalArgumentBuilder("unload")
                        .executes(ctx -> usage(ctx, "用法: /vtoolbox plugin unload <plugin-id>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .suggests(this::suggestPluginIds)
                                .executes(this::pluginUnload)))
                .then(BrigadierCommand.literalArgumentBuilder("reload")
                        .executes(ctx -> usage(ctx, "用法: /vtoolbox plugin reload <plugin-id>"))
                        .then(BrigadierCommand.requiredArgumentBuilder("id", StringArgumentType.word())
                                .suggests(this::suggestPluginIds)
                                .executes(this::pluginReload)));
    }

    private int help(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        info(source, "VelocityToolbox 命令:");
        info(source, "  /vtoolbox version");
        info(source, "  /vtoolbox status");
        info(source, "  /vtoolbox packs");
        info(source, "  /vtoolbox reload");
        info(source, "  /vtoolbox plugin list");
        info(source, "  /vtoolbox plugin load <file.jar>");
        info(source, "  /vtoolbox plugin unload <plugin-id>");
        info(source, "  /vtoolbox plugin reload <plugin-id>");
        return Command.SINGLE_SUCCESS;
    }

    private int version(CommandContext<CommandSource> ctx) {
        info(ctx.getSource(), "VelocityToolbox " + plugin.version()
                + " | 插件数=" + plugins.loadedIds().size()
                + " | 资源包托管=" + (packService.enabled() ? "开" : "关"));
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        info(source, "代理 " + proxy.getVersion().getVersion()
                + " | Java " + System.getProperty("java.version")
                + " | 插件数=" + plugins.loadedIds().size()
                + " | 资源包托管=" + (packService.enabled() ? "开" : "关"));
        if (packService.enabled()) {
            info(source, "下载来源 " + packService.publicOrigin());
            info(source, "资源包目录 " + packService.packsDirectory());
        }
        return pluginList(ctx);
    }

    private int packs(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        if (!packService.enabled()) {
            warn(source, "资源包托管未启用。");
            return 0;
        }
        List<HostedPack> hosted = packService.packs();
        if (hosted.isEmpty()) {
            warn(source, "目录里没有 zip: " + packService.packsDirectory());
            return Command.SINGLE_SUCCESS;
        }
        for (HostedPack pack : hosted) {
            info(source, pack.fileName());
            info(source, "  " + pack.url());
            info(source, "  sha1 " + pack.sha1());
        }
        return Command.SINGLE_SUCCESS;
    }

    private int reloadPacks(CommandContext<CommandSource> ctx) {
        boolean ok = plugin.reloadPacks();
        if (ok) {
            ok(ctx.getSource(), "已重载资源包托管。");
            return Command.SINGLE_SUCCESS;
        }
        error(ctx.getSource(), "资源包托管重载失败，请看代理日志。");
        return 0;
    }

    private int pluginList(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        info(source, "已加载插件:");
        for (String line : plugins.statusLines()) {
            info(source, " - " + line);
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
        if (result.success()) {
            ok(ctx.getSource(), result.message());
            return Command.SINGLE_SUCCESS;
        }
        error(ctx.getSource(), result.message());
        return 0;
    }

    private int usage(CommandContext<CommandSource> ctx, String message) {
        error(ctx.getSource(), message);
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

    private static void info(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    private static void ok(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void warn(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.YELLOW));
    }

    private static void error(CommandSource source, String message) {
        source.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
