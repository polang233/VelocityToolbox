package io.github.polang233.velocitytoolbox.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.polang233.velocitytoolbox.VelocityToolboxPlugin;
import io.github.polang233.velocitytoolbox.lang.Lang;
import io.github.polang233.velocitytoolbox.pack.delivery.PackSender;
import io.github.polang233.velocitytoolbox.pack.host.PackService;
import io.github.polang233.velocitytoolbox.plugins.PluginLoadService;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

import static io.github.polang233.velocitytoolbox.lang.Lang.ph;

public final class VelocityToolboxCommand extends CommandView {
    private final VelocityToolboxPlugin plugin;
    private final PluginLoadService plugins;
    private final PackService packService;
    private final PackSender sender;

    public VelocityToolboxCommand(VelocityToolboxPlugin plugin, ProxyServer proxy,
                                  PluginLoadService plugins, PackService packService, Lang lang,
                                  PackSender sender) {
        super(proxy, lang);
        this.plugin = plugin;
        this.plugins = plugins;
        this.packService = packService;
        this.sender = sender;
    }

    public BrigadierCommand build() {
        var root = literal("vtoolbox")
                .requires(this::hasBasePermission).executes(this::help);
        root.then(literal("help").executes(this::help));
        root.then(literal("info")
                .requires(source -> hasCommandPermission(source, INFO_PERMISSION)).executes(this::info));
        root.then(literal("reload")
                .requires(source -> hasCommandPermission(source, RELOAD_PERMISSION)).executes(this::reloadAll));
        root.then(new PluginCommand(proxy, lang, plugins).build());
        root.then(new PackCommand(proxy, lang, packService, sender).build());
        root.then(new ServerCommand(proxy, lang).build());
        return new BrigadierCommand(root);
    }

    private int help(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        lang.send(source, "main.help.title");
        sectionLine(source, "main.info.section");
        helpLineIfAllowed(source, INFO_PERMISSION, "/vtb info", "main.help.info");
        helpLineIfAllowed(source, RELOAD_PERMISSION, "/vtb reload", "main.help.reload");
        for (String module : List.of("plugin", "server", "pack"))
            if (module(source, module)) {
                sectionLine(source, module + ".title");
                helpLine(source, "/vtb " + module, module + ".help.summary");
            }
        return 1;
    }

    private int info(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        lang.send(source, "main.info.title");
        sectionLine(source, "main.info.section");
        fieldLine(source, "main.info.field.toolbox-version", plugin.version(), NamedTextColor.GREEN);
        fieldLine(source, "main.info.field.proxy-version", proxy.getVersion().getVersion(), Lang.BODY);
        fieldLine(source, "main.info.field.java-version", System.getProperty("java.version"), Lang.BODY);
        plugin.showStatus(source, true);
        return Command.SINGLE_SUCCESS;
    }

    private int reloadAll(CommandContext<CommandSource> ctx) {
        boolean ok = plugin.reloadAll();
        if (ctx.getSource() != proxy.getConsoleCommandSource())
            lang.sendRegenerationNotice(ctx.getSource());
        lang.send(ctx.getSource(), ok ? "main.reload.ok" : "main.reload.fail");
        if (ok) {
            lang.send(proxy.getConsoleCommandSource(), "main.log.reloaded",
                    ph("source", sourceName(ctx.getSource())));
        }
        plugin.showStatus(proxy.getConsoleCommandSource(), false);
        return ok ? Command.SINGLE_SUCCESS : 0;
    }

}
