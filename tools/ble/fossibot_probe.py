"""Read the status of a Fossibot (Sydpower / BrightEMS) power station over BLE — read-only.

    python fossibot_probe.py <address> [--polls 3]

Protocol: service A002, write C304, notify C305. "Read 80 input registers" request:
11 04 00 00 00 50 + CRC-16 Modbus. In the response, registers start at byte 6, 2 bytes big-endian each.
Sources: github.com/Ylianst/ESP-FBot, github.com/dandwhelan/fossibot-bluetooth.
"""

import argparse
import asyncio
import sys

from bleak import BleakClient

WRITE = "0000c304-0000-1000-8000-00805f9b34fb"
NOTIFY = "0000c305-0000-1000-8000-00805f9b34fb"

NAMES = {3: "AC input, W", 4: "DC input, W", 6: "total input, W", 7: "grid, W", 20: "total output, W",
         21: "AC input, V×10 / code", 39: "output (app), W", 48: "flags", 56: "battery, %×10"}


def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


FUNC = 0x04


def request(hi_first: bool) -> bytes:
    body = bytes([0x11, FUNC, 0x00, 0x00, 0x00, 0x50])
    c = crc16(body)
    return body + (bytes([c >> 8, c & 0xFF]) if hi_first else bytes([c & 0xFF, c >> 8]))


async def main(address: str, polls: int) -> None:
    got: asyncio.Queue = asyncio.Queue()
    buf = bytearray()

    def on_notify(_, data: bytearray):
        buf.extend(data)
        if len(buf) >= 6 + 80 * 2 and buf[0] == 0x11 and buf[1] == FUNC:
            got.put_nowait(bytes(buf))
            buf.clear()
        elif buf and buf[0] != 0x11:
            buf.clear()

    async with BleakClient(address, timeout=20) as client:
        print("connected:", address)
        await client.start_notify(NOTIFY, on_notify)
        for hi_first in (True, False):
            await client.write_gatt_char(WRITE, request(hi_first), response=False)
            try:
                frame = await asyncio.wait_for(got.get(), 5)
                print(f"response received (CRC {'high byte first' if hi_first else 'low byte first'}), {len(frame)} bytes")
                break
            except asyncio.TimeoutError:
                print(f"no response (CRC {'hi' if hi_first else 'lo'} first)")
        else:
            return
        for i in range(polls):
            if i:
                await asyncio.sleep(3)
                await client.write_gatt_char(WRITE, request(hi_first), response=False)
                frame = await asyncio.wait_for(got.get(), 5)
            regs = [(frame[6 + 2 * r] << 8) | frame[7 + 2 * r] for r in range(80)]
            print(f"--- poll {i + 1}")
            if FUNC == 0x04:
                for r, name in NAMES.items():
                    print(f"  reg {r:2} = {regs[r]:6}  {name}")
            nz = {r: v for r, v in enumerate(regs) if v and (FUNC != 0x04 or r not in NAMES)}
            print("  non-zero:", nz)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("address")
    p.add_argument("--polls", type=int, default=3)
    p.add_argument("--holding", action="store_true", help="read settings (0x03) instead of status (0x04)")
    a = p.parse_args()
    if a.holding:
        FUNC = 0x03
    sys.stdout.reconfigure(encoding="utf-8")
    asyncio.run(main(a.address, a.polls))
