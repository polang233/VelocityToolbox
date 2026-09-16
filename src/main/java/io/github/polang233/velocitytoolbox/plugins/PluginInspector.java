package io.github.polang233.velocitytoolbox.plugins;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 汇总插件依赖、已登记资源和卸载风险；不修改插件状态。 */
final class PluginInspector {
    private PluginInspector() {}

    static PluginInspection inspect(PluginContainer container, PluginCleanup.RuntimeInventory inventory,
                                    List<String> dependents, boolean protectedPlugin) {
        PluginDescription description = container.getDescription();
        Object instance = container.getInstance().orElse(null);
        List<String> requiredDependencies = dependencies(description, false);
        List<String> optionalDependencies = dependencies(description, true);
        List<String> providedIds = description.getProvidedIds().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        Path source = description.getSource().orElse(null);
        boolean sourceAvailable = source != null && Files.isRegularFile(source);

        List<PluginInspection.Issue> issues = new ArrayList<>();
        PluginInspection.Risk risk = PluginInspection.Risk.LOW;
        if (protectedPlugin) {
            issues.add(PluginInspection.Issue.PROTECTED);
            risk = PluginInspection.Risk.BLOCKED;
        }
        if (!dependents.isEmpty()) {
            issues.add(PluginInspection.Issue.REQUIRED_BY_OTHERS);
            risk = PluginInspection.Risk.BLOCKED;
        }
        if (!sourceAvailable) {
            issues.add(PluginInspection.Issue.NO_SOURCE_JAR);
            if (risk != PluginInspection.Risk.BLOCKED) {
                risk = PluginInspection.Risk.HIGH;
            }
        }
        if (instance == null) {
            issues.add(PluginInspection.Issue.NO_INSTANCE);
            if (risk != PluginInspection.Risk.BLOCKED) {
                risk = PluginInspection.Risk.HIGH;
            }
        }
        if (!providedIds.isEmpty()) {
            issues.add(PluginInspection.Issue.PROVIDED_IDS);
            if (risk == PluginInspection.Risk.LOW) {
                risk = PluginInspection.Risk.MEDIUM;
            }
        }
        if (inventory.channels() > 0) {
            issues.add(PluginInspection.Issue.CUSTOM_CHANNELS);
            if (risk == PluginInspection.Risk.LOW) {
                risk = PluginInspection.Risk.MEDIUM;
            }
        }
        if (inventory.executorActive()) {
            issues.add(PluginInspection.Issue.EXECUTOR);
            if (risk == PluginInspection.Risk.LOW) {
                risk = PluginInspection.Risk.MEDIUM;
            }
        }
        if (issues.isEmpty()) {
            issues.add(PluginInspection.Issue.STANDARD_CLEANUP_ONLY);
        }

        return new PluginInspection(
                true,
                description.getId(),
                description.getName().orElse(description.getId()),
                description.getVersion().orElse("?"),
                description.getAuthors(),
                description.getDescription().orElse(""),
                description.getUrl().orElse(""),
                source == null || source.getFileName() == null ? "?" : source.getFileName().toString(),
                instance == null ? "?" : instance.getClass().getName(),
                sourceAvailable,
                instance != null,
                risk,
                inventory.commands(),
                inventory.tasks(),
                inventory.listeners(),
                inventory.channels(),
                inventory.executorActive(),
                dependents,
                requiredDependencies,
                optionalDependencies,
                providedIds,
                issues);
    }

    static List<String> dependencies(PluginDescription description, boolean optional) {
        return description.getDependencies().stream()
                .filter(dependency -> dependency.isOptional() == optional)
                .map(dependency -> dependency.getVersion()
                        .filter(version -> !version.isBlank())
                        .map(version -> dependency.getId() + " " + version)
                        .orElse(dependency.getId()))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

}
