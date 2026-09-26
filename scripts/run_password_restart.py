#!/usr/bin/env python3
"""Actual unlocked Vault process force-stop and production-profile Argon2 calibration; not death during commit."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time


def verified_report(report: str, returncode: int) -> bool:
    return (returncode == 0 and 'passwordRestart=PASS' in report
            and bool(re.search(r'^OK \(3 tests\)$', report, re.M))
            and 'INSTRUMENTATION_CODE: -1' in report
            and not re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', report)
            and not any(marker in report for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--flavor', choices=('connected', 'offline'), required=True)
    parser.add_argument('--log-dir', type=Path, required=True)
    parser.add_argument('--optimized', action='store_true')
    parser.add_argument('--migration', action='store_true')
    args = parser.parse_args()
    adb = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', args.serial]
    def run(*values):
        return subprocess.check_output([*adb, *values], text=True, timeout=30)
    if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise RuntimeError('Synthetic force-stop fixture requires an emulator')
    package = 'app.umbra.privatechat' + ('.offline' if args.flavor == 'offline' else '') + ('.vaultlab' if args.optimized else '.dev')
    command = [*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'app.umbra.DeviceSignalTest',
               '-e', 'listener', 'app.umbra.PasswordRestartFixtureListener', '-e', 'passwordPhase']
    runner = package + '.test/androidx.test.runner.AndroidJUnitRunner'
    args.log_dir.mkdir(parents=True, exist_ok=True)
    before = args.log_dir / (args.flavor + '-password-before-kill.log')
    with before.open('w') as stream:
        process = subprocess.Popen([*command, 'prepare-migration' if args.migration else 'prepare', runner], stdout=stream, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 45
            while 'passwordRestart=READY' not in before.read_text():
                if process.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError('Password prepare failed or timed out; inspect ' + str(before))
                time.sleep(0.1)
            pid = run('shell', 'pidof', package).strip()
            if not re.fullmatch(r'\d+', pid):
                raise RuntimeError('Target process not alive before force-stop')
            run('shell', 'am', 'force-stop', package)
            process.wait(timeout=20)
            # pidof reports status 1 when no process exists; inspect it explicitly.
            stopped = subprocess.run([*adb, 'shell', 'pidof', package], capture_output=True, text=True, timeout=10)
            if stopped.returncode != 1 or stopped.stdout.strip():
                raise RuntimeError('Target process survived force-stop')
        finally:
            if process.poll() is None:
                run('shell', 'am', 'force-stop', package)
                process.terminate()
                process.wait(timeout=10)
    after = args.log_dir / (args.flavor + '-password-after-kill.log')
    with after.open('w') as stream:
        result = subprocess.run([*command, 'verify-migration' if args.migration else 'verify', runner], stdout=stream, stderr=subprocess.STDOUT, timeout=60)
    report = after.read_text()
    if not verified_report(report, result.returncode):
        raise RuntimeError('Password restart verification failed: ' + str(after))
    print(f'{args.flavor}: actual process force-stop then Vault restart required both factors; Argon2 measured; lost key rejected; not death during commit')


if __name__ == '__main__':
    main()
