# treadmill-hub project plan

Last updated: 2026-09-24. Final decisions are approved by the project owner.

## 1. Goal

- See in real time: running time, speed, incline (% and degrees), distance (total and broken down by speed/incline), **calories — both from the treadmill and our own calculation accounting for incline and weight**.
- Control: speed ±, incline ±, start/pause/stop.
- Run the treadmill's built-in programs (P1–P12) and custom programs.
- Keep workout history for two users.
- Give agents permanent access to the hub over the local network: telemetry, BLE packets, logs.

## 2. Hardware

| Device | Role | Notes |
|---|---|---|
| FitLogic T12B | Treadmill | 1–16 km/h, incline 0–15% (≈ 0–8.5°), 12 programs, heart rate from the handrails. FitShow BLE module |
| Xiaomi Redmi 6 4/64 (`cereus`, Helio P22) | Hub, home mini-server | MIUI Global 11.0.4.0, Android 9 (API 28), **32-bit** (`armeabi-v7a`) → hub: `minSdk 28`, native dependencies must support `armeabi-v7a`. Will be rooted, see redmi6-homeserver |
| Pixel 9, Pixel 10 | Clients | Chrome, PWA |
| PC (Windows, Bluetooth) | Development | Sits next to the treadmill: protocol prototype using `bleak` |

## 3. Decisions made

| # | Decision | ADR |
|---|---|---|
| 1 | Native Android hub on the Redmi 6, PWA clients. No separate mobile app | [0001](decisions/0001-android-hub-and-pwa.md) |
| 2 | Start with HTTP on the local network. For a fully installable PWA — the Chrome `unsafely-treat-insecure-origin-as-secure` flag on both Pixels. HTTPS only if needed | [0002](decisions/0002-http-on-lan.md) |
| 3 | The hub runs programs. Built-in P1–P12: launched via a treadmill command if the protocol allows it, otherwise a copy runs on the hub. Plus a custom program editor | [0003](decisions/0003-programs.md) |
| 4 | Home-only access, no Tailscale/port forwarding | — |
| 5 | Public monorepo, MIT. Phone infrastructure lives in a separate repository | — |
| 6 | Hub power: charge limiting via root + ACC (40–80%). Fallback: a smart plug with a local API | see redmi6-homeserver |
| 7 | Calories: two values — from the treadmill (FTMS/FitShow) and our own ACSM-based calculation (speed, incline, profile weight) | [0004](decisions/0004-calories.md) |

## 4. Architecture

### Hub (Android, Kotlin)

- **Foreground service**, starts on boot, keeps the BLE connection and reconnects.
- `TreadmillBackend` — a common interface with implementations:
  - `FitShowBle` — FitShow's proprietary protocol (service `FFF0`, notify `FFF1`, write `FFF2`);
  - `FtmsBle` — standard FTMS (`0x1826`), if the treadmill supports it;
  - `Simulator` — a virtual treadmill for development and risk-free testing.
- **Program engine**: segments of "duration / speed / incline", repeats, manual correction during a program.
- **Storage**: SQLite (Room). Samples once a second: time, speed, incline, distance, heart rate (if available).
- **Server**: Ktor — REST + WebSocket, serves the PWA's static files.
- **Debug API** for agents: raw BLE packet stream with decoding, GATT state, event log.
- Device status: charge, battery temperature, uptime.

### PWA (web)

