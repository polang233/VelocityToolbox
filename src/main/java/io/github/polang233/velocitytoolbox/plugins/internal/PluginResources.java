package io.github.polang233.velocitytoolbox.plugins.internal;

import com.mojang.brigadier.tree.CommandNode;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.polang233.velocitytoolbox.plugins.internal.Reflection.*;

/** 按类加载器识别并清理插件的命令、监听器和消息通道。 */
public final class PluginResources {
    private PluginResources() {}

    /**
     * 按类加载器补充清理未关联插件实例的监听器。
     */
    public static int removeHandlersLoadedBy(Object eventManager, ClassLoader loader) {
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

    public static int leftoverHandlerCount(Object eventManager, ClassLoader loader) {
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
     * 通道未记录插件归属，只清理由目标类加载器定义的 ChannelIdentifier。
     */
    public static int unregisterChannelsLoadedBy(Object channelRegistrar, ClassLoader loader) {
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

    public static int channelCountLoadedBy(Object channelRegistrar, ClassLoader loader) {
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

    public static List<String> leftoverCommandAliases(Object commandManager, ClassLoader loader) {
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
            // 缓存结构不兼容时保留原始注册信息供后续检查。
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
     */
    public static boolean commandAliasLoadedBy(Object commandManager, String alias, ClassLoader loader) {
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

}
