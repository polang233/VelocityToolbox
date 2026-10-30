- Simpler resource pack configuration: a single file can be `base: "@base.zip"`; `versions` and `permission` sit directly on a variant; `- none` sends nothing; servers can use `rpg: [rpg, ui]`, `default: keep` and `minigame: []`.
- Server names support wildcards such as `"survival-*": [survival]`. Exact names win; among wildcards, the first written wins.
- `keep-existing: true` can now be combined with `packs`: keep VTB packs if the player has any, otherwise send these. Useful for downloading on the first lobby join and keeping packs when returning.
- Players behind a shared IP no longer block each other: downloads offered by VTB to online players skip per-IP limits. They still count toward global concurrency, request rate and bandwidth, and direct links keep every limit.
- The console warns once when a reverse proxy is missing from `trusted-proxies`. Obsolete internal security options now warn and are ignored instead of stopping hosting; misspelled options still fail.
- The default config is much shorter and now has an English version. On first generation, mainland China, Hong Kong, Macau and Taiwan get Chinese comments; other regions get English comments. The README and resource pack guide were also trimmed.

**How to update**

Replace the old JAR and fully restart the proxy. **No configuration changes are needed.** The old forms `url: "@"`, `conditions`, `packs: [...]` and `keep-existing: true` all keep working, with no migration warnings. Language files are preserved, and missing messages fall back to bundled text.

To adopt the new forms, edit by hand as needed and run `/vtb reload`:

- Change `- url: "@"` to `- none`
- Remove the `conditions:` level and move `versions` and `permission` up beside `url`
- Change `survival: { packs: [survival] }` to `survival: [survival]`
- Change `lobby: { keep-existing: true }` to `lobby: keep`

See the [default config](https://github.com/polang233/VelocityToolbox/blob/main/src/main/resources/config.en.yml) and [resource pack guide](https://github.com/polang233/VelocityToolbox/blob/main/docs/RESOURCE_PACKS.en.md) for new options and full examples. Your existing config.yml is never overwritten and does not get the new comments automatically. To get them, back it up, delete it so the plugin regenerates one, then copy your settings back.

Requires Velocity 4.0+ and Java 25+. The full build and regression checks passed, covering old and new forms, wildcards, keep-with-packs and real loopback HTTP. In-game client testing was not performed for this release.
