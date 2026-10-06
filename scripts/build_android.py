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


def java_home() -> Path:
    """Validate the JDK Gradle will actually use, not only the shell's java."""
    configured = os.environ.get("JAVA_HOME")
    executable = shutil.which("java")
    if not configured and not executable:
        raise RuntimeError("JDK 21 not found")
    home = Path(configured).resolve() if configured else Path(executable).resolve().parent.parent
    suffix = ".exe" if os.name == "nt" else ""
    for name, pattern in (("java", r'version "21\.'), ("javac", r'javac 21\.')):
        result = subprocess.run([str(home / "bin" / (name + suffix)), "-version"], capture_output=True, text=True, check=True)
        if not re.search(pattern, result.stdout + result.stderr):
            raise RuntimeError("JAVA_HOME must select JDK 21 for java and javac")
    return home


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", help="Android SDK directory")
    parser.add_argument("--check-only", action="store_true")
    parser.add_argument("--release", action="store_true", help="Also compile and inspect unsigned R8 release variants")
    args = parser.parse_args()
    if sys.version_info < (3, 12):
        print("BLOCKED: Python 3.12 or newer is required.")
        return 2
    jdk = java_home()
    sdk = args.sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    candidates = [Path.home() / "Android/Sdk", Path.home() / "Library/Android/sdk"]
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    if not sdk:
        sdk = next((str(p) for p in candidates if p.is_dir()), None)
    print("JDK 21:", jdk)
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
    environment = dict(os.environ, JAVA_HOME=str(jdk), ANDROID_HOME=str(Path(sdk).resolve()), ANDROID_SDK_ROOT=str(Path(sdk).resolve()))
    command = [gradle, "--no-daemon", f"-Dorg.gradle.java.home={jdk}",
        ":app:testConnectedDebugUnitTest", ":app:testOfflineDebugUnitTest",
        ":app:assembleConnectedDebug", ":app:assembleOfflineDebug",
        ":app:lintConnectedDebug", ":app:lintOfflineDebug"]
    if args.release:
        command += [":app:assembleConnectedRelease", ":app:assembleOfflineRelease", ":app:lintConnectedRelease", ":app:lintOfflineRelease"]
    result = subprocess.call(command, cwd=ROOT / "android", env=environment)
    if result:
        return result
    result = subprocess.call([sys.executable, str(ROOT / "scripts/check_merged_permissions.py")], cwd=ROOT)
    if result:
        return result
    for script in ("check_android_tests.py", "check_debug_apks.py", "check_apk_policy.py"):
        command = [sys.executable, str(ROOT / "scripts" / script)]
        if script != "check_android_tests.py":
            command += ["--sdk", sdk] + (["--include-release"] if args.release else [])
        result = subprocess.call(command, cwd=ROOT)
        if result:
            return result
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Build failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
