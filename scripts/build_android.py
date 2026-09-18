#!/usr/bin/env python3
"""Run real Android compilation and JVM/libsignal integration tests. No mock success path."""
from __future__ import annotations
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys
from bootstrap_gradle import ROOT, bootstrap


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", help="Android SDK directory")
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    sdk = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    candidates = [Path.home() / "Android/Sdk", Path.home() / "Library/Android/sdk"]
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    if not sdk:
        sdk = next((str(p) for p in candidates if p.is_dir()), None)
    print("Java:", shutil.which("java") or "NOT FOUND")
    print("Android SDK:", sdk or "NOT FOUND")
    if not sdk or not (Path(sdk) / "platforms/android-36/android.jar").exists():
        print("BLOCKED: Android SDK platform 36 is not installed. No APK compiled.")
        print('Install it using Android Studio SDK Manager, or sdkmanager "platforms;android-36" "build-tools;35.0.0".')
        return 2
    if args.check_only:
        print("Platform found. This preflight does not prove compilation or dependency resolution.")
        return 0
    gradle = shutil.which("gradle") or str(bootstrap())
    environment = dict(os.environ, ANDROID_HOME=str(Path(sdk).resolve()), ANDROID_SDK_ROOT=str(Path(sdk).resolve()))
    command = [gradle, "--no-daemon",
        ":app:testConnectedDebugUnitTest", ":app:testOfflineDebugUnitTest",
        ":app:assembleConnectedDebug", ":app:assembleOfflineDebug",
        ":app:lintConnectedDebug", ":app:lintOfflineDebug"]
    result = subprocess.call(command, cwd=ROOT / "android", env=environment)
    if result:
        return result
    return subprocess.call([sys.executable, str(ROOT / "scripts/check_merged_permissions.py")], cwd=ROOT)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Build failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
