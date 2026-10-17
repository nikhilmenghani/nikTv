"""Check 64-bit ELF LOAD segments and uncompressed JNI ZIP offsets for 16 KB pages.

Usage: python tools/verify-native-page-alignment.py path/to/app.apk [--json report.json]
Exits nonzero on an incompatible library. Complements runtime testing on a 16 KB device.
"""
import argparse
import json
from pathlib import Path
import struct
import zipfile

PAGE_SIZE = 16_384


def inspect_apk(path):
    results = []
    with zipfile.ZipFile(path) as archive, open(path, "rb") as raw:
        for item in archive.infolist():
            if not item.filename.endswith(".so") or not item.filename.startswith(("lib/arm64-v8a/", "lib/x86_64/")):
                continue
            data = archive.read(item)
            if data[:5] != b"\x7fELF\x02" or data[5] not in (1, 2):
                raise ValueError(f"Invalid 64-bit ELF: {item.filename}")
            order = "<" if data[5] == 1 else ">"
            table = struct.unpack_from(order + "Q", data, 32)[0]
            entry_size, count = struct.unpack_from(order + "HH", data, 54)
            loads = []
            for index in range(count):
                kind, _, offset, address, _, _, _, alignment = struct.unpack_from(
                    order + "IIQQQQQQ", data, table + index * entry_size)
                if kind == 1:
                    loads.append({"alignment": alignment, "congruent": offset % PAGE_SIZE == address % PAGE_SIZE})
            raw.seek(item.header_offset)
            name_length, extra_length = struct.unpack_from("<HH", raw.read(30), 26)
            zip_offset = item.header_offset + 30 + name_length + extra_length
            elf_ok = bool(loads) and all(row["alignment"] >= PAGE_SIZE and row["congruent"] for row in loads)
            zip_ok = item.compress_type != zipfile.ZIP_STORED or zip_offset % PAGE_SIZE == 0
            results.append({"library": item.filename, "loadSegments": loads, "elfAligned": elf_ok,
                            "zipAligned": zip_ok, "compatible": elf_ok and zip_ok})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    rows = inspect_apk(args.apk)
    if args.json:
        args.json.write_text(json.dumps(rows, indent=2), encoding="utf-8")
    failures = [row for row in rows if not row["compatible"]]
    for row in failures:
        print(f"FAIL {row['library']}: ELF={row['elfAligned']}, ZIP={row['zipAligned']}")
    print(f"16 KB native alignment: {len(rows)} libraries checked, {len(failures)} failures")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
