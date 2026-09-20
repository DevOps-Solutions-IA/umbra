#!/usr/bin/env python3
"""Pair two disposable English-language AOSP AVDs through Android Settings, not TCP."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

MAC = r'(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}'


def nodes(xml: str) -> list[ET.Element]:
    root = ET.fromstring(xml)
    return [n for n in root.iter('node') if n.get('package') == 'com.android.settings']


def center(node: ET.Element) -> tuple[int, int]:
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
    if not match:
        raise ValueError('Missing valid UI bounds')
    left, top, right, bottom = map(int, match.groups())
    if left >= right or top >= bottom:
        raise ValueError('Empty UI bounds')
    return (left + right) // 2, (top + bottom) // 2


def pairing_code(tree: list[ET.Element]) -> str | None:
    if not any(n.get('text') == 'Bluetooth pairing code' for n in tree):
        return None
    codes = [n.get('text', '') for n in tree if re.fullmatch(r'\d{6}', n.get('text', ''))]
    if len(codes) != 1:
        raise RuntimeError('Pairing prompt has no unique six-digit comparison code')
    return codes[0]


def address_from_ui(tree: list[ET.Element]) -> str | None:
    for node in tree:
        text = node.get('text', '')
        if text.startswith("Phone's Bluetooth address:"):
            match = re.search(MAC, text)
            if match:
                return match.group().upper()
    return None


def bonded(dump: str, address: str) -> bool:
    in_section = False
    for line in dump.splitlines():
        if line.strip() == 'Bonded devices:':
            in_section = True
            continue
        if in_section:
            if not line.strip() or not re.match(r'^\s+' + MAC + r'\s', line):
                break
            if line.strip().split()[0].upper() == address.upper():
                return True
    return False


class Pairing:
    def __init__(self, adb: str, serials: tuple[str, str], logs: Path, seconds: int):
        self.adb, self.serials, self.logs = adb, serials, logs
        self.deadline = time.monotonic() + seconds

    def command(self, serial: str, *args: str) -> str:
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Pairing deadline expired')
        result = subprocess.run([self.adb, '-s', serial, *args], check=True,
                                capture_output=True, text=True, timeout=min(20, remaining))
        return result.stdout

    def ui(self, serial: str) -> list[ET.Element]:
        self.command(serial, 'shell', 'uiautomator', 'dump', '/sdcard/umbra-pairing.xml')
        xml = self.command(serial, 'shell', 'cat', '/sdcard/umbra-pairing.xml')
        (self.logs / f'{serial}-last-ui.xml').write_text(xml, encoding='utf-8')
        return nodes(xml)

    def tap(self, serial: str, tree: list[ET.Element], text: str) -> bool:
        matches = [n for n in tree if n.get('text') == text and n.get('enabled') == 'true']
        if len(matches) != 1:
            return False
        x, y = center(matches[0])
        self.command(serial, 'shell', 'input', 'tap', str(x), str(y))
        return True

    def discovery(self, serial: str) -> tuple[str, str]:
        self.command(serial, 'shell', 'am', 'start', '-a', 'android.settings.BLUETOOTH_SETTINGS')
        while True:
            tree = self.ui(serial)
            address = address_from_ui(tree)
            if address:
                # Settings shows the device name directly below its "Device name" row.
                texts = [n.get('text', '') for n in tree if n.get('text')]
                index = texts.index('Device name')
                return address, texts[index + 1]
            self.tap(serial, tree, 'Pair new device')
            time.sleep(0.25)

    def run(self) -> dict[str, str]:
        for serial in self.serials:
            if self.command(serial, 'shell', 'getprop', 'ro.kernel.qemu').strip() != '1':
                raise RuntimeError(f'{serial} is not an emulator; refusing UI/radio changes')
            if self.command(serial, 'shell', 'getprop', 'sys.boot_completed').strip() != '1':
                raise RuntimeError(f'{serial} has not completed boot')
        # Only mutate after checking both endpoints.
        for serial in self.serials:
            self.command(serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
            self.command(serial, 'shell', 'wm', 'dismiss-keyguard')
            self.command(serial, 'shell', 'cmd', 'bluetooth_manager', 'enable')
        a, b = self.serials
        address_a, _ = self.discovery(a)
        address_b, name_b = self.discovery(b)
        if address_a == address_b:
            raise RuntimeError('AVDs expose the same Bluetooth address; use independent AVDs')
        verified = False
        clicked = False
        confirmed: set[str] = set()
        while True:
            dumps = [self.command(s, 'shell', 'dumpsys', 'bluetooth_manager') for s in self.serials]
            for serial, dump in zip(self.serials, dumps):
                (self.logs / f'{serial}-bluetooth.txt').write_text(dump, encoding='utf-8')
            if bonded(dumps[0], address_b) and bonded(dumps[1], address_a):
                return {'serial_a': a, 'serial_b': b, 'address_a': address_a, 'address_b': address_b,
                        'pairing': 'confirmed-comparison' if verified else 'already-bonded'}
            trees = [self.ui(s) for s in self.serials]
            codes = [pairing_code(t) for t in trees]
            if all(codes) and not verified:
                if codes[0] != codes[1]:
                    raise RuntimeError('Bluetooth comparison codes differ; refusing confirmation')
                verified = True
            if verified:
                for serial, tree in zip(self.serials, trees):
                    if serial not in confirmed and self.tap(serial, tree, 'PAIR'):
                        confirmed.add(serial)
            elif not clicked:
                # Select only a unique matching remote name; ambiguous discovery must fail closed.
                remote_rows = [n for n in trees[0] if n.get('resource-id') == 'android:id/title']
                clicked = self.tap(a, remote_rows, name_b)
            time.sleep(0.25)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial-a', required=True)
    parser.add_argument('--serial-b', required=True)
    parser.add_argument('--log-dir', type=Path, required=True)
    parser.add_argument('--timeout', type=int, default=150)
    parser.add_argument('--adb', default=shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME', '')) / 'platform-tools/adb'))
    args = parser.parse_args()
    if args.serial_a == args.serial_b or not 30 <= args.timeout <= 600:
        raise SystemExit('Require distinct devices and a timeout between 30 and 600 seconds')
    for serial in (args.serial_a, args.serial_b):
        if not re.fullmatch(r'emulator-\d+', serial):
            raise SystemExit('Only explicit emulator-NNNN serials are accepted')
    args.log_dir.mkdir(parents=True, exist_ok=True)
    try:
        result = Pairing(args.adb, (args.serial_a, args.serial_b), args.log_dir, args.timeout).run()
    except (subprocess.SubprocessError, RuntimeError, ValueError, TimeoutError, ET.ParseError) as error:
        (args.log_dir / 'failure.txt').write_text(str(error), encoding='utf-8')
        raise SystemExit(f'Bluetooth pairing failed; inspect {args.log_dir}: {error}') from error
    (args.log_dir / 'pairing.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print('PASS: Android Settings reports both emulators bonded; metadata in', args.log_dir / 'pairing.json')


if __name__ == '__main__':
    main()
