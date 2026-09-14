<p align="center">
  <img src="../assets/logo.png" alt="VelocityToolbox" width="168">
</p>

# VelocityToolbox

**A Velocity operations toolbox for plugin management, virtual-host diagnostics, per-server client version rules, and resource-pack hosting and delivery.**

[中文 README](../README.md) · [Architecture](ARCHITECTURE.md) · [Issues and ideas](https://github.com/polang233/VelocityToolbox/issues)

![Velocity](https://img.shields.io/badge/Velocity-4.0%2B-654FF0)
![Java](https://img.shields.io/badge/Java-25%2B-E76F00)

## Highlights

- **Restart the proxy less often:** load, unload, or reload Velocity plugins from `plugins/`; inspect risk before the operation and receive a cleanup report afterward.
- **Debug multi-domain networks:** `/vtb server hosts` groups online players by the address they used to join; click an entry to expand names and pings, then hover a player for full details.
- **Host multiple packs locally:** serve ZIPs, calculate SHA-1, and deliver packs by server, client version and permission. Hosting and delivery default to disabled.

<p align="center">
  <img src="../assets/screenshot-vhosts.jpg" alt="Players grouped by virtual host" width="720">
</p>
<p align="center"><sub>Player counts and ping grouped by the domain used to join</sub></p>

<p align="center">
  <img src="../assets/screenshot-plugin-load.png" alt="Hot-load a plugin" width="720">
</p>
<p align="center">
  <img src="../assets/screenshot-plugin-unload.png" alt="Hot-unload a plugin" width="720">
</p>
<p align="center"><sub>Load or unload a plugin at runtime, with a cleanup report</sub></p>

<p align="center">
  <img src="../assets/screenshot-packs.png" alt="Resource pack download prompt" width="720">
</p>
<p align="center"><sub>Players see the standard pack prompt after delivery is configured and enabled</sub></p>

## Requirements and installation

- Velocity 4.0+
- Java 25+

1. Download the JAR from [Releases](https://github.com/polang233/VelocityToolbox/releases) and place it in Velocity's `plugins/` directory.
2. Fully start the proxy once to generate `plugins/VelocityToolbox/config.yml`.
3. Grant administrators `velocitytoolbox.admin`, or use the fine-grained permissions below, then run `/vtoolbox help` or `/vtb help`.

Build from source with `./gradlew build` or `.\gradlew.bat build` on Windows.

## Commands

The main command has the `/vtb` alias. `velocitytoolbox.admin` remains a backward-compatible all-access permission. Read-only commands stay quiet in the console; plugin load/unload/reload and configuration reload emit concise status messages.

| Command | Purpose |
| --- | --- |
| `/vtoolbox help` | Show help |
| `/vtoolbox info` | Plugin, proxy, Java, plugin-count, server version rules, and pack-host summary |
| `/vtoolbox pack list` | List configured packs and hosted files |
| `/vtoolbox pack status player` | Show delivery and load status |
| `/vtoolbox pack resend player` | Resend the current server's packs |
| `/vtoolbox server hosts` | Group players by entry domain/port and player count; click an entry for names and pings |
| `/vtoolbox reload` | Reload language, configuration, server version rules, and pack hosting |
| `/vtoolbox plugin list` | Names, versions, and authors; hover for full metadata |
| `/vtoolbox plugin inspect plugin-id` | Four-section metadata, dependency, runtime, and risk report |
| `/vtoolbox plugin load file.jar` | Load a plugin from `plugins/` |
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

Pack commands require `velocitytoolbox.command.pack` plus the respective `velocitytoolbox.command.pack.list`, `velocitytoolbox.command.pack.status` or `velocitytoolbox.command.pack.resend` permission. Server queries require `velocitytoolbox.command.server` and `velocitytoolbox.command.server.hosts`.

Since 1.3.0, the old `packs` and `vhosts` commands have moved to `pack list` and `server hosts`. Update their old action permissions; administrator access remains valid.

For example, inspection-only access requires `velocitytoolbox.command`, `velocitytoolbox.command.plugin`, and `velocitytoolbox.command.plugin.inspect`. Help output only lists commands the source can use.

## Per-server client version rules

![Per-server client version rules](../assets/screenshot-server-versions.png)

Enable this optional module in the `server-versions` section of `plugins/VelocityToolbox/config.yml`. It uses Velocity's client protocol API and does not require ViaVersion on the proxy or backend servers.

```yaml
server-versions:
  enabled: true
  servers:
    survival:
      allow: ["1.12.2", "1.20.1"]
    minigame:
      min: "1.18"
      max: max
      deny: ["1.20.2"]
```

The module defaults to disabled. Server names match `velocity.toml`, ignoring case. Unlisted servers have no restriction. `min` and `max` are inclusive; omitted bounds use Velocity's supported minimum and maximum. A nonempty `allow` list adds a whitelist, while `deny` always takes precedence. Both the range and whitelist must match. Quote version names; known integer protocol IDs such as `340` also work.

Use `/vtoolbox reload` or `/velocity reload` to apply changes and `/vtoolbox info` to check the module status and each server's active version range, allowlist, and blocklist, sorted by server name. A rejected server switch keeps the player on the current server; an initial rejection disconnects with the reason. There is no automatic fallback server selection. Invalid reloads retain the previous rules. If the first load fails, connections are blocked until you repair the file or disable the module and reload from the console.

Versions sharing a protocol, such as 1.20 and 1.20.1, match together. Displayed lower bounds use the earliest matching version and upper bounds use the latest. Lists and client versions show compact ranges such as `1.18～1.18.1`; hover an info rule or a denied-switch chat message for protocol IDs. Geyser connections are checked using Geyser's Java protocol. These rules do not translate protocols or expand Velocity's supported versions. Messages can be customized under `server-versions` in the language files. Routing plugins must finish redirecting before this module's `LAST` listener runs.

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
  delay: 1
  timeout: 60
  required: false
  prompt: "<aqua>Please load the server resource pack."
  packs:
    base:
      file: base.zip
  default: [base]
  servers: {}
```

Place your ZIP in the configured directory, set a reachable URL, then run `/vtb reload`. Local `file` entries reuse the host URL and SHA-1; external entries require `url` and a real `sha1`. Server `packs` replace `default`; `variants` match top to bottom using `versions.min/max/allow/deny` and optional `permission`.

Clients on 1.20.3+ stack packs in order. Older clients use the server's `legacy` full pack, or the first compatible entry. `delay` and `timeout` are seconds. `required` and `prompt` can be overridden per server. Required-pack rejection, incompatibility, missing permission, failure or timeout disconnects the player; optional failures only show a message. A client acceptance is not a successful load.

Backend packs coexist. VTB removes only its own modern-client packs. Legacy clients keep the backend's latest pack until switching servers or a manual resend; individual removal is unavailable, so a pack may remain until replaced or disconnected.

Invalid reloads retain the previous delivery rules. The host keeps its listener when bind/port are unchanged; a failed listener change attempts to restore the old service. If restoration fails, new local offers are suspended. VTB does not configure port forwarding or HTTPS. The legacy export snippet remains available for hosting-only setups. Disable the previous delivery plugin when enabling VTB delivery.

See [the configuration reference](RESOURCE_PACKS.md) for examples and migration notes.

## Runtime plugin safety

Velocity 4.0+ has no public plugin load/unload API. VelocityToolbox refuses to unload targets that are still required by another loaded plugin and attempts to remove listeners, tasks, commands, plugin-message channels, executors, and class loaders. It still cannot guarantee that every third-party plugin is safe to hot-unload.

Small utility plugins are the best candidates after testing. Fully restart the proxy after updating permission, protocol/packet, connection-management, or large-cache plugins. See the [architecture notes](ARCHITECTURE.md) for the implementation boundary.

## Language and support

`language` left empty follows the server's system language and falls back to Chinese when no matching language file exists. Set it to `zh_cn`, `en_us`, or a custom file under `lang/`. Standard files are `lang/zh_cn.yml` and `lang/en_us.yml`. Player-facing messages use MiniMessage; startup, pack, and critical plugin-operation console messages use color-coded Adventure components when supported. Help commands use a lighter orange than the prefix. `/vtoolbox reload` reloads the language.

Bug reports and feature ideas are welcome on [GitHub Issues](https://github.com/polang233/VelocityToolbox/issues), especially ideas around automatic rollback, multi-proxy operations, virtual-host diagnostics, and pack availability checks.

If VelocityToolbox saves you a proxy restart, consider leaving a [Star🌟](https://github.com/polang233/VelocityToolbox).

## Usage statistics

[![bStats](https://bstats.org/signatures/velocity/VelocityToolbox.svg)](https://bstats.org/plugin/velocity/VelocityToolbox/33451)
