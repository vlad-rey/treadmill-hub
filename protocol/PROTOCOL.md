# FitLogic T12B BLE protocol (FitShow module)

Status: **stage 0–1**: GATT structure, telemetry, and FTMS control are confirmed. Everything below was obtained on 2026-09-25 by reading the GATT table and passively listening to notifications from the PC (`tools/ble/recon.py`), with no write commands. The treadmill was idle.

## Device

| Field | Value |
|---|---|
| BLE name | `Run BT` |
| Manufacturer (2A29) | `FITSHOW` |
| Module model (2A24) | `FS-BT-D2` |
| Firmware (2A26) | `V2.6.3` |
| Hardware / Software (2A27 / 2A28) | `1.0` / `1.4.2` |
| MAC, serial number | in `protocol/raw/` (not in git) |

Advertising: services `FFF0` and `1826`.

## Services

| Service | Purpose | Characteristics |
|---|---|---|
| `1800` | Generic Access | `2A00` name |
| `180A` | Device Information | see above. The module fills some fields with garbage (`2A51`, `2A5D` = MAC, `2A46` = `FS-Update_1.0`) |
| **`1826`** | **FTMS — Fitness Machine (Bluetooth SIG standard)** | see below |
| `FFF0` | FitShow's proprietary protocol | `FFF1` notify, `FFF2` write-without-response |
| `FFE0` | Likely the module's UART / firmware update | `FFE4` notify, `FFE1` write-without-response. **Do not touch** |
| `180D` | Heart Rate | `2A37` notify, `B001`/`B002` notify (vendor-specific) |

## FTMS (`1826`) — main candidate for the hub

### Fitness Machine Feature (`2ACC`) = `dc 56 00 00 0f 00 00 00`

Machine features `0x000056DC`: total distance, inclination, elevation gain, step count, resistance level, expended energy, heart rate, elapsed time, power.

Target setting features `0x0000000F`: **speed target**, **incline target**, resistance, power → control via the Control Point (`2AD9`) is supported.

### Ranges

| Characteristic | Raw data | Value |
|---|---|---|
| Speed Range `2AD4` | `64 00 40 06 0a 00` | 1.00–16.00 km/h, step 0.1 (unit 0.01 km/h) |
| Inclination Range `2AD5` | `00 00 96 00 0a 00` | 0.0–15.0%, step 1.0% (unit 0.1%) |
| Resistance Range `2AD6` | `00 00 ff 00 01 00` | 0–255 (unused for a treadmill) |
| Power Range `2AD8` | `0a 00 0f 27 0a 00` | 10–9999 W |
| Heart Rate Range `2AD7` | `00 00 fa 00 01 00` | 0–250 bpm |
| Training Status `2AD3` | `01 01` | Idle |

Matches the T12B spec sheet: 1–16 km/h, 0–15%.

### Treadmill Data (`2ACD`), ~1 Hz, two packets

**Packet 1**: `8c 05 | 00 00 | 00 00 00 | 00 00 | 00 00 | 00 00 | ff ff | ff | 00 | 00 00`

Flags `0x058C` (bit 0 = 0 → instantaneous speed is present):

| Bytes | Field | Type | Unit | Current value |
|---|---|---|---|---|
| 0–1 | flags | uint16 | — | `0x058C` |
| 2–3 | Instantaneous Speed | uint16 | 0.01 km/h | 0 |
| 4–6 | Total Distance | uint24 | m | 0 |
| 7–8 | Inclination | sint16 | 0.1% | 0 |
| 9–10 | Ramp Angle | sint16 | 0.1° | 0 |
| 11–12 | Total Energy | uint16 | kcal | 0 |
| 13–14 | Energy per Hour | uint16 | kcal | `ffff` = no data |
| 15 | Energy per Minute | uint8 | kcal | `ff` = no data |
| 16 | Heart Rate | uint8 | bpm | 0 |
| 17–18 | Elapsed Time | uint16 | s | 0 |

**Packet 2**: `01 20 | 00 00 00` — flags `0x2001` (bit 0 = More Data, bit 13 undefined in FTMS). Likely a vendor field — a step counter (uint24). **Verify while moving.**

## FitShow (`FFF0`)

Without polling, the module sends on its own via `FFF1`:

| Frame | Period | Guess |
|---|---|---|
| `02 51 00 51 03` | ~1 s | status, `00` = idle |
| `02 44 cb 8f 03` | ~4 s | ? |

Frame format: `02` · command · data… · XOR(command, data…) · `03`. Verified: `51 ^ 00 = 51`, `44 ^ cb = 8f`.

Needed for things FTMS doesn't cover: launching built-in programs P1–P12 (if supported), possibly vendor-specific data. Commands come from a capture of the FitShow app's exchange (HCI snoop).

