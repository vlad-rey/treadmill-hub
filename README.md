# treadmill-hub

Home hub for the **FitLogic T12B** treadmill (FitShow Bluetooth module).

An old phone (Xiaomi Redmi 6) sits next to the treadmill, keeps a permanent BLE connection to it, and serves a PWA over the home network: real-time telemetry, speed and incline control, built-in and custom workout programs, and history and stats for multiple users.

> Status: **planning / stage 0 (protocol recon)**. See [docs/PLAN.md](docs/PLAN.md).

## Architecture (brief)

```
FitLogic T12B ──BLE──▶ Redmi 6 (Android hub)  ──HTTP/WebSocket (LAN)──▶ PWA on Pixel 9 / Pixel 10
                        ├─ BLE service (FitShow / FTMS / simulator)
                        ├─ program engine
                        ├─ SQLite (history)
                        └─ debug API for agents
```

## Structure

| Folder | What's inside |
|---|---|
| `android/` | Android hub (Kotlin): BLE, HTTP/WS server, program engine, storage |
| `web/` | PWA client |
| `protocol/` | Description of the treadmill's BLE protocol, filtered exchange captures, test packets |
| `tools/` | PC scripts: `bleak` prototype, btsnoop parser, ADB utilities |
| `docs/` | Plan, decisions (ADR), checklists |

Infrastructure for the phone itself (root, Magisk, charge limiting, Termux) lives in a separate repository, [redmi6-homeserver](https://github.com/vlad-rey/redmi6-homeserver).

## Safety

The treadmill's physical safety key remains the primary way to stop it. The app does not replace it. Rules for agents and development: [CLAUDE.md](CLAUDE.md).

## License

MIT. The protocol is studied from our own exchange captures and public sources. Third-party code (including GPL-licensed code from qdomyos-zwift and decompiled FitShow code) is not copied into the repository.
