# Resource packs

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

External required packs use the native flag. VTB enforces required itself for hosted packs to allow overload retries: declines still disconnect; failures explicitly recorded as locally rate-limited retry twice at five-second intervals, then follow required.

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

Each pack uses the first variant matching both version and permission conditions. An unmatched pack is skipped. Omitted conditions match everyone; put fallback variants last.

versions shares the server-versions parser and matching rules. min/max are inclusive and default to Velocity's supported bounds; max: max follows proxy updates. A nonempty allow list further restricts the range; deny takes precedence. Quote version names or use integer protocol IDs. Versions sharing a protocol match together.

Each variant inherits or overrides settings.required/prompt. Omitted settings mean a 3-second delay, 60-second timeout, optional loading and an empty prompt. Only a selected required pack disconnects on rejection, failure or timeout. Acceptance alone is not successful loading.

Server packs replace servers.default.packs; default is reserved. An empty list sends nothing and removes VTB's modern-client packs. Clients on 1.20.3+ stack packs in order; older clients receive the first matching complete pack.

VTB manages only its own packs. On older clients, backend offers take precedence until a switch or manual resend. Individual removal is unavailable on older clients. The no-pack option only removes VTB's modern-client packs.

## Commands and updates

Use /vtb reload, /vtb pack list, /vtb pack status player and /vtb pack resend player. Grant velocitytoolbox.admin, or the base command permission, pack module permission and matching action permission.

Invalid reloads retain previous rules; invalid initial loading disables delivery. Unchanged packs are not resent. Publish ZIPs by replacing completed temporary files. Set public-url for Internet access; leaving it empty selects a LAN address.

Migration and validation notes are in the [maintainer guide](maintainer/README.md).

## Hosting capabilities and limits

VTB serves local ZIPs over GET/HEAD with hashes, request limits and download concurrency controls. It can expose an existing resource directory directly. External URLs are served by their own hosts and are not subject to VTB's HTTP limits.

Empty public-url still selects a local network address. It generates client links but does not discover a public IP, configure port forwarding, DNS, HTTPS or a CDN. Verify a public download from an external network.

File boundary checks, request size limits, IP record expiry, hidden directory listings and overload retries are handled internally. URLs can be shared; pack-selection permissions are not HTTP download authorization. Use a reverse proxy or dedicated file service for TLS, large traffic attacks and slow-header protection. Application limits begin after header parsing. [JDK HTTP limitations](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/module-summary.html)

### Owner settings

pack-host.security exposes seven options:

- max-downloads / max-downloads-per-ip: global and per-IP concurrent downloads; players sharing a public IP share limits.
- requests-per-minute-per-ip: per-IP refill rate, including HEAD and invalid paths, with a short burst allowance.
- max-download-seconds: HTTP transfer deadline. Allow additional client loading time in resource-packs.settings.timeout.
- bandwidth-mib / per-download-mib: global and per-download MiB/s; 0 is unlimited. Tune for the uplink and allow enough download time.
- trusted-proxies: actual reverse-proxy IPs/CIDRs only. Forwarded headers are ignored by default; trusted chains are parsed right to left.

/vtb pack list shows HTTP counters. The console summarizes new rejections and timeouts once per minute. Changed files return 409 until reload; replace completed temporary ZIPs rather than editing files during downloads.
