#!/usr/bin/env python3

"""Generate the small Windows Navis identity tile without font dependencies."""

from __future__ import annotations

import struct
import zlib


SIZE = 70
SCALE = 4
ACCENT = (11, 87, 208)
FOREGROUND = (255, 255, 255)


def inside_rounded_square(x: float, y: float) -> bool:
    inset = 4.0
    radius = 18.0
    left = inset
    top = inset
    right = SIZE - inset
    bottom = SIZE - inset
    if x < left or x >= right or y < top or y >= bottom:
        return False
    inner_left = left + radius
    inner_right = right - radius
    inner_top = top + radius
    inner_bottom = bottom - radius
    if inner_left <= x < inner_right or inner_top <= y < inner_bottom:
        return True
    corner_x = inner_left if x < inner_left else inner_right
    corner_y = inner_top if y < inner_top else inner_bottom
    return (x - corner_x) ** 2 + (y - corner_y) ** 2 <= radius**2


def inside_mark(x: float, y: float) -> bool:
    if 20.0 <= x < 28.0 and 18.0 <= y < 52.0:
        return True
    if 42.0 <= x < 50.0 and 18.0 <= y < 52.0:
        return True
    # The diagonal follows the same compact "N" already used by newtab/help.
    center_x = 24.0 + (y - 18.0) * (22.0 / 34.0)
    return 18.0 <= y < 52.0 and abs(x - center_x) <= 4.5


def pixel(x: int, y: int) -> bytes:
    background_samples = 0
    mark_samples = 0
    for sample_y in range(SCALE):
        py = y + (sample_y + 0.5) / SCALE
        for sample_x in range(SCALE):
            px = x + (sample_x + 0.5) / SCALE
            if inside_rounded_square(px, py):
                background_samples += 1
                if inside_mark(px, py):
                    mark_samples += 1
    total = SCALE * SCALE
    if background_samples == 0:
        return bytes((0, 0, 0, 0))
    mark_ratio = mark_samples / total
    background_ratio = background_samples / total
    red = round(ACCENT[0] * (1.0 - mark_ratio) + FOREGROUND[0] * mark_ratio)
    green = round(
        ACCENT[1] * (1.0 - mark_ratio) + FOREGROUND[1] * mark_ratio
    )
    blue = round(
        ACCENT[2] * (1.0 - mark_ratio) + FOREGROUND[2] * mark_ratio
    )
    alpha = round(255 * background_ratio)
    return bytes((red, green, blue, alpha))


def chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    )


def render() -> bytes:
    scanlines = bytearray()
    for y in range(SIZE):
        scanlines.append(0)
        for x in range(SIZE):
            scanlines.extend(pixel(x, y))
    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(scanlines), level=9))
        + chunk(b"IEND", b"")
    )


def main(output) -> None:
    output.write(render())

