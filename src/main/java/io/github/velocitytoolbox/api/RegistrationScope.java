package io.github.velocitytoolbox.api;

import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.time.Duration;

public interface RegistrationScope {

    void registerListener(Object listener);

    CommandMeta registerCommand(String alias, SimpleCommand command, String... aliases);

    ScheduledTask schedule(Runnable task, Duration delay, Duration repeat);

    void registerChannel(ChannelIdentifier... identifiers);
}
