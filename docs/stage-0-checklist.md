# Stage 0. Recon: checklist

Goal: find out which BLE services the treadmill has and which commands the FitShow app sends.

> Unlocking the Redmi 6 bootloader wipes the phone. Copy everything captured here to the PC before unlocking.

## 0.1. Phone information

- [ ] Settings → About phone: MIUI version, Android version. Record it in `docs/PLAN.md` → "Open questions".

## 0.2. Treadmill services (nRF Connect)

1. Install **nRF Connect for Mobile** (Nordic Semiconductor) on the Redmi 6 or a Pixel.
2. Turn on the treadmill. Disconnect it from other apps (close FitShow).
3. SCANNER → find the treadmill (the name likely starts with `FS-` or resembles the model name) → CONNECT.
4. Take screenshots of the service and characteristic list (expand each service).
5. Answer:
   - [ ] Device name and MAC address.
   - [ ] Is there a `0x1826` (Fitness Machine) service? If so, which characteristics (`2ACD` Treadmill Data, `2AD9` Control Point, `2AD4` Supported Speed Range, `2AD5` Supported Inclination Range).
   - [ ] Is there an `FFF0` service with `FFF1` / `FFF2`?
   - [ ] Other services (`180A` Device Information — read the manufacturer, model, firmware version).

## 0.3. Recording the FitShow exchange (HCI snoop log)

1. Enable developer mode: Settings → About phone → tap "MIUI version" 7 times.
2. Settings → Additional settings → Developer options:
   - enable **USB debugging**;
   - enable **Bluetooth HCI snoop log** (Enable Bluetooth HCI snoop log).
3. Turn Bluetooth off and back on (so the recording starts clean).
4. Open FitShow, connect to the treadmill, and go through the scenario. **Pause ~5 seconds between steps**, and record the time of each step (a voice memo or on paper works):

   | # | Action |
   |---|---|
   | 1 | Connect, wait 10 s |
   | 2 | Start in manual mode |
   | 3 | Speed +1 step ×3 |
   | 4 | Speed −1 step ×3 |
   | 5 | Set a specific speed value (if the app supports it) |
   | 6 | Incline +1 ×3 |
   | 7 | Incline −1 ×3 |
   | 8 | Pause, then resume |
   | 9 | Stop |
   | 10 | Start a built-in program from FitShow (if available), 1–2 minutes, stop |
   | 11 | Start a program **from the treadmill console** while FitShow is connected, 1 minute, stop |
   | 12 | Hold the handrail heart-rate sensors for 30 s |
   | 13 | Disconnect |

5. Turn off Bluetooth (so the log gets written).
6. Connect the Redmi 6 to the PC over USB and pull the report (I or an agent will do this):
   ```bash
   adb bugreport protocol/raw/bugreport.zip
   ```
   Inside the archive: `FS/data/misc/bluetooth/logs/btsnoop_hci.log` (or a similar path).

> ⚠️ Raw logs and bugreports contain data for all of the phone's Bluetooth devices and pairing keys. The `protocol/raw/` folder is excluded via `.gitignore`. Only the filtered exchange with the treadmill goes into the repository.

## 0.4. Programs P1–P12

- [ ] Photograph the manual pages with the program tables (speed/incline per segment). Put them in `docs/manual/` (or send them in chat).
- [ ] If there's no manual, note that — we'll capture the profiles from the treadmill at stage 1.

## 0.5. ADB over Wi-Fi (before root)

```bash
adb devices
adb tcpip 5555
adb connect <phone-IP>:5555
```

After a phone reboot, before rooting, these commands will need to be repeated over USB.

## Stage result

- nRF Connect screenshots.
- `btsnoop_hci.log` plus notes on step timing.
- Program tables, or a note that none exist.
