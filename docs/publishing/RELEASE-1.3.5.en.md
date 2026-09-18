Adds resource pack checks, selection diagnostics and bulk resending.

- `/vtb pack check` checks configuration, hashes and local ZIPs without applying changes. Errors identify the configuration path and file and are reported to the console.
- `/vtb pack status player` shows assignment sources, variants and version or permission mismatch reasons.
- `/vtb pack resend all` schedules up to five players per second, rejects duplicate runs and cancels the remaining queue on reload.
- Adds stricter pack metadata and download URL checks, plus a warning for automatically selected LAN addresses.
- Fixes stale pack responses disconnecting older clients after server switches, moves bStats integration into the hook package, and updates all three languages.

Replace the JAR and restart the proxy. Existing configuration still works. See the [Wiki](https://github.com/polang233/VelocityToolbox/wiki/Modules-English) for granular permissions. Proxy-side integration checks are done; please re-check with your target clients.
