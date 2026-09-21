#!/usr/bin/env python3
"""Explicit two-AVD RFCOMM fixture. Requires independent, already Android-bonded synthetic AVDs."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial-a', required=True)
    parser.add_argument('--serial-b', required=True)
    parser.add_argument('--address-a', required=True)
    parser.add_argument('--address-b', required=True)
    parser.add_argument('--flavor', choices=('connected', 'offline'), default='offline')
    parser.add_argument('--app-apk', type=Path)
    parser.add_argument('--test-apk', type=Path)
    parser.add_argument('--log-dir', type=Path, required=True)
    parser.add_argument('--adb', default=shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', '')) / 'platform-tools/adb'))
    args = parser.parse_args()
    if args.serial_a == args.serial_b:
        raise SystemExit('Two independent devices are required')
    for address in (args.address_a, args.address_b):
        if not re.fullmatch(r'(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}', address):
            raise SystemExit('Invalid Bluetooth address')
    output = ROOT / 'android/app/build/outputs/apk'
    app = args.app_apk or output / args.flavor / 'debug' / f'app-{args.flavor}-debug.apk'
    test = args.test_apk or output / 'androidTest' / args.flavor / 'debug' / f'app-{args.flavor}-debug-androidTest.apk'
    package = 'app.umbra.privatechat' + ('.offline' if args.flavor == 'offline' else '') + '.dev'
    args.log_dir.mkdir(parents=True, exist_ok=True)
    devices = (args.serial_a, args.serial_b)
    paths = [args.log_dir / f'nearby-{role}.log' for role in ('listener', 'dialer')]
    processes, streams, started_devices = [], [], []
    successful = False

    def adb(serial: str, *command: str, **kwargs):
        return subprocess.run([args.adb, '-s', serial, *command], check=True, timeout=180, **kwargs)

    def texts():
        return [path.read_text(encoding='utf-8') if path.exists() else '' for path in paths]

    def wait_for(predicate, seconds: int, reason: str):
        deadline = time.monotonic() + seconds
        while not predicate(texts()):
            if any(process.poll() is not None for process in processes):
                raise RuntimeError('A device fixture ended before its coordination barrier; inspect logs')
            if time.monotonic() >= deadline:
                raise TimeoutError(reason)
            time.sleep(0.2)

    try:
        # Refuse physical devices before installing anything or changing radio settings.
        for serial in devices:
            probe = adb(serial, 'shell', 'getprop', 'ro.kernel.qemu', capture_output=True, text=True)
            if probe.stdout.strip() != '1':
                raise RuntimeError(f'{serial} is not an Android emulator; refusing radio changes')
        for serial in devices:
            for apk in (app, test):
                if not apk.is_file():
                    raise FileNotFoundError(apk)
                adb(serial, 'install', '-r', str(apk))
            for permission in ('CONNECT', 'SCAN', 'ADVERTISE'):
                adb(serial, 'shell', 'pm', 'grant', package, f'android.permission.BLUETOOTH_{permission}')
            adb(serial, 'shell', 'svc', 'wifi', 'disable')
            adb(serial, 'shell', 'svc', 'data', 'disable')
        for serial, role, address, path in zip(devices, ('listener', 'dialer'), (args.address_b, args.address_a), paths):
            stream = path.open('w', encoding='utf-8'); streams.append(stream)
            processes.append(subprocess.Popen([args.adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
                '-e', 'listener', 'app.umbra.NearbyFixtureListener', '-e', 'class', 'app.umbra.DeviceSignalTest',
                '-e', 'role', role, '-e', 'address', address,
                package + '.test/androidx.test.runner.AndroidJUnitRunner'], stdout=stream, stderr=subprocess.STDOUT))
            started_devices.append(serial)
            if role == 'listener':
                wait_for(lambda content: 'nearbyStage=listening-or-connecting' in content[0], 20, 'Listener startup failed')
        pattern = r'syntheticSafetyCode=([0-9a-f]{64})'
        wait_for(lambda content: all(re.search(pattern, text) for text in content), 45, 'RFCOMM handshake failed')
        codes = [re.search(pattern, text).group(1) for text in texts()]
        if codes[0] != codes[1]:
            raise RuntimeError('Synthetic out-of-band safety-code comparison failed')
        # Shell text contains only a validated package literal and hex digest, never arbitrary caller text.
        for serial in devices:
            adb(serial, 'shell', f"run-as {package} sh -c 'printf %s {codes[0]} > files/nearby-synthetic-approval'")
        wait_for(lambda content: all('nearbyStage=verified' in text for text in content), 15, 'Verification barrier failed')
        for serial in devices:
            adb(serial, 'shell', f"run-as {package} sh -c 'printf go > files/nearby-synthetic-approval'")
        for process in processes:
            if process.wait(timeout=90) != 0:
                raise RuntimeError('adb instrumentation failed')
        for text in texts():
            if ('nearbyResult=PASS:' not in text or 'authenticated device roster' not in text or 'encrypted location' not in text or not re.search(r'^OK \(3 tests\)$', text, re.MULTILINE)
                or 'INSTRUMENTATION_CODE: -1' not in text or 'nearbyResult=FAIL:' in text
                or re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', text)):
                raise RuntimeError('Bluetooth fixture or subsequent JNI tests failed; inspect device logs')
        successful = True
        print(f'PASS: actual emulated Bluetooth RFCOMM on {devices}; both directions, host code comparison, authenticated device roster, encrypted location, text, attachment, duplicates and receipts. Not physical Bluetooth or Vault persistence.')
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
        for stream in streams:
            stream.close()
        if not successful:
            # Killing the adb client does not stop instrumentation in Android.
            for serial in started_devices:
                adb(serial, 'shell', 'am', 'force-stop', package)


if __name__ == '__main__':
    main()
