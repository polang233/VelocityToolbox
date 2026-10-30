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
        metricsFactory.make(this, BSTATS_ID);
        packService = new PackService(dataDirectory, proxy.getConsoleCommandSource(), lang);
        packSender = new PackSender(this, proxy, packService, lang, logger);
        reloadConfig();

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
        boolean success = reloadConfig();
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

    /** 读取一次 config.yml，依次应用语言与资源包；失败时保留已生效的部分。 */
    private boolean reloadConfig() {
        try {
            PluginConfig config = PluginConfig.load(dataDirectory);
            lang.load(config.language());
            lang.sendRegenerationNotice(proxy.getConsoleCommandSource());
            PackConfig host = config.packHost();
            for (String warning : host.warnings()) logger.warn(warning);
            PackService.Prepared prepared = packService.prepare(host);
            PackRules rules = PackRules.read(config.resourcePacks(), prepared.list(), prepared.directory(),
                    host.enabled(), logger::warn);
            packService.apply(prepared);
            packSender.apply(rules);
            packError = false;
            return true;
        } catch (Exception exception) {
            packError = true;
            // 首次加载失败时语言尚未就绪，回退随包简体中文。
            if (!lang.loaded()) {
                try {
                    lang.load("zh_cn");
                } catch (Exception ignored) {
                    // 随包 zh_cn.yml 损坏时命令仍会回退显示 key。
                }
            }
            logger.error(lang.plain("main.log.reload-failed"), exception);
            return false;
        }
    }
}
