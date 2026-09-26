Short plugin filenames and optional resource-pack retention across server switches.

- `/vtb plugin load name` accepts names without `.jar`, partial filenames and case-insensitive matches. Exact names take priority; ambiguous matches list candidates. Tab completion also supports filename fragments.
- Server assignments can use `keep-existing: true` with `packs: []` to retain previously offered packs without sending new ones. Unsent delayed requests are cancelled on entry.
- The default config includes both retention and removal examples. Normal switches send no retention notice; administrative status output uses a short description.
- Protected ZIPs with `pack.mcmeta/` metadata and obfuscated size headers are supported, while actual decoded metadata remains size-limited and validated.

Replace the old JAR and fully restart the proxy. Existing configurations remain valid. See [resource-pack configuration](https://github.com/polang233/VelocityToolbox/blob/main/docs/RESOURCE_PACKS.en.md) for the new option. Existing configuration and language files are not overwritten.

Requires Velocity 4.0+ and Java 25+. The full build and regression checks passed; this release has not been tested in-game with every supported client.
