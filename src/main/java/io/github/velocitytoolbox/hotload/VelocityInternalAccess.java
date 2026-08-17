package io.github.velocitytoolbox.hotload;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 反射调用 Velocity 4.1 内部加载器。
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

    static Class<?> classForName(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("缺少 Velocity 类 " + name
                    + "。插件热加载需要完整代理运行时，不能只靠 velocity-api。", exception);
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
