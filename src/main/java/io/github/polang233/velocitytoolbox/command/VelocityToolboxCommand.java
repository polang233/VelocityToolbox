package io.github.polang233.velocitytoolbox.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.polang233.velocitytoolbox.VelocityToolboxPlugin;
import io.github.polang233.velocitytoolbox.plugins.PluginLoadService;
import io.github.polang233.velocitytoolbox.lang.Lang;
import io.github.polang233.velocitytoolbox.pack.PackService;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

import static io.github.polang233.velocitytoolbox.lang.Lang.ph;

public final class VelocityToolboxCommand extends CommandView {
    private final VelocityToolboxPlugin plugin;
    private final PluginLoadService plugins;
    private final PackService packService;
    private final io.github.polang233.velocitytoolbox.pack.PackSender sender;

    public VelocityToolboxCommand(VelocityToolboxPlugin plugin, ProxyServer proxy,
                                  PluginLoadService plugins, PackService packService, Lang lang,
                                  io.github.polang233.velocitytoolbox.pack.PackSender sender) {
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
        lang.send(source, "command.help.title");
        helpLineIfAllowed(source, INFO_PERMISSION, "/vtb info", "command.help.info");
        helpLineIfAllowed(source, RELOAD_PERMISSION, "/vtb reload", "command.help.reload");
        for (String module : List.of("plugin", "pack", "server"))
            if (module(source, module)) helpLine(source, "/vtb " + module, "modules." + module);
        return 1;
    }

    private int info(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        lang.send(source, "command.info.title");
        fieldLine(source, "command.field.toolbox-version", plugin.version(), NamedTextColor.GREEN);
        fieldLine(source, "command.field.proxy-version", proxy.getVersion().getVersion(), Lang.BODY);
        fieldLine(source, "command.field.java-version", System.getProperty("java.version"), Lang.BODY);
        fieldLine(source, "command.field.loaded-plugins", plugins.loadedIds().size(), Lang.BODY);
        lang.send(source, plugin.serverVersionStatus());
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
        lang.send(source, sender.rules().enabled() ? "delivery.enabled" : "delivery.disabled");
        return Command.SINGLE_SUCCESS;
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

}
