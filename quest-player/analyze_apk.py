#!/usr/bin/env python3
"""Inventory an APK without running, modifying, or extracting its code."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from zipfile import ZipFile

parser = argparse.ArgumentParser()
parser.add_argument("apk", type=Path)
args = parser.parse_args()
expected = [
    "androidx.media3.common.Player",
    "androidx.media3.ui.PlayerView",
    "androidx.media3.exoplayer.ExoPlayer",
    "androidx.media3.exoplayer.libass.LibassPlaybackSession",
]
with ZipFile(args.apk) as archive:
    names = archive.namelist()
    dex = [name for name in names if re.fullmatch(r"classes\d*\.dex", name)]
    markers = {name: False for name in expected}
    for name in dex:
        data = archive.read(name)
        for cls in expected:
            descriptor = ("L" + cls.replace(".", "/") + ";").encode()
            markers[cls] |= descriptor in data
    report = {
        "file": args.apk.name,
        "bytes": args.apk.stat().st_size,
        "sha256": hashlib.file_digest(args.apk.open("rb"), "sha256").hexdigest(),
        "dex_files": dex,
        "aar_files": [name for name in names if name.endswith(".aar")],
        "jar_files": [name for name in names if name.endswith(".jar")],
        "native_libraries": [name for name in names if name.startswith("lib/") and name.endswith(".so")],
        "source_class_name_markers": markers,
        "note": "A missing original class name does not establish absence of its implementation; obfuscation, optimization, and version differences may explain it.",
    }
print(json.dumps(report, ensure_ascii=False, indent=2))
