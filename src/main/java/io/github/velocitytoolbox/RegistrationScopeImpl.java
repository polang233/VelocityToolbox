package io.github.velocitytoolbox;

import io.github.velocitytoolbox.api.RegistrationScope;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class RegistrationScopeImpl implements RegistrationScope, AutoCloseable {

    private final ProxyServer proxy;
    private final Object owner;
    private final List<CommandMeta> commands = new ArrayList<>();
    private final List<ScheduledTask> tasks = new ArrayList<>();
    private final List<ChannelIdentifier> channels = new ArrayList<>();

    RegistrationScopeImpl(ProxyServer proxy, Object owner) {
        this.proxy = proxy;
        this.owner = owner;
    }

    @Override
    public void registerListener(Object listener) {
        proxy.getEventManager().register(owner, Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public CommandMeta registerCommand(String alias, SimpleCommand command, String... aliases) {
        CommandMeta.Builder builder = proxy.getCommandManager()
                .metaBuilder(Objects.requireNonNull(alias, "alias"))
                .plugin(owner);
        if (aliases != null && aliases.length > 0) {
            builder.aliases(aliases);
        }

        CommandMeta meta = builder.build();
        proxy.getCommandManager().register(meta, command);
        commands.add(meta);
        return meta;
    }

    @Override
    public ScheduledTask schedule(Runnable task, Duration delay, Duration repeat) {
        var builder = proxy.getScheduler().buildTask(owner, Objects.requireNonNull(task, "task"));
        if (delay != null) {
            builder.delay(delay);
        }
        if (repeat != null) {
            builder.repeat(repeat);
        }

        ScheduledTask scheduledTask = builder.schedule();
        tasks.add(scheduledTask);
        return scheduledTask;
    }

    @Override
    public void registerChannel(ChannelIdentifier... identifiers) {
        Objects.requireNonNull(identifiers, "identifiers");
        proxy.getChannelRegistrar().register(identifiers);
        channels.addAll(List.of(identifiers));
    }

    @Override
    public void close() {
        for (ScheduledTask task : tasks) {
            task.cancel();
        }
        tasks.clear();

        for (CommandMeta command : commands) {
            proxy.getCommandManager().unregister(command);
        }
        commands.clear();

        if (!channels.isEmpty()) {
            proxy.getChannelRegistrar().unregister(channels.toArray(ChannelIdentifier[]::new));
            channels.clear();
        }

        proxy.getEventManager().unregisterListeners(owner);
    }
}
