package io.github.velocitytoolbox;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "velocitytoolbox",
        name = "VelocityToolbox",
        version = "0.1.0-SNAPSHOT",
        description = "A public-API-first toolbox and reloadable module host for Velocity.",
        authors = {"VelocityToolbox contributors"}
)
public final class VelocityToolboxPlugin {

    public static final String VERSION = "0.1.0-SNAPSHOT";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private ModuleManager moduleManager;
    private CommandMeta commandMeta;

    @Inject
    public VelocityToolboxPlugin(
            ProxyServer proxy,
            Logger logger,
            @DataDirectory Path dataDirectory
    ) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        try {
            moduleManager = new ModuleManager(proxy, logger, dataDirectory);
            moduleManager.start();

            ToolboxCommand command = new ToolboxCommand(proxy, moduleManager);
            commandMeta = proxy.getCommandManager()
                    .metaBuilder("vtoolbox")
                    .aliases("vtb")
                    .plugin(this)
                    .build();
            proxy.getCommandManager().register(commandMeta, command);

            logger.info("VelocityToolbox {} initialized with {} module(s).",
                    VERSION, moduleManager.loadedCount());
        } catch (Exception exception) {
            logger.error("VelocityToolbox failed to initialize.", exception);
        }
    }

    @Subscribe
    public void onProxyReload(ProxyReloadEvent event) {
        if (moduleManager != null) {
            moduleManager.reloadConfiguration();
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (commandMeta != null) {
            proxy.getCommandManager().unregister(commandMeta);
        }
        if (moduleManager != null) {
            moduleManager.close();
        }
    }
}
