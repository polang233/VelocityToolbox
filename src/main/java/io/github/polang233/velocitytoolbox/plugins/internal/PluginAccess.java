package io.github.polang233.velocitytoolbox.plugins.internal;

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

import static io.github.polang233.velocitytoolbox.plugins.internal.Reflection.*;

/** 按 Velocity 的加载顺序创建插件，并定向触发生命周期事件。 */
public final class PluginAccess {
    private PluginAccess() {}

    public static Object newJavaPluginLoader(Object proxy, Path pluginsDirectory) {
        Class<?> type = classForName("com.velocitypowered.proxy.plugin.loader.java.JavaPluginLoader");
        Constructor<?> constructor = constructor(type,
                classForName("com.velocitypowered.api.proxy.ProxyServer"), Path.class);
        return newInstance(constructor, proxy, pluginsDirectory);
    }

    public static Object loadCandidate(Object loader, Path source) {
        return invoke(findMethod(loader.getClass(), "loadCandidate", Path.class), loader, source);
    }

    public static Object createPluginFromCandidate(Object loader, Object candidate) {
        return invoke(findMethod(loader.getClass(), "createPluginFromCandidate",
                classForName("com.velocitypowered.api.plugin.PluginDescription")), loader, candidate);
    }

    public static Object createModule(Object loader, Object container) {
        return invoke(findMethod(loader.getClass(), "createModule",
                classForName("com.velocitypowered.api.plugin.PluginContainer")), loader, container);
    }

    public static void createPlugin(Object loader, Object container, Object... modules) {
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

    public static Object newPluginContainer(Object description) {
        Class<?> type = classForName("com.velocitypowered.proxy.plugin.loader.VelocityPluginContainer");
        Constructor<?> constructor = constructor(type,
                classForName("com.velocitypowered.api.plugin.PluginDescription"));
        return newInstance(constructor, description);
    }

    public static void registerPlugin(Object pluginManager, Object container) {
        invoke(findMethod(pluginManager.getClass(), "registerPlugin",
                classForName("com.velocitypowered.api.plugin.PluginContainer")), pluginManager, container);
    }

    public static void unregisterPlugin(Object pluginManager, Object container) {
        Method method;
        try {
            method = findMethod(pluginManager.getClass(), "unregisterPlugin",
                    classForName("com.velocitypowered.api.plugin.PluginContainer"));
        } catch (IllegalStateException missingApi) {
            removeContainerFromFields(pluginManager, container);
            return;
        }
        // 已找到 API 时，调用失败应报告，不能当作接口缺失再修改内部集合。
        invoke(method, pluginManager, container);
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

    public static void registerInternally(Object eventManager, Object container, Object listener) {
        invoke(findMethod(eventManager.getClass(), "registerInternally",
                classForName("com.velocitypowered.api.plugin.PluginContainer"), Object.class),
                eventManager, container, listener);
    }

    /**
     * 只对 {@code pluginContainer} 名下的处理器触发事件。
     * 全局 {@code EventManager.fire} 会把所有插件的初始化/关闭再跑一遍。
     */
    public static void fireForPlugin(Object eventManager, Object event, Object pluginContainer, Object pluginInstance) {
        Runnable dispatch;
        try {
            dispatch = prepareEvent(eventManager, event, pluginContainer);
        } catch (RuntimeException exception) {
            if (pluginInstance != null) {
                invokeAnnotated(pluginInstance, event);
                return;
            } else {
                throw exception;
            }
        }
        // 进入执行阶段后，事件异常直接上报，避免处理器被回退逻辑再次调用。
        dispatch.run();
    }

    private static Runnable prepareEvent(Object eventManager, Object event, Object pluginContainer) {
        Object cache = get(field(eventManager.getClass(), "handlersCache"), eventManager);
        Object handlersCache = invoke(findMethod(cache.getClass(), "get", Object.class), cache, event.getClass());
        if (handlersCache == null) return () -> {};
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
        if (matched.isEmpty()) return () -> {};

        Class<?> registrationType = matched.getFirst().getClass();
        Object array = Array.newInstance(registrationType, matched.size());
        for (int i = 0; i < matched.size(); i++) {
            Array.set(array, i, matched.get(i));
        }

        Method fire = findFireMethod(eventManager.getClass());
        CompletableFuture<?> future = new CompletableFuture<>();
        return () -> {
            invoke(fire, eventManager, future, event, 0, true, array);
            try {
                future.join();
            } catch (Exception exception) {
                throw new IllegalStateException("Plugin lifecycle event failed", exception);
            }
        };
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
                "Missing VelocityEventManager.fire(future, event, index, async, handlers)");
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

    public static Optional<ClassLoader> classLoaderOf(Object description) {
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
    public static void closeClassLoadersForSource(Path jar) {
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
                    // 保留加载失败的原始异常。
                }
            }
        } catch (Exception ignored) {
            // 代理内部结构不兼容，由调用方继续关闭已知加载器。
        }
    }

}
