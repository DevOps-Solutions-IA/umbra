#!/usr/bin/env python3
"""Install synthetic debug fixtures and require all real single-device checks, including connected video lifecycle.

Optionally pulls the UI rendering evidence (synthetic data only) written by UiScreensRenderTest."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
from instrumentation_progress import run as run_with_progress

ROOT = Path(__file__).resolve().parents[1]
MIN_EVIDENCE = 30
# 123-case suite: prior complete run299.233s; current test120 began296.444s
# and the measured four-test tail needs13.536s. Budget310s plus20s bounded
# host variability, without filtering/splitting tests or altering product leases.
# Claude product convergence adds four synthetic render cases (no Vault/Argon2/network work). PROVISIONAL
# +15s retained: the S1 CI receipt executed the complete 129-case suite in 303.199s
# (304.022s host elapsed, instrumentation_progress JSON). No timeout increase.
SUITE_TIMEOUT_SECONDS = 345


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--flavor', choices=('connected', 'offline'), required=True)
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--app-apk', type=Path)
    parser.add_argument('--test-apk', type=Path)
    parser.add_argument('--evidence-dir', type=Path, help='copy ui-evidence PNGs rendered with synthetic data here')
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
        result = run_with_progress([*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'syntheticNoHostAudio', 'true',
            package + '.test/androidx.test.runner.AndroidJUnitRunner'], stream=stream,
            report=args.log.with_suffix('.timing.json'), timeout=SUITE_TIMEOUT_SECONDS)
    output = args.log.read_text(encoding='utf-8')
    # Inventory: 83 shared technical methods (including concurrent Vault ONCE open, encrypted DB/WAL
    # inspection, native clipboard, SQLite admission snapshots that preserve corruption, and
    # sixteen access-readiness/deadline real-Vault domain cases; the opt-in lifecycle harness is a separate lab) + 36 Claude UI
    # methods (UiScreensRenderTest, UiSecurityFlowTest, UiContentIntegrationTest); connected adds capture and
    # two video surface lifecycle methods.
    # Pairing adds five encrypted SQLite/Argon2 persistence and lifecycle methods per flavor.
    # S1 provisioning adds two SQLite/password-Vault persistence and stale-review methods per flavor.
    # Exact counts remain fail-closed: adding a class requires updating this contract.
    expected=129 if args.flavor=='connected' else 126
    failed = result.returncode != 0 or not re.search(r'^OK \('+str(expected)+r' tests\)$', output, re.MULTILINE)
    failed |= 'INSTRUMENTATION_CODE: -1' not in output
    failed |= bool(re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', output))
    failed |= any(marker in output for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed'))
    if failed:
        raise SystemExit(f'Instrumentation failed, skipped checks, or did not run exactly {expected} tests: {args.log}')
    if args.evidence_dir:
        remote = f'/sdcard/Android/data/{package}/files/ui-evidence/{args.flavor}'
        args.evidence_dir.mkdir(parents=True, exist_ok=True)
        subprocess.run([*adb, 'pull', remote + '/.', str(args.evidence_dir)], check=True, timeout=120)
        images = sorted(args.evidence_dir.glob('*.png'))
        if len(images) < MIN_EVIDENCE:
            raise SystemExit(f'Expected at least {MIN_EVIDENCE} UI evidence images, found {len(images)} in {args.evidence_dir}')
        print(f'{args.flavor}: {len(images)} synthetic UI renders copied to {args.evidence_dir}')
    print(f'{args.flavor}: {expected} Android instrumentation tests passed on {args.serial}; log: {args.log}')


if __name__ == '__main__':
    main()
