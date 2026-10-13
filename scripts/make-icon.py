#!/usr/bin/env python3
"""Generates the AI Chat icons (16x16 and 32x32 PNG, speech bubble) with the standard library only."""
import struct
import sys
import zlib
from pathlib import Path

BLUE = (47, 123, 216, 255)
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)


def pixel(x, y):
    """16x16 design: rounded bubble (rows 1-11), three 2x2 dots, tail at bottom-left (rows 12-14)."""
    if 1 <= y <= 11 and 1 <= x <= 14:
        if x in (1, 14) and y in (1, 11):
            return CLEAR
        if y in (5, 6) and x in (4, 5, 7, 8, 10, 11):
            return WHITE
        return BLUE
    if 12 <= y <= 14 and 3 <= x <= 3 + (14 - y):
        return BLUE
    return CLEAR


def chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def write_png(size, path):
    scale = size // 16
    rows = []
    for y in range(size):
        row = b"".join(bytes(pixel(x // scale, y // scale)) for x in range(size))
        rows.append(b"\x00" + row)
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows)))
            + chunk(b"IEND", b""))
    path.write_bytes(data)


out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
write_png(16, out / "chat.png")
write_png(32, out / "chat@2x.png")
