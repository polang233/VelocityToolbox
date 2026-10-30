# Modules and permissions

[中文](MODULES.md) · [Overview](README.en.md)

## Plugin management

Use `/vtb plugin list` to list metadata, `inspect id` to inspect dependencies and resources, and `load name`, `unload id` or `reload id` to manage plugins.

`load` accepts names without `.jar` or partial names, such as `/vtb plugin load MyPlugin`. Multiple matches are listed for selection; Tab completion is also available.

Required dependencies block unloading. VTB cleans registered commands, listeners, tasks, identifiable channels and class loaders. Plugins must close their own threads and external connections. Inspection cannot guarantee safe hot updates.

## Resource packs

`pack-host` serves ZIPs with rate limits; `resource-packs` picks packs by server, version and permission and sends them. Each has its own switch. See [Resource packs](RESOURCE_PACKS.en.md).

- `/vtb pack list`: pack sources and conditions, hosted files and HTTP counters.
- `/vtb pack check`: check the configuration, hashes and ZIP metadata on disk without applying changes; failures name the config path.
- `/vtb pack status player`: current server and load status, plus version/permission matches and mismatch reasons.
- `/vtb pack resend player|all`: resend with current rules; all runs at most five players per second, repeated runs do not stack, and completion only means scheduled.
- `/vtb pack remove player|all`: withdraw VTB packs and pause delivery on the current server. Reload does not resume; a server switch or resend does. Clients below 1.20.3 must reconnect to clear loaded packs.

A running listener does not prove public reachability or that clients loaded the pack.

## Servers and entries

`/vtb server hosts [index]` groups players by the domain and port they used to join. Click to expand players and latency; hover for connection details. Entry information comes from clients and does not change DNS or routing.

`server-versions` applies min/max/allow/deny to the target backend. A denied switch keeps the current backend; a denied initial join disconnects with the reason. Invalid reloads keep previous rules. Invalid first loading blocks backend connections until fixed.

## Status and configuration

`/vtb info` groups general information, plugins, servers and resource packs. Hosting and delivery have separate status lines. Startup and reload use the same hierarchy.

`/vtb reload` and `/velocity reload` reload configuration, language and rules. Modules attempt reload independently. Existing configuration/language files are preserved; missing translations fall back to bundled keys.

## Permissions

`velocitytoolbox.admin` grants all commands. Otherwise grant `velocitytoolbox.command` plus:

- `velocitytoolbox.command.info` or `velocitytoolbox.command.reload` for general actions.
- `velocitytoolbox.command.plugin` and `velocitytoolbox.command.plugin.<action>` for list, inspect, load, unload or reload.
- `velocitytoolbox.command.pack` and `velocitytoolbox.command.pack.<action>` for list, check, status, resend or remove. Bulk resend also needs `velocitytoolbox.command.pack.resend.all`; bulk removal needs `velocitytoolbox.command.pack.remove.all`. Administrator access includes both.
- `velocitytoolbox.command.server` and `velocitytoolbox.command.server.hosts` for entries.

Variant permissions affect pack selection, not command access.
