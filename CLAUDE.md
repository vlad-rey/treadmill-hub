# Rules for agents

Project: hub for the FitLogic T12B treadmill. Plan — [docs/PLAN.md](docs/PLAN.md). Documentation, code comments and instructions are in English. The web UI, Telegram messages and chat with the owner are in Russian.

## Safety — mandatory

- **Never send the treadmill a movement command** (start, speed, incline, program launch) without the owner's explicit confirmation in chat for each session; the owner must be at the treadmill. There is no "Test" mode (owner's decision).
- Reading telemetry, subscribing to notifications, and reading logs are always allowed.
- The "stop" command is always allowed.

## The repository is public

- Do not commit: raw HCI logs and bugreports (`protocol/raw/`), decompiled FitShow code, workout history, IP/MAC addresses, or other local data (`*.local.*`, `.env`).
- Do not copy code from qdomyos-zwift (GPL-3.0). It can be used as a source of protocol knowledge — with attribution.
- `protocol/captures/` must contain only filtered exchanges with the treadmill.

## Access to the hub

- ADB: `adb connect <hub-IP>:5555` (the IP is in `hub.local.json`, not in the repository).
- Hub debug API — see the API section in the plan (after stage 2).
