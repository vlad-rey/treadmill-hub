"""BLE treadmill recon: read-only, no commands.

    python recon.py scan [--seconds 10]
    python recon.py gatt <address> [--listen 20]

scan   — list of BLE devices; treadmill candidates are marked "*".
gatt   — connect, list all services and characteristics, read readable values,
         subscribe to notify/indicate for --listen seconds (raw packets in hex).
"""

import argparse
import asyncio
import sys
from datetime import datetime

from bleak import BleakClient, BleakScanner

FTMS = "00001826-0000-1000-8000-00805f9b34fb"
FITSHOW = "0000fff0-0000-1000-8000-00805f9b34fb"
HINTS = ("fs-", "fitshow", "fitlogic", "t12", "treadmill", "run", "walk")


def is_candidate(name: str, uuids: list[str]) -> bool:
    n = (name or "").lower()
    return any(h in n for h in HINTS) or FTMS in uuids or FITSHOW in uuids


def short(uuid: str) -> str:
    return uuid[4:8] if uuid.endswith("-0000-1000-8000-00805f9b34fb") else uuid


async def scan(seconds: float) -> None:
    found = await BleakScanner.discover(timeout=seconds, return_adv=True)
    rows = sorted(found.values(), key=lambda da: -da[1].rssi)
    for dev, adv in rows:
        name = adv.local_name or dev.name or ""
        mark = "*" if is_candidate(name, adv.service_uuids) else " "
        uuids = ",".join(short(u) for u in adv.service_uuids)
        print(f"{mark} {dev.address}  {adv.rssi:>4} dBm  {name:<24} {uuids}")
    print(f"\ndevices: {len(rows)}; \"*\" — looks like a treadmill (name or FFF0/1826 service)")


async def gatt(address: str, listen: float) -> None:
    async with BleakClient(address, timeout=20) as client:
        print(f"connected: {address}\n")
        notifiable = []
        for svc in client.services:
            print(f"[service] {short(svc.uuid)}  {svc.description}")
            for ch in svc.characteristics:
                props = ",".join(ch.properties)
                line = f"   [char] {short(ch.uuid)}  ({props})  {ch.description}"
                if "read" in ch.properties:
                    try:
                        val = await client.read_gatt_char(ch)
                        text = val.decode("utf-8", "replace") if val and all(32 <= b < 127 for b in val) else ""
                        line += f"\n          = {val.hex(' ')}" + (f'  "{text}"' if text else "")
                    except Exception as e:  # noqa: BLE001 — recon tool, just print it as-is
                        line += f"\n          ! read: {e}"
                print(line)
                if {"notify", "indicate"} & set(ch.properties):
                    notifiable.append(ch)

        if listen <= 0 or not notifiable:
            return

        def handler(ch):
            def cb(_, data: bytearray):
                ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
                print(f"{ts}  {short(ch.uuid)}  {data.hex(' ')}")
            return cb

        print(f"\nlistening for notify on {len(notifiable)} characteristics for {listen:.0f} s…")
        for ch in notifiable:
            try:
                await client.start_notify(ch, handler(ch))
            except Exception as e:  # noqa: BLE001
                print(f"   ! notify {short(ch.uuid)}: {e}")
        await asyncio.sleep(listen)


def main() -> None:
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("scan")
    s.add_argument("--seconds", type=float, default=10)
    g = sub.add_parser("gatt")
    g.add_argument("address")
    g.add_argument("--listen", type=float, default=20)
    a = p.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    asyncio.run(scan(a.seconds) if a.cmd == "scan" else gatt(a.address, a.listen))


if __name__ == "__main__":
    main()
