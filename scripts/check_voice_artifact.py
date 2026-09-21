#!/usr/bin/env python3
"""Verify the pinned WebRTC distribution and its presence/absence in final APKs."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

PIN = Path(__file__).resolve().parents[1] / "android/webrtc-artifact.json"
ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


def verify_aar(path: Path, pin: dict) -> None:
    if hashlib.sha256(path.read_bytes()).hexdigest() != pin["aarSha256"]:
        raise RuntimeError("WebRTC AAR digest mismatch")
    with zipfile.ZipFile(path) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())) or set(archive.namelist()) != set(pin["entries"]):
            raise RuntimeError("WebRTC AAR entries mismatch")
        for name, digest in pin["entries"].items():
            if hashlib.sha256(archive.read(name)).hexdigest() != digest:
                raise RuntimeError("WebRTC entry digest mismatch: " + name)


def verify_apk(archive: zipfile.ZipFile, variant: str, pin: dict) -> None:
    if variant not in {"connected", "offline"}:
        raise ValueError("Unknown variant")
    names = archive.namelist()
    expected = {f"lib/{abi}/libsignal_jni.so" for abi in ABIS}
    voice = {f"lib/{abi}/libjingle_peerconnection_so.so" for abi in ABIS}
    if variant == "connected":
        expected |= voice
    actual = {name for name in names if name.startswith("lib/") and name.endswith(".so")}
    if actual != expected:
        raise RuntimeError("Native library inventory mismatch for " + variant)
    if variant == "connected":
        for name,digest in pin["entries"].items():
            if name.startswith("assets/umbra-webrtc/") and (name not in names or hashlib.sha256(archive.read(name)).hexdigest()!=digest):
                raise RuntimeError("WebRTC license asset digest mismatch")
        for name in voice:
            digest = hashlib.sha256(archive.read(name)).hexdigest()
            if digest != pin["entries"]["jni/" + name.removeprefix("lib/")]:
                raise RuntimeError("WebRTC JNI digest mismatch: " + name)
    else:
        if any(name.startswith("assets/umbra-webrtc/") for name in names):
            raise RuntimeError("WebRTC assets leaked into offline")
        for name in names:
            if name.startswith("classes") and name.endswith(".dex"):
                if b"Lorg/webrtc/" in archive.read(name):
                    raise RuntimeError("WebRTC DEX leaked into offline")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    args = parser.parse_args()
    verify_aar(args.aar, json.loads(PIN.read_text()))
    print("PASS pinned WebRTC AAR, classes, manifest and four native ABI digests")


if __name__ == "__main__":
    main()
