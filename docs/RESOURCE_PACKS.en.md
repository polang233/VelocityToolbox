# Resource packs

## Keep packs across server switches (1.3.7+)

Set `keep-existing: true` on a server assignment (or on `servers.default`) to preserve VTB packs already offered during this proxy connection. Players who have not received a pack get no new request on that server. The `packs` field can be omitted or explicitly set to `[]`; a nonempty list cannot be combined with this flag. Without the flag, the existing replacement/removal behavior is unchanged.

`servers.default` applies only to servers without their own assignment. Explicit assignments replace it entirely, including `keep-existing`. For “download on survival, keep elsewhere”, set `default: { keep-existing: true }` and `survival: { packs: [survival] }`. No lobby entry is needed: an explicit `lobby: { packs: [] }` would instead remove VTB packs on modern clients. Initial lobby login sends nothing; returning to survival keeps an unchanged pack.

Unsent delayed requests are cancelled when entering a passive server. Requests already sent can finish there and retain their original timeout/required policy. Passive servers do not issue retries or manual/batched resends. Returning to an assigned server keeps an unchanged pack and applies changed choices. Reloading while on a passive server preserves the old pack until the player returns to an assigned server. Disconnecting, disabling delivery, or unloading VTB still cleans up the session. Switching servers sends no retention notice to the player; pack status shows the policy only to the command caller. The bundled config includes a retaining `lobby` example and a clearing `vanilla` example.

Protected ZIPs with a nonempty `pack.mcmeta/` payload are accepted using Java ZipFile's normal lookup semantics. The 64 KiB limit applies to actual decoded bytes rather than potentially obfuscated header sizes. Empty directories, ambiguous metadata aliases, oversized payloads and malformed metadata remain rejected.

[中文](RESOURCE_PACKS.md)

resource-packs sets network-wide defaults and chooses packs by backend, client version and permission; pack-host serves ZIP files over HTTP. Both default to disabled. Hosted sources need pack-host and a public-url reachable by players. External URLs work without local hosting.

Existing configuration still works. Add resource-packs when you want pack delivery; leaving it out keeps delivery disabled.

## How Minecraft loads a resource pack

The proxy sends a URL and SHA-1 over the game protocol, plus the pack ID, prompt and required flag where supported. The client connects to the HTTP/HTTPS address, downloads the ZIP, loads its resources and reports status. ZIP bytes do not travel through the game port; joining the server does not prove the download address is reachable.

Saved client preferences can accept or decline automatically, so an offer does not always open a prompt. Acceptance, download completion and successful loading are separate states. VTB uses the successful-load response; that response is not anti-cheat proof, and downloaded packs are not confidential files.

### Version differences

