package io.github.polang233.velocitytoolbox;

import com.google.inject.Inject;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.polang233.velocitytoolbox.command.VelocityToolboxCommand;
import io.github.polang233.velocitytoolbox.config.PluginConfig;
import io.github.polang233.velocitytoolbox.plugins.PluginLoadService;
import io.github.polang233.velocitytoolbox.lang.Lang;
import io.github.polang233.velocitytoolbox.metrics.Metrics;
import io.github.polang233.velocitytoolbox.pack.PackService;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * 主类。Velocity 通过 Guice 构造本类；代理生命周期事件挂在这里。
 *
 * <p>插件元数据来自 {@code build.gradle}，由 {@link BuildConstants} 填进 {@code @Plugin}，
 * 再经注解处理器写成 {@code velocity-plugin.json}。运行时版本从 {@link PluginContainer} 读取。</p>
 */
@Plugin(
        id = BuildConstants.ID,
        name = BuildConstants.NAME,
        version = BuildConstants.VERSION,
        description = BuildConstants.DESCRIPTION,
        url = BuildConstants.URL,
        authors = {BuildConstants.AUTHOR}
)
public final class VelocityToolboxPlugin {

    private static final int BSTATS_ID = 33451;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final PluginContainer container;
    private final Metrics.Factory metricsFactory;
    private final Lang lang;

    private PackService packService;
    private PluginLoadService pluginLoadService;
    private CommandMeta commandMeta;

    @Inject
    public VelocityToolboxPlugin(
            ProxyServer proxy,
            Logger logger,
            @DataDirectory Path dataDirectory,
            PluginContainer container,
            Metrics.Factory metricsFactory
    ) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.container = container;
        this.metricsFactory = metricsFactory;
        this.lang = new Lang(dataDirectory);
    }

    public String version() {
        return container.getDescription().getVersion().orElse("unknown");
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        PluginConfig config = loadConfigAndLang();
        metricsFactory.make(this, BSTATS_ID);

        packService = new PackService(dataDirectory, proxy.getConsoleCommandSource(), lang);
        try {
            if (config != null) {
                packService.start(config.packHost());
            } else {
                packService.start();
            }
        } catch (Exception exception) {
            logger.error(lang.plain("log.pack-fail"), exception);
        }

        pluginLoadService = new PluginLoadService(proxy, logger, lang, dataDirectory);

        VelocityToolboxCommand toolboxCommand = new VelocityToolboxCommand(
                this, proxy, pluginLoadService, packService, lang);
        BrigadierCommand command = toolboxCommand.build();
        commandMeta = proxy.getCommandManager()
                .metaBuilder(command)
                .aliases("vtb")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(commandMeta, command);

        lang.send(proxy.getConsoleCommandSource(), "log.console.started",
                Lang.ph("version", version()));
        lang.send(proxy.getConsoleCommandSource(), packService.enabled()
                ? "log.console.pack-host-enabled"
                : "log.console.pack-host-disabled");
        lang.send(proxy.getConsoleCommandSource(), "log.console.plugins-dir",
                Lang.ph("dir", pluginLoadService.pluginsDirectory()));
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        reloadAll();
    }

    /**
     * {@code /vtoolbox reload} 与代理 {@code /velocity reload} 都会走到这里：
     * 重载语言、配置和资源包托管，不重载其它插件。
     */
    public boolean reloadAll() {
        try {
            PluginConfig config = PluginConfig.load(dataDirectory);
            lang.load(config.language());
            packService.reload(config.packHost());
            return true;
        } catch (Exception exception) {
            logger.error(lang.plain("log.reload-fail"), exception);
            return false;
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (commandMeta != null) {
            proxy.getCommandManager().unregister(commandMeta);
            commandMeta = null;
        }
        if (packService != null) {
            packService.close();
        }
    }

    private PluginConfig loadConfigAndLang() {
        try {
            PluginConfig config = PluginConfig.load(dataDirectory);
            lang.load(config.language());
            return config;
        } catch (Exception exception) {
            logger.error("无法加载配置或语言文件。", exception);
            try {
                lang.load("zh_cn");
            } catch (Exception ignored) {
                // 随包 zh_cn.yml 损坏时命令仍会回退显示 key。
            }
            return null;
        }
    }
}
