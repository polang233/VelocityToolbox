package io.github.polang233.velocitytoolbox.plugins;

import com.mojang.brigadier.tree.CommandNode;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 反射调用 Velocity 4.0 以上内部加载器。
 *
 * <p>优先找同名方法（代理以后如果补了 {@code unregisterPlugin} 会自动走方法），
 * 找不到再退回扫字段。公共 {@code PluginManager} 目前仍然没有 load / unload。</p>
 */
final class VelocityInternalAccess {

    private VelocityInternalAccess() {
    }

    static Object newJavaPluginLoader(Object proxy, Path pluginsDirectory) {
        Class<?> type = classForName("com.velocitypowered.proxy.plugin.loader.java.JavaPluginLoader");
        Constructor<?> constructor = constructor(type,
                classForName("com.velocitypowered.api.proxy.ProxyServer"), Path.class);
        return newInstance(constructor, proxy, pluginsDirectory);
    }

    static Object loadCandidate(Object loader, Path source) {
        return invoke(findMethod(loader.getClass(), "loadCandidate", Path.class), loader, source);
    }

    static Object createPluginFromCandidate(Object loader, Object candidate) {
        return invoke(findMethod(loader.getClass(), "createPluginFromCandidate",
                classForName("com.velocitypowered.api.plugin.PluginDescription")), loader, candidate);
    }

    static Object createModule(Object loader, Object container) {
        return invoke(findMethod(loader.getClass(), "createModule",
                classForName("com.velocitypowered.api.plugin.PluginContainer")), loader, container);
    }

    static void createPlugin(Object loader, Object container, Object... modules) {
        Class<?> moduleType = classForName("com.google.inject.Module");
        Object moduleArray = Array.newInstance(moduleType, modules.length);
        for (int i = 0; i < modules.length; i++) {
            Array.set(moduleArray, i, modules[i]);
        }
        Method method = findMethod(loader.getClass(), "createPlugin",
                classForName("com.velocitypowered.api.plugin.PluginContainer"),
                moduleType.arrayType());
        invoke(method, loader, container, moduleArray);
    }

    static Object newPluginContainer(Object description) {
        Class<?> type = classForName("com.velocitypowered.proxy.plugin.loader.VelocityPluginContainer");
        Constructor<?> constructor = constructor(type,
                classForName("com.velocitypowered.api.plugin.PluginDescription"));
        return newInstance(constructor, description);
    }

    static void registerPlugin(Object pluginManager, Object container) {
        invoke(findMethod(pluginManager.getClass(), "registerPlugin",
                classForName("com.velocitypowered.api.plugin.PluginContainer")), pluginManager, container);
    }

