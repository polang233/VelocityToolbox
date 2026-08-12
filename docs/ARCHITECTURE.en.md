# VelocityToolbox Architecture

```text
Velocity
└── VelocityToolboxPlugin
    ├── ToolboxCommand
    ├── ModuleManager
    │   ├── URLClassLoader per module
    │   ├── ServiceLoader discovery
    │   └── lifecycle and error isolation
    └── RegistrationScope
        ├── EventManager listeners
        ├── CommandManager commands
        ├── Scheduler tasks
        └── ChannelRegistrar channels
```

## Design choice

The project uses `velocity-api:4.1.0-SNAPSHOT` and manages module resources through public APIs:

- `EventManager`
- `CommandManager`
- `Scheduler`
- `ChannelRegistrar`
- Proxy lifecycle events

Velocity 4.1's `PluginManager` can query plugins and inject JARs into a plugin classpath, but it does not expose a supported external plugin load/unload API. VelocityToolbox therefore does not depend on Velocity internals.

## Why not emulate PlugMan directly?

Loading a complete Velocity plugin would require handling:

- Plugin metadata and dependency ordering.
- Velocity plugin containers.
- Plugin initialization lifecycle.
- Main-class listeners.
- Command, task, and channel cleanup.
- Classloaders and stale code references.
- Dependencies between other plugins.

The module system owns only resources it can track, which keeps the boundary smaller and failure diagnosis clearer.

## Initial security boundary

- Only JARs under `plugins/VelocityToolbox/modules` are loaded.
- Each JAR accepts one `ToolboxModule` implementation.
- Module IDs must match the safe format.
- File names are normalized and restricted to the module directory.
- Every module JAR must be treated as executable code.
- Online module downloads are not supported.
- Module loading is not intended for ordinary players.
