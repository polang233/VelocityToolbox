# VelocityToolbox

A public-API-first toolbox and reloadable module host for Velocity.

## Current status

Version: `0.1.0-SNAPSHOT`

The first version provides:

- A Velocity plugin built against `velocity-api:4.1.0-SNAPSHOT`.
- A reloadable module directory at `plugins/VelocityToolbox/modules`.
- Module lifecycle methods: `enable` and `disable`.
- Automatic cleanup of module-owned listeners, commands, scheduled tasks, and plugin channels.
- Commands for status, module loading, unloading, and reloading.
- A public module API for small self-owned proxy features.

The public Velocity 4.1 API exposes plugin discovery and classpath injection, but not a supported external plugin load/unload operation. VelocityToolbox therefore treats reloadable modules as the stable extension boundary and does not depend on Velocity internals.

## Requirements

- Java 25 or newer for the current `4.1.0-SNAPSHOT` build. This snapshot is currently published with JVM 25 bytecode.
- Velocity 4.1.0-SNAPSHOT or a compatible later API/runtime.
- A test proxy before production use.

## Build

The project includes a Gradle Wrapper:

```powershell
.\gradlew.bat build
```

The output JAR is:

```text
build/libs/VelocityToolbox-0.1.0-SNAPSHOT.jar
```

## Installation

1. Build the project.
2. Copy the output JAR into Velocity's `plugins` directory.
3. Start the proxy once.
4. Put module JARs into `plugins/VelocityToolbox/modules`.
5. Use the module commands.

## Commands

All commands require:

```text
velocitytoolbox.admin
```

Available commands:

```text
/vtoolbox help
/vtoolbox version
/vtoolbox status
/vtoolbox reload
/vtoolbox module list
/vtoolbox module load <file.jar>
/vtoolbox module unload <module-id>
/vtoolbox module reload <module-id>
```

`/vtoolbox reload` currently acknowledges a proxy configuration reload request. Code reload is explicit through `/vtoolbox module reload <module-id>`.

## Module JAR contract

A module JAR must:

1. Implement `io.github.velocitytoolbox.api.ToolboxModule`.
2. Contain the following ServiceLoader registration file:

   ```text
   META-INF/services/io.github.velocitytoolbox.api.ToolboxModule
   ```

3. Expose one module implementation per JAR.
4. Return a stable lowercase ID matching:

   ```text
   [a-z][a-z0-9-_]{0,63}
   ```

5. Register runtime resources through `ToolboxContext.registrations()`.

Example module:

```java
public final class HelloModule implements ToolboxModule {
    @Override
    public String id() {
        return "hello";
    }

    @Override
    public void enable(ToolboxContext context) {
        context.registrations().registerListener(new HelloListener(context.logger()));
    }

    @Override
    public void disable() {
        // Close module-owned external resources here.
    }
}
```

## Reload rules

A module must release everything it owns in `disable()`, including:

- Database connections.
- Executors, threads, and thread pools.
- Scheduled tasks.
- Event listeners.
- Commands.
- Plugin messaging channels.
- References to proxy objects or other modules.

VelocityToolbox automatically cleans resources registered through `RegistrationScope`, but it cannot clean arbitrary resources created directly by a module. If a module leaks threads, futures, static references, or third-party global registrations, the old classloader may remain reachable.

The first version targets self-owned, small modules and test/development workflows. It is not a complete safe hot-unloader for arbitrary Velocity plugins.

## Security

Do not give `velocitytoolbox.admin` to ordinary players. Loading a module JAR executes arbitrary code inside the proxy process.

Only load JARs produced by your own build pipeline or reviewed source.

## Roadmap

- Separate published `velocitytoolbox-api` artifact.
- Module metadata and dependency declarations.
- Better command suggestions.
- Module health state and reload transaction reporting.
- Optional module file watcher.
- Resource-pack module.
- Cross-server event and routing modules.
- An experimental adapter for Velocity internal plugin loading, only if a stable public API remains unavailable.

## License

No license has been selected yet. Choose and add a clear license before publishing publicly.
