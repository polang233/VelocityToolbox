package io.github.polang233.velocitytoolbox.plugins;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.name.Names;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.plugin.meta.PluginDependency;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.polang233.velocitytoolbox.lang.Lang;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 对 {@code plugins/*.jar} 里的真实 Velocity 插件做 load / unload / reload。
 *
 * <p>加载顺序与 4.0 以上代理启动时一致：{@code loadCandidate} → {@code createPluginFromCandidate}
 * → Guice {@code createPlugin} → {@code registerPlugin} → {@code registerInternally}
 * → 只对该插件触发 {@code ProxyInitializeEvent}。</p>
 */
public final class PluginLoadService {

    private static final Set<String> PROTECTED_IDS = Set.of("velocity", "velocitytoolbox");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Lang lang;
    private final Path pluginsDirectory;
    private final PluginCleanup cleanup;

    public PluginLoadService(ProxyServer proxy, Logger logger, Lang lang, Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.lang = lang;
        this.pluginsDirectory = dataDirectory.toAbsolutePath().normalize().getParent();
        this.cleanup = new PluginCleanup(proxy, logger, lang);
    }

    public Path pluginsDirectory() {
        return pluginsDirectory;
    }

    public List<String> loadedIds() {
        return proxy.getPluginManager().getPlugins().stream()
                .map(container -> container.getDescription().getId())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public List<String> unmanagedIds() {
        return loadedIds().stream()
                .filter(id -> !PROTECTED_IDS.contains(id.toLowerCase(Locale.ROOT)))
                .toList();
    }

    public List<PluginInfo> pluginInfos() {
        List<PluginInfo> infos = new ArrayList<>();
        for (PluginContainer container : proxy.getPluginManager().getPlugins()) {
            PluginDescription description = container.getDescription();
            List<String> required = dependencies(description, false);
            List<String> optional = dependencies(description, true);
            infos.add(new PluginInfo(
                    description.getId(),
                    description.getName().orElse(description.getId()),
                    description.getVersion().orElse("?"),
                    description.getAuthors(),
                    description.getDescription().orElse(""),
                    description.getUrl().orElse(""),
                    required,
                    optional,
                    description.getProvidedIds().stream()
                            .sorted(String.CASE_INSENSITIVE_ORDER)
                            .toList()));
        }
        infos.sort((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.id(), right.id()));
        return infos;
    }

    public List<String> jarFileNames() {
        if (!Files.isDirectory(pluginsDirectory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(pluginsDirectory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException exception) {
            logger.warn(lang.plain("log.list-jars", Lang.ph("dir", pluginsDirectory)), exception);
            return List.of();
        }
    }

    public synchronized PluginInspection inspect(String id) {
        String requested = id == null ? "" : id.trim();
        Optional<PluginContainer> optional = proxy.getPluginManager().getPlugin(requested);
        if (optional.isEmpty()) {
            return PluginInspection.notFound(requested);
        }

        PluginContainer container = optional.get();
        PluginDescription description = container.getDescription();
        Object instance = container.getInstance().orElse(null);
        PluginCleanup.RuntimeInventory inventory = cleanup.inspect(container, instance);
        List<String> dependents = dependentsOf(container);
        List<String> requiredDependencies = dependencies(description, false);
        List<String> optionalDependencies = dependencies(description, true);
        List<String> providedIds = description.getProvidedIds().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        Path source = description.getSource().orElse(null);
        boolean sourceAvailable = source != null && Files.isRegularFile(source);

        List<PluginInspection.Issue> issues = new ArrayList<>();
        PluginInspection.Risk risk = PluginInspection.Risk.LOW;
        if (isProtected(description.getId())) {
            issues.add(PluginInspection.Issue.PROTECTED);
            risk = PluginInspection.Risk.BLOCKED;
        }
        if (!dependents.isEmpty()) {
            issues.add(PluginInspection.Issue.REQUIRED_BY_OTHERS);
            risk = PluginInspection.Risk.BLOCKED;
        }
        if (!sourceAvailable) {
            issues.add(PluginInspection.Issue.NO_SOURCE_JAR);
            if (risk != PluginInspection.Risk.BLOCKED) {
                risk = PluginInspection.Risk.HIGH;
            }
        }
        if (instance == null) {
            issues.add(PluginInspection.Issue.NO_INSTANCE);
            if (risk != PluginInspection.Risk.BLOCKED) {
                risk = PluginInspection.Risk.HIGH;
            }
        }
        if (!providedIds.isEmpty()) {
            issues.add(PluginInspection.Issue.PROVIDED_IDS);
            if (risk == PluginInspection.Risk.LOW) {
                risk = PluginInspection.Risk.MEDIUM;
            }
        }
        if (inventory.channels() > 0) {
            issues.add(PluginInspection.Issue.CUSTOM_CHANNELS);
            if (risk == PluginInspection.Risk.LOW) {
                risk = PluginInspection.Risk.MEDIUM;
            }
        }
        if (inventory.executorActive()) {
            issues.add(PluginInspection.Issue.EXECUTOR);
            if (risk == PluginInspection.Risk.LOW) {
                risk = PluginInspection.Risk.MEDIUM;
            }
        }
        if (issues.isEmpty()) {
            issues.add(PluginInspection.Issue.STANDARD_CLEANUP_ONLY);
        }

        return new PluginInspection(
                true,
                description.getId(),
                description.getName().orElse(description.getId()),
                description.getVersion().orElse("?"),
                description.getAuthors(),
                description.getDescription().orElse(""),
                description.getUrl().orElse(""),
                source == null || source.getFileName() == null ? "?" : source.getFileName().toString(),
                instance == null ? "?" : instance.getClass().getName(),
                sourceAvailable,
                instance != null,
                risk,
                inventory.commands(),
                inventory.tasks(),
                inventory.listeners(),
                inventory.channels(),
                inventory.executorActive(),
                dependents,
                requiredDependencies,
                optionalDependencies,
                providedIds,
                issues);
    }

    public synchronized OperationResult loadByFileName(String fileName) {
        Path jar = resolvePluginJar(fileName);
        if (jar == null) {
            return OperationResult.fail("plugins.load.need-jar", Map.of("dir", pluginsDirectory.toString()));
        }
        return load(jar);
    }

    public synchronized OperationResult load(Path jar) {
        Path absoluteJar = jar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absoluteJar)) {
            return OperationResult.fail("plugins.load.missing-file", Map.of("file", absoluteJar.getFileName().toString()));
        }

        PluginDescription realPlugin = null;
        PluginContainer container = null;
        boolean registered = false;
        try {
            Object loader = VelocityInternalAccess.newJavaPluginLoader(proxy, pluginsDirectory);
            PluginDescription candidate = (PluginDescription) VelocityInternalAccess.loadCandidate(loader, absoluteJar);
            String id = candidate.getId();
            if (isProtected(id)) {
                return OperationResult.fail("plugins.load.protected", Map.of("plugin", id));
            }
            if (proxy.getPluginManager().isLoaded(id)) {
                return OperationResult.fail("plugins.load.already", Map.of("plugin", id));
            }
            for (String providedId : candidate.getProvidedIds()) {
                if (proxy.getPluginManager().isLoaded(providedId)) {
                    return OperationResult.fail("plugins.load.id-taken", Map.of("plugin", providedId));
                }
            }
            for (PluginDependency dependency : candidate.getDependencies()) {
                if (!dependency.isOptional() && !proxy.getPluginManager().isLoaded(dependency.getId())) {
                    return OperationResult.fail("plugins.load.missing-dep", Map.of(
                            "plugin", id,
                            "dependency", dependency.getId()));
                }
            }

            realPlugin = (PluginDescription) VelocityInternalAccess.createPluginFromCandidate(loader, candidate);
            container = (PluginContainer) VelocityInternalAccess.newPluginContainer(realPlugin);
            Module pluginModule = (Module) VelocityInternalAccess.createModule(loader, container);
            Module commonModule = commonModule(container);
            VelocityInternalAccess.createPlugin(loader, container, pluginModule, commonModule);
            VelocityInternalAccess.registerPlugin(proxy.getPluginManager(), container);
            registered = true;

            Object instance = container.getInstance().orElse(null);
            if (instance != null) {
                VelocityInternalAccess.registerInternally(proxy.getEventManager(), container, instance);
                VelocityInternalAccess.fireForPlugin(
                        proxy.getEventManager(), new ProxyInitializeEvent(), container, instance);
            }

            lang.send(proxy.getConsoleCommandSource(), "log.console.plugin-loaded",
                    Lang.ph("plugin", realPlugin.getId()),
                    Lang.ph("version", realPlugin.getVersion().orElse("")));
            lang.send(proxy.getConsoleCommandSource(), "log.console.plugin-loaded-file",
                    Lang.ph("file", absoluteJar.getFileName()));
            return OperationResult.ok("plugins.load.ok", Map.of("plugin", realPlugin.getId()));
        } catch (Exception exception) {
            logger.error(lang.plain("log.load-fail", Lang.ph("file", absoluteJar.getFileName())), exception);
            CleanupReport rollback = null;
            if (registered && container != null) {
                try {
                    rollback = rollbackFailedLoad(container);
                } catch (Exception rollbackError) {
                    logger.error(lang.plain("log.rollback-fail", Lang.ph("file", absoluteJar.getFileName())), rollbackError);
                }
            } else {
                cleanup.closeDescriptionClassLoader(realPlugin);
            }
            cleanup.closeClassLoadersForJar(absoluteJar);
            return OperationResult.fail("plugins.load.fail", Map.of("file", absoluteJar.getFileName().toString()),
                    rollback, exception);
        }
    }

    public synchronized OperationResult unload(String id) {
        if (isProtected(id)) {
            return OperationResult.fail("plugins.unload.protected", Map.of("plugin", id));
        }
        Optional<PluginContainer> optional = proxy.getPluginManager().getPlugin(id);
        if (optional.isEmpty()) {
            return OperationResult.fail("plugins.unload.not-loaded", Map.of("plugin", id));
        }
        PluginContainer container = optional.get();
        String pluginId = container.getDescription().getId();
        List<String> dependents = dependentsOf(container);
        if (!dependents.isEmpty()) {
            return OperationResult.fail("plugins.unload.depended", Map.of(
                    "plugin", pluginId,
                    "dependents", String.join(", ", dependents)));
        }

        Object instance = container.getInstance().orElse(null);
        Path source = container.getDescription().getSource().orElse(null);
        Throwable shutdownError = null;
        try {
            if (instance != null) {
                try {
                    VelocityInternalAccess.fireForPlugin(
                            proxy.getEventManager(), new ProxyShutdownEvent(), container, instance);
                } catch (Exception exception) {
                    shutdownError = exception;
                    logger.error(lang.plain("log.unload-fail", Lang.ph("plugin", pluginId)), exception);
                }
            }

            CleanupReport report = cleanup.detach(container, instance);
            if (shutdownError != null) {
                report.markShutdownEventFailed();
            }
            VelocityInternalAccess.unregisterPlugin(proxy.getPluginManager(), container);
            cleanup.verify(container, instance, report);
            boolean stillLoaded = proxy.getPluginManager().isLoaded(pluginId);
            cleanup.closePluginClassLoader(instance);
            if (instance == null) {
                cleanup.closeDescriptionClassLoader(container.getDescription());
            }
            cleanup.closeClassLoadersForJar(source);

            if (stillLoaded) {
                logger.error(lang.plain("log.unload-fail", Lang.ph("plugin", pluginId)));
                return OperationResult.fail("plugins.unload.fail", Map.of("plugin", pluginId), report,
                        shutdownError != null ? shutdownError : new IllegalStateException(pluginId));
            }

            lang.send(proxy.getConsoleCommandSource(), "log.console.plugin-unloaded",
                    Lang.ph("plugin", pluginId));
            return new OperationResult(true, "plugins.unload.ok", Map.of("plugin", pluginId), report, shutdownError);
        } catch (Exception exception) {
            logger.error(lang.plain("log.unload-fail", Lang.ph("plugin", pluginId)), exception);
            return OperationResult.fail("plugins.unload.fail", Map.of("plugin", pluginId), exception);
        }
    }

    public synchronized OperationResult reload(String id) {
        Optional<PluginContainer> optional = proxy.getPluginManager().getPlugin(id);
        if (optional.isEmpty()) {
            return OperationResult.fail("plugins.unload.not-loaded", Map.of("plugin", id));
        }
        Path jar = optional.get().getDescription().getSource().orElse(null);
        if (jar == null) {
            return OperationResult.fail("plugins.reload.no-jar", Map.of("plugin", id));
        }
        OperationResult unloaded = unload(id);
        if (!unloaded.success()) {
            return unloaded;
        }
        OperationResult loaded = load(jar);
        if (!loaded.success()) {
            return new OperationResult(
                    false,
                    "plugins.reload.load-failed",
                    Map.of("plugin", id),
                    loaded.cleanup(),
                    loaded.error());
        }
        return OperationResult.ok("plugins.reload.ok", Map.of("plugin", id), unloaded.cleanup());
    }

    private CleanupReport rollbackFailedLoad(PluginContainer container) {
        Object instance = container.getInstance().orElse(null);
        CleanupReport report = cleanup.detach(container, instance);
        VelocityInternalAccess.unregisterPlugin(proxy.getPluginManager(), container);
        cleanup.closePluginClassLoader(instance);
        if (instance == null) {
            cleanup.closeDescriptionClassLoader(container.getDescription());
        }
        return report;
    }

    /**
     * 与 Velocity 启动时的公共 Guice module 对齐，并额外绑定当前已加载插件，
     * 方便新插件按 {@code @Named("id") PluginContainer} 注入依赖。
     */
    private Module commonModule(PluginContainer incoming) {
        PluginManager pluginManager = proxy.getPluginManager();
        EventManager eventManager = proxy.getEventManager();
        CommandManager commandManager = proxy.getCommandManager();
        List<PluginContainer> existing = List.copyOf(pluginManager.getPlugins());
        return new AbstractModule() {
            @Override
            protected void configure() {
                bind(ProxyServer.class).toInstance(proxy);
                bind(PluginManager.class).toInstance(pluginManager);
                bind(EventManager.class).toInstance(eventManager);
                bind(CommandManager.class).toInstance(commandManager);
                bind(PluginContainer.class)
                        .annotatedWith(Names.named(incoming.getDescription().getId()))
                        .toInstance(incoming);
                for (PluginContainer existingContainer : existing) {
                    bind(PluginContainer.class)
                            .annotatedWith(Names.named(existingContainer.getDescription().getId()))
                            .toInstance(existingContainer);
                }
            }
        };
    }

    private List<String> dependentsOf(PluginContainer target) {
        Set<String> claimed = claimedIds(target.getDescription());
        List<String> dependents = new ArrayList<>();
        for (PluginContainer container : proxy.getPluginManager().getPlugins()) {
            if (container == target) {
                continue;
            }
            for (PluginDependency dependency : container.getDescription().getDependencies()) {
                if (!dependency.isOptional() && claimed.contains(dependency.getId())) {
                    dependents.add(container.getDescription().getId());
                }
            }
        }
        return dependents;
    }

    private static Set<String> claimedIds(PluginDescription description) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(description.getId());
        ids.addAll(description.getProvidedIds());
        return ids;
    }

    private static List<String> dependencies(PluginDescription description, boolean optional) {
        return description.getDependencies().stream()
                .filter(dependency -> dependency.isOptional() == optional)
                .map(dependency -> dependency.getVersion()
                        .filter(version -> !version.isBlank())
                        .map(version -> dependency.getId() + " " + version)
                        .orElse(dependency.getId()))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private Path resolvePluginJar(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String trimmed = fileName.trim();
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) {
            return null;
        }
        if (!trimmed.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return null;
        }
        Path root = pluginsDirectory.toAbsolutePath().normalize();
        Path candidate = root.resolve(trimmed).toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            return null;
        }
        // Windows 上文件名大小写不敏感，所以只比较 Path，不再要求字符串完全一致。
        if (!trimmed.equalsIgnoreCase(candidate.getFileName().toString())) {
            return null;
        }
        return candidate;
    }

    private static boolean isProtected(String id) {
        return PROTECTED_IDS.contains(id.toLowerCase(Locale.ROOT));
    }

    public static String rootMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message;
    }

    public record PluginInfo(
            String id,
            String name,
            String version,
            List<String> authors,
            String description,
            String url,
            List<String> requiredDependencies,
            List<String> optionalDependencies,
            List<String> providedIds
    ) {
        public PluginInfo {
            authors = List.copyOf(authors);
            requiredDependencies = List.copyOf(requiredDependencies);
            optionalDependencies = List.copyOf(optionalDependencies);
            providedIds = List.copyOf(providedIds);
        }
    }

    public record OperationResult(
            boolean success,
            String messageKey,
            Map<String, String> placeholders,
            CleanupReport cleanup,
            Throwable error
    ) {
        public static OperationResult ok(String messageKey, Map<String, String> placeholders) {
            return new OperationResult(true, messageKey, Map.copyOf(placeholders), null, null);
        }

        public static OperationResult ok(String messageKey, Map<String, String> placeholders, CleanupReport cleanup) {
            return new OperationResult(true, messageKey, Map.copyOf(placeholders), cleanup, null);
        }

        public static OperationResult fail(String messageKey, Map<String, String> placeholders) {
            return new OperationResult(false, messageKey, Map.copyOf(placeholders), null, null);
        }

        public static OperationResult fail(String messageKey, Map<String, String> placeholders, Throwable error) {
            return new OperationResult(false, messageKey, Map.copyOf(placeholders), null, error);
        }

        public static OperationResult fail(
                String messageKey,
                Map<String, String> placeholders,
                CleanupReport cleanup,
                Throwable error
        ) {
            return new OperationResult(false, messageKey, Map.copyOf(placeholders), cleanup, error);
        }
    }
}
