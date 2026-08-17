package io.github.velocitytoolbox.hotload;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 卸载时的公共清理：监听器、调度任务、带归属的命令、插件线程池、插件类加载器。
 */
final class PluginCleanup {

    private final ProxyServer proxy;
    private final Logger logger;

    PluginCleanup(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    /**
     * 在触发 {@code ProxyShutdownEvent} 之后调用。用 {@link PluginContainer} 注销监听器，
     * 这样主类之外通过 {@code EventManager.register(plugin, listener)} 挂上的监听器也会被拆掉。
     */
    void detach(PluginContainer container, Object instance) {
        try {
            proxy.getEventManager().unregisterListeners(container);
        } catch (RuntimeException exception) {
            logger.warn("无法注销插件 {} 的事件监听器。", container.getDescription().getId(), exception);
        }
        if (instance != null) {
            cancelTasks(instance);
        }
        unregisterCommands(instance, container);
        shutdownExecutor(container);
    }

    void closePluginClassLoader(Object instance) {
        if (instance == null) {
            return;
        }
        closeClassLoader(instance.getClass().getClassLoader());
    }

    void closeDescriptionClassLoader(Object description) {
        VelocityInternalAccess.classLoaderOf(description).ifPresent(this::closeClassLoader);
    }

    void closeClassLoadersForJar(Path jar) {
        VelocityInternalAccess.closeClassLoadersForSource(jar);
    }

    private void cancelTasks(Object pluginInstance) {
        try {
            Collection<ScheduledTask> tasks = proxy.getScheduler().tasksByPlugin(pluginInstance);
            for (ScheduledTask task : tasks) {
                task.cancel();
            }
        } catch (NoSuchMethodError | RuntimeException exception) {
            logger.warn("无法取消插件的调度任务。", exception);
        }
    }

    private void unregisterCommands(Object pluginInstance, PluginContainer container) {
        CommandManager commands = proxy.getCommandManager();
        Set<CommandMeta> owned = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (String alias : List.copyOf(commands.getAliases())) {
            CommandMeta meta = commands.getCommandMeta(alias);
            if (meta == null) {
                continue;
            }
            if (ownsCommand(meta.getPlugin(), pluginInstance, container)) {
                owned.add(meta);
            }
        }
        for (CommandMeta meta : owned) {
            commands.unregister(meta);
        }
    }

    private boolean ownsCommand(Object owner, Object pluginInstance, PluginContainer container) {
        if (owner == null) {
            return false;
        }
        if (owner == pluginInstance || owner == container) {
            return true;
        }
        Optional<PluginContainer> ownerContainer = proxy.getPluginManager().fromInstance(owner);
        return ownerContainer.isPresent() && ownerContainer.get() == container;
    }

    /**
     * 4.1 公共 {@link PluginContainer} 只有 {@code getExecutorService()}，调用它会懒创建线程池。
     * 实现类上的 {@code hasExecutorService()} 不在 API 里，所以先反射再看内部字段。
     */
    private void shutdownExecutor(PluginContainer container) {
        if (!executorAlreadyCreated(container)) {
            return;
        }
        try {
            ExecutorService executor = container.getExecutorService();
            if (executor != null) {
                executor.shutdownNow();
            }
        } catch (RuntimeException exception) {
            logger.warn("无法关闭插件 {} 的线程池。", container.getDescription().getId(), exception);
        }
    }

    private static boolean executorAlreadyCreated(PluginContainer container) {
        try {
            Method method = container.getClass().getMethod("hasExecutorService");
            Object result = method.invoke(container);
            return result instanceof Boolean flag && flag;
        } catch (ReflectiveOperationException ignored) {
            try {
                Field field = container.getClass().getDeclaredField("service");
                field.setAccessible(true);
                return field.get(container) != null;
            } catch (ReflectiveOperationException ignoredToo) {
                return false;
            }
        }
    }

    private void closeClassLoader(ClassLoader classLoader) {
        if (classLoader instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                logger.warn("无法关闭插件类加载器。", exception);
            }
        }
    }
}
