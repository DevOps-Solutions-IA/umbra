#!/usr/bin/env python3
"""Fail unless REAL merged debug manifests exist and the offline variant has no INTERNET permission."""
from pathlib import Path
import xml.etree.ElementTree as ET
from permission_policy import manifest_permissions, validate_permissions

ROOT = Path(__file__).resolve().parents[1]
NAME = "{http://schemas.android.com/apk/res/android}name"
BUILD = ROOT / "android/app/build/intermediates"
found = {"connectedDebug": 0, "offlineDebug": 0}
for path in BUILD.rglob("AndroidManifest.xml"):
    if "merged_manifests" not in path.parts:
        continue
    for variant in found:
        if variant not in path.parts:
            continue
        permissions = manifest_permissions(ET.parse(path).getroot())
        validate_permissions(permissions, variant.removesuffix("Debug"))
        has_internet = "android.permission.INTERNET" in permissions
        if has_internet != (variant == "connectedDebug"):
            raise SystemExit(f"FAIL: unexpected network permission in {variant}")
        if ("android.permission.ACCESS_NETWORK_STATE" in permissions) != (variant == "connectedDebug"):
            raise SystemExit(f"FAIL: unexpected network state permission in {variant}")
        found[variant] += 1
        print(f"PASS merged manifest: {variant}; INTERNET={has_internet}")
if not all(found.values()):
    raise SystemExit("BLOCKED: compile both variants first; missing real merged manifests. No permission claim verified on an APK.")
