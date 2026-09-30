#!/usr/bin/env python3
"""Run admission Android SQLite/Vault tests in an isolated debug or optimized target."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]


def valid_report(text, code):
    return (code == 0 and re.search(r'^OK \(4 tests\)$', text, re.M)
            and 'INSTRUMENTATION_CODE: -1' in text
            and not re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', text)
            and not any(x in text for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')))


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
        raise RuntimeError('Admission fixture requires disposable emulator')
    evidence = {'optimized': args.optimized, 'exactProductionApk': False, 'artifacts': {}}
    for apk in apks:
        evidence['artifacts'][apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
        subprocess.run([*adb, 'install', '-r', str(apk)], check=True, timeout=180)
    if args.optimized:
        mapping = output / f'mapping/{args.flavor}VaultLab/mapping.txt'
        configuration = mapping.with_name('configuration.txt')
        if re.search(r'^\s*-(?:dontoptimize|dontobfuscate|dontshrink)\b', configuration.read_text(), re.M):
            raise RuntimeError('Vault optimization disabled')
        match = re.search(r'^app\.umbra\.admission\.AdmissionService -> ([^:]+):$', mapping.read_text(), re.M)
        if not match or match[1] == 'app.umbra.admission.AdmissionService':
            raise RuntimeError('Optimized admission implementation missing or not obfuscated')
        evidence['admissionClass'] = match[1]
        evidence['mappingSha256'] = hashlib.sha256(mapping.read_bytes()).hexdigest()
    log = args.reports / 'admission-tests.log'
    with log.open('w') as stream:
        result = subprocess.run([*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'app.umbra.DeviceAdmissionTest', package + '.test/androidx.test.runner.AndroidJUnitRunner'],
            stdout=stream, stderr=subprocess.STDOUT, timeout=180)
    if not valid_report(log.read_text(), result.returncode):
        raise RuntimeError(f'Admission instrumentation did not pass all three cases: {log}')
    # Keep prepare instrumentation alive, prove its PID, kill it, then verify in a new process.
    command=[*adb,'shell','am','instrument','-w','-r','-e','class','app.umbra.DeviceSignalTest',
             '-e','listener','app.umbra.AdmissionRestartFixtureListener','-e','admissionPhase']
    runner=package+'.test/androidx.test.runner.AndroidJUnitRunner'
    before=args.reports/'admission-before-kill.log'
    with before.open('w') as stream:
        process=subprocess.Popen([*command,'prepare',runner],stdout=stream,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+45
            while 'admissionRestart=READY' not in before.read_text():
                if process.poll() is not None or time.monotonic()>=deadline:
                    raise RuntimeError('Admission restart preparation failed')
                time.sleep(0.1)
            pid=subprocess.check_output([*adb,'shell','pidof',package],text=True,timeout=10).strip()
            if not re.fullmatch(r'\d+',pid): raise RuntimeError('Missing live target before admission force-stop')
            subprocess.run([*adb,'shell','am','force-stop',package],check=True,timeout=10)
            process.wait(timeout=20)
            stopped=subprocess.run([*adb,'shell','pidof',package],capture_output=True,text=True,timeout=10)
            if stopped.returncode!=1 or stopped.stdout.strip(): raise RuntimeError('Admission target survived force-stop')
        finally:
            if process.poll() is None:
                subprocess.run([*adb,'shell','am','force-stop',package],check=True,timeout=10)
                process.terminate();process.wait(timeout=10)
    after=args.reports/'admission-after-kill.log'
    with after.open('w') as stream:
        result=subprocess.run([*command,'verify',runner],stdout=stream,stderr=subprocess.STDOUT,timeout=90)
    if not valid_report(after.read_text(),result.returncode) or 'admissionRestart=PASS' not in after.read_text():
        raise RuntimeError('Admission force-stop restart not verified')
    evidence['forceStopAfterCommittedRevocation']='PASS; synthetic SQLite, not death during commit or hardware Vault'
    evidence['result'] = 'PASS'
    (args.reports / 'receipt.json').write_text(json.dumps(evidence, indent=2) + '\n')


if __name__ == '__main__':
    main()
