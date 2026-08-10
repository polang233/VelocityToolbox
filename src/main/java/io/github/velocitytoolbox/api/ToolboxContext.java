package io.github.velocitytoolbox.api;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

public interface ToolboxContext {

    ProxyServer proxy();

    Logger logger();

    Path dataDirectory();

    RegistrationScope registrations();
}
