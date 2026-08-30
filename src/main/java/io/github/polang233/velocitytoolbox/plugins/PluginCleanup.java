package io.github.polang233.velocitytoolbox.plugins;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.github.polang233.velocitytoolbox.lang.Lang;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * 卸载时的公共清理：监听器、调度任务、命令、插件消息通道、插件线程池、插件类加载器。
 *
 * <p>对照 ServerUtils / VelocityHotReloader：它们拆监听器、任务、命令、类加载器。
 * 这里额外按类加载器扫残留监听器和自定义消息通道，并在清理后复查。</p>
 */
final class PluginCleanup {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Lang lang;

    PluginCleanup(ProxyServer proxy, Logger logger, Lang lang) {
        this.proxy = proxy;
        this.logger = logger;
        this.lang = lang;
    }

    /**
     * 在触发 {@code ProxyShutdownEvent} 之后调用。
     *
     * <p>监听器必须用插件实例去注销：Velocity 的 {@code unregisterListeners} 会
     * {@code fromInstance(plugin)}，传入 {@link PluginContainer} 在部分版本会找不到。</p>
     */
    CleanupReport detach(PluginContainer container, Object instance) {
        CleanupReport report = new CleanupReport();
        ClassLoader loader = classLoaderOf(instance, container);
        unregisterEvents(container, instance);
        report.addExtraListeners(VelocityInternalAccess.removeHandlersLoadedBy(proxy.getEventManager(), loader));
        if (report.extraListeners() > 0) {
            lang.send(proxy.getConsoleCommandSource(), "log.console.leftover-handlers",
                    Lang.ph("plugin", container.getDescription().getId()),
                    Lang.ph("count", report.extraListeners()));
        }
        if (instance != null) {
            report.addTasks(cancelTasks(instance));
        }
        report.addCommands(unregisterCommands(instance, container, loader));
        int channels = VelocityInternalAccess.unregisterChannelsLoadedBy(proxy.getChannelRegistrar(), loader);
        report.addChannels(channels);
        if (channels > 0) {
            lang.send(proxy.getConsoleCommandSource(), "log.console.leftover-channels",
                    Lang.ph("plugin", container.getDescription().getId()),
                    Lang.ph("count", channels));
        }
        if (shutdownExecutor(container)) {
            report.markExecutorShutdown();
        }
        return report;
    }

    RuntimeInventory inspect(PluginContainer container, Object instance) {
        ClassLoader loader = classLoaderOf(instance, container);
        int tasks = 0;
        if (instance != null) {
            try {
                tasks = proxy.getScheduler().tasksByPlugin(instance).size();
            } catch (NoSuchMethodError | RuntimeException ignored) {
                // 只读预检失败不应影响后续真实卸载；实际卸载仍会单独尝试并记录异常。
            }
        }
        OwnedCommands commands = ownedCommands(instance, container, loader);
        int listeners = VelocityInternalAccess.leftoverHandlerCount(proxy.getEventManager(), loader);
        int channels = VelocityInternalAccess.channelCountLoadedBy(proxy.getChannelRegistrar(), loader);
        return new RuntimeInventory(
                commands.count(), tasks, listeners, channels, executorAlreadyCreated(container));
    }

