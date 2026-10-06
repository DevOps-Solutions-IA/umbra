#!/usr/bin/env python3
"""Read-only two-USB-phone pairing preflight. Never installs or executes pairing."""
import argparse
from contextlib import ExitStack
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

from run_physical_tests import adb_command, device_lock, digest, inspect_apk, installed_digest, run

ROOT = Path(__file__).resolve().parents[1]
PACKAGES = ('app.umbra.privatechat.dev', 'app.umbra.privatechat.offline.dev')
PENDING = ('qrImport', 'humanVerificationBothPeers', 'bidirectionalSignal',
           'replayRejection', 'restartPersistence', 'RFCOMM', 'authenticatedVault')


def select_pair(listing, serials):
    if len(serials) != 2 or len(set(serials)) != 2:
        raise ValueError('Exactly two distinct explicit USB targets required')
    if any(not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', s) or s.startswith('emulator-') for s in serials):
        raise ValueError('Emulator or network target refused')
    rows = [line.split() for line in listing.splitlines()
            if line.strip() and not line.startswith('List of devices attached')]
    if len(rows) != 2 or {row[0] for row in rows} != set(serials):
        raise ValueError('Exactly two connected target rows required')
    for row in rows:
        if len(row) < 3 or row[1] != 'device' or not any(re.fullmatch(r'usb:[A-Za-z0-9_.:-]+', token) for token in row[2:]):
            raise ValueError('USB transport unavailable, device offline or RSA unauthorized')
    return tuple(serials)


def inspect_artifact(apk, package, expected_hash, signer, build_tools):
    if package not in PACKAGES:
        raise ValueError('Only isolated debug package IDs approved for preparation')
    if not re.fullmatch(r'[a-f0-9]{64}', expected_hash or '') or digest(apk) != expected_hash:
        raise ValueError('Local APK SHA-256 mismatch')
    if not re.fullmatch(r'[a-f0-9]{64}', signer or ''):
        raise ValueError('Pinned lab signer SHA-256 required')
    info = inspect_apk(apk, package, build_tools, signer)
    badging = run([str(build_tools / 'aapt'), 'dump', 'badging', str(apk)])
    versions = re.findall(r"^package: name='[^']+' versionCode='([0-9]+)' versionName='([^']*)'", badging, re.M)
    if len(versions) != 1 or digest(apk) != expected_hash:
        raise ValueError('APK changed or version metadata unavailable')
    info.update(versionCode=versions[0][0], versionName=versions[0][1])
    return info


def preflight(adb, port, serials, artifact):
    prefix = adb_command(adb, port)
    select_pair(run([*prefix, 'devices', '-l']), serials)
    phones = []
    for serial in serials:
        command = [*prefix, '-s', serial]
        def prop(key):
            return run([*command, 'shell', 'getprop', key]).strip()
        qemu = prop('ro.kernel.qemu')
        boot_qemu = prop('ro.boot.qemu')
        api = prop('ro.build.version.sdk')
        if qemu not in ('', '0') or boot_qemu not in ('', '0') or not api.isdigit() or int(api) < 31:
            raise ValueError('Physical API 31+ device could not be established')
        model = prop('ro.product.model')
        if not model or len(model) > 200:
            raise ValueError('Physical model metadata unavailable')
        installed = installed_digest(command, artifact['package'])
        if installed is not None and installed != artifact['sha256']:
            raise ValueError('Installed APK SHA-256 mismatch; replacement prohibited')
        phones.append({'deviceSha256': hashlib.sha256(serial.encode()).hexdigest(),
                       'model': model, 'api': int(api), 'package': artifact['package'],
                       'versionCode': artifact['versionCode'], 'versionName': artifact['versionName'],
                       'apkSha256': artifact['sha256'], 'installedApkSha256': installed,
                       'installation': 'EXACT_BYTES_PRESENT' if installed else 'NOT_INSTALLED'})
    # Detect disconnects/extra devices during preflight as a failure, not acceptance.
    select_pair(run([*prefix, 'devices', '-l']), serials)
    return phones


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--server-port', type=int)
    parser.add_argument('--serial', action='append', required=True, help='Repeat exactly twice; keep identifiers private')
    parser.add_argument('--package', required=True, choices=PACKAGES)
    parser.add_argument('--app-apk', type=Path, required=True)
    parser.add_argument('--apk-sha256', required=True)
    parser.add_argument('--signer-sha256', required=True)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--reports', type=Path, required=True)
    args = parser.parse_args(argv)
    evidence = {'schema': 1, 'mode': 'READ_ONLY_PREFLIGHT', 'result': 'NOT_EXECUTED',
                'pairingExecuted': False, 'physicalAcceptance': False,
                'bootPropertiesAreAttestation': False,
                'pending': {case: 'MANUAL_PENDING' for case in PENDING},
                'installationAuthorization': 'NEW_EXPLICIT_OWNER_APPROVAL_REQUIRED'}
    code = 1
    try:
        args.reports.mkdir(parents=True, exist_ok=False, mode=0o700)
    except OSError:
        print('New private report directory required', file=sys.stderr)
        return 1
    try:
        artifact = inspect_artifact(args.app_apk, args.package, args.apk_sha256,
                                    args.signer_sha256, args.build_tools)
        evidence['artifact'] = artifact
        evidence['sourceHead'] = run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD']).strip()
        evidence['sourceTree'] = run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD^{tree}']).strip()
        evidence['sourceDirty'] = bool(run(['git', '-C', str(ROOT), 'status', '--porcelain']).strip())
        evidence['runnerSha256'] = digest(Path(__file__))
        # Validate before using identifiers in local lock paths or adb arguments.
        select_pair(run([*adb_command(args.adb, args.server_port), 'devices', '-l']), args.serial)
        with ExitStack() as stack:
            for serial in sorted(args.serial):
                stack.enter_context(device_lock(Path.home() / '.cache/umbra-physical', serial))
            evidence['devices'] = preflight(args.adb, args.server_port, args.serial, artifact)
        evidence['result'] = 'PREFLIGHT_READY' if all(d['installedApkSha256'] for d in evidence['devices']) else 'PENDING_INSTALL_APPROVAL'
        code = 0
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError):
        # No tool stdout/stderr, command strings, serials, APK paths or exception text.
        evidence['result'] = 'BLOCKED_PREFLIGHT'
    finally:
        report = json.dumps(evidence, indent=2) + '\n'
        for serial in args.serial:
            if serial:
                report = report.replace(serial, '[redacted-device]')
        path = args.reports / 'preflight.json'
        path.write_text(report)
        path.chmod(0o600)
    print(evidence['result'] + '; pairing acceptance remains MANUAL_PENDING')
    return code


if __name__ == '__main__':
    sys.exit(main())
