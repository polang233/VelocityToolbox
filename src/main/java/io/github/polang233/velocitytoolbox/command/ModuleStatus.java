package io.github.polang233.velocitytoolbox.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.polang233.velocitytoolbox.lang.Lang;
import io.github.polang233.velocitytoolbox.pack.delivery.PackSender;
import io.github.polang233.velocitytoolbox.pack.host.PackService;
import io.github.polang233.velocitytoolbox.plugins.PluginLoadService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** info、启动和重载共用模块状态；每级缩进两个空格。 */
public final class ModuleStatus {
    private final ProxyServer proxy;
    private final Lang lang;
    private final PluginLoadService plugins;
    private final PackService host;
    private final PackSender sender;

    public ModuleStatus(ProxyServer proxy, Lang lang, PluginLoadService plugins, PackService host, PackSender sender) {
        this.proxy = proxy;
        this.lang = lang;
        this.plugins = plugins;
        this.host = host;
        this.sender = sender;
    }

    public void show(CommandSource source, Component versions, boolean packError, boolean details) {
        line(source, 0, "plugin.title");
        line(source, 1, "plugin.info.count", Lang.ph("count", plugins.loadedIds().size()));
        if (details) line(source, 1, "plugin.info.directory", Lang.ph("path", plugins.pluginsDirectory()));
        line(source, 0, "server.title");
        line(source, 1, "server.info.players", Lang.ph("count", proxy.getAllPlayers().size()));
        lang.send(source, Component.text("  ").append(versions));
        line(source, 0, "pack.title");
        if (packError) line(source, 1, "pack.info.load-failed");
        line(source, 1, "pack.host.status", Lang.ph("state", state(host.enabled())));
        if (host.enabled()) {
            line(source, 2, "pack.host.count", Lang.ph("count", host.packs().size()));
            line(source, 2, "pack.host.url", Lang.ph("url", host.publicOrigin()));
            if (details) line(source, 2, "pack.host.directory", Lang.ph("path", host.packsDirectory()));
        }
        line(source, 1, "pack.delivery.status", Lang.ph("state", state(sender.rules().enabled())));
        if (sender.rules().enabled())
            line(source, 2, "pack.info.count", Lang.ph("count", sender.rules().packs().size()));
    }

    private String state(boolean enabled) { return lang.plain(enabled ? "common.enabled" : "common.disabled"); }

    private void line(CommandSource source, int depth, String key, TagResolver... values) {
        lang.send(source, Component.text("  ".repeat(depth)).append(lang.get(key, values)));
    }
}
