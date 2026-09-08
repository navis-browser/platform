#!/usr/bin/env python3

"""Stage the canonical Windows identity tile without build-time SVG tooling."""

from pathlib import Path
import struct


SIZE = 70


def main(output, source_png) -> None:
    data = Path(source_png).read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", data[16:24]) != (SIZE, SIZE):
        raise ValueError("Expected the generated 70px canonical Navis PNG")
    output.write(data)
