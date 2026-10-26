#!/usr/bin/env python3
"""Fail if any 64-bit native library in an AAB/APK has a PT_LOAD alignment below 16 KB.

usage: python3 -I check_elf_16k.py app-release.aab
Google Play requires 16 KB page-size support on 64-bit devices
(https://developer.android.com/guide/practices/page-sizes); 32-bit ABIs are reported but not failed.
"""
import struct
import sys
import zipfile

SIXTEEN_K = 0x4000
ABIS_64 = ("arm64-v8a", "x86_64")


def min_load_align(data: bytes):
    if data[:4] != b"\x7fELF":
        return None
    is64, e = data[4] == 2, ("<" if data[5] == 1 else ">")
    if is64:
        (phoff,) = struct.unpack_from(e + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(e + "HH", data, 0x36)
    else:
        (phoff,) = struct.unpack_from(e + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(e + "HH", data, 0x2A)
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        (p_type,) = struct.unpack_from(e + "I", data, off)
        if p_type == 1:  # PT_LOAD
            (p_align,) = struct.unpack_from(e + "Q", data, off + 48) if is64 else struct.unpack_from(e + "I", data, off + 28)
            aligns.append(p_align)
    return min(aligns) if aligns else None


def main(path: str) -> int:
    bad = 0
    with zipfile.ZipFile(path) as z:
        libs = [n for n in z.namelist() if n.endswith(".so")]
        for name in libs:
            align = min_load_align(z.read(name))
            is64 = any(f"/lib/{abi}/" in f"/{name}" for abi in ABIS_64)
            ok = align is not None and align >= SIXTEEN_K
            status = "OK" if ok else ("FAIL" if is64 else "info (32-bit, not required)")
            print(f"{status:28} align=0x{(align or 0):x}  {name}")
            if is64 and not ok:
                bad += 1
    if not libs:
        print("no native libraries found (pure Kotlin/Java is 16 KB-compatible)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
