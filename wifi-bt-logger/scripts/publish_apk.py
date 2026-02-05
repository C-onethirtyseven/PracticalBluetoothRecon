#!/usr/bin/env python3
from __future__ import annotations
from pathlib import Path
import re
import sys

APK_PATH = Path("/Users/kyledavis/Documents/Projects/wifi-bt-logger/app/build/outputs/apk/release/app-release.apk")
DOWNLOADS = Path.home() / "Downloads"

VERSION_RE = re.compile(r"^PBRv(\d+)\.(\d+)\.apk$")


def find_next_version(downloads: Path) -> str:
    max_major = 0
    max_minor = -1
    for path in downloads.glob("PBRv*.apk"):
        m = VERSION_RE.match(path.name)
        if not m:
            continue
        major = int(m.group(1))
        minor = int(m.group(2))
        if (major, minor) > (max_major, max_minor):
            max_major, max_minor = major, minor
    if max_minor < 0:
        return "0.1"
    # increment by 0.1
    return f"{max_major}.{max_minor + 1}"


def main() -> int:
    if not APK_PATH.exists():
        print(f"ERROR: APK not found at {APK_PATH}")
        return 1

    version = find_next_version(DOWNLOADS)
    apk_out = DOWNLOADS / f"PBRv{version}.apk"
    zip_out = DOWNLOADS / f"PBRv{version}.zip"

    apk_out.write_bytes(APK_PATH.read_bytes())

    # Create zip containing only the APK
    import zipfile
    with zipfile.ZipFile(zip_out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.write(apk_out, arcname=apk_out.name)

    print(f"Wrote {apk_out}")
    print(f"Wrote {zip_out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
