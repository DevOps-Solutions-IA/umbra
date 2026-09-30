#!/usr/bin/env python3
"""Run the Claude UI integration suite on a disposable emulator, debug or R8 (vaultLab).

Classes: UiScreensRenderTest (real Android views, synthetic data), UiSecurityFlowTest (UI vault/admission flow over
the real Vault/Keystore fixture), UiContentIntegrationTest (UI restricted-content flow + protected frame over real
Signal/AES-GCM/SQLite/codecs/emergency coordinator). Debug also runs LockedActivityTest (the real Activity, locked).
Exact counts fail closed; skipped or crashed cases fail. This is not physical-device or hardware-Keystore evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
UI_CLASSES = ('app.umbra.UiScreensRenderTest', 'app.umbra.UiSecurityFlowTest', 'app.umbra.UiContentIntegrationTest')
ACTIVITY_CLASS = 'app.umbra.LockedActivityTest'
# 24 render + 3 security flow + 5 content integration; LockedActivityTest (3) needs a debuggable target.
EXPECTED = {False: 35, True: 32}
UI_ENTRY_POINTS = ('app.umbra.ui.flow.RestrictedFlow$Viewer', 'app.umbra.ui.design.ProtectedFrameView',
                   'app.umbra.ui.screens.ContentScreens', 'app.umbra.ui.model.EmergencyPresentation',
                   'app.umbra.ui.design.QrCodes')
# The QR encoder the production path (QrCodes) reaches must survive shrinking. R8 may legitimately class-inline
# stateless facades (MultiFormatWriter, QRCodeWriter), so the check requires the QR encoder package itself (e.g.
# qrcode.decoder.Version tables, qrcode.encoder.*), renaming allowed. Functional proof is the R8 instrumentation
# case that renders a real QR through QrCodes and checks its finder patterns on screen.
REQUIRED_LIBRARY_PREFIX = 'com.google.zxing.qrcode.'
# Exact resource roots (res/raw/umbra_resource_keep.xml) that must remain in the optimized APK.
REQUIRED_RESOURCES = ('drawable/ic_notification_umbra',)
MIN_EVIDENCE = 30


def valid(text: str, code: int, expected: int) -> bool:
    return (code == 0 and bool(re.search(r'^OK \(' + str(expected) + r' tests\)$', text, re.M))
            and 'INSTRUMENTATION_CODE: -1' in text
            and not re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', text)
            and not any(x in text for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')))


def optimized_ui(mapping: str, configuration: str) -> dict:
    """The optimized target must actually contain the tested UI entry points, renamed by R8."""
    if re.search(r'^\s*-(?:dontoptimize|dontobfuscate|dontshrink)\b', configuration, re.M):
        raise RuntimeError('R8 optimization disabled')
    result = {}
    for name in UI_ENTRY_POINTS:
        match = re.search(r'^' + re.escape(name) + r' -> ([^:]+):$', mapping, re.M)
        if not match or match[1] == name:
            raise RuntimeError('Optimized UI entry point missing or not obfuscated: ' + name)
        result[name] = match[1]
    encoder = re.findall(r'^(' + re.escape(REQUIRED_LIBRARY_PREFIX) + r'[\w.$]+) -> ([^:]+):$', mapping, re.M)
    if not encoder:
        raise RuntimeError('QR encoder removed by R8: no ' + REQUIRED_LIBRARY_PREFIX + '* class in mapping')
    result.update(dict(encoder))
    return result


def resources_present(dump: str) -> list:
    """Resource names from `aapt2 dump resources`; every exact keep root must be packaged."""
    missing = [r for r in REQUIRED_RESOURCES if not re.search(r'\b' + re.escape(r) + r'\b', dump)]
    if missing:
        raise RuntimeError('Optimized APK lost required resources: ' + ', '.join(missing))
    return list(REQUIRED_RESOURCES)


def main() -> None:
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
        raise RuntimeError('UI integration fixture requires a disposable emulator')
    receipt = {'flavor': args.flavor, 'optimized': args.optimized, 'exactProductionApk': False,
               'physicalDevice': 'MANUAL_PENDING', 'artifacts': {}}
    for apk in apks:
        receipt['artifacts'][apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
        subprocess.run([*adb, 'install', '-r', str(apk)], check=True, timeout=180)
    if args.optimized:
        mapping = output / f'mapping/{args.flavor}VaultLab/mapping.txt'
        receipt['optimizedUi'] = optimized_ui(mapping.read_text(), mapping.with_name('configuration.txt').read_text())
        receipt['mappingSha256'] = hashlib.sha256(mapping.read_bytes()).hexdigest()
        aapt = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0/aapt2'
        dump = subprocess.check_output([str(aapt), 'dump', 'resources', str(apks[0])], text=True, timeout=120)
        receipt['keptResources'] = resources_present(dump)
    classes = list(UI_CLASSES) + ([] if args.optimized else [ACTIVITY_CLASS])
    expected = EXPECTED[args.optimized]
    log = args.reports / 'ui-integration.log'
    with log.open('w', encoding='utf-8') as stream:
        result = subprocess.run([*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', ','.join(classes),
                                 '-e', 'syntheticNoHostAudio', 'true', package + '.test/androidx.test.runner.AndroidJUnitRunner'],
                                stdout=stream, stderr=subprocess.STDOUT, timeout=420)
    if not valid(log.read_text(encoding='utf-8'), result.returncode, expected):
        raise SystemExit(f'UI integration did not pass exactly {expected} tests: {log}')
    evidence = args.reports / 'ui-evidence'
    evidence.mkdir(parents=True, exist_ok=True)
    subprocess.run([*adb, 'pull', f'/sdcard/Android/data/{package}/files/ui-evidence/{args.flavor}/.', str(evidence)], check=True, timeout=120)
    images = sorted(evidence.glob('*.png'))
    if len(images) < MIN_EVIDENCE:
        raise SystemExit(f'Expected at least {MIN_EVIDENCE} synthetic UI renders, found {len(images)}')
    receipt['evidence'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in images}
    receipt['tests'] = expected
    receipt['classes'] = classes
    receipt['result'] = 'PASS'
    (args.reports / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f'{args.flavor} {build}: {expected} UI integration tests passed; {len(images)} renders')


if __name__ == '__main__':
    main()
