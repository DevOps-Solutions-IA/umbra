#!/usr/bin/env python3
"""Run real Android compilation and JVM/libsignal integration tests. No mock success path."""
from __future__ import annotations
import argparse
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
from bootstrap_gradle import ROOT, VERSION, bootstrap


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", help="Android SDK directory")
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    if sys.version_info < (3, 12):
        print("BLOCKED: Python 3.12 or newer is required.")
        return 2
    java = subprocess.run(["java", "-version"], capture_output=True, text=True, check=True)
    javac = subprocess.run(["javac", "-version"], capture_output=True, text=True, check=True)
    if not re.search(r'version "21\.', java.stderr + java.stdout) or not re.search(r'javac 21\.', javac.stdout + javac.stderr):
        print("BLOCKED: select JDK 21 for both java and javac (JAVA_HOME and PATH).")
        return 2
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
    aapt = Path(sdk) / "build-tools/35.0.0" / ("aapt.exe" if os.name == "nt" else "aapt")
    if not aapt.is_file():
        print('BLOCKED: install build-tools;35.0.0 (required for build and APK inspection).')
        return 2
    if args.check_only:
        print("Platform found. This preflight does not prove compilation or dependency resolution.")
        return 0
    # Do not silently use an unrelated system Gradle (e.g. Debian's 4.4.1).
    gradle = str(bootstrap())
    print(f"Gradle: {VERSION} ({gradle})", flush=True)
    environment = dict(os.environ, ANDROID_HOME=str(Path(sdk).resolve()), ANDROID_SDK_ROOT=str(Path(sdk).resolve()))
    command = [gradle, "--no-daemon",
        ":app:testConnectedDebugUnitTest", ":app:testOfflineDebugUnitTest",
        ":app:assembleConnectedDebug", ":app:assembleOfflineDebug",
        ":app:lintConnectedDebug", ":app:lintOfflineDebug"]
    result = subprocess.call(command, cwd=ROOT / "android", env=environment)
    if result:
        return result
    result = subprocess.call([sys.executable, str(ROOT / "scripts/check_merged_permissions.py")], cwd=ROOT)
    if result:
        return result
    return subprocess.call([sys.executable, str(ROOT / "scripts/check_debug_apks.py"), "--sdk", sdk], cwd=ROOT)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Build failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
