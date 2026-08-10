package io.github.velocitytoolbox;

import io.github.velocitytoolbox.api.ToolboxContext;
import io.github.velocitytoolbox.api.ToolboxModule;
import org.slf4j.Logger;

import com.velocitypowered.api.proxy.ProxyServer;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ModuleManager implements AutoCloseable {

    private static final Pattern MODULE_ID = Pattern.compile("[a-z][a-z0-9-_]{0,63}");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Path moduleDirectory;
    private final Map<String, LoadedModule> loaded = new LinkedHashMap<>();

    ModuleManager(ProxyServer proxy, Logger logger, Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.moduleDirectory = dataDirectory.resolve("modules");
    }

    void start() throws IOException {
        Files.createDirectories(moduleDirectory);
        loadAll();
    }

    void reloadConfiguration() {
        logger.info("VelocityToolbox configuration reload requested. "
                + "Use /vtoolbox module reload <id> for code reload.");
    }

    int loadedCount() {
        return loaded.size();
    }

    List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        if (loaded.isEmpty()) {
            lines.add("No modules loaded.");
            return lines;
        }

        loaded.values().forEach(module ->
                lines.add(module.module().id() + " -> " + module.jar().getFileName()));
        return lines;
    }

    Path moduleDirectory() {
        return moduleDirectory;
    }

    void loadAll() {
        try (Stream<Path> paths = Files.list(moduleDirectory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(this::load);
        } catch (IOException exception) {
            logger.error("Unable to scan module directory {}.", moduleDirectory, exception);
        }
    }

    boolean loadByFileName(String fileName) {
        Path candidate = moduleDirectory.resolve(fileName).normalize();
        if (!candidate.startsWith(moduleDirectory) || !candidate.getFileName().toString().endsWith(".jar")) {
            return false;
        }
        return load(candidate);
    }

    boolean load(Path jar) {
        if (!Files.isRegularFile(jar)) {
            logger.warn("Module JAR does not exist: {}", jar);
            return false;
        }

        try {
            URLClassLoader classLoader = new URLClassLoader(
                    new URL[]{jar.toUri().toURL()},
                    VelocityToolboxPlugin.class.getClassLoader()
            );
            ServiceLoader<ToolboxModule> services = ServiceLoader.load(ToolboxModule.class, classLoader);

            var iterator = services.iterator();
            if (!iterator.hasNext()) {
                classLoader.close();
                logger.warn("Skipping {}: no ToolboxModule service was found.", jar.getFileName());
                return false;
            }

            ToolboxModule module = iterator.next();
            if (!MODULE_ID.matcher(module.id()).matches()) {
                classLoader.close();
                logger.warn("Skipping {}: invalid module id '{}'.", jar.getFileName(), module.id());
                return false;
            }
            if (loaded.containsKey(module.id())) {
                classLoader.close();
                logger.warn("Skipping {}: module id '{}' is already loaded.", jar.getFileName(), module.id());
                return false;
            }

            RegistrationScopeImpl scope = new RegistrationScopeImpl(proxy, module);
            Path moduleData = dataDirectory.resolve(module.id());
            Files.createDirectories(moduleData);
            ToolboxContext context = new ModuleContextImpl(proxy, logger, moduleData, scope);
            try {
                module.enable(context);
            } catch (Exception exception) {
                scope.close();
                classLoader.close();
                throw exception;
            }

            loaded.put(module.id(), new LoadedModule(jar, classLoader, module, scope));
            logger.info("Loaded module '{}' from {}.", module.id(), jar.getFileName());
            return true;
        } catch (Exception exception) {
            logger.error("Unable to load module {}.", jar.getFileName(), exception);
            return false;
        }
    }

    boolean unload(String id) {
        LoadedModule module = loaded.remove(id);
        if (module == null) {
            return false;
        }

        try {
            module.module().disable();
        } catch (Exception exception) {
            logger.error("Module '{}' threw while disabling.", id, exception);
        } finally {
            module.scope().close();
            try {
                module.classLoader().close();
            } catch (IOException exception) {
                logger.warn("Unable to close classloader for module '{}'.", id, exception);
            }
        }

        logger.info("Unloaded module '{}'.", id);
        return true;
    }

    boolean reload(String id) {
        LoadedModule current = loaded.get(id);
        if (current == null) {
            return false;
        }

        Path jar = current.jar();
        unload(id);
        return load(jar);
    }

    @Override
    public void close() {
        for (String id : List.copyOf(loaded.keySet())) {
            unload(id);
        }
    }

    private record LoadedModule(
            Path jar,
            URLClassLoader classLoader,
            ToolboxModule module,
            RegistrationScopeImpl scope
    ) {
    }
}
