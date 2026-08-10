package io.github.velocitytoolbox;

import io.github.velocitytoolbox.api.RegistrationScope;
import io.github.velocitytoolbox.api.ToolboxContext;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

final class ModuleContextImpl implements ToolboxContext {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final RegistrationScope registrations;

    ModuleContextImpl(
            ProxyServer proxy,
            Logger logger,
            Path dataDirectory,
            RegistrationScope registrations
    ) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.registrations = registrations;
    }

    @Override
    public ProxyServer proxy() {
        return proxy;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public RegistrationScope registrations() {
        return registrations;
    }
}
