"""First check of treadmill control via the FTMS Control Point.

    python ftms_test.py <address>

WARNING: the belt will move. Run this only with a person standing nearby (not on the belt),
with the safety key in place. Hard limits: speed <= 3.0 km/h, incline <= 3 %.
Stop is sent on any error or Ctrl+C.
"""

import asyncio
import struct
import sys
from datetime import datetime

from bleak import BleakClient

MAX_SPEED_KMH = 3.0
MAX_INCLINE_PCT = 3.0

CP = "00002ad9-0000-1000-8000-00805f9b34fb"
TREADMILL_DATA = "00002acd-0000-1000-8000-00805f9b34fb"
MACHINE_STATUS = "00002ada-0000-1000-8000-00805f9b34fb"
TRAINING_STATUS = "00002ad3-0000-1000-8000-00805f9b34fb"
FITSHOW_NOTIFY = "0000fff1-0000-1000-8000-00805f9b34fb"

RESULTS = {1: "OK", 2: "not supported", 3: "invalid parameter", 4: "operation failed", 5: "control not permitted"}


def ts() -> str:
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


def decode_treadmill(data: bytes) -> str:
    flags = int.from_bytes(data[0:2], "little")
    if flags & 1:  # More Data — the second packet has no speed
        return f"(extra packet flags=0x{flags:04x} {data[2:].hex(' ')})"
    i = 2
    out = []
    speed = int.from_bytes(data[i:i + 2], "little") / 100; i += 2
    out.append(f"speed {speed:.2f} km/h")
    if flags & (1 << 1): i += 2
    if flags & (1 << 2):
        out.append(f"distance {int.from_bytes(data[i:i + 3], 'little')} m"); i += 3
    if flags & (1 << 3):
        incl, ramp = struct.unpack_from("<hh", data, i); i += 4
        out.append(f"incline {incl / 10:.1f} % ({ramp / 10:.1f}°)")
    if flags & (1 << 4): i += 4
    if flags & (1 << 5): i += 1
    if flags & (1 << 6): i += 1
    if flags & (1 << 7):
        out.append(f"kcal {int.from_bytes(data[i:i + 2], 'little')}"); i += 5
    if flags & (1 << 8):
        out.append(f"heart rate {data[i]}"); i += 1
    if flags & (1 << 9): i += 1
    if flags & (1 << 10):
        out.append(f"time {int.from_bytes(data[i:i + 2], 'little')} s"); i += 2
    return ", ".join(out)


class Treadmill:
    def __init__(self, client: BleakClient):
        self.c = client
        self.responses: asyncio.Queue = asyncio.Queue()

    def on_cp(self, _, data: bytearray):
        print(f"{ts()}  CP  <- {data.hex(' ')}")
        self.responses.put_nowait(bytes(data))

    async def send(self, payload: bytes, label: str) -> bool:
        print(f"{ts()}  CP  -> {payload.hex(' ')}   ({label})")
        await self.c.write_gatt_char(CP, payload, response=True)
        try:
            r = await asyncio.wait_for(self.responses.get(), timeout=5)
        except asyncio.TimeoutError:
            print(f"{ts()}  !! no response to {label}")
            return False
        ok = len(r) >= 3 and r[0] == 0x80 and r[1] == payload[0] and r[2] == 1
        print(f"{ts()}  {'OK' if ok else '!!'} {label}: {RESULTS.get(r[2] if len(r) > 2 else 0, r.hex())}")
        return ok

    async def speed(self, kmh: float) -> bool:
        if not 0 < kmh <= MAX_SPEED_KMH:
            raise ValueError(f"speed {kmh} outside limit {MAX_SPEED_KMH}")
        return await self.send(bytes([0x02]) + struct.pack("<H", round(kmh * 100)), f"speed {kmh} km/h")

    async def incline(self, pct: float) -> bool:
        if not 0 <= pct <= MAX_INCLINE_PCT:
            raise ValueError(f"incline {pct} outside limit {MAX_INCLINE_PCT}")
        return await self.send(bytes([0x03]) + struct.pack("<h", round(pct * 10)), f"incline {pct} %")

    async def stop(self) -> bool:
        return await self.send(bytes([0x08, 0x01]), "STOP")


async def run(address: str) -> None:
    async with BleakClient(address, timeout=20) as client:
        tm = Treadmill(client)
        await client.start_notify(CP, tm.on_cp)
        await client.start_notify(TREADMILL_DATA, lambda _, d: print(f"{ts()}  DATA {decode_treadmill(bytes(d))}"))
        await client.start_notify(MACHINE_STATUS, lambda _, d: print(f"{ts()}  STATUS  {bytes(d).hex(' ')}"))
        await client.start_notify(TRAINING_STATUS, lambda _, d: print(f"{ts()}  TRAIN   {bytes(d).hex(' ')}"))
        await client.start_notify(FITSHOW_NOTIFY, lambda _, d: print(f"{ts()}  FS      {bytes(d).hex(' ')}"))
        await asyncio.sleep(2)

        started = False
        try:
            if not await tm.send(b"\x00", "Request Control"):
                print("control not granted — aborting, the belt was not started")
                return
            started = True
            if not await tm.send(b"\x07", "Start"):
                raise RuntimeError("Start не принят")
            await asyncio.sleep(8)
            for kmh in (2.0, 3.0):
                await tm.speed(kmh)
                await asyncio.sleep(6)
            for pct in (2.0, 0.0):
                await tm.incline(pct)
                await asyncio.sleep(10)
        finally:
            if started:
                await tm.stop()
                await asyncio.sleep(6)
        print(f"{ts()}  test finished")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
    try:
        asyncio.run(run(sys.argv[1]))
    except KeyboardInterrupt:
        print("interrupted")
