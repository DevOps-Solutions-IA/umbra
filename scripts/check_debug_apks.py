#!/usr/bin/env python3
"""Inspect actual debug APK permissions with aapt and packaged Signal JNI with zipfile."""
import argparse
import hashlib
import json
from check_voice_artifact import verify_apk, PIN
import os
from pathlib import Path
import re
import subprocess
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
from permission_policy import NETWORK, validate_permissions
ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


def inspect(apk: Path, aapt: Path, variant: str) -> None:
    if variant not in {"connected", "offline"}:
        raise ValueError("Unknown transport variant")
    output = subprocess.check_output([str(aapt), "dump", "permissions", str(apk)], text=True)
    permissions = set(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", output))
    if not permissions:
        raise RuntimeError(f"No permissions decoded: {apk}")
    validate_permissions(permissions, variant)
    with zipfile.ZipFile(apk) as package:
        if len(package.namelist()) != len(set(package.namelist())):
            raise RuntimeError("Ambiguous duplicate ZIP entries")
        if any(n.endswith("/libsignal_jni_testing.so") for n in package.namelist()):
            raise RuntimeError("Client-testing JNI must not ship in the app")
        for abi in sorted(ABIS):
            name = f"lib/{abi}/libsignal_jni.so"
            with package.open(name) as library:
                header = library.read(64)
                expected_class, expected_machine = {
                    "arm64-v8a": (2, 183), "armeabi-v7a": (1, 40),
                    "x86": (1, 3), "x86_64": (2, 62),
                }[abi]
                minimum = 64 if expected_class == 2 else 52
                if (len(header) < minimum or header[:7] != b"\x7fELF" + bytes([expected_class, 1, 1]) or
                        struct.unpack_from("<HHI", header, 16) != (3, expected_machine, 1)):
                    raise RuntimeError(f"Invalid Signal JNI ELF class/machine/header: {name}")
        verify_apk(package, variant, json.loads(PIN.read_text()))
        desktop = [n for n in package.namelist() if not n.startswith("lib/")
                   and re.search(r"(?:libsignal_jni.*\.(?:so|dylib)|signal_jni.*\.dll)$", n)]
        if desktop:
            raise RuntimeError(f"Desktop JNI leaked into APK: {desktop}")
    with apk.open("rb") as artifact:
        digest = hashlib.file_digest(artifact, "sha256").hexdigest()
    print(f"PASS {variant}: permissions={','.join(sorted(permissions))}")
    print(f"PASS Signal JNI ELF packaged for {','.join(sorted(ABIS))}; desktop/testing JNI excluded")
    print(f"SHA256 {digest}  {apk.relative_to(ROOT)}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--include-release", action="store_true")
    args = parser.parse_args()
    if not args.sdk:
        raise SystemExit("BLOCKED: specify --sdk or ANDROID_HOME")
    aapt = Path(args.sdk) / "build-tools/35.0.0" / ("aapt.exe" if os.name == "nt" else "aapt")
    for variant in ("connected", "offline"):
        inspect(ROOT / f"android/app/build/outputs/apk/{variant}/debug/app-{variant}-debug.apk", aapt, variant)
        if args.include_release:
            inspect(ROOT / f"android/app/build/outputs/apk/{variant}/release/app-{variant}-release-unsigned.apk", aapt, variant)


if __name__ == "__main__":
    main()
