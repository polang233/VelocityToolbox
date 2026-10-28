- Improve server switching and bulk updates. Replacements wait at least one second; reload and resend all start offers for up to five players per second. Hosted download failures retry automatically up to twice.
- Prevent retired tasks and receipts from interfering with new packs. Bulk resend retires all old requests first. Consecutive remove all, resend all and reload commands preserve the latest removal or resend intent; old failures, refusals and timeouts cannot disconnect players.
- Add `/vtb pack remove player|all` to withdraw packs and cancel pending sends, retries and timeouts. Reload on the same backend keeps delivery paused until a server switch or manual resend. Clients below 1.20.3 can only pause delivery and must reconnect to clear loaded packs.
- Raise default per-IP download concurrency from 8 to 12 and the short request burst from 20 to 40. Sustained request, global concurrency and bandwidth limits still apply.
- Keep pack names, client status and available HTTP diagnostics in console logs. Player notices omit pack names and repeated plugin names. Removal shows "An administrator removed your resource packs."

Replace the old JAR and fully restart the proxy. Existing configuration and custom language files are preserved; missing new messages fall back to bundled text. Existing `max-downloads-per-ip: 8` is not changed automatically. To use 12, merge the upgrade snippet in the [resource pack guide](https://github.com/polang233/VelocityToolbox/blob/main/docs/RESOURCE_PACKS.en.md). The 40-request burst allowance applies automatically.

Removal requires `velocitytoolbox.command.pack.remove`; bulk removal also requires `velocitytoolbox.command.pack.remove.all`, alongside the base and module permissions. `velocitytoolbox.admin` includes all permissions.

Requires Velocity 4.0+ and Java 25+. The full build and regression checks passed, including 30-player updates, consecutive commands, stale callbacks and receipts, retries and real loopback HTTP. In-game client testing was not performed for this release.
