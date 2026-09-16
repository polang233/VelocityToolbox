# Modules and permissions

[中文](MODULES.md) · [Overview](README.en.md)

## Plugin management

Use `/vtb plugin list` to list metadata, `inspect id` to inspect dependencies and resources, and `load file.jar`, `unload id` or `reload id` to manage plugins.

Required dependencies block unloading. VTB cleans registered commands, listeners, tasks, identifiable channels and class loaders. Plugins must close their own threads and external connections. Inspection cannot guarantee safe hot updates.

## Resource packs

`pack-host` serves ZIPs and computes SHA-1, with request, concurrency and bandwidth limits. `resource-packs` chooses variants by server, client version and permission, then sends URLs and tracks responses. The modules have independent switches; delivery supports local files and external URLs.

Use `/vtb pack list`, `status player` and `resend player`. A running HTTP listener does not prove public reachability or successful client loading. See [resource packs](RESOURCE_PACKS.en.md).

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
- `velocitytoolbox.command.pack` and `velocitytoolbox.command.pack.<action>` for list, status or resend.
- `velocitytoolbox.command.server` and `velocitytoolbox.command.server.hosts` for entries.

Variant permissions affect pack selection, not command access. The old packs/vhosts commands became pack list/server hosts; update scripts and permissions accordingly.
