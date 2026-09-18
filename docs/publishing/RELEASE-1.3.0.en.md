You can now send custom resource packs across the network, with defaults for all backends and variants selected by server, client version and permission. Configure `resource-packs` in `config.yml`. See the [resource pack guide](https://github.com/polang233/VelocityToolbox/wiki/Resource-Packs-English) and [default configuration](https://github.com/polang233/VelocityToolbox/wiki/Configuration).

- Choose required or optional packs, add prompts, set loading timeouts, check player status and resend packs. Clients on 1.20.3+ can stack packs. Reloading updates online players without resending unchanged packs.
- Use `@filename.zip` for hosted files with automatic SHA-1, an external URL with the actual hash, or `@` to send no pack. Hosting adds request limits, concurrency limits, transfer deadlines, bandwidth controls and trusted proxy settings under `pack-host.security`.
- Commands, help and status are grouped by module. Packs and backend restrictions share version rules. `/vtb packs` and `/vtb vhosts` are now `/vtb pack list` and `/vtb server hosts`; update scripts and granular permissions as needed.
- Added Traditional Chinese and revised the Simplified Chinese and English text and language file layout. Guides are available on the [Wiki](https://github.com/polang233/VelocityToolbox/wiki/English).
- Fixed plugin lifecycle handlers running twice after an exception and incorrect registry changes after a failed unload.

Replace the JAR and restart the proxy to update. Existing configuration still works: add `resource-packs` when you want delivery; leaving it out keeps delivery disabled. If you made few language changes, back up and move the old language files, then run `/vtb reload` to generate fresh copies. Reapply custom text using the new keys.
