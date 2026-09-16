package io.github.polang233.velocitytoolbox.plugins.internal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** 使用不同类加载器验证资源归属，防止清理时影响其它插件。 */
public final class PluginAccessTest {
    private static final RuntimeException FAILURE = new IllegalArgumentException("original");

    public static void main(String[] args) {
        lifecycleFailure();
        unregisterFailure();
        var child = new Child();
        check(Reflection.get(Reflection.field(Child.class, "value"), child).equals("parent"), "inherited field");
        check(Reflection.invoke(Reflection.findMethod(Child.class, "greet"), child).equals("hello"), "inherited method");
        check(Reflection.optionalField(child, "absent") == null, "optional missing field");
        try {
            Reflection.invoke(Reflection.findMethod(Child.class, "fail"), child);
            throw new AssertionError("expected original failure");
        } catch (RuntimeException failure) {
            check(failure == FAILURE, "preserve original exception");
        }

        ClassLoader owner = new ClassLoader(PluginAccessTest.class.getClassLoader()) {};
        ClassLoader other = new ClassLoader(PluginAccessTest.class.getClassLoader()) {};
        Object owned = proxy(owner, Callable.class);
        Object unrelated = proxy(other, Callable.class);
        Events events = new Events();
        events.handlersByType.entries.add(new Registration(owned));
        events.handlersByType.entries.add(new Registration(unrelated));
        check(PluginResources.leftoverHandlerCount(events, owner) == 1, "count owned handler");
        check(PluginResources.removeHandlersLoadedBy(events, owner) == 1, "remove owned handler");
        check(events.handlersByType.entries.size() == 1
                && events.handlersByType.entries.getFirst().instance == unrelated, "keep other plugin handler");
        check(events.lock.write.depth == 0 && events.handlersCache.invalidated, "release lock and clear cache");

        ChannelIdentifier ownedChannel = proxy(owner, ChannelIdentifier.class);
        ChannelIdentifier otherChannel = proxy(other, ChannelIdentifier.class);
        Channels channels = new Channels(ownedChannel, otherChannel);
        check(PluginResources.channelCountLoadedBy(channels, owner) == 1, "count owned channel");
        check(PluginResources.unregisterChannelsLoadedBy(channels, owner) == 1
                && channels.removed.equals(List.of(ownedChannel)), "unregister only owned channel");

        @SuppressWarnings("unchecked")
        Command<Object> action = proxy(owner, Command.class);
        CommandNode<Object> node = LiteralArgumentBuilder.<Object>literal("root")
                .then(LiteralArgumentBuilder.<Object>literal("child").executes(action)).build();
        Commands commands = new Commands(node);
        check(PluginResources.commandAliasLoadedBy(commands, "example", owner), "nested command ownership");
        check(!PluginResources.commandAliasLoadedBy(commands, "example", other), "unrelated command loader");
        check(PluginResources.leftoverCommandAliases(commands, owner).equals(List.of("example")), "command aliases");
        System.out.println("Plugin access tests passed: reflection, commands, listeners and channels.");
    }

    private static void unregisterFailure() {
        var container = proxy(PluginAccessTest.class.getClassLoader(), com.velocitypowered.api.plugin.PluginContainer.class);
        var manager = new FailingManager(container);
        try {
            PluginAccess.unregisterPlugin(manager, container);
            throw new AssertionError("unregister failure swallowed");
        } catch (IllegalStateException expected) {
            check(manager.registered.get("example") == container, "failed unregister must not fall back to mutating fields");
        }
    }

    public static final class FailingManager {
        final Map<String, Object> registered = new java.util.HashMap<>();
        FailingManager(Object container) { registered.put("example", container); }
        public void unregisterPlugin(com.velocitypowered.api.plugin.PluginContainer container) {
            throw new IllegalStateException("simulated unregister failure");
        }
    }

    private static void lifecycleFailure() {
        Object container = new Object();
        Lifecycle listener = new Lifecycle(true);
        try {
            PluginAccess.fireForPlugin(new LifecycleEvents(container, listener), new Object(), container, listener);
            throw new AssertionError("expected lifecycle failure");
        } catch (RuntimeException expected) {
            check(listener.calls == 1, "lifecycle failure must not invoke the handler a second time");
        }
        Lifecycle fallback = new Lifecycle(false);
        PluginAccess.fireForPlugin(new Object(), new Object(), container, fallback);
        check(fallback.calls == 1, "incompatible internal API still uses annotation fallback");
    }

    public static final class Lifecycle {
        final boolean fail;
        int calls;
        Lifecycle(boolean fail) { this.fail = fail; }
        @com.velocitypowered.api.event.Subscribe
        public void handle(Object event) {
            calls++;
            if (fail) throw FAILURE;
        }
    }
    public static final class LifecycleRegistration {
        final Object plugin;
        LifecycleRegistration(Object plugin) { this.plugin = plugin; }
    }
    public static final class LifecycleHandlers {
        final LifecycleRegistration[] handlers;
        LifecycleHandlers(Object plugin) { handlers = new LifecycleRegistration[]{new LifecycleRegistration(plugin)}; }
    }
    public static final class LifecycleCache {
        final Object plugin;
        LifecycleCache(Object plugin) { this.plugin = plugin; }
        public LifecycleHandlers get(Object event) { return new LifecycleHandlers(plugin); }
    }
    public static final class LifecycleEvents {
        final LifecycleCache handlersCache;
        final Lifecycle listener;
        LifecycleEvents(Object plugin, Lifecycle listener) { handlersCache = new LifecycleCache(plugin); this.listener = listener; }
        private void fire(java.util.concurrent.CompletableFuture<Object> future, Object event, int index,
                          boolean async, LifecycleRegistration[] handlers) {
            try { listener.handle(event); future.complete(null); }
            catch (RuntimeException error) { future.completeExceptionally(error); }
        }
    }

    private static class Parent {
        private final String value = "parent";
        private String greet() { return "hello"; }
        private void fail() { throw FAILURE; }
    }
    private static final class Child extends Parent {}

    public static final class Registration {
        final Object instance;
        Registration(Object instance) { this.instance = instance; }
    }
    public static final class Handlers {
        final List<Registration> entries = new ArrayList<>();
        public Collection<Registration> values() { return entries; }
    }
    public static final class Cache {
        boolean invalidated;
        public void invalidateAll() { invalidated = true; }
    }
    public static final class WriteLock {
        int depth;
        public void lock() { depth++; }
        public void unlock() { depth--; }
    }
    public static final class Lock {
        final WriteLock write = new WriteLock();
        public WriteLock writeLock() { return write; }
    }
    public static final class Events {
        final Handlers handlersByType = new Handlers();
        final Cache handlersCache = new Cache();
        final Lock lock = new Lock();
    }
    public static final class Channels {
        final Map<String, ChannelIdentifier> identifierMap;
        List<ChannelIdentifier> removed = List.of();
        Channels(ChannelIdentifier first, ChannelIdentifier second) {
            identifierMap = Map.of("first", first, "second", second);
        }
        public void unregister(ChannelIdentifier... identifiers) { removed = List.of(identifiers); }
    }
    public static final class Commands {
        final CommandNode<Object> node;
        Commands(CommandNode<Object> node) { this.node = node; }
        public CommandNode<Object> getCommand(String alias) { return node; }
        public Collection<String> getAliases() { return List.of("example"); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(ClassLoader loader, Class<T> type) {
        return (T) Proxy.newProxyInstance(loader, new Class<?>[]{type}, (instance, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(instance);
            case "equals" -> instance == args[0];
            case "toString" -> type.getSimpleName();
            default -> null;
        });
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
