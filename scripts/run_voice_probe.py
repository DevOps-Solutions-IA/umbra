#!/usr/bin/env python3
"""Native synthetic audio subsystem probe; NOT Engine/HTTPS or two-AVD acceptance."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from turn_lab import TurnLab

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "app.umbra.privatechat.dev"


def verified_report(report: str, returncode: int) -> bool:
    return (returncode == 0 and "nativeVoice=PASS" in report
            and bool(re.search(r"^OK \(3 tests\)$", report, re.M))
            and "INSTRUMENTATION_CODE: -1" in report
            and not re.search(r"INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b", report)
            and not any(x in report for x in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--log", type=Path, required=True)
    args = parser.parse_args()
    adb = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", args.serial]
    def run(*command, **kwargs):
        return subprocess.run([*adb, *command], check=True, capture_output=True, timeout=120, **kwargs)
    if run("shell", "getprop", "ro.kernel.qemu").stdout.strip() != b"1":
        raise RuntimeError("Synthetic audio probe requires an AVD")
    for path in ("connected/debug/app-connected-debug.apk", "androidTest/connected/debug/app-connected-debug-androidTest.apk"):
        run("install", "-r", str(ROOT / "android/app/build/outputs/apk" / path))
    args.log.parent.mkdir(parents=True, exist_ok=True)
    with TurnLab() as turn:
        try:
            run("shell", "run-as", PACKAGE, "mkdir", "-p", "files")
            run("shell", "run-as", PACKAGE, "sh", "-c", "'umask 077; cat > files/synthetic-voice-turn.json'",
                input=json.dumps({"a": turn.credentials(), "b": turn.credentials()}).encode())
            with args.log.open("w") as output:
                result = subprocess.run([*adb, "shell", "am", "instrument", "-w", "-r",
                    "-e", "class", "app.umbra.DeviceSignalTest",
                    "-e", "listener", "app.umbra.VoiceNativeFixtureListener",
                    PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"],
                    stdout=output, stderr=subprocess.STDOUT, timeout=100)
            report = args.log.read_text()
            if not verified_report(report, result.returncode):
                raise RuntimeError("Native voice probe failed; inspect " + str(args.log))
            print("PASS native synthetic audio subsystem probe; NOT complete Engine/HTTPS call acceptance")
        finally:
            run("shell", "am", "force-stop", PACKAGE)
            run("shell", "run-as", PACKAGE, "rm", "-f", "files/synthetic-voice-turn.json")


if __name__ == "__main__":
    main()
