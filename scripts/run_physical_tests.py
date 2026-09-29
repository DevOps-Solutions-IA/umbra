#!/usr/bin/env python3
"""Explicit USB target, read-only preflight by default; audited synthetic cases only.

Never delegates to an AVD runner. No permission grants, sensors, shell settings,
user data inspection, uninstall, clear, downgrade, root or implicit device target.
"""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CONTENT = 'app.umbra.RestrictedContentAndroidTest#'
CASES = tuple(CONTENT + method for method in (
    'syntheticImageDecodedAfterSignalThenCannotReopenAfterSqliteReopen',
    'sqliteConsumeFailureDoesNotDeliverDecoderSession',
    'lockAfterPositiveRenderClosesDecoderAndRejectsLateFrame',
    'syntheticNoteEncodedSanitizedSignalDecodedAndConsumed',
    'notePreparationRejectsMalformedAndExpiredAuthorizationWithoutRecording',
)) + tuple('app.umbra.RestrictedDocumentAndroidTest#' + method for method in (
    'isolatedPreparationSignalAndNavigationShareOnePersistentOpening',
    'malformedAndTooManyPagesFailWithoutPoisoningNextPreparation',
    'admissionConsentAndManifestIsolationAreRequired',
)) + tuple('app.umbra.RestrictedVideoAndroidTest#' + method for method in (
    'nativeFilePlaybackDeliversChangingFramesAndCannotReplay',
    'lockAfterNativeVideoFramesClosesSurfaceAndRejectsOldSession',
))
PROPERTIES = ('ro.product.manufacturer', 'ro.product.model', 'ro.build.version.release',
              'ro.build.version.sdk', 'ro.build.version.security_patch', 'ro.product.cpu.abilist',
              'ro.kernel.qemu', 'ro.boot.verifiedbootstate', 'ro.boot.flash.locked')


def run(command, timeout=20):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise RuntimeError('Bounded command timed out') from None
    if result.returncode:
        # Never echo commands (serial) or unrestricted device/tool stderr.
        raise RuntimeError('Command failed, exit=' + str(result.returncode))
    return result.stdout


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def select_device(listing, serial):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', serial) or serial.startswith('emulator-'):
        raise ValueError('Explicit USB hardware serial required; no network or AVD target')
    rows = [line.split() for line in listing.splitlines() if line.strip() and not line.startswith('List of')]
    matches = [row for row in rows if row[0] == serial]
    if len(matches) != 1 or len(matches[0]) < 2 or matches[0][1] != 'device':
        raise RuntimeError('Selected device absent, offline or RSA unauthorized')
    # Other devices may exist, but are NEVER selected or touched.
    return serial


@contextmanager
def device_lock(directory, serial):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    key = hashlib.sha256(serial.encode()).hexdigest()
    with (directory / (key + '.lock')).open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError('Selected device already owned by another physical runner') from None
        try:
            yield directory / (key + '.json')
        finally:
            fcntl.flock(lock, fcntl.LOCK_UN)


def adb_command(adb, port=None):
    if port is None:
        return [adb]
    if not isinstance(port,int) or not 1024 <= port <= 65535:
        raise ValueError('Local ADB server port outside allowed range')
    return [adb, '-H', 'localhost', '-P', str(port)]


def physical_profile(adb, serial, port=None):
    prefix = adb_command(adb, port)
    select_device(run([*prefix, 'devices', '-l']), serial)
    command = [*prefix, '-s', serial]
    props = {key: run([*command, 'shell', 'getprop', key]).strip() for key in PROPERTIES}
    if props['ro.kernel.qemu'] == '1' or not props['ro.build.version.sdk'].isdigit():
        raise RuntimeError('Physical device/API could not be established')
    if int(props['ro.build.version.sdk']) < 31:
        raise RuntimeError('Device below the existing minSdk 31')
    battery = run([*command, 'shell', 'dumpsys', 'battery'])
    values = {}
    for key in ('level', 'scale', 'temperature'):
        match = re.search(r'^\s*' + key + r': (\d+)\s*$', battery, re.M)
        if not match:
            raise RuntimeError('Battery/temperature readiness unknown')
        values[key] = int(match[1])
    if values['scale'] <= 0 or values['level'] / values['scale'] < .2 or values['temperature'] >= 400:
        raise RuntimeError('Readiness refused: battery below 20% or temperature at least 40 C')
    return {'properties': props, 'battery': values, 'bootPropertiesAreAttestation': False}