- Before 1.17, clients lack the newer custom prompt and native required flag. These were added in 1.17; older clients do not display the same UI. [Minecraft 1.17 notes](https://feedback.minecraft.net/hc/en-us/articles/4402626897165-Minecraft-Caves-Cliffs-Part-1-1-17-Java)
- Before 1.20.3, clients use one server pack; VTB sends the first matching complete pack. From 1.20.3, clients support multiple packs, removal by ID and additional status responses. VTB stacks in assignment order, with later packs overriding matching resources. [Minecraft 1.20.3 notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-20-3)
- Protocol version, pack.mcmeta format and resource compatibility are separate. conditions.versions selects a file; it does not convert resources, merge ZIPs or repair models or fonts. Supply variants for the target clients.
- Download size limits, resource formats and loading capacity vary by client version. Large packs can fail due to networking, client memory or format errors. Test target clients; increasing a timeout cannot fix an invalid pack.

VTB enforces required for both external and hosted packs against the current valid request, with the protocol's native force flag disabled. Declines, failures and timeouts of current required packs still disconnect the player; late responses to cancelled offers cannot trigger a native or VTB disconnect. Hosted packs allow two retries for FAILED_DOWNLOAD or explicitly recorded local overload, after waits of 5 and 10 seconds, before applying required. Retries retain the loaded prefix, remove the failed pack and subsequent layers, and issue new request IDs. Declines, invalid URLs, client loading failures and discarded offers do not trigger automatic retries.

## Hosting, URLs and delivery

Minecraft clients download ZIPs over HTTP/HTTPS. The configuration separates three jobs:

- `packs-directory` specifies where the ZIP files are stored on the server.
- `pack-host` serves the files. `bind/port` control the local listener; `public-url` is the download address prefix sent to clients.
- `resource-packs` selects packs by server, version and permission, then sends their URLs. Hosting alone does not send packs.

There are three source forms:

- `url: "@survival.zip"` uses VTB hosting. Enable the host and place the ZIP in the hosting directory. VTB generates the URL and hash; omit `hash`.
- `url: "https://cdn.example.com/survival.zip"` uses an existing direct link and requires the actual `hash`. Clients download from that URL. VTB hosting and `public-url` do not participate.
- `url: "@"` sends no pack and needs no file, hash or hosting.

For example, `public-url: "https://packs.example.com"` and `@survival.zip` produce `https://packs.example.com/packs/survival.zip`.

`public-url` does not configure port forwarding or HTTPS. A reverse-proxy path prefix is allowed; credentials, queries and fragments are not. In the current version, leaving it empty selects a LAN address such as `192.168.1.10`, which Internet players usually cannot reach. For public servers, set a reachable IP or domain and configure port forwarding or a reverse proxy. Inspect generated URLs with `/vtb pack list` and test downloads from an external network.

pack-host is public HTTP with rate limits. Anyone who knows the full URL can download the ZIP. The ticket query parameter correlates download diagnostics and overload retries; it is not a login or download password. Pack-selection permissions are not HTTP authorization either.

## Example

The bundled configuration includes active examples for default, survival, RPG, UI layers, permission-based packs and external event packs. Modules default to disabled. Prepare or replace the example resources and remove unused definitions and assignments before enabling.

Replace the filenames, URL, hash and server names. Place survival.zip in packs-directory.

```yaml
resource-packs:
  enabled: true
  settings:
    delay: 3
    timeout: 60
    required: false
    prompt: "<#CCFFFF>Please load the server resource pack."
  packs:
    default:
      - url: "@"
    survival:
      - url: "@survival.zip"
        required: true
        conditions:
          versions:
            min: "1.20.3"
            max: max
      - url: "@"
    rpg:
      - url: "https://packs.example.com/rpg.zip"
        hash: "REPLACE_WITH_THE_ZIP_SHA1"
        conditions:
          versions:
            min: "1.20.3"
            max: max
            deny: ["1.21.2"]
          permission: "velocitytoolbox.pack.rpg"
  servers:
    default:
      packs: [default]
    survival:
      packs: [survival]
    rpg:
      packs: [rpg]
    lobby:
      packs: []
```

## Hashes and file updates

SHA-1 identifies the ZIP bytes for caching, update detection and download checks. It is not a password or an author signature. VTB computes it for hosted files, so omit hash. For external files, provide and update the hash yourself. Recompressing the same files may change the ZIP hash. Run /vtb reload after replacing ZIPs. Local sources also accept hash: "@"; an explicit local hash must match the file.

## Selection and delivery

If a configured `@filename.zip` is missing from `packs-directory` during startup or reload, VTB logs its configuration path and filename and skips that variant. If every variant of a pack is missing, assigned servers receive no offer for that pack. Other valid packs continue to be offered. Restore the file and run `/vtb reload` to enable it again.

Each pack uses the first variant matching both version and permission conditions. An unmatched pack is skipped. Omitted conditions match everyone; put fallback variants last.

versions shares the server-versions parser and matching rules. min/max are inclusive and default to Velocity's supported bounds; max: max follows proxy updates. A nonempty allow list further restricts the range; deny takes precedence. Quote version names or use integer protocol IDs. Versions sharing a protocol match together.

Each variant inherits or overrides settings.required/prompt. Omitted settings mean a 3-second delay, 60-second timeout, optional loading and an empty prompt. Only a selected required pack disconnects on rejection, failure or timeout. Acceptance alone is not successful loading.

Server packs replace servers.default.packs; default is reserved. An empty list sends nothing and removes VTB's modern-client packs. Clients on 1.20.3+ stack packs in order; older clients receive the first matching complete pack.

VTB manages only its own packs. On older clients, backend offers take precedence until a switch or manual resend. Individual removal is unavailable on older clients. The no-pack option only removes VTB's modern-client packs.

## Commands and updates

Use /vtb reload, /vtb pack list, /vtb pack status player and /vtb pack resend player. Status separates recorded loading state from matches calculated using the current rules, client version and permissions, including the assignment source and variant number.

/vtb pack remove player|all removes VTB's packs, cancels pending delivery, retry and timeout tasks, and pauses automatic delivery on the current backend. Removing one player invalidates their queued bulk-resend entry; remove all also cancels the existing bulk-resend queue. The command caller receives the results. Players see only "An administrator removed your resource packs", or a delivery-paused notice for unsent offers and older clients. Repeated removal does not repeat the notice. Only VTB's tracked pack IDs are removed; backend and other plugins' packs are unaffected. Clients on 1.20.3+ support removal by ID. Older clients have tasks cancelled and delivery paused, but must reconnect to clear an applied pack; the command reports this limitation.

Removal pauses even players whose offers have not been sent yet, and status shows the pause. Reloads, rule changes and disabling/re-enabling delivery on the same backend preserve it. A switch to another backend or a new manual resend player/all resumes selection using current rules. keep-existing assignments still retain their normal passive behavior. The pause belongs to the current proxy connection and is not saved across disconnects or restarts.

The latest remove or resend determines delivery intent; reload preserves it. remove all → resend all → reload resumes everyone with the latest rules. resend all → remove all → reload keeps everyone paused. Earlier batch callbacks, delays, retries, timeouts and receipts cannot resume paused delivery or disconnect the player.

From 1.3.5:

- /vtb pack check reads disk configuration and checks enabled modules without applying it, creating files or directories, opening a listener or sending packs. Disabled delivery rules and their pack metadata are skipped. The check does not test port availability, public reachability or external URL contents.
- /vtb pack resend all first retires old requests, withdraws packs and cancels old tasks for all online players, then sends offers to at most five players per second. The configured delay and replacement interval still apply. keep-existing assignments retain their packs. Entries superseded by a switch or individual operation are skipped. Duplicate runs are rejected. Reload continues pending requests in batches using the latest rules; disabling delivery or remove all cancels them. The summary counts scheduled, empty and skipped players. Check status for actual loading results.

Grant velocitytoolbox.admin, or velocitytoolbox.command plus velocitytoolbox.command.pack and the matching list/check/status/resend/remove action permission. Bulk resend also requires velocitytoolbox.command.pack.resend.all; bulk removal requires velocitytoolbox.command.pack.remove.all.

Invalid reloads retain previous rules; invalid initial loading disables delivery. Unchanged packs are not resent. Publish ZIPs by replacing completed temporary files. Set public-url for Internet access; leaving it empty selects a LAN address.

Reload staggers players needing new offers in groups of five per second, plus the configured delay. For 30 players, the final group starts five seconds after the first. Unchanged loaded or downloading packs remain; hosted packs with download-failure, busy or timeout states may be offered again. Each pack's loading timeout starts when its offer is sent, excluding time waiting for a batch. Reloading unchanged configuration preserves existing retry waits and delivery delays. Switching servers, disconnecting or disabling delivery invalidates old tasks.

Batching controls offer starts; HTTP concurrency, bandwidth and transfer deadlines still govern downloads, and client loading can still fail. Resource pack retry logs distinguish host-busy from FAILED_DOWNLOAD. A download failure without recorded local overload still needs client logs to identify network, file verification or client problems.

Replacing, updating or forcibly resending packs leaves at least one second between withdrawing an old offer and sending a new one. Use the larger of this interval and the configured delay, then add reload batch spacing. Initial offers, added layers and unchanged packs need no extra wait. Identical reload/resend requests during a wait share the pending task; rapid switches offer only the latest selection. Receipts and timeouts are checked against the player connection, actual backend and request ID. Retired offers detach before withdrawal, so late failures and cancelled callbacks cannot remove new packs.

Player failures use "Resource pack could not be applied: reason", without the pack name. Hosted GET requests record HTTP status, bytes written/expected, elapsed time and server-side errors; failure and retry logs retain these diagnostics. Confirmed 429, 503, 404 and 409 responses, transfer deadlines, connection interruptions and file read errors receive distinct reasons. HEAD does not replace GET diagnostics, and older connections cannot overwrite a newer request using the same ticket.

Automatic player notices omit the plugin name, pack names, retry counts and internal task details. Administrator status and console logs retain diagnostic details. Existing custom language files are preserved; missing new notice keys fall back to bundled text.

FAILED_DOWNLOAD alone contains no client network exception or hash-verification details. Without a matching GET, with an external host, or after the server writes the file, VTB cannot identify DNS errors, network instability, client cancellations or verification failures. It reports "download failed; cause unknown" and client logs are needed. A connection interruption does not identify its cause or responsible side. A timeout after the client reports DOWNLOADED is an application timeout.

Migration and validation notes are in the [maintainer guide](maintainer/README.md).

## Hosting capabilities and limits

From 1.3.5, reload and pack check validate local ZIPs referenced by delivery rules: the archive must open, pack.mcmeta must be at the root, and its JSON and required fields must be valid. Metadata is limited to 64 KiB and 64 nesting levels. Both pack_format and newer min_format/max_format declarations are supported. Errors identify the configuration path and file; failed reloads retain previous rules. This does not validate every resource or client compatibility. External URLs receive syntax and explicit hash checks only; no files are downloaded.

VTB serves local ZIPs over GET/HEAD with hashes, request limits and download concurrency controls. It can expose an existing resource directory directly. External URLs are served by their own hosts and are not subject to VTB's HTTP limits.

Empty public-url still selects a local address and logs a short warning when it selects a LAN address. It generates client links but does not discover a public IP, configure port forwarding, DNS, HTTPS or a CDN. Verify a public download from an external network.

File boundary checks, request size limits, IP record expiry, hidden directory listings and overload retries are handled internally. pack-host is public HTTP: URLs can be shared, pack-selection permissions are not download authorization, and ticket query parameters are not either. Use a reverse proxy or dedicated file service for TLS, large traffic attacks and slow-header protection. Application limits begin after header parsing. [JDK HTTP limitations](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/module-summary.html)

### Owner settings

pack-host.security exposes only the options that take effect. Start with the defaults. Obsolete internal keys such as show-index or burst-per-ip fail reload and name the key; remove them.

- max-downloads / max-downloads-per-ip: global and per-IP concurrent downloads, defaulting to 64/12. Players sharing a public IP share limits; unfinished downloads from the previous server still occupy slots.
- requests-per-minute-per-ip: per-IP refill rate, defaulting to 120 per minute, including HEAD and invalid paths. Up to 40 requests may arrive in a short burst. Server switches, probes and retries share this allowance; sustained floods still receive 429 and excess concurrent downloads receive 503.
- max-download-seconds: HTTP transfer deadline. Allow additional client loading time in resource-packs.settings.timeout.
- bandwidth-mib / per-download-mib: global and per-download MiB/s; 0 is unlimited. Tune for the uplink and allow enough download time.
- trusted-proxies: actual reverse-proxy IPs/CIDRs only. Forwarded headers are ignored by default; trusted chains are parsed right to left.

Explicit limits in existing configurations remain effective. If max-downloads-per-ip still uses the previous default of 8, change it to 12 and run /vtb reload. The 40-request burst allowance applies automatically without a new configuration key. Tickets do not bypass these limits.

To adopt the new per-IP concurrency default when upgrading to 1.3.9, merge this snippet and preserve the other security settings:

```yaml
pack-host:
  security:
    max-downloads-per-ip: 12
```

/vtb pack list shows HTTP counters. The console summarizes new rejections and timeouts once per minute. Changed files return 409 until reload; replace completed temporary ZIPs rather than editing files during downloads.
