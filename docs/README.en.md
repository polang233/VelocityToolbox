<p align="center">
  <img src="../assets/logo.png" alt="VelocityToolbox" width="168">
</p>

# VelocityToolbox

**A compact Velocity operations toolbox for runtime plugin management, virtual-host diagnostics, and optional resource-pack hosting.**

[中文 README](../README.md) · [Modrinth description](MODRINTH.md) · [Architecture](ARCHITECTURE.md) · [Issues and ideas](https://github.com/polang233/VelocityToolbox/issues)

![Velocity](https://img.shields.io/badge/Velocity-4.0%2B-654FF0)
![Java](https://img.shields.io/badge/Java-25%2B-E76F00)

## Highlights

- **Restart the proxy less often:** load, unload, or reload Velocity plugins from `plugins/`; inspect risk before the operation and receive a cleanup report afterward.
- **Debug multi-domain networks:** `/vtoolbox vhosts` groups online players by the address they used to join; click an entry to expand names and pings, then hover a player for full details.
- **Host multiple packs locally:** serve any number of `.zip` files, calculate SHA-1 hashes, and generate a multi-pack VelocityResourcepacks snippet.
- **Conservative defaults:** the HTTP pack host is disabled by default; layered permissions, concise critical-operation console messages, and bilingual output are built in.

## Requirements and installation

- Velocity 4.0+
- Java 25+

1. Download the JAR from [Releases](https://github.com/polang233/VelocityToolbox/releases) and place it in Velocity's `plugins/` directory.
2. Fully start the proxy once to generate `plugins/VelocityToolbox/config.yml`.
3. Grant administrators `velocitytoolbox.admin`, or use the fine-grained permissions below, then run `/vtoolbox help`.

Build from source with `./gradlew build` or `.\gradlew.bat build` on Windows.

## Commands

The main command has the `/vtb` alias. `velocitytoolbox.admin` remains a backward-compatible all-access permission. Read-only commands stay quiet in the console; plugin load/unload/reload and configuration reload emit concise status messages.

| Command | Purpose |
|---|---|
| `/vtoolbox help` | Show help |
| `/vtoolbox info` | Show plugin, proxy, Java, plugin-count, and pack-host summary |
| `/vtoolbox packs` | List resource-pack URLs and SHA-1 hashes |
| `/vtoolbox vhosts` | Group players by entry domain/port and player count; click an entry for names and pings, hover for details |
| `/vtoolbox reload` | Reload language, configuration, and pack hosting |
| `/vtoolbox plugin list` | Show names, versions, and authors; hover for full metadata |
| `/vtoolbox plugin inspect <plugin-id>` | Four-section metadata, dependency, runtime, and risk report |
| `/vtoolbox plugin load <file.jar>` | Load a plugin from `plugins/` |
| `/vtoolbox plugin unload <plugin-id>` | Unload a plugin |
| `/vtoolbox plugin reload <plugin-id>` | Unload and load a plugin again |

### Fine-grained permissions

Without `velocitytoolbox.admin`, grant the base permission `velocitytoolbox.command` plus the matching subcommand permission:

- General commands: `velocitytoolbox.command.info`, `velocitytoolbox.command.packs`, `velocitytoolbox.command.vhosts`, `velocitytoolbox.command.reload`
- Plugin-management parent: `velocitytoolbox.command.plugin`
- Plugin actions: `velocitytoolbox.command.plugin.list`, `velocitytoolbox.command.plugin.inspect`, `velocitytoolbox.command.plugin.load`, `velocitytoolbox.command.plugin.unload`, `velocitytoolbox.command.plugin.reload`

For example, inspection-only access requires `velocitytoolbox.command`, `velocitytoolbox.command.plugin`, and `velocitytoolbox.command.plugin.inspect`. Help output only lists commands the source can use.

## Optional resource-pack hosting

Pack hosting is disabled by default. Once enabled, VelocityToolbox runs an HTTP server on the proxy machine, scans any number of ZIP files, and writes `velocityresourcepacks-snippet.yml`. Every ZIP receives its own URL, SHA-1, and `local-path`. [VelocityResourcepacks](https://modrinth.com/plugin/velocityresourcepacks) decides which packs are sent to each player; VelocityToolbox does not send packs itself.

```yaml
pack-host:
  enabled: false
  bind: 0.0.0.0
  port: 8765
  public-url: ""          # set a player-reachable URL for internet use
  packs-directory: packs  # defaults to plugins/VelocityToolbox/packs
```

Place `.zip` files in the pack directory, set `enabled: true`, configure firewall/reverse-proxy access and `public-url` when needed, then run `/vtoolbox reload`. Merge the generated snippet into VelocityResourcepacks.

The generated `global.packs` list contains every ZIP in file-name order. Minecraft 1.20.3+ clients can stack all listed packs; older clients use only the first entry. This field requires VelocityResourcepacks 1.9.0+. Remove entries that should not be global; use `restricted` / `permission` for player-specific combinations, or configure per-server and per-version assignments in VelocityResourcepacks.

VelocityToolbox does not configure port forwarding, DNS, or HTTPS. When `public-url` is empty, it attempts to use the first detected LAN IPv4 address. Never use `0.0.0.0` as a player-facing download address.

## Runtime plugin safety

Velocity 4.0+ has no public plugin load/unload API. VelocityToolbox refuses to unload targets that are still required by another loaded plugin and attempts to remove listeners, tasks, commands, plugin-message channels, executors, and class loaders. It still cannot guarantee that every third-party plugin is safe to hot-unload.

Small utility plugins are the best candidates after testing. Fully restart the proxy after updating permission, protocol/packet, connection-management, or large-cache plugins. See the [architecture notes](ARCHITECTURE.md) for the implementation boundary.

## Language, metrics, and support

`language` left empty follows the server's system language and falls back to Chinese when no matching language file exists. Set it to `zh_cn`, `en_us`, or a custom file under `lang/`. Standard files are `lang/zh_cn.yml` and `lang/en_us.yml`. Player-facing messages use MiniMessage; startup, pack, and critical plugin-operation console messages use color-coded Adventure components when supported. Help commands use a lighter orange than the prefix. `/vtoolbox reload` reloads the language.

Anonymous usage statistics are provided by [bStats](https://bstats.org/plugin/velocity/VelocityToolbox/33451) and can be disabled in `plugins/bStats/config.txt`.

[![bStats](https://bstats.org/signatures/velocity/VelocityToolbox.svg)](https://bstats.org/plugin/velocity/VelocityToolbox/33451)

Bug reports and feature ideas are welcome on [GitHub Issues](https://github.com/polang233/VelocityToolbox/issues), especially ideas around automatic rollback, multi-proxy operations, virtual-host diagnostics, and pack availability checks.

## License

Copyright (C) 2026 Polang.

VelocityToolbox is licensed under the [GNU General Public License v3.0 only](../LICENSE).

If VelocityToolbox saves you a proxy restart, consider leaving a [Star ⭐](https://github.com/polang233/VelocityToolbox).