def valid_receipt(text, code):
    starts, ends = [], []
    fields = {}
    for line in text.splitlines():
        entry = re.fullmatch(r'INSTRUMENTATION_STATUS: (class|test)=(.*)', line)
        if entry:
            fields[entry[1]] = entry[2]
        code_line = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if code_line:
            if set(fields) == {'class', 'test'}:
                case = fields['class'] + '#' + fields['test']
                if code_line[1] == '1': starts.append(case)
                elif code_line[1] == '0': ends.append(case)
            fields = {}
    return bool(code == 0 and sorted(starts) == sorted(ends) == sorted(CASES)
                and re.search(r'^OK \(' + str(len(CASES)) + r' tests\)\s*$', text, re.M)
                and 'INSTRUMENTATION_CODE: -1' in text
                and re.search(r'^INSTRUMENTATION_STATUS: physicalKeystoreLevel=(SOFTWARE|TEE|STRONGBOX|UNKNOWN)\s*$', text, re.M)
                and not re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', text)
                and not any(token in text for token in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')))


def package(flavor, optimized):
    if flavor not in ('connected', 'offline'):
        raise ValueError('Unknown flavor')
    return 'app.umbra.privatechat' + ('.offline' if flavor == 'offline' else '') + ('.vaultlab' if optimized else '.dev')


def inspect_apk(apk, expected, tools, signer, target=None):
    badging = run([str(tools / 'aapt'), 'dump', 'badging', str(apk)])
    names = re.findall(r"^package: name='([^']+)'", badging, re.M)
    if names != [expected]:
        raise RuntimeError('APK applicationId mismatch')
    cert = run([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], 60)
    hashes = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$', cert, re.M)
    if hashes != [signer]:
        raise RuntimeError('APK signature differs from explicitly pinned lab certificate')
    if target:
        tree = run([str(tools / 'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'])
        from check_apk_policy import decode_tree
        root = decode_tree(tree)
        instrumentation = root.findall('instrumentation')
        if len(instrumentation) != 1 or instrumentation[0].get('android:targetPackage') != target:
            raise RuntimeError('Test APK target mismatch')
        if instrumentation[0].get('android:name') != 'androidx.test.runner.AndroidJUnitRunner':
            raise RuntimeError('Unexpected instrumentation runner')
    with zipfile.ZipFile(apk) as archive:
        abis = sorted({name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')})
    return {'sha256': digest(apk), 'package': expected, 'signerSha256': signer, 'abis': abis,
            'permissions': sorted(re.findall(r"uses-permission: name='([^']+)'", badging))}


def installed_digest(command, pkg):
    result = subprocess.run([*command, 'shell', 'pm', 'path', pkg], capture_output=True, text=True, timeout=20)
    if result.returncode not in (0,1) or result.stderr.strip():
        raise RuntimeError('Installed package query failed')
    paths = result.stdout.strip().splitlines()
    if not paths:
        return None
    if result.returncode or len(paths) != 1 or not re.fullmatch(r'package:/data/app/[A-Za-z0-9_~+=/.-]+/base\.apk', paths[0]):
        raise RuntimeError('Installed package layout ambiguous')
    text = run([*command, 'shell', 'sha256sum', paths[0][8:]])
    if not re.match(r'^[a-f0-9]{64}\s', text):
        raise RuntimeError('Installed APK digest unavailable')
    return text.split()[0]


def assert_no_collision(installed, owned, info, update_owned=False):
    if installed and (installed != owned or (installed != info['sha256'] and not update_owned)):
        raise RuntimeError('Existing package ownership/hash mismatch; replacement refused')


def apk_path_for_adb(adb, apk):
    if adb.lower().endswith('.exe'):
        return run(['wslpath', '-w', str(apk.resolve())]).strip()
    return str(apk.resolve())


def inspect_optimized(apk, flavor, tools, mapping, receipt):
    from copy import deepcopy
    from check_apk_policy import decode_tree, validate_manifest, validate_resources, validate_dex
    from run_privacy_tests import optimized_classes
    tree = decode_tree(run([str(tools/'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml']))
    if tree.get('package') != package(flavor, True):
        raise RuntimeError('Unexpected optimized laboratory applicationId')
    # Only the isolated applicationId differs from the production manifest policy.
    # Do not normalize debuggable/testOnly/permissions/components or other flags.
    normalized = deepcopy(tree)
    normalized.set('package', 'app.umbra.privatechat' + ('.offline' if flavor == 'offline' else ''))
    refs = validate_manifest(normalized, flavor, 'release')
    table = run([str(tools/'aapt'), 'dump', '--values', 'resources', str(apk)])
    resources = []
    for ref in refs:
        paths = set(re.findall(r'resource ' + ref[1:] + r' [^\n]+\n\s*\(string8\) "([^"]+)"', table))
        if len(paths) != 1:
            raise RuntimeError('Ambiguous optimized policy resource')
        resources.append(decode_tree(run([str(tools/'aapt'), 'dump', 'xmltree', str(apk), paths.pop()])))
    validate_resources(*resources)
    with zipfile.ZipFile(apk) as archive:
        validate_dex(archive)
    configuration = mapping.with_name('configuration.txt')
    receipt['optimizedClasses'] = optimized_classes(mapping.read_text(), configuration.read_text())
    receipt['mappingSha256'] = digest(mapping)
    receipt['configurationSha256'] = digest(configuration)
    receipt['exactProductionApk'] = False


def install_outcome(code, stdout, stderr):
    if code == 0 and re.search(r'^Success\s*$', stdout, re.M):
        return {'exitCode': code, 'result': 'SUCCESS', 'code': 'SUCCESS'}
    match=re.search(r'\b(INSTALL_(?:FAILED|PARSE_FAILED)_[A-Z_]+)\b',stdout+'\n'+stderr)
    return {'exitCode': code, 'result': 'FAILED', 'code': match[1] if match else 'INSTALL_COMMAND_FAILED'}


def execute(args, profile, state_path, receipt):
    pkg = package(args.flavor, args.optimized)
    tools = Path(args.sdk) / 'build-tools' / '35.0.0'
    if not re.fullmatch(r'[a-f0-9]{64}', args.signer_sha256 or ''):
        raise ValueError('Pin the locally built lab signer SHA-256 before installation')
    sources = [Path(args.app_apk).resolve(), Path(args.test_apk).resolve()]
    snapshot=args.reports/'artifacts';snapshot.mkdir(exist_ok=False)
    apks=[]
    for source in sources:
        before=digest(source);destination=snapshot/source.name
        if destination.exists():raise RuntimeError('Ambiguous artifact names')
        shutil.copyfile(source,destination)
        if digest(destination)!=before or digest(source)!=before:raise RuntimeError('Build changed while snapshotting APK')
        destination.chmod(0o400);apks.append(destination.resolve())
    # Inspect both artifacts before touching either package.
    infos = [inspect_apk(apks[0], pkg, tools, args.signer_sha256),
             inspect_apk(apks[1], pkg + '.test', tools, args.signer_sha256, pkg)]
    if not set(infos[0]['abis']).intersection(profile['properties']['ro.product.cpu.abilist'].split(',')):
        raise RuntimeError('APK JNI ABI incompatible with selected phone')
    from check_apk_policy import inspect
    # Existing complete manifest/backup/TLS/permission guard for debug product.
    if not args.optimized:
        inspect(apks[0], tools / 'aapt', args.flavor, 'debug')
        from check_debug_apks import inspect as inspect_jni_permissions
        inspect_jni_permissions(apks[0], tools / 'aapt', args.flavor)
    else:
        mapping=ROOT/'android/app/build/outputs/mapping'/(args.flavor+'VaultLab')/'mapping.txt'
        inspect_optimized(apks[0], args.flavor, tools, mapping, receipt)
        from check_debug_apks import inspect as inspect_jni_permissions
        inspect_jni_permissions(apks[0], tools / 'aapt', args.flavor)
    receipt['artifacts'] = infos
    owned = json.loads(state_path.read_text()) if state_path.exists() else {}
    command = [*adb_command(args.adb,args.server_port), '-s', args.serial]
    installs = []
    for info, apk in zip(infos, apks):
        installed = installed_digest(command, info['package'])
        assert_no_collision(installed, owned.get(info['package']), info, args.update_owned)
        if installed != info['sha256']:
            installs.append((info, apk, installed))
    for info, apk, previous in installs:
        if digest(apk) != info['sha256']:
            raise RuntimeError('APK changed after inspection')
        flags=['-t'] if previous is None else ['-t','-r']
        result = subprocess.run([*command, 'install', *flags, apk_path_for_adb(args.adb, apk)],
                                capture_output=True, text=True, timeout=180)
        # Only fixed Android error code, never arbitrary installer stderr or paths.
        outcome = install_outcome(result.returncode, result.stdout, result.stderr)
        receipt.setdefault('installAttempts', []).append({'package': info['package'], 'previousSha256': previous, **outcome})
        if outcome['result'] != 'SUCCESS':
            raise RuntimeError('Isolated APK install failed: ' + outcome['code'])
        if installed_digest(command, info['package']) != info['sha256']:
            raise RuntimeError('Installed bytes differ from inspected APK')
        owned[info['package']] = info['sha256']
        state_path.write_text(json.dumps(owned, indent=2) + '\n')
        state_path.chmod(0o600)
    started = time.monotonic_ns()
    try:
        result = subprocess.run([*command, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', ','.join(CASES),
                                 '-e', 'physicalSafe', 'true', '-e', 'listener', 'app.umbra.PhysicalKeystoreFixture', pkg + '.test/androidx.test.runner.AndroidJUnitRunner'],
                                capture_output=True, text=True, timeout=180)
        text = result.stdout + result.stderr
        # Only instrumented app output, no global logcat or bugreport. Remove serial if present.
        text = text.replace(args.serial, '[selected-device]')
        (args.reports / 'instrumentation.log').write_text(text)
        receipt.update(exitCode=result.returncode, elapsedNanos=time.monotonic_ns() - started)
        if not valid_receipt(text, result.returncode):
            raise RuntimeError('Physical instrumentation did not pass every selected case')
        receipt['result'] = 'PASS'
    except subprocess.TimeoutExpired as expired:
        def printable(value):
            return value.decode(errors='replace') if isinstance(value,bytes) else (value or '')
        partial=(printable(expired.stdout)+printable(expired.stderr)).replace(args.serial,'[selected-device]')
        (args.reports/'instrumentation.log').write_text(partial)
        receipt.update(exitCode=None, elapsedNanos=time.monotonic_ns()-started, timedOut=True)
        raise RuntimeError('Physical instrumentation timed out; partial output retained') from None
    finally:
        # Only our preflighted, owned package. No caller-selected arbitrary shell operations.
        run([*command, 'shell', 'am', 'force-stop', pkg])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--server-port', type=int, help='Explicit existing localhost ADB server; never kills/restarts a server')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--safe', required=True, action='store_true')
    parser.add_argument('--flavor', choices=('connected', 'offline'), default='offline')
    parser.add_argument('--reports', required=True, type=Path)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--update-owned', action='store_true', help='Explicitly replace ONLY a runner-owned exact installed hash; retain data, no downgrade')
    parser.add_argument('--optimized', action='store_true')
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--app-apk')
    parser.add_argument('--test-apk')
    parser.add_argument('--signer-sha256')
    args = parser.parse_args()
    args.reports.mkdir(parents=True, exist_ok=False)  # Never overwrite an earlier attempt.
    evidence = {'adbServerPort': args.server_port or 5037, 'layer': 'PHYSICAL_LAB', 'result': 'NOT_EXECUTED', 'cases': CASES,
                'productionAuthenticationTested': False, 'physicalTwoPeerTested': False,
                'sourceHead': run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD']).strip(),
                'sourceTree': run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD^{tree}']).strip(),
                'sourceStatus': run(['git', '-C', str(ROOT), 'status', '--short']).splitlines(),
                'runnerSha256': digest(Path(__file__)),
                'pending': {'authenticatedVault': 'MANUAL_PENDING', 'RFCOMM': 'NEEDS_SECOND_PEER',
                            'globalPacketAbsence': 'BLOCKED_OBSERVABILITY', 'cameraMicrophoneLocation': 'MANUAL_PENDING'}}
    try:
        with device_lock(Path.home() / '.cache/umbra-physical-locks', args.serial) as state:
            evidence['adbVersion'] = run([*adb_command(args.adb,args.server_port), 'version']).splitlines()[:2]
            profile = physical_profile(args.adb, args.serial, args.server_port)
            evidence['device'] = profile
            if args.execute:
                if not all((args.sdk, args.app_apk, args.test_apk)):
                    raise ValueError('Explicit SDK and both local APKs required')
                execute(args, profile, state, evidence)
            else:
                evidence['result'] = 'PREFLIGHT_ONLY'
    except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as failure:
        evidence['result'] = 'BLOCKED_OR_FAILED'
        evidence['reason'] = str(failure).replace(args.serial, '[selected-device]')[:300]
        raise SystemExit(evidence['reason']) from None
    finally:
        (args.reports / 'receipt.json').write_text(json.dumps(evidence, indent=2) + '\n')


if __name__ == '__main__':
    main()