## Control via FTMS — verified 2026-09-25

Test `tools/ble/ftms_test.py` (a person at the treadmill, limits of 3 km/h and 3%). All commands were accepted with result `01` (success):

| Command | Write to `2AD9` | Response (indicate) | Behavior |
|---|---|---|---|
| Request Control | `00` | `80 00 01` | — |
| Start | `07` | `80 07 01` | 3-2-1 countdown (~3 s), then the belt moves at 1.0 km/h |
| Set Target Speed 2.0 km/h | `02 c8 00` | `80 02 01` | telemetry shows the new speed in the next packet |
| Set Target Speed 3.0 km/h | `02 2c 01` | `80 02 01` | |
| Set Target Inclination 2% | `03 14 00` | `80 03 01` | telemetry shows the target incline immediately (not the deck's actual position) |
| Set Target Inclination 0% | `03 00 00` | `80 03 01` | |
| Stop | `08 01` | `80 08 01` | smooth deceleration 3.0 → 0 over ~4 s |

Indications on `2AD9` must be enabled **before** the first write.

### Statuses

- Fitness Machine Status `2ADA`: `04` — "started" (after Start and at the beginning of movement).
- Training Status `2AD3`: `01 0e` — pre-workout (countdown), `01 0d` — manual mode (moving), `01 0f` — post-workout (after Stop), `01 01` — idle.

### Notes

- **Total Distance in FTMS stayed at 0 m** over ~40 s of movement (~25 m). Either the field updates in large steps or isn't populated at all — check over a longer distance and cross-check against the console.
- The second Treadmill Data packet (flags `0x2001`, vendor bit 13): a 3-byte value grows during movement (0 → 10 over the test) and matches field "B" in the FitShow `0x51` frame (see below). Meaning still unknown.

## FitShow `0x51` — status (from the same test)

`02 51 <state> [data] <xor> 03`

| State | Data | When |
|---|---|---|
| `00` | — | idle |
| `02` | `NN` — countdown seconds (`03`, `02`, `01`) | countdown before start |
| `03` | 12 bytes, see below | moving |
| `04` | 12 bytes | decelerating / after Stop |
| `0a` | 12 bytes | **paused** (FTMS `08 02`): time, distance, and calories freeze, incline is kept; Start (`07`) → 3-2-1 countdown → 1.0 km/h |

Data in state `03`/`04`, e.g. `1e 00 22 00 00 00 11 00 07 00 00 00`:

| Offset | Example | Guess |
|---|---|---|
| 0 | `1e` | speed, 0.1 km/h (3.0) |
| 1 | `00` | incline, % |
| 2–3 | `22 00` | time, s (34) — matches FTMS Elapsed Time |
| 4–5 | `00 00` | **distance, m** (step 10 m) — cross-checked against the console: 750 m → "0.7" |
| 6–7 | `11 00` | **kcal × 10** — cross-checked against the console: 157 → "15" |
| 8–9 | `07 00` | "B" — same as the vendor field in FTMS; keeps growing after Stop |
| 10–11 | `00 00` | ? |

The module's response to FTMS commands on `FFF1`: `02 53 01 03 00 51 03` (start), `02 53 02 <speed> <incline> <xor> 03` (new targets), `02 53 03 50 03` (stop) — the module translates FTMS into its own `0x53` commands.
## Open questions

- [x] Control via the FTMS Control Point works: Request Control, Start, speed, incline, Stop.
- [ ] Distance: FTMS reports 0 — cross-check against the console over a longer distance; meaning of the "B" field and kcal × 10.
- [ ] What `180D` / `B001` / `B002` send when the heart-rate sensors are touched.
- [ ] FitShow protocol: capture of the FitShow app's exchange, launching built-in programs.
- [x] **If BLE drops during movement, the treadmill immediately stops the belt itself**, without an error, and returns to idle (FitShow `0x51` = `00`). Verified 2026-09-25: 2 km/h, hub force-killed (`am force-stop`), owner at the treadmill.

## Cross-check against the console — test 2026-09-25 (5 km/h, 0% and 10%, pause, STOP)

- Time, distance, and calories from FitShow match the console. FTMS Total Distance is always 0.
- The hub's speed-based distance overshoots by ~5% (accelerations: telemetry immediately shows the target speed) → the hub uses the treadmill's own counter instead.
- Treadmill calories: 5.8 kcal/min at 0% and 6.4 at 10% (+11%); ACSM for 90 kg: 5.3 and 12.1 (+127%) — the treadmill barely accounts for incline.
- Incline level on the console = % by FTMS (10 → "10").
- STOP from the console = pause (incline lowers); FTMS Stop (`08 01`) = full console reset; FTMS Pause (`08 02`) = pause with incline kept.
- Fitness Machine Status while paused: `02 02`.