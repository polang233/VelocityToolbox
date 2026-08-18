package io.github.polang233.velocitytoolbox.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一次卸载实际拆掉了什么，以及扫出来的残留。
 */
public final class CleanupReport {

    private int commands;
    private int tasks;
    private int extraListeners;
    private int channels;
    private boolean executorShutdown;
    private boolean shutdownEventFailed;
    private final List<Leftover> leftovers = new ArrayList<>();

    public int commands() {
        return commands;
    }

    public int tasks() {
        return tasks;
    }

    public int extraListeners() {
        return extraListeners;
    }

    public int channels() {
        return channels;
    }

    public boolean executorShutdown() {
        return executorShutdown;
    }

    public boolean shutdownEventFailed() {
        return shutdownEventFailed;
    }

    public List<Leftover> leftovers() {
        return List.copyOf(leftovers);
    }

    void addCommands(int count) {
        this.commands += count;
    }

    void addTasks(int count) {
        this.tasks += count;
    }

    void addExtraListeners(int count) {
        this.extraListeners += count;
    }

    void addChannels(int count) {
        this.channels += count;
    }

    void markExecutorShutdown() {
        this.executorShutdown = true;
    }

    void markShutdownEventFailed() {
        this.shutdownEventFailed = true;
    }

    void leftover(String key, Map<String, String> placeholders) {
        leftovers.add(new Leftover(key, Map.copyOf(placeholders)));
    }

    public record Leftover(String key, Map<String, String> placeholders) {
    }
}
