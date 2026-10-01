#!/usr/bin/env python3
"""Reproduce one expected historical RED on exact base, never convert arbitrary failures to evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BASE = '1a0b040ff0fc3cd723bc519c279818616155b0b7'
TREE = '17f79f114798ae6254648a5fb424711a59a6ade1'
CLASS = 'app.umbra.DeviceVaultDeadlineProbeTest'
METHOD = 'selectedDeadlineRejectsDataBeforeTimerDispatch'
EXPECTED_ASSERTION = 'expected app.umbra.core.AccessGate.LockedException to be thrown, but nothing was thrown'


def expected_red(text, code):
    """Only the specified missing rejection, one executed method, and a complete runner count."""
    return (code == 0
            and 'INSTRUMENTATION_CODE: -1' in text
            and 'INSTRUMENTATION_STATUS: class=' + CLASS in text
            and 'INSTRUMENTATION_STATUS: test=' + METHOD in text
            and EXPECTED_ASSERTION in text
            and CLASS + '.' + METHOD in text
            and set(re.findall(r'^INSTRUMENTATION_STATUS: test=(.+)$', text, re.M)) == {METHOD}
            and set(re.findall(r'^INSTRUMENTATION_STATUS: class=(.+)$', text, re.M)) == {CLASS}
            and 'java.lang.AssertionError:' in text
            and len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: -2\s*$', text, re.M)) == 1
            and not re.search(r'^INSTRUMENTATION_STATUS_CODE: -(?:1|3|4)\s*$', text, re.M)
            and bool(re.search(r'Tests run:\s*1,\s*Failures:\s*1\s*$', text, re.M))
            and 'FAILURES!!!' in text
            and not any(x in text for x in ('INSTRUMENTATION_FAILED', 'Process crashed', 'ClassNotFoundException',
                                             'NoClassDefFoundError', 'VerifyError', 'ExceptionInInitializerError')))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(reports):
    # Fetch only this immutable ancestor if the Actions checkout is shallow.
    present = subprocess.run(['git', 'cat-file', '-e', BASE + '^{commit}'], cwd=ROOT,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if not present:
        subprocess.run(['git', 'fetch', '--no-tags', '--depth=1', 'origin', BASE], cwd=ROOT, check=True, timeout=120)
    tree = subprocess.check_output(['git', 'rev-parse', BASE + '^{tree}'], cwd=ROOT, text=True).strip()
    if tree != TREE:
        raise RuntimeError('Historical tree mismatch')
    parent = Path(tempfile.mkdtemp(prefix='umbra-deadline-baseline-', dir=os.environ.get('RUNNER_TEMP')))
    worktree = parent / 'checkout'
    subprocess.run(['git', 'worktree', 'add', '--detach', str(worktree), BASE], cwd=ROOT, check=True, timeout=120)
    gradle = shutil.which('gradle')
    if not gradle:
        raise RuntimeError('Pinned Actions Gradle is unavailable')
    version = subprocess.check_output([gradle, '--version'], text=True, timeout=60)
    if not re.search(r'^Gradle 8\.14\.4\s*$', version, re.M):
        raise RuntimeError('Baseline requires the pinned Gradle 8.14.4')
    with (reports / 'baseline-build.log').open('w') as log:
        result = subprocess.run([gradle, '-p', str(worktree / 'android'), '--no-daemon', ':app:assembleConnectedDebug'],
                                stdout=log, stderr=subprocess.STDOUT, timeout=600)
    if result.returncode:
        raise RuntimeError('Exact-base compilation failed; no behavioral RED established')
    actual_head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=worktree, text=True).strip()
    actual_tree = subprocess.check_output(['git', 'rev-parse', 'HEAD^{tree}'], cwd=worktree, text=True).strip()
    if actual_head != BASE or actual_tree != TREE:
        raise RuntimeError('Baseline checkout changed during compilation')
    subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--'], cwd=worktree, check=True, timeout=30)
    apk = worktree / 'android/app/build/outputs/apk/connected/debug/app-connected-debug.apk'
    receipt = {'base': BASE, 'tree': tree, 'worktree': str(worktree), 'apk': str(apk), 'apkSha256': sha(apk),
               'gradle': '8.14.4', 'result': 'BASELINE_COMPILED_NOT_EXECUTED'}
    (reports / 'baseline-build.json').write_text(json.dumps(receipt, indent=2) + '\n')


def run(reports, serial):
    receipt = json.loads((reports / 'baseline-build.json').read_text())
    apk = Path(receipt['apk'])
    if receipt['base'] != BASE or receipt['tree'] != TREE or sha(apk) != receipt['apkSha256']:
        raise RuntimeError('Baseline provenance mismatch')
    test = ROOT / 'android/app/build/outputs/apk/androidTest/connected/debug/app-connected-debug-androidTest.apk'
    adb = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', serial]
    if subprocess.check_output([*adb, 'shell', 'getprop', 'ro.kernel.qemu'], text=True, timeout=30).strip() != '1':
        raise RuntimeError('Historical probe is only authorized on disposable emulators')
    for item in (apk, test):
        subprocess.run([*adb, 'install', '-r', str(item)], check=True, timeout=180)
    log = reports / 'baseline-deadline-RED.log'
    with log.open('w') as stream:
        result = subprocess.run([*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS,
                                 'app.umbra.privatechat.dev.test/androidx.test.runner.AndroidJUnitRunner'],
                                stdout=stream, stderr=subprocess.STDOUT, timeout=120)
    receipt['candidateTestApkSha256'] = sha(test)
    receipt['logSha256'] = sha(log)
    receipt['adbExitCode'] = result.returncode
    receipt['result'] = 'EXPECTED_HISTORICAL_RED' if expected_red(log.read_text(), result.returncode) else 'UNEXPECTED_RESULT'
    (reports / 'baseline-red-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    if receipt['result'] != 'EXPECTED_HISTORICAL_RED':
        raise RuntimeError('Probe did not reproduce the exact missing-deadline rejection; inspect retained evidence')
    print('EXPECTED_HISTORICAL_RED: exact-base protected read accepted after selected deadline; candidate validation follows separately')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('build', 'run'))
    parser.add_argument('--reports', required=True, type=Path)
    parser.add_argument('--serial')
    args = parser.parse_args()
    args.reports.mkdir(parents=True, exist_ok=True)
    if args.action == 'build':
        build(args.reports)
    elif args.serial:
        run(args.reports, args.serial)
    else:
        parser.error('run requires --serial')


if __name__ == '__main__':
    main()
