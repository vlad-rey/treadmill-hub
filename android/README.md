# android — hub

Kotlin, `minSdk 28` (Redmi 6: Android 9, 32-bit). Foreground service `HubService`:

- `treadmill/` — `TreadmillBackend`: `FtmsBleBackend` (FTMS + FitShow FFF1, Nordic BLE library) and `SimulatorBackend`; packet parsing in `Codecs.kt`.
- `session/` — workout tracking: distance from speed (FTMS on the T12B reports 0), "speed × incline" breakdown, calories via ACSM.
- `HubServer.kt` — Ktor (CIO): REST, WebSocket, static web UI files from `assets/web`.

## Build and install

```bash
powershell -ExecutionPolicy Bypass -File tools\deploy-hub.ps1 -Serial <IP>:5555
```

Requires JDK 17 and the Android SDK. The script runs unit tests, builds the APK, installs it via root (`pm install` — MIUI blocks `adb install`), grants permissions, and restarts the service. Autostart on boot is handled by the Magisk script `40-treadmill-hub.sh` from [redmi6-homeserver](https://github.com/vlad-rey/redmi6-homeserver).

Versions are pinned for AGP 8.13: Kotlin 2.2.21, coroutines 1.10.2, serialization 1.9.0 (newer ones are built with Kotlin 2.4, and D8 from AGP 8.13 can't read their metadata).

## API (port 8080)

| Method | Path | What |
|---|---|---|
| GET | `/` | home page: menu with treadmill, station, network, and hub status |
| GET | `/treadmill/` | treadmill (tabs open via the `#programs`, `#history`, `#awards` links) |
| GET | `/hub/`, `/net/` | hub status (battery, memory, connection to the treadmill, backup); network (router, internet, outage log) |
| GET | `/api/state` | snapshot: `treadmill`, `session`, `hub` |
| WS | `/ws/live` | the same snapshot on every change (~1 Hz) |
| POST | `/api/control` | `{"action": "start\|stop\|pause\|speed\|incline\|speedDelta\|inclineDelta\|program\|programEnd", "value": 5.0, "profileId": "…", "programId": "P3", "level": 4, "minutes": 30}` |
| GET/POST | `/api/config` | `deviceAddress`, `backend` (`ftms`/`sim`), `weightKg`, `maxSpeedKmh` (default 12) |
| WS | `/ws/debug/ble` | raw BLE packets in hex (for agents) |
| GET/POST | `/api/profiles`, `/api/profiles/{id}` | profiles: name, weight, speed limit |
| GET/POST | `/api/profiles/{id}/weights` | weight history; POST `{kg}` — new entry and new current weight for the profile |
| GET | `/api/export/sessions.csv?profile=ID`, `/api/sessions/{id}/export?format=tcx\|csv` | export: workout table; TCX for Strava/Garmin; per-second CSV |
| POST | `/api/hub/backup` | marker for the backup script on the PC |
| GET | `/api/game/{profileId}` | achievements (progress, earned) and the profile's real-world rewards (progress for the period, earned, delivered) |
| POST | `/api/game/{profileId}/rewards` | set the profile's real-world rewards: `[{id, title, icon, period: WEEK\|MONTH, km, effect: sound\|fireworks}]` |
| POST | `/api/game/celebrations/{id}/ack`, `/api/game/rewards/{id}/{period}/delivered`, `/api/game/telegram-test` | acknowledge a celebration popup; mark a reward as "delivered"; send a test Telegram message |
| GET | `/api/stats?profile=ID` | totals: today / week / month / all time |
| GET | `/api/sessions?profile=ID`, `/api/sessions/{id}` | history (empty `profile=` — no owner) |
| POST | `/api/sessions/{id}/profile`, `/api/sessions/{id}/console` | reassign the owner; console readout for cross-checking |
| GET | `/api/programs?profile=ID` | built-in P1–P8 plus custom programs (shared and profile-specific) |
| GET | `/api/programs/{id}/segments?level=&minutes=&profile=` | segments for preview (speed capped at the profile's limit) |
| POST/DELETE | `/api/programs`, `/api/programs/{id}` | custom programs: `{name, profileId, blocks:[{repeat, steps:[{durationS, speedKmh, inclinePct?}]}]}` |
| GET | `/power/` | Fossibot F2400 station page: charge, power, settings, stats |
| GET | `/api/power` | stations: `{id, name, address, state, stats}`; `stats` — totals for `today/week/month/quarter/year/all` (`chargeSessions`, `chargedPct`, `dischargedPct`, `chargedWh`, `outputWh`, `offgridOutputWh`, `outages`, `outageS`) and `sinceDate`; cycle = `chargedPct / 100` |
| GET | `/api/power/outages` | power-outage log: `[{stationName, outage: {stationId, startMs, endMs, socStart, socEnd, minSoc, batteryWh, maxOutputW, approximate}}]`, newest first |
| GET | `/api/net/outages` | network outage log `[{kind: ROUTER\|INTERNET, startMs, endMs}]`; current state — `/api/state` → `hub.net` (checked every 20 s: ping the Wi-Fi gateway, TCP to 1.1.1.1/8.8.8.8/9.9.9.9) |
| GET | `/api/router` | ASUS router: `{configured, user, connected, model, error, waitingForPassword, lastOkMs, clients, online, wanDownMbps, wanUpMbps}` (password is never returned) |
| POST | `/api/router/credentials` | `{user, password}` — router admin login and password; `password: null` — disconnect the router |
| GET | `/api/net/speed` | router-measured speed tests: `{running, results: [{atMs, downMbps, upMbps, pingMs, error}]}` |
| POST | `/api/net/speed/run` | run a speed test now (202; 409 — one is already running or the router isn't connected) |
| GET | `/api/router/debug?hook=…` / `?page=/…` | integration debugging: raw router response; only from the phone itself (`adb forward`) — 403 over Wi-Fi |
| GET | `/api/net/devices` | devices on Wi-Fi: `{devices: [{mac, ip, name, hostname, firstSeenMs, lastSeenMs, known}], learnUntilMs, lastScanMs}` (polls the /24 every 5 min via ARP; the first day is a learning period) |
| POST/DELETE | `/api/net/devices/{mac}` | `{name?, known?}` — name and "known" flag; DELETE — forget the device |
| POST | `/api/power/stations` | station list `[{id, name, address}]` (empty `id` — new station) |
| POST | `/api/power/{id}/settings` | `{key, value}` — only allow-listed settings, verified by reading back |

Limits are enforced on the hub: speed 1–min(limit, 16) km/h, incline 0–15%. `stop` does not wait in the queue behind other commands.

**For agents:** `/api/control` moves the belt — only with the owner's confirmation (see `CLAUDE.md`). For risk-free checks, use `backend: "sim"` and restart the service.

Telegram messages go through a queue (`telegram-outbox.json`): without internet they wait and are sent later, marked "sent with a delay".

The app icon is drawn by the `tools/icons/make_icons.py` script (SVG favicon and PNG 32/180/192/512).

### Telegram bot

Commands are received via long polling (`getUpdates`). The owner is the chat set in the hub's settings; the owner grants other chats access (`/allow ID Profile_Name`), the list is stored in `bot.json`.
- `/status`, `/progress`, `/week`, `/help` — available to anyone with access; `/charge 100` (50–100, step 5, optionally with a station number), `/speedtest`, `/allow`, `/deny`, `/me`, `/chats` — owner only.
- Monday 09:00 — last week's report: the owner gets the whole household (power, stations, internet, everyone's workouts), each linked chat gets its own treadmill.
- 19:00 — reminders about unearned rewards: weekly ones on Thu and Sat, monthly ones 7, 3, and 1 day before the end of the month.

### ASUS router

Logs in as the ASUS Router app (`login.cgi` → `asus_token`, User-Agent `asusrouter--DUTUtil-`), data comes from `appGet.cgi?hook=…`: `get_clientlist()`, `netdev(appobj)`, `nvram_get(productid)`.
Speed testing uses the router's built-in Ookla test: `ookla_speedtest_exe.cgi` → poll `ookla_speedtest_get_result()` until a `type=result` record (bandwidth in bytes/s) → `ookla_speedtest_write_history.cgi`, so the test also shows up in the router's own history. Scheduled at 7:00 and 21:00, deferred during a workout; a result below half the median of the last 10 tests triggers a Telegram message.
The Fossibot stations' Wi-Fi (ESP32) is identified by MAC = Bluetooth address − 2 and labeled with the station's name.
