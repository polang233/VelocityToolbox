# VelocityToolbox Module Development

## Goal

VelocityToolbox modules are not official Velocity plugins.

They are hosted by an already loaded VelocityToolbox plugin and loaded/unloaded through an independent `URLClassLoader`. Modules are intended for small, low-dependency features that you control.

## Resource registration

Modules must register runtime resources through `ToolboxContext.registrations()`:

- `registerListener`
- `registerCommand`
- `schedule`
- `registerChannel`

VelocityToolbox cleans these resources when the module is unloaded.

Do not register resources on untracked objects or cache the module instance in static fields.

## Minimal module example

```java
package example;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import io.github.velocitytoolbox.api.ToolboxContext;
import io.github.velocitytoolbox.api.ToolboxModule;

public final class ExampleModule implements ToolboxModule {
    @Override
    public String id() {
        return "example";
    }

    @Override
    public void enable(ToolboxContext context) {
        context.registrations().registerListener(new Listener(context));
        context.logger().info("Example module enabled.");
    }

    @Override
    public void disable() {
        // Close databases, executors, HTTP clients, and other private resources.
    }

    private static final class Listener {
        private final ToolboxContext context;

        private Listener(ToolboxContext context) {
            this.context = context;
        }

        @Subscribe
        public void onServerConnected(ServerConnectedEvent event) {
            context.logger().info("{} connected to {}.",
                    event.getPlayer().getUsername(),
                    event.getServer().getServerInfo().getName());
        }
    }
}
```

## ServiceLoader registration

Create this file inside the module JAR:

```text
src/main/resources/META-INF/services/io.github.velocitytoolbox.api.ToolboxModule
```

Its content is the implementation's fully qualified class name:

```text
example.ExampleModule
```

## Lifecycle

Loading:

1. Create a module-specific classloader.
2. Discover `ToolboxModule` through `ServiceLoader`.
3. Create the module data directory.
4. Call `enable`.
5. Mark the module as loaded.

Unloading:

1. Call `disable`.
2. Cancel tasks registered by the module.
3. Unregister commands.
4. Unregister plugin channels.
5. Unregister module listeners.
6. Close the module classloader.

If `disable()` throws, VelocityToolbox still attempts to complete cleanup.

## Known boundaries

- Module JAR dependencies are not resolved automatically.
- Modules should not cache each other's instances.
- A module cannot assume it can unload another Velocity plugin.
- Leaked threads, futures, static references, or third-party global registrations may keep the old classloader alive.
- Complete external plugin load/unload is not part of the Velocity 4.1 public API.
