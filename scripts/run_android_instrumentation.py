#!/usr/bin/env python3
"""Install synthetic debug fixtures and require all eighteen real single-device checks."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--flavor', choices=('connected', 'offline'), required=True)
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--app-apk', type=Path)
    parser.add_argument('--test-apk', type=Path)
    parser.add_argument('--adb', default=shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', '')) / 'platform-tools/adb'))
    args = parser.parse_args()
    outputs = ROOT / 'android/app/build/outputs/apk'
    app = args.app_apk or outputs / args.flavor / 'debug' / f'app-{args.flavor}-debug.apk'
    test = args.test_apk or outputs / 'androidTest' / args.flavor / 'debug' / f'app-{args.flavor}-debug-androidTest.apk'
    package = 'app.umbra.privatechat' + ('.offline' if args.flavor == 'offline' else '') + '.dev'
    adb = [args.adb, '-s', args.serial]
    for apk in (app, test):
        if not apk.is_file():
            raise SystemExit(f'Missing APK: {apk}')
        subprocess.run([*adb, 'install', '-r', str(apk)], check=True, timeout=180)
    args.log.parent.mkdir(parents=True, exist_ok=True)
    with args.log.open('w', encoding='utf-8') as stream:
        result = subprocess.run([*adb, 'shell', 'am', 'instrument', '-w', '-r',
            package + '.test/androidx.test.runner.AndroidJUnitRunner'], stdout=stream,
            stderr=subprocess.STDOUT, timeout=180)
    output = args.log.read_text(encoding='utf-8')
    failed = result.returncode != 0 or not re.search(r'^OK \(18 tests\)$', output, re.MULTILINE)
    failed |= 'INSTRUMENTATION_CODE: -1' not in output
    failed |= bool(re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', output))
    failed |= any(marker in output for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed'))
    if failed:
        raise SystemExit(f'Instrumentation failed, skipped checks, or did not run exactly 18 tests: {args.log}')
    print(f'{args.flavor}: 18 Android instrumentation tests passed on {args.serial}; log: {args.log}')


if __name__ == '__main__':
    main()
