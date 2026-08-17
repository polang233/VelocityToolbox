package io.github.velocitytoolbox;

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
import io.github.velocitytoolbox.command.VelocityToolboxCommand;
import io.github.velocitytoolbox.hotload.PluginLoadService;
import io.github.velocitytoolbox.pack.PackService;
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

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final PluginContainer container;

    private PackService packService;
    private PluginLoadService pluginLoadService;
    private CommandMeta commandMeta;

    @Inject
    public VelocityToolboxPlugin(
            ProxyServer proxy,
            Logger logger,
            @DataDirectory Path dataDirectory,
            PluginContainer container
    ) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.container = container;
    }

    public String version() {
        return container.getDescription().getVersion().orElse("unknown");
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        packService = new PackService(logger, dataDirectory);
        try {
            packService.start();
        } catch (Exception exception) {
            logger.error("资源包托管启动失败。插件热加载命令仍可使用。", exception);
        }

        pluginLoadService = new PluginLoadService(proxy, logger, dataDirectory);

        VelocityToolboxCommand toolboxCommand = new VelocityToolboxCommand(this, proxy, pluginLoadService, packService);
        BrigadierCommand command = toolboxCommand.build();
        commandMeta = proxy.getCommandManager()
                .metaBuilder(command)
                .aliases("vtb")
                .plugin(this)
                .build();
        proxy.getCommandManager().register(commandMeta, command);

        logger.info("VelocityToolbox {} 已启动。资源包托管 {}，插件目录 {}。",
                version(),
                packService.enabled() ? "开" : "关",
                pluginLoadService.pluginsDirectory());
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        reloadPacks();
    }

    /**
     * {@code /vtoolbox reload} 与代理 {@code /velocity reload} 都会走到这里，只重载资源包托管。
     */
    public boolean reloadPacks() {
        if (packService == null) {
            return false;
        }
        try {
            packService.reload();
            return true;
        } catch (Exception exception) {
            logger.error("无法重载资源包托管。", exception);
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
}
