package io.github.velocitytoolbox;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.Arrays;
import java.util.List;

final class ToolboxCommand implements SimpleCommand {

    private static final String PERMISSION = "velocitytoolbox.admin";

    private final ProxyServer proxy;
    private final ModuleManager modules;

    ToolboxCommand(ProxyServer proxy, ModuleManager modules) {
        this.proxy = proxy;
        this.modules = modules;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            invocation.source().sendPlainMessage(
                    "VelocityToolbox: /vtoolbox version, status, reload, "
                            + "module list|load|unload|reload");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "version" -> invocation.source().sendPlainMessage(
                    "VelocityToolbox " + VelocityToolboxPlugin.VERSION
                            + " | modules=" + modules.loadedCount());
            case "status" -> sendStatus(invocation);
            case "reload" -> {
                modules.reloadConfiguration();
                invocation.source().sendPlainMessage("Configuration reload acknowledged.");
            }
            case "module" -> handleModule(invocation, Arrays.copyOfRange(args, 1, args.length));
            default -> invocation.source().sendPlainMessage(
                    "Unknown subcommand. Use /vtoolbox help.");
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of("help", "version", "status", "reload", "module");
    }

    private void sendStatus(Invocation invocation) {
        invocation.source().sendPlainMessage(
                "Proxy " + proxy.getVersion().getVersion()
                        + " | Java " + System.getProperty("java.version")
                        + " | loaded modules=" + modules.loadedCount());
        for (String line : modules.statusLines()) {
            invocation.source().sendPlainMessage(" - " + line);
        }
    }

    private void handleModule(Invocation invocation, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sendStatus(invocation);
            return;
        }
        if (args.length < 2) {
            invocation.source().sendPlainMessage(
                    "Usage: /vtoolbox module list|load|unload|reload <value>");
            return;
        }

        String operation = args[0];
        String value = args[1];

        boolean success = switch (operation.toLowerCase()) {
            case "load" -> modules.loadByFileName(value);
            case "unload" -> modules.unload(value);
            case "reload" -> modules.reload(value);
            default -> false;
        };

        invocation.source().sendPlainMessage(
                success ? "Module operation completed." : "Module operation failed.");
    }
}
