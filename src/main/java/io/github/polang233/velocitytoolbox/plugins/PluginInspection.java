package io.github.polang233.velocitytoolbox.plugins;

import java.util.List;

/**
 * 插件热卸载前的只读检查结果。计数来自当前 Velocity 运行时，检查本身不注销任何资源。
 */
public record PluginInspection(
        boolean found,
        String id,
        String name,
        String version,
        List<String> authors,
        String description,
        String url,
        String jar,
        String instanceClass,
        boolean sourceAvailable,
        boolean instanceAvailable,
        Risk risk,
        int commands,
        int tasks,
        int listeners,
        int channels,
        boolean executorActive,
        List<String> dependents,
        List<String> requiredDependencies,
        List<String> optionalDependencies,
        List<String> providedIds,
        List<Issue> issues
) {

    public PluginInspection {
        authors = List.copyOf(authors);
        dependents = List.copyOf(dependents);
        requiredDependencies = List.copyOf(requiredDependencies);
        optionalDependencies = List.copyOf(optionalDependencies);
        providedIds = List.copyOf(providedIds);
        issues = List.copyOf(issues);
    }

    static PluginInspection notFound(String id) {
        return new PluginInspection(
                false, id, id, "?", List.of(), "", "", "?", "?", false, false,
                Risk.BLOCKED,
                0, 0, 0, 0, false,
                List.of(), List.of(), List.of(), List.of(), List.of(Issue.NOT_LOADED));
    }

    public enum Risk {
        LOW,
        MEDIUM,
        HIGH,
        BLOCKED
    }

    public enum Issue {
        NOT_LOADED,
        PROTECTED,
        REQUIRED_BY_OTHERS,
        NO_SOURCE_JAR,
        NO_INSTANCE,
        PROVIDED_IDS,
        CUSTOM_CHANNELS,
        EXECUTOR,
        STANDARD_CLEANUP_ONLY
    }
}
