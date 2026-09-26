#!/usr/bin/env python3
"""Run actual Android Vault tests in an isolated debug or optimized test target."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def valid_report(text, code):
    return (code == 0 and re.search(r'^OK \(9 tests\)$', text, re.M)
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
        raise RuntimeError('Password fixture requires disposable emulator')
    evidence = {'optimized': args.optimized, 'exactProductionApk': False, 'artifacts': {}}
    for apk in apks:
        evidence['artifacts'][apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
        subprocess.run([*adb, 'install', '-r', str(apk)], check=True, timeout=180)
    if args.optimized:
        mapping = output / f'mapping/{args.flavor}VaultLab/mapping.txt'
        configuration = mapping.with_name('configuration.txt')
        if re.search(r'^\s*-(?:dontoptimize|dontobfuscate|dontshrink)\b', configuration.read_text(), re.M):
            raise RuntimeError('Vault optimization disabled')
        match = re.search(r'^app\.umbra\.vault\.PasswordEnvelope -> ([^:]+):$', mapping.read_text(), re.M)
        if not match or match[1] == 'app.umbra.vault.PasswordEnvelope':
            raise RuntimeError('Optimized envelope implementation missing or not obfuscated')
        evidence['envelopeClass'] = match[1]
        evidence['mappingSha256'] = hashlib.sha256(mapping.read_bytes()).hexdigest()
    log = args.reports / 'password-tests.log'
    with log.open('w') as stream:
        result = subprocess.run([*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'app.umbra.DeviceVaultPasswordTest', package + '.test/androidx.test.runner.AndroidJUnitRunner'],
            stdout=stream, stderr=subprocess.STDOUT, timeout=180)
    if not valid_report(log.read_text(), result.returncode):
        raise RuntimeError(f'Password instrumentation did not pass all nine cases: {log}')
    command = ['python', str(ROOT / 'scripts/run_password_restart.py'), '--serial', args.serial,
               '--flavor', args.flavor, '--log-dir', str(args.reports)]
    if args.optimized:
        command.append('--optimized')
    subprocess.run(command, check=True, timeout=150)
    command[command.index('--log-dir') + 1] = str(args.reports / 'interrupted-migration')
    subprocess.run([*command, '--migration'], check=True, timeout=150)
    if not args.optimized:
        command[command.index('--log-dir') + 1] = str(args.reports / 'actual-reinstall')
        subprocess.run([*command, '--reinstall'], check=True, timeout=330)
    evidence['result'] = 'PASS'
    (args.reports / 'receipt.json').write_text(json.dumps(evidence, indent=2) + '\n')


if __name__ == '__main__':
    main()
