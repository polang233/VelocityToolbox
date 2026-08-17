package io.github.polang233.velocitytoolbox.hotload;

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
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private final Path pluginsDirectory;
    private final PluginCleanup cleanup;

    public PluginLoadService(ProxyServer proxy, Logger logger, Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.pluginsDirectory = dataDirectory.toAbsolutePath().normalize().getParent();
        this.cleanup = new PluginCleanup(proxy, logger);
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

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        for (PluginContainer container : proxy.getPluginManager().getPlugins()) {
            PluginDescription description = container.getDescription();
            String jar = description.getSource()
                    .map(path -> path.getFileName().toString())
                    .orElse("?");
            lines.add(description.getId()
                    + " " + description.getVersion().orElse("?")
                    + " -> " + jar);
        }
        lines.sort(String.CASE_INSENSITIVE_ORDER);
        return lines;
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
            logger.warn("无法列出 {} 里的插件 JAR。", pluginsDirectory, exception);
            return List.of();
        }
    }

    public synchronized OperationResult loadByFileName(String fileName) {
        Path jar = resolvePluginJar(fileName);
        if (jar == null) {
            return OperationResult.fail("需要 " + pluginsDirectory + " 下的 .jar 文件名");
        }
        return load(jar);
    }

    public synchronized OperationResult load(Path jar) {
        Path absoluteJar = jar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absoluteJar)) {
            return OperationResult.fail("插件 JAR 不存在: " + absoluteJar.getFileName());
        }

        PluginDescription realPlugin = null;
        PluginContainer container = null;
        boolean registered = false;
        try {
            Object loader = VelocityInternalAccess.newJavaPluginLoader(proxy, pluginsDirectory);
            PluginDescription candidate = (PluginDescription) VelocityInternalAccess.loadCandidate(loader, absoluteJar);
            String id = candidate.getId();
            if (isProtected(id)) {
                return OperationResult.fail("拒绝加载受保护插件 '" + id + "'。");
            }
            if (proxy.getPluginManager().isLoaded(id)) {
                return OperationResult.fail("插件 '" + id + "' 已经加载。");
            }
            for (String providedId : candidate.getProvidedIds()) {
                if (proxy.getPluginManager().isLoaded(providedId)) {
                    return OperationResult.fail("插件 ID '" + providedId + "' 已被占用。");
                }
            }
            for (PluginDependency dependency : candidate.getDependencies()) {
                if (!dependency.isOptional() && !proxy.getPluginManager().isLoaded(dependency.getId())) {
                    return OperationResult.fail("插件 " + id + " 缺少依赖 '" + dependency.getId() + "'");
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

            logger.info("已加载插件 {} {}，来自 {}。",
                    realPlugin.getId(),
                    realPlugin.getVersion().orElse(""),
                    absoluteJar.getFileName());
            return OperationResult.ok("已加载插件 " + realPlugin.getId() + "。");
        } catch (Exception exception) {
            logger.error("无法加载插件 {}。", absoluteJar.getFileName(), exception);
            if (registered && container != null) {
                try {
                    rollbackFailedLoad(container);
                } catch (Exception rollback) {
                    logger.error("回滚失败的加载 {} 时出错。", absoluteJar.getFileName(), rollback);
                }
            } else {
                cleanup.closeDescriptionClassLoader(realPlugin);
            }
            cleanup.closeClassLoadersForJar(absoluteJar);
            return OperationResult.fail("加载失败: " + rootMessage(exception));
        }
    }

    public synchronized OperationResult unload(String id) {
        if (isProtected(id)) {
            return OperationResult.fail("拒绝卸载受保护插件 '" + id + "'。");
        }
        Optional<PluginContainer> optional = proxy.getPluginManager().getPlugin(id);
        if (optional.isEmpty()) {
            return OperationResult.fail("插件 '" + id + "' 未加载。");
        }
        PluginContainer container = optional.get();
        List<String> dependents = dependentsOf(id);
        if (!dependents.isEmpty()) {
            return OperationResult.fail("插件 '" + id + "' 仍被依赖: " + String.join(", ", dependents));
        }

        Object instance = container.getInstance().orElse(null);
        Path source = container.getDescription().getSource().orElse(null);
        try {
            if (instance != null) {
                VelocityInternalAccess.fireForPlugin(
                        proxy.getEventManager(), new ProxyShutdownEvent(), container, instance);
            }
            cleanup.detach(container, instance);
            VelocityInternalAccess.unregisterPlugin(proxy.getPluginManager(), container);
            cleanup.closePluginClassLoader(instance);
            if (instance == null) {
                cleanup.closeDescriptionClassLoader(container.getDescription());
            }
            cleanup.closeClassLoadersForJar(source);
            logger.info("已卸载插件 {}。", id);
            return OperationResult.ok("已卸载插件 " + id + "。");
        } catch (Exception exception) {
            logger.error("无法卸载插件 {}。", id, exception);
            return OperationResult.fail("卸载失败: " + rootMessage(exception));
        }
    }

    public synchronized OperationResult reload(String id) {
        Optional<PluginContainer> optional = proxy.getPluginManager().getPlugin(id);
        if (optional.isEmpty()) {
            return OperationResult.fail("插件 '" + id + "' 未加载。");
        }
        Path jar = optional.get().getDescription().getSource().orElse(null);
        if (jar == null) {
            return OperationResult.fail("插件 '" + id + "' 没有 JAR 路径，无法重载。");
        }
        OperationResult unloaded = unload(id);
        if (!unloaded.success()) {
            return unloaded;
        }
        OperationResult loaded = load(jar);
        if (!loaded.success()) {
            return OperationResult.fail("已卸载 '" + id + "'，但重新加载失败: " + loaded.message());
        }
        return OperationResult.ok("已重载插件 " + id + "。");
    }

    private void rollbackFailedLoad(PluginContainer container) {
        Object instance = container.getInstance().orElse(null);
        cleanup.detach(container, instance);
        VelocityInternalAccess.unregisterPlugin(proxy.getPluginManager(), container);
        cleanup.closePluginClassLoader(instance);
        if (instance == null) {
            cleanup.closeDescriptionClassLoader(container.getDescription());
        }
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

    private List<String> dependentsOf(String id) {
        List<String> dependents = new ArrayList<>();
        for (PluginContainer container : proxy.getPluginManager().getPlugins()) {
            for (PluginDependency dependency : container.getDescription().getDependencies()) {
                if (!dependency.isOptional() && id.equalsIgnoreCase(dependency.getId())) {
                    dependents.add(container.getDescription().getId());
                }
            }
        }
        return dependents;
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

    private static String rootMessage(Throwable exception) {
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

    public record OperationResult(boolean success, String message) {
        public static OperationResult ok(String message) {
            return new OperationResult(true, message);
        }

        public static OperationResult fail(String message) {
            return new OperationResult(false, message);
        }
    }
}
