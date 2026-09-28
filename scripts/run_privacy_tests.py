#!/usr/bin/env python3
"""Run nonvisual privacy adapters on isolated Android debug/R8 targets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def valid_restart_report(text, code):
    return (code == 0 and 'restrictedRestart=PASS' in text
            and re.search(r'^OK \(3 tests\)$', text, re.M)
            and 'INSTRUMENTATION_CODE: -1' in text
            and not re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', text)
            and not any(x in text for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')))


def consumption_restart(adb, package, reports):
    """Kill a positively rendering target AFTER consume commit, then reject reopen/replay."""
    command = [*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
               'app.umbra.DeviceSignalTest', '-e', 'listener',
               'app.umbra.RestrictedRestartFixtureListener', '-e', 'restrictedPhase']
    runner = package + '.test/androidx.test.runner.AndroidJUnitRunner'
    before = reports / 'restricted-before-force-stop.log'
    with before.open('w') as stream:
        process = subprocess.Popen([*command, 'prepare', runner], stdout=stream, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 45
            while 'restrictedRestart=READY' not in before.read_text():
                if process.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError('Restricted positive render/consume not reached; inspect ' + str(before))
                time.sleep(0.1)
            pid = subprocess.check_output([*adb, 'shell', 'pidof', package], text=True, timeout=10).strip()
            if not re.fullmatch(r'\d+', pid):
                raise RuntimeError('Restricted target missing before force-stop')
            subprocess.run([*adb, 'shell', 'am', 'force-stop', package], check=True, timeout=15)
            process.wait(timeout=20)
            stopped = subprocess.run([*adb, 'shell', 'pidof', package], capture_output=True, text=True, timeout=10)
            if stopped.returncode != 1 or stopped.stdout.strip():
                raise RuntimeError('Restricted target survived force-stop')
        finally:
            if process.poll() is None:
                try:
                    subprocess.run([*adb, 'shell', 'am', 'force-stop', package], check=True, timeout=15)
                finally:
                    process.terminate()
                    process.wait(timeout=10)
    after = reports / 'restricted-after-force-stop.log'
    with after.open('w') as stream:
        result = subprocess.run([*command, 'verify', runner], stdout=stream, stderr=subprocess.STDOUT, timeout=60)
    if not valid_restart_report(after.read_text(), result.returncode):
        raise RuntimeError('Restricted restart/duplicate rejection not verified; inspect ' + str(after))
    (reports / 'restricted-restart.json').write_text(json.dumps({
        'result': 'PASS', 'positiveRenderBeforeKill': True, 'hostForceStop': True,
        'deathDuringCommit': False, 'storage': 'synthetic-plaintext-SQLite-test-adapter',
        'productionKeystore': False, 'consumedReopenRejected': True,
        'postRestartDuplicateRejected': True}, indent=2) + '\n')


def valid_report(text, code, expected=12):
    return (expected in (12, 13) and code == 0 and re.search(r'^OK \('+str(expected)+r' tests\)$', text, re.M)
            and 'INSTRUMENTATION_CODE: -1' in text
            and not re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', text)
            and not any(x in text for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')))



def optimized_classes(mapping, configuration):
    """Require the actual tested production entry points, with optimization enabled."""
    if re.search(r'^\s*-(?:dontoptimize|dontobfuscate|dontshrink)\b', configuration, re.M):
        raise RuntimeError('Privacy optimization disabled')
    result = {}
    for name in ('app.umbra.privacy.ImagePreparation',
                 'app.umbra.content.RestrictedContentService',
                 'app.umbra.content.RestrictedImages$Decoder',
                 'app.umbra.content.RestrictedAudio',
                 'app.umbra.content.RestrictedPlayback',
                 'app.umbra.content.RestrictedDocuments$Decoder'):
        match = re.search(r'^' + re.escape(name) + r' -> ([^:]+):$', mapping, re.M)
        if not match or match[1] == name:
            raise RuntimeError('Optimized privacy entry point missing or not obfuscated: ' + name)
        result[name] = match[1]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--flavor', choices=('connected', 'offline'), required=True)
    parser.add_argument('--optimized', action='store_true')
    parser.add_argument('--reports', type=Path, required=True)
    args = parser.parse_args()
    build = 'vaultLab' if args.optimized else 'debug'
    suffix = '.vaultlab' if args.optimized else '.dev'
    package = 'app.umbra.privatechat' + ('.offline' if args.flavor == 'offline' else '') + suffix
    output = ROOT / 'android/app/build/outputs'
    apks = [output / f'apk/{args.flavor}/{build}/app-{args.flavor}-{build}.apk',
            output / f'apk/androidTest/{args.flavor}/{build}/app-{args.flavor}-{build}-androidTest.apk']
    args.reports.mkdir(parents=True, exist_ok=True)
    adb = [str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb'), '-s', args.serial]
    if subprocess.check_output([*adb, 'shell', 'getprop', 'ro.kernel.qemu'], text=True).strip() != '1':
        raise RuntimeError('Privacy fixture requires disposable emulator')
    evidence = {'optimized': args.optimized, 'exactProductionApk': False, 'artifacts': {}}
    for apk in apks:
        evidence['artifacts'][apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
        subprocess.run([*adb, 'install', '-r', str(apk)], check=True, timeout=180)
    if args.optimized:
        mapping = output / f'mapping/{args.flavor}VaultLab/mapping.txt'
        configuration = mapping.with_name('configuration.txt')
        evidence['optimizedClasses'] = optimized_classes(mapping.read_text(), configuration.read_text())
        evidence['mappingSha256'] = hashlib.sha256(mapping.read_bytes()).hexdigest()
    consumption_restart(adb, package, args.reports)
    if args.flavor == 'connected':
        import sys
        command = [sys.executable, str(ROOT/'scripts/run_restricted_https.py'),
                   '--serial', args.serial, '--reports', str(args.reports)]
        if args.optimized:
            command.append('--optimized')
        subprocess.run(command, check=True, timeout=180)
    classes = 'app.umbra.PrivacyAdaptersAndroidTest,app.umbra.RestrictedContentAndroidTest,app.umbra.RestrictedDocumentAndroidTest'
    expected = 13 if args.flavor == 'connected' else 12
    if args.flavor == 'connected':
        classes += ',app.umbra.RestrictedRecordingAndroidTest'
    log = args.reports / 'privacy-tests.log'
    with log.open('w') as stream:
        result = subprocess.run([*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            classes, '-e', 'syntheticNoHostAudio', 'true', package + '.test/androidx.test.runner.AndroidJUnitRunner'],
            stdout=stream, stderr=subprocess.STDOUT, timeout=180)
    if not valid_report(log.read_text(), result.returncode, expected):
        raise RuntimeError(f'Privacy instrumentation did not pass all {expected} cases: {log}')
    evidence['result'] = 'PASS'
    (args.reports / 'receipt.json').write_text(json.dumps(evidence, indent=2) + '\n')


if __name__ == '__main__':
    main()
