package io.github.polang233.velocitytoolbox.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.polang233.velocitytoolbox.lang.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static io.github.polang233.velocitytoolbox.lang.Lang.ph;

/** 各命令共用的权限、补全和文本显示。 */
abstract class CommandView {
    protected final ProxyServer proxy;
    protected final Lang lang;
    public static final String PERMISSION = "velocitytoolbox.admin";
    public static final String COMMAND_PERMISSION = "velocitytoolbox.command";
    public static final String INFO_PERMISSION = "velocitytoolbox.command.info";
    public static final String RELOAD_PERMISSION = "velocitytoolbox.command.reload";
    public static final String PLUGIN_PERMISSION = "velocitytoolbox.command.plugin";
    public static final String PLUGIN_LIST_PERMISSION = PLUGIN_PERMISSION + ".list";
    public static final String PLUGIN_INSPECT_PERMISSION = PLUGIN_PERMISSION + ".inspect";
    public static final String PLUGIN_LOAD_PERMISSION = PLUGIN_PERMISSION + ".load";
    public static final String PLUGIN_UNLOAD_PERMISSION = PLUGIN_PERMISSION + ".unload";
    public static final String PLUGIN_RELOAD_PERMISSION = PLUGIN_PERMISSION + ".reload";

    CommandView(ProxyServer proxy, Lang lang) {
        this.proxy = proxy;
        this.lang = lang;
    }

    protected static LiteralArgumentBuilder<CommandSource> literal(String name) {
        return new LiteralArgumentBuilder<CommandSource>(name) {
            @Override
            public com.mojang.brigadier.tree.LiteralCommandNode<CommandSource> build() {
                var original = super.build();
                var filtered = new com.mojang.brigadier.tree.LiteralCommandNode<CommandSource>(
                        original.getLiteral(), original.getCommand(), original.getRequirement(),
                        original.getRedirect(), original.getRedirectModifier(), original.isFork()) {
                    @Override
                    public CompletableFuture<Suggestions> listSuggestions(CommandContext<CommandSource> context,
                                                                          SuggestionsBuilder builder) {
                        return canUse(context.getSource()) ? super.listSuggestions(context, builder) : Suggestions.empty();
                    }
                };
                original.getChildren().forEach(filtered::addChild);
                return filtered;
            }
        };
    }

    protected boolean module(CommandSource source, String name) {
        return hasCommandPermission(source, "velocitytoolbox.command." + name);
    }

    protected boolean action(CommandSource source, String module, String action) {
        return source.hasPermission(PERMISSION) || module(source, module)
                && source.hasPermission("velocitytoolbox.command." + module + "." + action);
    }

    protected int usage(CommandContext<CommandSource> ctx, String command) {
        lang.send(ctx.getSource(), "common.usage", ph("usage", command));
        return 0;
    }

    protected static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, List<String> options) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(option);
            }
        }
        return builder.buildFuture();
    }

    protected Component hoverField(String labelKey, String value) {
        return Component.text()
                .append(lang.get(labelKey))
                .append(Component.text(lang.plain("common.separator"), NamedTextColor.DARK_GRAY))
                .append(Component.text(value, Lang.BODY))
                .build();
    }

    protected void helpLine(CommandSource source, String command, String descriptionKey) {
        lang.send(source, Component.text()
                .append(Component.text("  " + command, Lang.COMMAND))
                .append(Component.text("  "))
                .append(lang.get(descriptionKey))
                .build());
    }

    protected void helpLineIfAllowed(
            CommandSource source,
            String permission,
            String command,
            String descriptionKey
    ) {
        if (hasCommandPermission(source, permission)) {
            helpLine(source, command, descriptionKey);
        }
    }

    protected void pluginHelpLineIfAllowed(
            CommandSource source,
            String permission,
            String command,
            String descriptionKey
    ) {
        if (hasPluginPermission(source, permission)) {
            helpLine(source, command, descriptionKey);
        }
    }

    protected boolean hasBasePermission(CommandSource source) {
        return source.hasPermission(PERMISSION) || source.hasPermission(COMMAND_PERMISSION);
    }

    protected boolean hasCommandPermission(CommandSource source, String permission) {
        return source.hasPermission(PERMISSION)
                || source.hasPermission(COMMAND_PERMISSION) && source.hasPermission(permission);
    }

    protected boolean hasPluginPermission(CommandSource source, String permission) {
        return source.hasPermission(PERMISSION)
                || source.hasPermission(COMMAND_PERMISSION)
                && source.hasPermission(PLUGIN_PERMISSION)
                && source.hasPermission(permission);
    }

    protected void sectionLine(CommandSource source, String key) {
        lang.send(source, lang.get(key));
    }

    protected void fieldLine(CommandSource source, String labelKey, Object value, TextColor valueColor) {
        lang.send(source, Component.text()
                .append(Component.text("  "))
                .append(lang.get(labelKey))
                .append(Component.text(lang.plain("common.separator"), NamedTextColor.DARK_GRAY))
                .append(Component.text(String.valueOf(value), valueColor))
                .build());
    }

    protected Component labelValue(String labelKey, Object value) {
        return Component.text()
                .append(lang.get(labelKey))
                .append(Component.text(" ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.valueOf(value), Lang.BODY))
                .build();
    }

    protected String listOrNone(List<String> values) {
        return values.isEmpty() ? lang.plain("common.none") : String.join(", ", values);
    }

    protected String valueOrNone(String value) {
        return value == null || value.isBlank() ? lang.plain("common.none") : value;
    }

    protected String yesNo(boolean value) {
        return lang.plain(value ? "common.yes" : "common.no");
    }

    protected String sourceName(CommandSource source) {
        return source instanceof Player player
                ? player.getUsername()
                : lang.plain("common.console");
    }

}
