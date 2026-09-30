- Missing hosted ZIPs are skipped during startup or reload. The proxy logs the configuration path, filename and pack directory.
- If all variants of a pack are missing, assigned servers receive no offer for that pack. Other valid packs continue to be offered. Restore the file and run `/vtb reload` to enable it again.

Replace the old JAR and fully restart the proxy. Existing configurations remain valid. See [resource-pack configuration](https://github.com/polang233/VelocityToolbox/blob/main/docs/RESOURCE_PACKS.en.md) for details.

Requires Velocity 4.0+ and Java 25+.

The full build and regression checks passed, including reload after file deletion, fallback variants, delivery on other servers and recovery after restoring the file. In-game client testing was not performed for this release.
