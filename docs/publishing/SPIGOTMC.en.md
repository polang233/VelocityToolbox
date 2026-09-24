**This is a Velocity proxy plugin. Install it in your Velocity proxy's plugins directory.**

# VelocityToolBox

Manage Velocity plugins, send custom resource packs, inspect player entry domains, and set client version rules for each backend.

![VelocityToolBox](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/logo-256.png)

[Downloads](https://github.com/polang233/VelocityToolbox/releases/latest) · [Wiki](https://github.com/polang233/VelocityToolbox/wiki/English) · [Source code](https://github.com/polang233/VelocityToolbox) · [Support](https://github.com/polang233/VelocityToolbox/issues)

## Plugin management

Use `/vtb plugin list|inspect|load|unload|reload` to inspect and manage Velocity plugins at runtime, with cleanup reports. Plugins required by other loaded plugins cannot be unloaded.

Restart the proxy when updating permission, protocol or connection plugins. Hot reloading is not suitable for every plugin.

![Loading a plugin](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-plugin-load.png)

![Unloading a plugin and cleanup results](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-plugin-unload.png)

## Custom resource packs and hosting

Set network-wide defaults and assign packs by backend, client version and permission. Hosting and delivery have separate switches, so you can use local ZIP files or an external download service.

- `url: "@filename.zip"`: use a hosted file with an automatically generated URL and SHA-1.
- External HTTP/HTTPS URL: supply the actual file hash. The client downloads directly from that address.
- `url: "@"`: send no pack; no file or hosting is needed.

Clients on 1.20.3+ can stack packs. Older clients receive the first matching complete pack. A selected required pack disconnects the player if declined, failed or timed out.

- `/vtb pack list`: inspect pack definitions and hosted files.
- `/vtb pack status player`: see loading status and why variants match or are skipped.
- `/vtb pack check`: check configuration without applying changes.
- `/vtb pack resend player`: resend packs to one player.
- `/vtb pack resend all`: resend to online players in batches.

![Client resource pack prompt](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-packs.png)

The built-in HTTP host limits requests and concurrent downloads. `public-url` is the download address prefix sent to clients. Leaving it empty selects a local network address; public servers need a reachable IP or domain, with port forwarding or a reverse proxy as needed.

Hosting is public HTTP: anyone with the full URL can download the ZIP. Retry tickets are not authentication, and VTB does not set up port forwarding or HTTPS for you.

[Resource pack guide](https://github.com/polang233/VelocityToolbox/wiki/Resource-Packs-English)

## Backends and entry domains

`/vtb server hosts` groups online players by the domain they used to join and shows ports and latency. Click an entry for player details.

![Players grouped by entry domain](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-vhosts.jpg)

`server-versions` sets per-backend version ranges, allowlists and exclusion lists. It needs no ViaVersion and does not provide protocol translation.

![Client version rules by backend](https://raw.githubusercontent.com/polang233/VelocityToolbox/main/assets/screenshot-server-versions.png)

## Setup and commands

Built for Velocity 4 and Java 25. Put the JAR in the proxy's plugins directory and start the proxy to generate config.yml. Grant administrators `velocitytoolbox.admin`, then run `/vtb help`. `/vtoolbox` is also available.

Version restrictions, HTTP hosting and pack delivery are disabled by default. Replace example filenames and backend names, and remove unused examples before enabling modules.

Use `/vtb info` for module status and `/vtb reload` to reload configuration, language, version rules and resource packs. It does not reload other plugins. Restart the proxy when updating VelocityToolBox itself.

Messages support Simplified Chinese, Traditional Chinese, English and custom MiniMessage language files. Leave `language` empty to follow the system locale; unsupported languages fall back to Simplified Chinese.

[Commands and permissions](https://github.com/polang233/VelocityToolbox/wiki/Modules-English) · [Report an issue](https://github.com/polang233/VelocityToolbox/issues)

[![bStats](https://bstats.org/signatures/velocity/VelocityToolbox.svg)](https://bstats.org/plugin/velocity/VelocityToolbox/33451)