    void verify(PluginContainer container, Object instance, CleanupReport report) {
        String id = container.getDescription().getId();
        ClassLoader loader = classLoaderOf(instance, container);
        if (proxy.getPluginManager().isLoaded(id)) {
            report.leftover("plugins.leftover.still-loaded", Map.of("plugin", id));
        }
        for (String provided : container.getDescription().getProvidedIds()) {
            if (proxy.getPluginManager().isLoaded(provided)
                    && proxy.getPluginManager().getPlugin(provided).orElse(null) == container) {
                report.leftover("plugins.leftover.still-loaded", Map.of("plugin", provided));
            }
        }
        List<String> leftoverCommands = VelocityInternalAccess.leftoverCommandAliases(proxy.getCommandManager(), loader);
        if (!leftoverCommands.isEmpty()) {
            report.leftover("plugins.leftover.commands", Map.of("detail", String.join(", ", leftoverCommands)));
        }
        int leftoverListeners = VelocityInternalAccess.leftoverHandlerCount(proxy.getEventManager(), loader);
        if (leftoverListeners > 0) {
            report.leftover("plugins.leftover.listeners", Map.of("count", String.valueOf(leftoverListeners)));
        }
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

    private int cancelTasks(Object pluginInstance) {
        try {
            Collection<ScheduledTask> tasks = proxy.getScheduler().tasksByPlugin(pluginInstance);
            int count = tasks.size();
            for (ScheduledTask task : tasks) {
                task.cancel();
            }
            return count;
        } catch (NoSuchMethodError | RuntimeException exception) {
            logger.warn(lang.plain("log.cleanup-warn.tasks"), exception);
            return 0;
        }
    }

    private void unregisterEvents(PluginContainer container, Object instance) {
        try {
            if (instance != null) {
                proxy.getEventManager().unregisterListeners(instance);
            } else {
                proxy.getEventManager().unregisterListeners(container);
            }
        } catch (RuntimeException exception) {
            logger.warn(lang.plain("log.cleanup-warn.events",
                    Lang.ph("plugin", container.getDescription().getId())), exception);
        }
    }

    private int unregisterCommands(Object pluginInstance, PluginContainer container, ClassLoader loader) {
        CommandManager commands = proxy.getCommandManager();
        OwnedCommands owned = ownedCommands(pluginInstance, container, loader);
        for (CommandMeta meta : owned.meta()) {
            commands.unregister(meta);
        }
        for (String alias : owned.aliases()) {
            commands.unregister(alias);
        }
        int removed = owned.count();
        if (removed > 0) {
            lang.send(proxy.getConsoleCommandSource(), "log.console.commands-unregistered",
                    Lang.ph("plugin", container.getDescription().getId()),
                    Lang.ph("count", removed));
        }
        return removed;
    }

    private OwnedCommands ownedCommands(Object pluginInstance, PluginContainer container, ClassLoader loader) {
        CommandManager commands = proxy.getCommandManager();
        Set<CommandMeta> ownedMeta = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> ownedAliases = new LinkedHashSet<>();
        for (String alias : List.copyOf(commands.getAliases())) {
            CommandMeta meta = commandMeta(commands, alias);
            if (ownsCommand(meta, pluginInstance, container)
                    || VelocityInternalAccess.commandAliasLoadedBy(commands, alias, loader)) {
                if (meta != null) {
                    ownedMeta.add(meta);
                } else {
                    ownedAliases.add(alias);
                }
            }
        }
        return new OwnedCommands(ownedMeta, ownedAliases);
    }

    private static CommandMeta commandMeta(CommandManager commands, String alias) {
        CommandMeta meta = commands.getCommandMeta(alias);
        if (meta != null) {
            return meta;
        }
        return commands.getCommandMeta(alias.toLowerCase(Locale.ROOT));
    }

    private boolean ownsCommand(CommandMeta meta, Object pluginInstance, PluginContainer container) {
        if (meta == null) {
            return false;
        }
        Object owner = meta.getPlugin();
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
     * 4.0 以上公共 {@link PluginContainer} 只有 {@code getExecutorService()}，调用它会懒创建线程池。
     * 实现类上的 {@code hasExecutorService()} 不在 API 里，所以先反射再看内部字段。
     */
    private boolean shutdownExecutor(PluginContainer container) {
        if (!executorAlreadyCreated(container)) {
            return false;
        }
        try {
            ExecutorService executor = container.getExecutorService();
            if (executor != null) {
                executor.shutdownNow();
                return true;
            }
        } catch (RuntimeException exception) {
            logger.warn(lang.plain("log.cleanup-warn.executor",
                    Lang.ph("plugin", container.getDescription().getId())), exception);
        }
        return false;
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
                logger.warn(lang.plain("log.cleanup-warn.classloader"), exception);
            }
        }
    }

    private static ClassLoader classLoaderOf(Object instance, PluginContainer container) {
        if (instance != null) {
            return instance.getClass().getClassLoader();
        }
        return VelocityInternalAccess.classLoaderOf(container.getDescription()).orElse(null);
    }

    record RuntimeInventory(
            int commands,
            int tasks,
            int listeners,
            int channels,
            boolean executorActive
    ) {
    }

    private record OwnedCommands(Set<CommandMeta> meta, Set<String> aliases) {
        int count() {
            return meta.size() + aliases.size();
        }
    }
}
