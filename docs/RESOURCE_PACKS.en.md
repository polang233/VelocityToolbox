# Resource packs

[中文](RESOURCE_PACKS.md) · [Annotated default config](../src/main/resources/config.en.yml) · [Commands and permissions](MODULES.en.md#resource-packs)

`resource-packs` decides who gets which pack; `pack-host` serves the ZIPs. Both are disabled by default and have separate switches: external URLs work without hosting, and hosting alone does not send anything.

## How clients load resource packs

The proxy sends a download URL, SHA-1 and prompt over the game protocol. The client then downloads the ZIP from that HTTP/HTTPS address itself; the ZIP never travels over the game port. Being able to join does not mean being able to download.

A player's saved resource pack preference may accept or decline automatically, so a prompt does not always appear. VTB counts only the "successfully loaded" response; accepting or finishing the download is not success.

Version differences:

- 1.17 added custom prompts and the native required flag; older clients show a different screen.
- 1.20.3 added pack stacking and removal by ID. Older clients receive only the first matching pack in the assignment, so give them one complete pack.
- `conditions.versions` only picks a file. It does not convert pack formats or merge ZIPs; prepare a pack for each target version and test with that client.

## Sources

| Value | Meaning |
| --- | --- |
| `"@survival.zip"` | A ZIP in the hosting directory; needs pack-host. URL and SHA-1 are automatic, no hash needed |
| `"https://cdn.example.com/a.zip"` | External URL; requires the real 40-character SHA-1 `hash`. VTB hosting is not involved |
| `none` | Send nothing; usually the last fallback. Without it, players are told when nothing matches |

`public-url` is the download prefix given to clients: `@survival.zip` becomes `<public-url>/packs/survival.zip`. Empty selects a local network address that public players usually cannot reach. Public servers need a reachable IP or domain plus port forwarding, a reverse proxy or HTTPS set up separately. Check the generated URL with `/vtb pack list` and download it once from an outside network.

## Example

```yaml
resource-packs:
  enabled: true
  settings:
    delay: 3        # seconds to wait after joining a server
    timeout: 60     # deadline from sending to successful load
    required: false
    prompt: "<#CCFFFF>Please load the server resource pack."
  packs:
    # a single file can be written directly
    lobby-ui: "@lobby-ui.zip"
    # use a variant list to choose by conditions; the first match wins
    survival:
      - url: "@survival.zip"
        required: true
        versions:
          min: "1.20.3"
      - none
    vip:
      - url: "https://packs.example.com/vip.zip"
        hash: "the real 40-character SHA-1 of the ZIP"
        permission: "velocitytoolbox.pack.vip"
  servers:
    default: [lobby-ui]
    "survival-*": [survival, vip]
    minigame: []
```

The generated config.yml shows the main forms, with Chinese comments in mainland China, Hong Kong, Macau and Taiwan and English comments elsewhere. Older forms such as `url: "@"`, `conditions` and full `packs:` entries still work.

## Selection rules

- **Variants**: each pack uses the first matching variant. `versions` and `permission` are optional; when both are set, both must match. If none match, the pack is skipped. Put fallbacks last.
- **Version conditions**: same rules as [server version rules](SERVER_VERSIONS.md). min/max are inclusive, a non-empty allow list must contain the version, and deny wins.
- **required / prompt**: variant values override `settings`. A required pack that is declined, fails or times out disconnects the player; an optional pack only shows a message.
- **Server assignments**: servers without their own entry use `servers.default`. An explicit entry replaces default completely. Server names can use `*`, such as `"survival-*"`; exact names win, and among wildcards the first written wins. On 1.20.3+ packs stack in `packs` order, later ones overriding earlier ones.
- **Removal**: `packs: []` sends nothing and removes VTB packs from 1.20.3+ clients. Older clients cannot remove a single pack; it is replaced by a later pack or cleared on reconnect.
- **Missing files**: a missing `@file.zip` is skipped with a console warning; other packs still work. Restore the file and run `/vtb reload`.

VTB manages only its own packs and never touches packs from backends or other plugins.

### Keep packs across server switches

Write `keep` on a server to keep VTB packs the player already has, without sending new ones. Players who never received a pack download nothing there. To send packs only when the player has none, use the full form:

```yaml
resource-packs:
  servers:
    default: keep
    lobby:
      keep-existing: true
      packs: [base]
    survival: [survival]
```

Here the first lobby login downloads base, survival switches to its pack, and the lobby and other servers keep it. Returning to survival does not re-download an unchanged pack. Kept packs last only for the current connection.

## Updates and resends

- **Updating files**: replace the ZIP and run `/vtb reload`. VTB recalculates hashes and resends only to players who need it, five per second. Write to a temporary file and then replace, rather than editing during downloads. Update `hash` when an external file changes.
- **Failed reload**: the last valid rules stay active; a failed first load disables delivery.
- **`/vtb pack resend player|all`**: resend using current rules, up to five players per second for all; explains why when nothing matches.
- **`/vtb pack remove player|all`**: withdraw VTB packs and pause delivery on the player's current server. Reload does not resume; switching servers or a manual resend does. Older clients can only be paused and must reconnect to clear loaded packs.
- **`/vtb pack check`**: check the configuration, hashes and ZIP metadata on disk without applying anything, sending packs or testing public reachability.

## Troubleshooting

Players see only "Resource pack could not be applied: reason". The console records the player, server, pack, client response and, for hosted packs, HTTP diagnostics (status, bytes sent, duration). Causes such as rate limiting, busy host, missing file, changed file, transfer timeout and broken connection are distinguished. When the client only reports a generic download failure, the reason is marked unknown; check the client log.

Hosted packs retry automatically after a download failure or host overload, up to twice (after 5 and 10 seconds), then follow `required`. Declines, invalid URLs and client load failures are not retried.

## Hosting and security

Each ZIP must contain a valid `pack.mcmeta` at its root. Reload and `pack check` verify that referenced local ZIPs open and have valid metadata, including packs processed by tools such as PackSquash. Other resources inside the ZIP are not checked.

pack-host is public HTTP with rate limits: anyone with the full URL can download. Selection permissions and the URL ticket are not download authentication. Use a reverse proxy or dedicated file service for TLS, large traffic attacks and slow-header protection.

### Adjustable limits

Keep `pack-host.security` at its defaults in most cases; omitting the section also uses them. Obsolete internal options are ignored with a warning; misspelled options fail the reload.

| Option | Default | Meaning |
| --- | --- | --- |
| `max-downloads` | 64 | Concurrent downloads for the whole server |
| `max-downloads-per-ip` | 12 | Concurrent downloads per IP |
| `requests-per-minute-per-ip` | 120 | Requests per IP per minute, with a burst of 40 |
| `max-download-seconds` | 60 | Transfer deadline; `settings.timeout` must also leave time for the client to load |
| `bandwidth-mib` / `per-download-mib` | 0 | Total / per-download bandwidth in MiB/s; 0 is unlimited |
| `trusted-proxies` | `[]` | Reverse-proxy IPs/CIDRs whose X-Forwarded-For is trusted |

URLs offered by VTB to online players carry a short-lived ticket. The first 4 requests with a ticket skip per-IP limits, so players behind a shared school or cafe IP do not block each other. They still count toward global concurrency, the global request rate and bandwidth. Direct links without a ticket keep every limit.

Behind an HTTP reverse proxy such as Nginx, add its address to `trusted-proxies`; otherwise every player shares the proxy's per-IP limits, and the console warns once. Do not add TCP tunnels such as frp, which make every visitor appear as 127.0.0.1.

`/vtb pack list` shows HTTP counters, and the console summarizes new rate-limit hits and timeouts once per minute.
