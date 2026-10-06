#!/usr/bin/env python3
"""Force-stop an emulator process after four committed pairing stages; never death during commit."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time


PHASES = ('invite', 'request', 'accept', 'complete')


def verified_report(report: str, returncode: int, *, final: bool = True) -> bool:
    return (returncode == 0 and (not final or 'pairingRestart=PASS' in report)
            and bool(re.search(r'^OK \(3 tests\)$', report, re.M))
            and 'INSTRUMENTATION_CODE: -1' in report
            and not re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', report)
            and not any(marker in report for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')))


def target_package(flavor: str, optimized: bool) -> str:
    if flavor not in ('connected', 'offline'):
        raise ValueError('Unsupported flavor')
    return 'app.umbra.privatechat' + ('.offline' if flavor == 'offline' else '') + ('.vaultlab' if optimized else '.dev')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--flavor', choices=('connected', 'offline'), required=True)
    parser.add_argument('--log-dir', type=Path, required=True)
    parser.add_argument('--optimized', action='store_true')
    args = parser.parse_args()
    adb = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', args.serial]

    def run(*values):
        return subprocess.check_output([*adb, *values], text=True, timeout=30)

    if run('shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
        raise RuntimeError('Synthetic pairing force-stop fixture requires an emulator')
    package = target_package(args.flavor, args.optimized)
    runner = package + '.test/androidx.test.runner.AndroidJUnitRunner'
    base = [*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'app.umbra.DeviceSignalTest']
    command = [*base, '-e', 'listener', 'app.umbra.PairingRestartFixtureListener', '-e', 'pairingPhase']
    args.log_dir.mkdir(parents=True, exist_ok=True)
    baseline = args.log_dir / (args.flavor + '-pairing-baseline.log')
    with baseline.open('w') as stream:
        result = subprocess.run([*base, runner], stdout=stream, stderr=subprocess.STDOUT, timeout=90)
    if not verified_report(baseline.read_text(), result.returncode, final=False):
        raise RuntimeError('DeviceSignalTest baseline failed: ' + str(baseline))
    evidence = {'flavor': args.flavor, 'optimized': args.optimized, 'transport': 'none',
                'baselineTests': 3, 'deathDuringCommit': False, 'stages': []}
    for phase in PHASES:
        log = args.log_dir / f'{args.flavor}-pairing-{phase}-before-kill.log'
        with log.open('w') as stream:
            process = subprocess.Popen([*command, phase, runner], stdout=stream, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 90
                while f'pairingRestart=READY phase={phase} ' not in log.read_text():
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError('Pairing stage failed or timed out: ' + str(log))
                    time.sleep(0.1)
                pid = run('shell', 'pidof', package).strip()
                if not re.fullmatch(r'\d+', pid):
                    raise RuntimeError('Target process not alive before force-stop')
                run('shell', 'am', 'force-stop', package)
                process.wait(timeout=20)
                stopped = subprocess.run([*adb, 'shell', 'pidof', package], capture_output=True, text=True, timeout=10)
                if stopped.returncode != 1 or stopped.stdout.strip():
                    raise RuntimeError('Target process survived force-stop')
                evidence['stages'].append({'phase': phase, 'pidBefore': int(pid), 'absentAfter': True})
            finally:
                if process.poll() is None:
                    run('shell', 'am', 'force-stop', package)
                    process.terminate()
                    process.wait(timeout=10)
    final = args.log_dir / (args.flavor + '-pairing-after-kill.log')
    with final.open('w') as stream:
        result = subprocess.run([*command, 'verify', runner], stdout=stream, stderr=subprocess.STDOUT, timeout=90)
    if not verified_report(final.read_text(), result.returncode):
        raise RuntimeError('Pairing restart verification failed: ' + str(final))
    evidence['verificationTests'] = 3
    evidence['result'] = 'PASS'
    (args.log_dir / (args.flavor + '-pairing-restart.json')).write_text(json.dumps(evidence, indent=2) + '\n')
    print(f'{args.flavor}: four committed pairing stages survived actual process force-stop; '
          'fresh Vault password unlocks required; contacts remain UNVERIFIED; no transport or death-during-commit claim')


if __name__ == '__main__':
    main()
