<p align="center">
  <img src="../assets/logo.png" alt="VelocityToolbox" width="168">
</p>

# VelocityToolbox

A [Velocity](https://papermc.io/software/velocity) 4.0+ plugin that hosts resource packs over HTTP and can load, unload, or reload other Velocity plugins at runtime.

[中文 README](../README.md) · [架构说明](ARCHITECTURE.md)

## Features

- **Resource-pack HTTP hosting**: scan a directory of `.zip` files, compute SHA-1, and serve download URLs from this machine.
- **Plugin management**: load / unload / reload other Velocity plugins from `plugins/`.

## Requirements

- Java 25+
- Velocity 4.0 or later

## Install

1. Put `VelocityToolbox-*.jar` in Velocity's `plugins` directory.
2. Start the proxy once to generate `plugins/VelocityToolbox/config.yml`.
3. Configure pack hosting and/or use the plugin commands below.

```powershell
.\gradlew.bat build
```

Output: `build/libs/VelocityToolbox-1.0.0.jar`

## Resource-pack hosting

Minecraft clients download packs from an HTTP URL. This plugin only starts an HTTP server **on the proxy host** and turns local zip files into that URL. Which player gets which pack is still decided by plugins such as [VelocityResourcepacks](https://modrinth.com/plugin/velocityresourcepacks).

This plugin does **not** map ports, register DNS, or terminate HTTPS. It only provides a LAN-reachable HTTP service. For public players, you must expose `port` yourself (firewall, router, cloud security group, or reverse proxy), then put that reachable address in `public-url`.

| Key | Role | Typical value |
|---|---|---|
| `bind` | Listen address. `0.0.0.0` accepts on every NIC | `0.0.0.0` |
| `port` | Listen port. Open it yourself if the internet must reach it | `8765` |
| `public-url` | Origin written into client download URLs | empty on LAN; a public URL on the internet |

Download URLs look like `{public-url}/packs/{file}`. Leave `public-url` empty to use the first detected LAN IPv4. Do not use `127.0.0.1` unless players and the proxy share one machine, and do not use `0.0.0.0`.

1. Put `.zip` files in `pack-host.packs-directory` (default: `plugins/VelocityToolbox/packs/`).
2. Set `bind` / `port` / `public-url` as above.
3. The startup log or `/vtoolbox packs` lists each pack's URL and SHA-1.
4. Merge `plugins/VelocityToolbox/velocityresourcepacks-snippet.yml` into VelocityResourcepacks' `config.yml`.
5. After changing zips, the path, or `public-url`, run `/vtoolbox reload`.

File names may include Unicode and spaces, but not `/`, `\`, or `..`.

```yaml
language: en          # zh / en, or a custom file under lang/

pack-host:
  enabled: true
  bind: 0.0.0.0
  port: 8765
  public-url: ""          # empty on LAN; a player-reachable URL on the internet
  packs-directory: packs  # relative to this plugin's data folder, or absolute
```

Examples for `packs-directory`: `packs`, `../OtherPlugin/packs`, `D:/resourcepacks`, `/var/www/resourcepacks`.

## Language

`language` in `config.yml` defaults to `zh`. Set `en` for English, or any file name under `lang/` without `.yml`. The first start copies `lang/zh.yml` and `lang/en.yml` into the data folder; edit those to change wording and colors. `/vtoolbox reload` reloads language, config, and pack hosting.

Player-facing messages use Adventure MiniMessage. Defaults are accent `#FF6600` and body `#CCFFFF`. RGB works on 1.16+ clients; the console depends on the terminal.

## Plugin management

Operate on Velocity plugin JARs already in `plugins/`. `/vtoolbox reload` reloads this plugin's language, config, and pack hosting only.

```text
/vtoolbox plugin list
/vtoolbox plugin load SomePlugin-1.0.jar
/vtoolbox plugin unload someplugin
/vtoolbox plugin reload someplugin
```

- `load` accepts a file name inside `plugins/` only.
- `velocity` and `velocitytoolbox` cannot be loaded or unloaded.
- Unload fails when another loaded plugin has a required dependency on the target (including IDs it `provides`).
- If reload fails after unload, the plugin stays unloaded.
- Unload tries to strip listeners, tasks, commands, custom plugin-message channels, the plugin executor, and the class loader. On failure the chat shows the exception type and message, and points you to the proxy log.

**Being able to unload a plugin does not mean you should hot-unload it.** Small plugins with a few commands and listeners usually come off cleanly. Do not make a habit of hot-unloading large plugins (LuckPerms, protocol/packet hooks, permission providers, anything holding player connections or a big in-memory cache). Velocity does not track plugin-message channel ownership, other plugins may still hold old classes, and memory is not guaranteed to be reclaimed. Restart the proxy after replacing those JARs.

Velocity 4.0+ has no public load / unload API, so this uses the proxy's own plugin loader. See [架构说明](ARCHITECTURE.md). The commands require `velocitytoolbox.admin`; do not grant it to ordinary players.

## Commands

Permission: `velocitytoolbox.admin`. Alias: `/vtb`.

| Command | Description |
|---|---|
| `/vtoolbox help` | Help |
| `/vtoolbox version` | Version, plugin count, pack-host switch |
| `/vtoolbox status` | Proxy / Java / pack origin / loaded plugins |
| `/vtoolbox packs` | URL and SHA-1 for each zip |
| `/vtoolbox vhosts` | List online players by the virtual host they joined through (the address typed in the client, which is what MiniMOTD uses to pick a MOTD), plus their source IP |
| `/vtoolbox reload` | Reload language, config, and pack hosting |
| `/vtoolbox plugin list` | List loaded plugins |
| `/vtoolbox plugin load <file.jar>` | Load from `plugins/` |
| `/vtoolbox plugin unload <plugin-id>` | Unload |
| `/vtoolbox plugin reload <plugin-id>` | Unload then load |

## Statistics

This plugin uses [bStats](https://bstats.org/plugin/velocity/VelocityToolbox/33451) to collect anonymous usage data. Set `enabled` to `false` in `plugins/bStats/config.txt` to opt out.

[![bStats](https://bstats.org/signatures/velocity/VelocityToolbox.svg)](https://bstats.org/plugin/velocity/VelocityToolbox/33451)

## Docs

- [架构说明](ARCHITECTURE.md)
- [Chinese README](../README.md)

---

*If this plugin is useful, please give it a [Star⭐](https://github.com/polang233/VelocityToolbox).*
