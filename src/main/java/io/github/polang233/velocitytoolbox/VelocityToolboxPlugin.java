package io.github.polang233.velocitytoolbox;

import com.google.inject.Inject;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.polang233.velocitytoolbox.command.VelocityToolboxCommand;
import io.github.polang233.velocitytoolbox.command.ModuleStatus;
import io.github.polang233.velocitytoolbox.config.PluginConfig;
import io.github.polang233.velocitytoolbox.config.ResourceFiles;
import io.github.polang233.velocitytoolbox.lang.Lang;
import io.github.polang233.velocitytoolbox.hook.Metrics;
import io.github.polang233.velocitytoolbox.pack.config.PackConfig;
import io.github.polang233.velocitytoolbox.pack.config.PackRules;
import io.github.polang233.velocitytoolbox.pack.delivery.PackSender;
import io.github.polang233.velocitytoolbox.pack.host.PackService;
import io.github.polang233.velocitytoolbox.plugins.PluginLoadService;
import io.github.polang233.velocitytoolbox.version.ServerVersionService;
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
    private PackSender packSender;
    private PluginLoadService pluginLoadService;
    private ServerVersionService serverVersionService;
    private CommandMeta commandMeta;
    private ModuleStatus moduleStatus;
    private volatile boolean packError;

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
        packSender = new PackSender(this, proxy, packService, lang, logger);
        try {
            reloadPacks(config != null ? config.packHost() : PackConfig.load(dataDirectory));
        } catch (Exception exception) {
            packError = true;
            logger.error(lang.plain("pack.log.load-failed"), exception);
        }

        pluginLoadService = new PluginLoadService(proxy, logger, lang, dataDirectory);
        serverVersionService = new ServerVersionService(this, proxy, dataDirectory, lang, logger);
        serverVersionService.start();
        moduleStatus = new ModuleStatus(proxy, lang, pluginLoadService, packService, packSender);

        VelocityToolboxCommand toolboxCommand = new VelocityToolboxCommand(
                this, proxy, pluginLoadService, packService, lang, packSender);
        BrigadierCommand command = toolboxCommand.build();
        commandMeta = proxy.getCommandManager()
                .metaBuilder(command)
                .aliases("vtb")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(commandMeta, command);

        lang.send(proxy.getConsoleCommandSource(), "main.log.started",
                Lang.ph("version", version()));
        showStatus(proxy.getConsoleCommandSource(), false);
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        reloadAll();
        showStatus(proxy.getConsoleCommandSource(), false);
    }

    /**
     * {@code /vtoolbox reload} 与代理 {@code /velocity reload} 都会走到这里：
     * 重载语言、配置、资源包托管和子服版本限制，不重载其它插件。
     */
    public synchronized boolean reloadAll() {
        boolean success = true;
        try {
            PluginConfig config = PluginConfig.load(dataDirectory);
            lang.load(config.language());
            lang.sendRegenerationNotice(proxy.getConsoleCommandSource());
            reloadPacks(config.packHost());
            packError = false;
        } catch (Exception exception) {
            packError = true;
            logger.error(lang.plain("main.log.reload-failed"), exception);
            success = false;
        }
        // 各模块分别重载，单个模块失败不阻断其它模块。
        try {
            serverVersionService.reload();
        } catch (Exception exception) {
            logger.error(lang.plain("server.versions.log.reload-failed"), exception);
            success = false;
        }
        return success;
    }

    public void showStatus(CommandSource source, boolean details) {
        moduleStatus.show(source, serverVersionService.status(details), packError, details);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (commandMeta != null) {
            proxy.getCommandManager().unregister(commandMeta);
            commandMeta = null;
        }
        if (packSender != null) packSender.close();
        if (packService != null) {
            packService.close();
        }
        if (serverVersionService != null) {
            serverVersionService.close();
        }
    }

    private void reloadPacks(PackConfig config) throws java.io.IOException {
        PackService.Prepared prepared = packService.prepare(config);
        PackRules rules = PackRules.read(ResourceFiles.loadYaml(dataDirectory.resolve("config.yml"))
                .node("resource-packs"), prepared.list(), prepared.directory(), config.enabled());
        packService.apply(prepared);
        packSender.apply(rules);
    }

    private PluginConfig loadConfigAndLang() {
        try {
            PluginConfig config = PluginConfig.load(dataDirectory);
            lang.load(config.language());
            lang.sendRegenerationNotice(proxy.getConsoleCommandSource());
            return config;
        } catch (Exception exception) {
            logger.error("Configuration or language initialization failed. / 配置或语言初始化失败。", exception);
            try {
                lang.load("zh_cn");
            } catch (Exception ignored) {
                // 随包 zh_cn.yml 损坏时命令仍会回退显示 key。
            }
            return null;
        }
    }
}