    static void unregisterPlugin(Object pluginManager, Object container) {
        try {
            Method method = findMethod(pluginManager.getClass(), "unregisterPlugin",
                    classForName("com.velocitypowered.api.plugin.PluginContainer"));
            invoke(method, pluginManager, container);
        } catch (IllegalStateException ignored) {
            removeContainerFromFields(pluginManager, container);
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeContainerFromFields(Object pluginManager, Object container) {
        for (Field field : pluginManager.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = get(field, pluginManager);
            if (value instanceof Map<?, ?> map) {
                ((Map<?, Object>) map).entrySet().removeIf(entry -> entry.getValue() == container);
            } else if (value instanceof Collection<?> collection) {
                collection.removeIf(element -> element == container);
            }
        }
    }

    static void registerInternally(Object eventManager, Object container, Object listener) {
        invoke(findMethod(eventManager.getClass(), "registerInternally",
                classForName("com.velocitypowered.api.plugin.PluginContainer"), Object.class),
                eventManager, container, listener);
    }

    /**
     * 只对 {@code pluginContainer} 名下的处理器触发事件。
     * 全局 {@code EventManager.fire} 会把所有插件的初始化/关闭再跑一遍。
     */
    static void fireForPlugin(Object eventManager, Object event, Object pluginContainer, Object pluginInstance) {
        try {
            fireThroughEventManager(eventManager, event, pluginContainer);
        } catch (RuntimeException exception) {
            if (pluginInstance != null) {
                invokeAnnotated(pluginInstance, event);
            } else {
                throw exception;
            }
        }
    }

    private static void fireThroughEventManager(Object eventManager, Object event, Object pluginContainer) {
        Object cache = get(field(eventManager.getClass(), "handlersCache"), eventManager);
        Object handlersCache = invoke(findMethod(cache.getClass(), "get", Object.class), cache, event.getClass());
        if (handlersCache == null) {
            return;
        }
        Object handlers = get(field(handlersCache.getClass(), "handlers"), handlersCache);
        int length = Array.getLength(handlers);
        List<Object> matched = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            Object registration = Array.get(handlers, i);
            Object owner = get(field(registration.getClass(), "plugin"), registration);
            if (owner == pluginContainer) {
                matched.add(registration);
            }
        }
        if (matched.isEmpty()) {
            return;
        }

        Class<?> registrationType = matched.getFirst().getClass();
        Object array = Array.newInstance(registrationType, matched.size());
        for (int i = 0; i < matched.size(); i++) {
            Array.set(array, i, matched.get(i));
        }

        Method fire = findFireMethod(eventManager.getClass());
        CompletableFuture<?> future = new CompletableFuture<>();
        invoke(fire, eventManager, future, event, 0, true, array);
        try {
            future.join();
        } catch (Exception exception) {
            throw new IllegalStateException("插件生命周期事件失败", exception);
        }
    }

    private static Method findFireMethod(Class<?> eventManagerType) {
        for (Method method : eventManagerType.getDeclaredMethods()) {
            if (!method.getName().equals("fire") || method.getParameterCount() != 5) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types[2] == int.class && types[3] == boolean.class && types[4].isArray()) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException(
                "找不到 VelocityEventManager.fire(future, event, index, async, handlers)");
    }

    private static void invokeAnnotated(Object listener, Object event) {
        for (Method method : listener.getClass().getMethods()) {
            if (method.getAnnotation(com.velocitypowered.api.event.Subscribe.class) == null) {
                continue;
            }
            if (method.getParameterCount() != 1 || !method.getParameterTypes()[0].isInstance(event)) {
                continue;
            }
            invoke(method, listener, event);
        }
    }

    static Optional<ClassLoader> classLoaderOf(Object description) {
        try {
            Method getMainClass = findMethod(description.getClass(), "getMainClass");
            Object main = invoke(getMainClass, description);
            if (main instanceof Class<?> type) {
                return Optional.ofNullable(type.getClassLoader());
            }
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * {@code createPluginFromCandidate} 一旦 {@code addToClassloaders()} 就会把加载器放进静态集合。
     * 加载失败时必须关掉，否则旧 JAR 的类还会被其它插件解析到。
     */
    static void closeClassLoadersForSource(Path jar) {
        if (jar == null) {
            return;
        }
        try {
            URL jarUrl = jar.toUri().toURL();
            Class<?> type = classForName("com.velocitypowered.proxy.plugin.PluginClassLoader");
            Field loadersField = field(type, "loaders");
            Object raw = get(loadersField, null);
            if (!(raw instanceof Iterable<?> loaders)) {
                return;
            }
            List<AutoCloseable> matches = new ArrayList<>();
            for (Object loader : loaders) {
                if (loader instanceof URLClassLoader urlLoader && loader instanceof AutoCloseable closeable) {
                    for (URL url : urlLoader.getURLs()) {
                        if (jarUrl.equals(url)) {
                            matches.add(closeable);
                            break;
                        }
                    }
                }
            }
            for (AutoCloseable closeable : matches) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // 加载失败路径上的尽力清理，不能再把原始异常盖掉。
                }
            }
        } catch (Exception ignored) {
            // 内部类结构变了就跳过，调用方还有其它关闭路径。
        }
    }

    /**
     * 按类加载器扫掉 {@code unregisterListeners} 没覆盖到的监听器
     * （例如登记时没绑对插件实例）。
     */
    static int removeHandlersLoadedBy(Object eventManager, ClassLoader loader) {
        if (eventManager == null || loader == null) {
            return 0;
        }
        Object writeLock = writeLock(eventManager);
        lock(writeLock);
        List<Object> removed = new ArrayList<>();
        try {
            Object handlersByType = get(field(eventManager.getClass(), "handlersByType"), eventManager);
            Object values = invoke(findMethod(handlersByType.getClass(), "values"), handlersByType);
            if (!(values instanceof Iterable<?> iterable)) {
                return 0;
            }
            Iterator<?> iterator = iterable.iterator();
            while (iterator.hasNext()) {
                Object registration = iterator.next();
                if (registrationLoadedBy(registration, loader)) {
                    iterator.remove();
                    removed.add(registration);
                }
            }
        } catch (RuntimeException ignored) {
            return 0;
        } finally {
            unlock(writeLock);
        }
        if (!removed.isEmpty()) {
            invalidateHandlerCache(eventManager);
        }
        return removed.size();
    }

    static int leftoverHandlerCount(Object eventManager, ClassLoader loader) {
        if (eventManager == null || loader == null) {
            return 0;
        }
        try {
            Object handlersByType = get(field(eventManager.getClass(), "handlersByType"), eventManager);
            Object values = invoke(findMethod(handlersByType.getClass(), "values"), handlersByType);
            int count = 0;
            if (values instanceof Iterable<?> iterable) {
                for (Object registration : iterable) {
                    if (registrationLoadedBy(registration, loader)) {
                        count++;
                    }
                }
            }
            return count;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    /**
     * Velocity 的通道登记不记插件归属。只能卸掉自定义 {@code ChannelIdentifier} 实现类来自该插件类加载器的通道。
     * LuckPerms 这类用 API 自带 identifier 的通道卸不掉，这是代理限制。
     */
    static int unregisterChannelsLoadedBy(Object channelRegistrar, ClassLoader loader) {
        Set<Object> owned = channelsLoadedBy(channelRegistrar, loader);
        if (owned.isEmpty()) {
            return 0;
        }
        try {
            Object array = Array.newInstance(
                    classForName("com.velocitypowered.api.proxy.messages.ChannelIdentifier"),
                    owned.size());
            int index = 0;
            for (Object identifier : owned) {
                Array.set(array, index++, identifier);
            }
            invoke(findMethod(channelRegistrar.getClass(), "unregister", array.getClass()),
                    channelRegistrar, array);
            return owned.size();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    static int channelCountLoadedBy(Object channelRegistrar, ClassLoader loader) {
        return channelsLoadedBy(channelRegistrar, loader).size();
    }

    private static Set<Object> channelsLoadedBy(Object channelRegistrar, ClassLoader loader) {
        if (channelRegistrar == null || loader == null) {
            return Set.of();
        }
        try {
            Object raw = get(field(channelRegistrar.getClass(), "identifierMap"), channelRegistrar);
            if (!(raw instanceof Map<?, ?> map)) {
                return Set.of();
            }
            Set<Object> owned = new HashSet<>();
            for (Object identifier : map.values()) {
                if (identifier != null && sameLoader(identifier.getClass().getClassLoader(), loader)) {
                    owned.add(identifier);
                }
            }
            return owned;
        } catch (RuntimeException ignored) {
            return Set.of();
        }
    }

    static List<String> leftoverCommandAliases(Object commandManager, ClassLoader loader) {
        List<String> leftovers = new ArrayList<>();
        if (commandManager == null || loader == null) {
            return leftovers;
        }
        try {
            Object aliases = invoke(findMethod(commandManager.getClass(), "getAliases"), commandManager);
            if (!(aliases instanceof Collection<?> collection)) {
                return leftovers;
            }
            for (Object alias : collection) {
                if (alias instanceof String name && commandAliasLoadedBy(commandManager, name, loader)) {
                    leftovers.add(name);
                }
            }
        } catch (RuntimeException ignored) {
            return leftovers;
        }
        return leftovers;
    }

    private static boolean registrationLoadedBy(Object registration, ClassLoader loader) {
        return classLoadedBy(optionalField(registration, "instance"), loader)
                || classLoadedBy(optionalField(registration, "handler"), loader);
    }

    private static boolean classLoadedBy(Object value, ClassLoader loader) {
        return value != null && sameLoader(value.getClass().getClassLoader(), loader);
    }

    private static void invalidateHandlerCache(Object eventManager) {
        try {
            Object cache = get(field(eventManager.getClass(), "handlersCache"), eventManager);
            invoke(findMethod(cache.getClass(), "invalidateAll"), cache);
        } catch (RuntimeException ignored) {
            // 缓存清不掉时，下一轮事件仍可能打到已卸插件；调用方会记残留。
        }
    }

    private static Object writeLock(Object eventManager) {
        try {
            Object lock = get(field(eventManager.getClass(), "lock"), eventManager);
            return invoke(findMethod(lock.getClass(), "writeLock"), lock);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void lock(Object writeLock) {
        if (writeLock != null) {
            invoke(findMethod(writeLock.getClass(), "lock"), writeLock);
        }
    }

    private static void unlock(Object writeLock) {
        if (writeLock != null) {
            invoke(findMethod(writeLock.getClass(), "unlock"), writeLock);
        }
    }

    /**
     * 命令没设置 {@code CommandMeta.plugin} 时，用命令图里捕获的对象类加载器判断归属。
     * {@code ShadiaoVelocity} 这类 {@code metaBuilder(...).build()} 不带 {@code .plugin(this)} 的注册会走这里。
     */
    static boolean commandAliasLoadedBy(Object commandManager, String alias, ClassLoader loader) {
        if (commandManager == null || alias == null || loader == null) {
            return false;
        }
        try {
            Object node = invoke(findMethod(commandManager.getClass(), "getCommand", String.class),
                    commandManager, alias);
            return nodeLoadedBy(node, loader);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean nodeLoadedBy(Object node, ClassLoader loader) {
        if (!(node instanceof CommandNode<?> commandNode)) {
            return false;
        }
        if (valueLoadedBy(commandNode.getCommand(), loader)
                || valueLoadedBy(commandNode.getRequirement(), loader)) {
            return true;
        }
        for (CommandNode<?> child : commandNode.getChildren()) {
            if (nodeLoadedBy(child, loader)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valueLoadedBy(Object value, ClassLoader loader) {
        if (value == null) {
            return false;
        }
        if (sameLoader(value.getClass().getClassLoader(), loader)) {
            return true;
        }
        Object registrant = optionalField(value, "registrant");
        if (registrant != null && sameLoader(registrant.getClass().getClassLoader(), loader)) {
            return true;
        }
        Object delegate = optionalField(value, "delegate");
        if (delegate != null && valueLoadedBy(delegate, loader)) {
            return true;
        }
        for (Field field : value.getClass().getDeclaredFields()) {
            if (field.getType().isPrimitive()) {
                continue;
            }
            Object captured = optionalGet(field, value);
            if (captured != null && sameLoader(captured.getClass().getClassLoader(), loader)) {
                return true;
            }
        }
        return false;
    }

    private static Object optionalField(Object target, String name) {
        try {
            return get(field(target.getClass(), name), target);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Object optionalGet(Field field, Object target) {
        try {
            if (!field.trySetAccessible()) {
                return null;
            }
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean sameLoader(ClassLoader left, ClassLoader right) {
        return left != null && left == right;
    }

    static Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("缺少 Velocity 类 " + name
                    + "。插件管理需要完整代理运行时，不能只靠 velocity-api。", exception);
        }
    }

    private static Constructor<?> constructor(Class<?> type, Class<?>... parameters) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(parameters);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        for (Class<?> iface : type.getInterfaces()) {
            try {
                Method method = iface.getMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // 继续看下一个接口。
            }
        }
        throw new IllegalStateException("缺少方法 " + type.getName() + "." + name);
    }

    private static Field field(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException("缺少字段 " + type.getName() + "." + name);
    }

    private static Object newInstance(Constructor<?> constructor, Object... arguments) {
        try {
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException exception) {
            throw unwrap(exception);
        }
    }

    static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (ReflectiveOperationException exception) {
            throw unwrap(exception);
        }
    }

    private static Object get(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RuntimeException unwrap(ReflectiveOperationException exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        return new IllegalStateException(cause);
    }
}
