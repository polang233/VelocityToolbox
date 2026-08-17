<p align="center">
  <img src="../assets/logo.png" alt="VelocityToolbox" width="168">
</p>

# VelocityToolbox

A [Velocity](https://papermc.io/software/velocity) 4.1+ plugin that hosts resource packs over HTTP and can load, unload, or reload other Velocity plugins at runtime.

[中文 README](../README.md) · [架构说明](ARCHITECTURE.md)

## Features

- **Resource-pack HTTP hosting**: scan a directory of `.zip` files, compute SHA-1, and serve download URLs from this machine.
- **Plugin hot-load**: load / unload / reload other Velocity plugins from `plugins/`.

## Requirements

- Java 25+
- Velocity 4.1.0-SNAPSHOT or a compatible later runtime

## Install

1. Put `VelocityToolbox-*.jar` in Velocity's `plugins` directory.
2. Start the proxy once to generate `plugins/VelocityToolbox/config.yml`.
3. Configure pack hosting and/or use the plugin commands below.

```powershell
.\gradlew.bat build
```

Output: `build/libs/VelocityToolbox-0.1.0-SNAPSHOT.jar`

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
pack-host:
  enabled: true
  bind: 0.0.0.0
  port: 8765
  public-url: ""          # empty on LAN; a player-reachable URL on the internet
  packs-directory: packs  # relative to this plugin's data folder, or absolute
```

Examples for `packs-directory`: `packs`, `../OtherPlugin/packs`, `D:/resourcepacks`, `/var/www/resourcepacks`.

## Plugin hot-load

Operate on Velocity plugin JARs already in `plugins/`. `/vtoolbox reload` reloads pack hosting only.

```text
/vtoolbox plugin list
/vtoolbox plugin load SomePlugin-1.0.jar
/vtoolbox plugin unload someplugin
/vtoolbox plugin reload someplugin
```

- `load` accepts a file name inside `plugins/` only.
- `velocity` and `velocitytoolbox` cannot be loaded or unloaded.
- Unload fails when another loaded plugin has a required dependency on the target.
- If reload fails after unload, the plugin stays unloaded.

Velocity 4.1 has no public load / unload API, so this uses the proxy's own plugin loader. Not every plugin hot-unloads cleanly. See [架构说明](ARCHITECTURE.md). The commands require `velocitytoolbox.admin`; do not grant it to ordinary players.

## Commands

Permission: `velocitytoolbox.admin`. Alias: `/vtb`.

| Command | Description |
|---|---|
| `/vtoolbox help` | Help |
| `/vtoolbox version` | Version, plugin count, pack-host switch |
| `/vtoolbox status` | Proxy / Java / pack origin / loaded plugins |
| `/vtoolbox packs` | URL and SHA-1 for each zip |
| `/vtoolbox reload` | Reload pack hosting |
| `/vtoolbox plugin list` | List loaded plugins |
| `/vtoolbox plugin load <file.jar>` | Load from `plugins/` |
| `/vtoolbox plugin unload <plugin-id>` | Unload |
| `/vtoolbox plugin reload <plugin-id>` | Unload then load |

## Docs

- [架构说明](ARCHITECTURE.md)
- [Chinese README](../README.md)
