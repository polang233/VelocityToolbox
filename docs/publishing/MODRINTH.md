<p align="center">
  <img src="https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/logo.png" alt="VelocityToolbox logo" width="168">
</p>

# VelocityToolbox

**Hot plugin management, custom resource pack delivery and hosting, entry-domain diagnostics, and per-server client version rules for Velocity networks.**

Load or reload proxy plugins, choose resource packs for the whole network or individual backends, and check player connections from one command. Packs can come from VTB hosting or external URLs, with variants selected by client version and permission.

[Source code](https://github.com/polang233/VelocityToolbox) · [Issue tracker](https://github.com/polang233/VelocityToolbox/issues) · [Chinese documentation](https://github.com/polang233/VelocityToolbox/blob/main/README.md) · [Architecture notes](https://github.com/polang233/VelocityToolbox/blob/main/docs/maintainer/ARCHITECTURE.md)

![Velocity](https://img.shields.io/badge/Velocity-4.0%2B-654FF0)
![Java](https://img.shields.io/badge/Java-25%2B-E76F00)

## Features

- Load, unload, and reload plugin JARs already inside Velocity's `plugins/` directory
- Set network-wide default resource packs and choose variants by backend, client version and permission
- Use hosted ZIPs with automatic hashes or external URLs; stack packs on 1.20.3+ clients
- Inspect a loaded plugin's dependencies, registered runtime resources, and unload risk without changing its state
- Refuse unloads when another loaded plugin declares a required dependency on the target
- Report cleanup of listeners, tasks, commands, plugin-message channels, executors, and class loaders
- Group online players by the virtual host they joined through, including source IPs for live diagnostics
- Set per-server client version ranges, allowlists, and blocklists using Velocity's protocol API, without ViaVersion
- Use backward-compatible administrator access or layered base, parent, and action permissions
- Keep read-only commands quiet while logging concise plugin and configuration operations
- Use color-coded Adventure console output for startup, pack status, and critical plugin operations
- Reload language, configuration, server version rules, and pack hosting without reloading other plugins
- Built-in Simplified Chinese, Traditional Chinese and English messages with MiniMessage formatting

The resource-pack HTTP server is **disabled by default** and listens only after you explicitly enable it.

<p align="center">
  <img src="https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-plugin-load.png" alt="Hot-load a plugin" width="720">
</p>
<p align="center">
  <img src="https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-plugin-unload.png" alt="Hot-unload a plugin" width="720">
</p>
<p align="center"><sub>Load or unload a plugin at runtime, with a cleanup report</sub></p>

<p align="center">
  <img src="https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-packs.png" alt="Resource pack download prompt" width="720">
</p>
<p align="center"><sub>Players see the standard pack prompt after delivery is configured and enabled</sub></p>

<p align="center">
  <img src="https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-vhosts.jpg" alt="Players grouped by virtual host" width="720">
</p>
<p align="center"><sub>Player counts and ping grouped by the domain used to join</sub></p>

## Requirements

- **Proxy:** Velocity 4.0 or newer
- **Java:** 25 or newer
- **Dependencies:** none

## Installation

1. Download the latest file from the **Versions** tab.
2. Place the JAR in Velocity's `plugins/` directory.
3. Fully start the proxy once to generate `plugins/VelocityToolbox/config.yml`.
4. Grant trusted administrators `velocitytoolbox.admin`.
5. Run `/vtoolbox help` or `/vtb help`.

## Commands

The main command alias is `/vtb`. `velocitytoolbox.admin` remains a backward-compatible all-access permission. Read-only commands stay quiet in the console; plugin load/unload/reload and configuration reload emit concise status messages.

| Command | Purpose |
| --- | --- |
| `/vtoolbox help` | Show help |
| `/vtoolbox info` | Plugin, proxy, Java, plugin-count, server version rules, and pack-host summary |
| `/vtoolbox pack list` | List hosted pack URLs and SHA-1 hashes |
| `/vtoolbox server hosts` | Group players by entry domain/port and player count; click an entry for names and pings |
| `/vtoolbox reload` | Reload language, configuration, server version rules, and pack hosting |
| `/vtoolbox plugin list` | Names, versions, and authors; hover for full metadata |
| `/vtoolbox plugin inspect plugin-id` | Four-section metadata, dependency, runtime, and risk report |
| `/vtoolbox plugin load file.jar` | Load a JAR from `plugins/` |
| `/vtoolbox plugin unload plugin-id` | Unload a plugin |
| `/vtoolbox plugin reload plugin-id` | Unload and load a plugin again |

### Fine-grained permissions

Without `velocitytoolbox.admin`, grant the base permission `velocitytoolbox.command`, then the matching subcommand permission.

General commands:

- `velocitytoolbox.command.info`
- `velocitytoolbox.command.pack.list`
- `velocitytoolbox.command.server.hosts`
- `velocitytoolbox.command.reload`

Plugin-management parent:

- `velocitytoolbox.command.plugin`

Plugin actions:

- `velocitytoolbox.command.plugin.list`
- `velocitytoolbox.command.plugin.inspect`
- `velocitytoolbox.command.plugin.load`
- `velocitytoolbox.command.plugin.unload`
- `velocitytoolbox.command.plugin.reload`

For example, inspection-only access requires `velocitytoolbox.command`, `velocitytoolbox.command.plugin`, and `velocitytoolbox.command.plugin.inspect`. Help output only lists commands the source can use.

## Resource pack hosting and delivery

`pack-host` serves local ZIPs over HTTP. `resource-packs` selects and sends packs to players. Both default to disabled; external URLs work without the local host. Scanned ZIPs are only sent when a delivery rule references them.

```yaml
pack-host:
  enabled: true
  bind: 0.0.0.0
  port: 8765
  public-url: "https://packs.example.com"
  packs-directory: packs

resource-packs:
  enabled: true
  settings:
    delay: 3
    timeout: 60
    required: false
    prompt: "<#CCFFFF>Please load the server resource pack."
  packs:
    survival:
      - url: "@survival.zip"
        required: false
  servers:
    default:
      packs: [survival]
```

Place survival.zip in the hosting directory. public-url is the client download address prefix. Leaving it empty selects a LAN address; public servers need a reachable address, and HTTPS requires a reverse proxy.

`url: "@"` sends no resource pack and needs no hosting; `@filename.zip` uses a hosted file. Hosted files calculate hashes automatically. External URLs need a real `hash` for caching, update detection and download checks. Run `/vtb reload` after changes.

Clients on 1.20.3+ stack packs in order; older clients receive the first matching complete pack. Server assignments replace `servers.default.packs`. Variants inherit or override `settings.required/prompt`. Unmatched packs are skipped; selected required packs disconnect on rejection, failure or timeout.

Backend packs coexist. VTB removes only its own modern-client packs. Legacy clients keep the backend's latest pack until switching servers or a manual resend; individual removal is unavailable, so a pack may remain until replaced or disconnected.

Invalid reloads keep the previous rules. Set a reachable public-url and configure any port forwarding or HTTPS separately. Disable the previous delivery plugin when enabling VTB delivery.

See [resource pack configuration](https://github.com/polang233/VelocityToolbox/wiki/Resource-Packs-English) for examples.

Pack actions require the pack module permission and the respective list/status/resend action permission. Server hosts requires the server module permission and its hosts action. See [commands and permissions](https://github.com/polang233/VelocityToolbox/wiki/Modules-English).

## Per-server client version rules

![Per-server client version rules](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-server-versions.png)

Configure version ranges, allowlists, and blocklists under `server-versions` in `config.yml`. The module is disabled by default and uses Velocity's protocol API without ViaVersion. `/vtoolbox info` lists the active rules for each server, and `/vtoolbox reload` applies changes. Versions sharing a protocol are displayed as compact ranges; hover a rule for protocol IDs.

See the [configuration guide](https://github.com/polang233/VelocityToolbox/blob/main/docs/SERVER_VERSIONS.md) for examples.

## Important hot-reload note

Velocity does not provide a public plugin load/unload API. VelocityToolbox performs dependency checks and extensive cleanup, but no tool can guarantee safe hot-unloading for every third-party plugin.

Small, self-contained utility plugins are the best candidates after testing. Permission systems, protocol/packet plugins, connection managers, and plugins with large in-memory state should still be updated with a full proxy restart.

## Support

Bug reports and feature suggestions are welcome on the [GitHub issue tracker](https://github.com/polang233/VelocityToolbox/issues).

If VelocityToolbox saves you a proxy restart, consider giving the project a [Star🌟](https://github.com/polang233/VelocityToolbox).

## Usage statistics

[![bStats](https://bstats.org/signatures/velocity/VelocityToolbox.svg)](https://bstats.org/plugin/velocity/VelocityToolbox/33451)