- Vite + TypeScript + a lightweight framework (Svelte or Preact, to be chosen at stage 2), charts via uPlot.
- Screens: "Workout", "Programs", "History", "Hub" (server and connection status; warnings live only there, no notifications — owner's decision). Phone — single column; computer (≥ 900 px) — two columns, full width, large.
- Profiles: two users, each with weight (for calories), speed limit, own programs, own history. No passwords (home network only).

### API (draft)

| Method | Path | Purpose |
|---|---|---|
| WS | `/ws/live` | Real-time telemetry and program state |
| POST | `/api/control` | `start` / `pause` / `stop` / `speed` / `incline` |
| GET/POST | `/api/programs` | List and edit programs |
| POST | `/api/programs/{id}/run` | Run a program for a profile |
| GET | `/api/sessions`, `/api/sessions/{id}` | Workout history and details |
| GET | `/api/stats` | Aggregates: distance by speed and incline, etc. |
| GET | `/api/hub` | Hub and BLE status |
| WS | `/ws/debug/ble` | Raw BLE packets (for agents) |

## 5. Safety

- The physical safety key is the primary way to stop the treadmill. The app does not replace it.
- Per-profile speed limit, smooth speed changes, "STOP" button always visible.
- No "Test" mode (owner's decision, 2026-09-25): movement commands from agents require the owner's confirmation in chat, see CLAUDE.md.
- If the controlling client disconnects during a program, the program keeps running on the hub. It can be stopped from any client or with the safety key.

## 6. Agent access

- ADB over Wi-Fi (automatic on boot, once rooted).
- HTTP/WS hub debug API.
- HCI snoop log for analyzing the FitShow ↔ treadmill exchange.
- No MCP server (owner's decision, 2026-09-25).

## 7. Stages

| # | Stage | Result |
|---|---|---|
| 0 | **Recon** | nRF Connect: list of services, whether FTMS is present. Recording FitShow HCI logs per scenario. P1–P12 program tables. See [stage-0-checklist.md](stage-0-checklist.md) |
| 1 | **Protocol** | `protocol/PROTOCOL.md`, decoder with tests against recorded packets, control prototype on the PC using `bleak` |
| 2 | **Hub MVP** done 2026-09-25 | APK: FTMS connection, telemetry, calories (treadmill + ACSM), REST/WS, a simple page with ± and STOP, debug API, simulator, autostart via Magisk |
| 3 | **PWA and history** done — profiles, history, totals, PWA manifest, "Hub" tab; no separate design stage planned | Profiles, workouts, stats, charts, PWA install |
| 4 | **Programs** done 2026-09-25 (verified on the treadmill: P2 level 1, 5 min) | Engine, built-in P1–P12, custom program editor with repeats, profile shown on the chart, recording launches started from the treadmill console |
| 6 | **Gamification** done 2026-09-25 | 31 achievements (5 tiers, secret ones), per-profile real-world rewards (weekly/monthly, recurring) with a popup, sound, fireworks on achievement, progress toward the next one, "delivered" marking, Telegram to the owner |
| 5 | **Refinements** done 2026-09-25 | Workout details, owner reassignment, copying a built-in program, export (CSV, TCX for Strava/Garmin), weight history with a weekly prompt |
| 7 | **Fossibot F2400 stations** done 2026-09-26 | Two stations over BLE from the hub: charge, power, power-outage detection → Telegram (debounced, 20/10% warnings), safe settings, `/power/` page, daily stats → week/month/quarter/year/all (cycles, charge/discharge %, energy, outages) |
| 8 | **Home hub** done 2026-09-26 | Home page with tiles, shared header and icon, `/hub/` and `/net/` pages; power outage log; network: router and internet checked every 20 s, outage log, Wi-Fi device list (one-day learning period, new devices reported to Telegram, station Wi-Fi identified by BLE address); Telegram: retry queue, bot with commands and owner-granted access, weekly reports and reward reminders |
| 9 | **ASUS RT-BE58U router** done 2026-09-26 | Logs in as the ASUS Router app (verified it doesn't kick out the owner's web admin session), clients with names and IP range, WAN traffic, built-in Ookla speed test at 7:00 and 21:00 with a chart and a slowdown alert. First measurement: 895 ↓ / 848 ↑ Mbps |

Phone prep (unlocking, root, ACC, Termux) is proceeding in parallel — in redmi6-homeserver.

## Stage 2 status (2026-09-25)

- The hub on the Redmi connects to the treadmill over FTMS, telemetry at ~1 Hz, API and WebSocket work, autostart via Magisk (~60 s after boot).
- The web UI has no separate design stage — it's refined based on the owner's feedback.
- Controlling the real treadmill from the hub hasn't been tested yet — next step, only with the owner at the treadmill.

## 8. Open questions

- [ ] The treadmill's distance counter on program P2 (frequent speed changes, 1–7 km/h) gave 210 m versus 313 m computed from speed; at a steady 5 km/h they matched. The hub logs the counter reading in every sample — cross-check against the console on the next program.

- [x] MIUI/Android version on the Redmi 6: MIUI Global 11.0.4.0, Android 9 (API 28).
- [x] The treadmill has **both**: FTMS (`1826`) with speed and incline control, and FitShow (`FFF0`). Module FITSHOW FS-BT-D2, firmware V2.6.3. See `protocol/PROTOCOL.md`.
- [ ] Can a built-in program be started by command? Does the treadmill report the program and segment number?
- [x] Program tables — from the manual: 8 programs P1–P8 × 8 levels × 18 segments → `protocol/programs-t12b.json`. Plus on the console: 3 custom (U01–U03), 3 heart-rate-based (H-1…H-3), Body Fat. No sound settings in the manual.
- [x] Heart rate from the handrails is inaccurate — displayed only, not used in calculations (owner's decision).
- [x] The treadmill barely accounts for incline in its calorie count: +11% at 10% incline versus +127% by ACSM (2026-09-25).
- [x] If BLE drops during movement, the treadmill stops itself immediately (2026-09-25).
- [ ] Manual-correction behavior during a program: does it apply until the end of the segment, or shift the rest of the program?

## 9. Sources

- [qdomyos-zwift](https://github.com/cagnulein/qdomyos-zwift) — an open implementation of FitShow (GPL-3.0; used only as a source of protocol knowledge).
- [PR #4919](https://github.com/cagnulein/qdomyos-zwift/pull/4919) — FitShow: `FFF0` / `FFF1` / `FFF2`, polled at ~2 Hz.
