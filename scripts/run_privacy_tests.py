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


PACKAGES = frozenset('app.umbra.privatechat' + offline + suffix
                     for offline in ('', '.offline') for suffix in ('.dev', '.vaultlab'))
EXIT_REASONS = {0: 'UNKNOWN', 1: 'EXIT_SELF', 2: 'SIGNALED', 3: 'LOW_MEMORY', 4: 'CRASH',
                5: 'CRASH_NATIVE', 6: 'ANR', 7: 'INITIALIZATION_FAILURE', 8: 'PERMISSION_CHANGE',
                9: 'EXCESSIVE_RESOURCE_USAGE', 10: 'USER_REQUESTED', 11: 'USER_STOPPED',
                12: 'DEPENDENCY_DIED', 13: 'OTHER', 14: 'FREEZER', 15: 'PACKAGE_STATE_CHANGE',
                16: 'PACKAGE_UPDATED'}


def bounded_diagnostic(command, *, limit=65536, timeout=5):
    """Read a bounded pipe into memory only; never save raw device diagnostics."""
    import selectors
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    chunks = bytearray(); state = 'COMPLETE'; deadline = time.monotonic() + timeout
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    state = 'TIMEOUT'; break
                if not selector.select(remaining):
                    state = 'TIMEOUT'; break
                part = os.read(process.stdout.fileno(), min(4096, limit + 1 - len(chunks)))
                if not part:
                    break
                chunks.extend(part)
                if len(chunks) > limit:
                    state = 'OUTPUT_LIMIT'; break
        if state == 'COMPLETE':
            try:
                process.wait(timeout=max(0.01, min(1, deadline - time.monotonic())))
            except subprocess.TimeoutExpired:
                state = 'TIMEOUT'
    finally:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=5)
        process.stdout.close()
    if state == 'COMPLETE' and process.returncode != 0:
        state = 'NONZERO_EXIT'
    return (chunks.decode('utf-8', errors='replace') if state == 'COMPLETE' else '',
            {'state': state, 'exitCode': process.returncode})


def summarize_exit_info(text, package):
    if package not in PACKAGES:
        raise ValueError('Not an isolated privacy target')
    records = []
    for block in re.split(r'(?m)^\s*ApplicationExitInfo ', text)[1:]:
        # The package-specific command is insufficient: correlate every record.
        match = re.search(r'(?m)^\s*process=' + re.escape(package)
                          + r' reason=(\d{1,3}) \([^\r\n]*?\) subreason=\d+ \([^\r\n]*?\) status=(-?\d{1,10})$', block)
        if not match:
            continue
        item = {'reason': EXIT_REASONS.get(int(match[1]), 'UNKNOWN'), 'status': int(match[2])}
        timestamp = re.search(r'(?m)^\s*timestamp=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d{1,3})?) pid=', block)
        if timestamp:
            item['timestamp'] = timestamp[1]
        memory = re.search(r'(?m)^\s*importance=(\d{1,4}) pss=([0-9.]{1,12}[KMGT]?) rss=([0-9.]{1,12}[KMGT]?) ', block)
        if memory:
            item.update(importance=int(memory[1]), pss=memory[2], rss=memory[3])
        records.append(item)
    return records[:4]


def summarize_proc_status(text):
    result = {}
    for name in ('Threads', 'VmPeak', 'VmSize', 'VmRSS', 'VmSwap'):
        match = re.search(r'(?m)^' + name + r':\s+(\d{1,12})(?: kB)?$', text)
        if match:
            result[name] = int(match[1])
    state = re.search(r'(?m)^State:\s+([RSDZTtXIP])(?:\s|$)', text)
    if state:
        result['state'] = state[1]
    return result


def collect_failure_diagnostics(adb, package, reports, phase, reason, *, process_alive=None):
    """Failure-only, before cleanup. Collection cannot replace the original failure."""
    if package not in PACKAGES or phase not in ('PREPARE', 'VERIFY', 'SUITE') or reason not in ('DEADLINE', 'PROCESS_EXIT', 'INVALID_REPORT', 'COMMAND_FAILURE'):
        raise ValueError('Invalid privacy diagnostic scope')
    report = {'phase': phase, 'failureReason': reason, 'beforeHostCleanup': True,
              'instrumentationClientAliveAtFailure': process_alive,
              'collectedWallTime': time.time(), 'rawLogPersisted': False, 'collections': {}}
    def read(name, command):
        try:
            text, status = bounded_diagnostic([*adb, *command])
        except (OSError, subprocess.SubprocessError):
            text, status = '', {'state': 'UNAVAILABLE'}
        report['collections'][name] = status
        return text
    exits = read('exitInfo', ['shell', 'dumpsys', 'activity', 'exit-info', package])
    report['ownedExitRecords'] = summarize_exit_info(exits, package)
    report['exitRecordScope'] = 'PACKAGE_HISTORY_NOT_PROOF_OF_CURRENT_FAILURE'
    crash = read('crashBuffer', ['logcat', '-b', 'crash', '-d', '-t', '300'])
    from media_crash_diagnostic import summarize
    report['crash'] = summarize(crash, package)
    # Keep only fixed exception enums; no arbitrary class, message or stack text.
    known = {'java.lang.OutOfMemoryError', 'java.lang.IllegalStateException',
             'java.lang.IllegalArgumentException', 'java.lang.AssertionError',
             'java.lang.NullPointerException', 'java.lang.SecurityException',
             'java.io.IOException', 'android.media.MediaCodec$CodecException'}
    for record in report['crash']['ownedCrashRecords']:
        record['exceptionTypes'] = sorted({name if name in known else 'OTHER_EXCEPTION_TYPE'
                                          for name in record['exceptionTypes']})
    pid = read('pid', ['shell', 'pidof', package]).strip()
    report['targetPidPresent'] = bool(re.fullmatch(r'[1-9]\d{0,9}', pid))
    if report['targetPidPresent']:
        report['liveProcess'] = summarize_proc_status(read('processStatus', ['shell', 'cat', '/proc/' + pid + '/status']))
        stack = read('nativeBacktrace', ['shell', 'debuggerd', '-b', pid])
        correlated = bool(re.search(r'(?m)^Cmd line: ' + re.escape(package) + r'$', stack))
        report['liveNativeStack'] = {'ownedPackageCorrelated': correlated,
            'sites': [site for site in ('MediaCodec::stop', 'MediaCodec::release', 'CCodec::initiateStop',
                'CCodec::initiateRelease', 'AMessage::postAndAwaitResponse', 'ALooper::loop',
                'pthread_cond_wait', 'pthread_cond_timedwait', '__futex_wait_ex',
                'MediaMuxer::stop', 'MPEG4Writer::stop', 'BinderProxy_transact')
                if correlated and site in stack], 'rawStackPersisted': False}
    memory = read('systemMemory', ['shell', 'cat', '/proc/meminfo'])
    report['systemMemoryKb'] = {name: int(match[1]) for name in ('MemTotal', 'MemAvailable', 'MemFree', 'SwapTotal', 'SwapFree')
                               if (match := re.search(r'(?m)^' + name + r':\s+(\d{1,12}) kB$', memory))}
    try:
        (reports / ('privacy-failure-' + phase.lower() + '.json')).write_text(json.dumps(report, indent=2) + '\n')
    except OSError:
        # The caller must still raise its original instrumentation failure.
        pass


def valid_restart_report(text, code):
    return (code == 0 and 'restrictedRestart=PASS formats=PNG,AAC_ADTS,PDF_PAGES,AVC_MP4' in text
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
            while 'restrictedRestart=READY formats=PNG,AAC_ADTS,PDF_PAGES,AVC_MP4' not in before.read_text():
                alive = process.poll() is None
                if not alive or time.monotonic() >= deadline:
                    collect_failure_diagnostics(adb, package, reports, 'PREPARE',
                                                'DEADLINE' if alive else 'PROCESS_EXIT', process_alive=alive)
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
        try:
            result = subprocess.run([*command, 'verify', runner], stdout=stream, stderr=subprocess.STDOUT, timeout=60)
        except subprocess.SubprocessError:
            collect_failure_diagnostics(adb, package, reports, 'VERIFY', 'COMMAND_FAILURE')
            raise
    if not valid_restart_report(after.read_text(), result.returncode):
        collect_failure_diagnostics(adb, package, reports, 'VERIFY', 'INVALID_REPORT')
        raise RuntimeError('Restricted restart/duplicate rejection not verified; inspect ' + str(after))
    (reports / 'restricted-restart.json').write_text(json.dumps({
        'formats': ['PNG', 'AAC_ADTS', 'PDF_PAGES', 'AVC_MP4'], 'result': 'PASS', 'positiveDecodeRenderBeforeKill': True, 'hostForceStop': True,
        'deathDuringCommit': False, 'storage': 'synthetic-plaintext-SQLite-test-adapter',
        'productionKeystore': False, 'consumedReopenRejected': True,
        'postRestartDuplicateRejected': True}, indent=2) + '\n')


def valid_report(text, code, expected=20):
    return (expected in (20, 21) and code == 0 and re.search(r'^OK \('+str(expected)+r' tests\)$', text, re.M)
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
                 'app.umbra.content.RestrictedDocuments$Decoder',
                 'app.umbra.content.RestrictedVideo'):
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
    classes = 'app.umbra.PrivacyAdaptersAndroidTest,app.umbra.RestrictedContentAndroidTest,app.umbra.RestrictedDocumentAndroidTest,app.umbra.RestrictedVideoAndroidTest'
    expected = 21 if args.flavor == 'connected' else 20
    if args.flavor == 'connected':
        classes += ',app.umbra.RestrictedRecordingAndroidTest'
    log = args.reports / 'privacy-tests.log'
    with log.open('w') as stream:
        try:
            result = subprocess.run([*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                classes, '-e', 'syntheticNoHostAudio', 'true', package + '.test/androidx.test.runner.AndroidJUnitRunner'],
                stdout=stream, stderr=subprocess.STDOUT, timeout=180)
        except subprocess.SubprocessError:
            collect_failure_diagnostics(adb, package, args.reports, 'SUITE', 'COMMAND_FAILURE')
            raise
    if not valid_report(log.read_text(), result.returncode, expected):
        collect_failure_diagnostics(adb, package, args.reports, 'SUITE', 'INVALID_REPORT')
        raise RuntimeError(f'Privacy instrumentation did not pass all {expected} cases: {log}')
    evidence['result'] = 'PASS'
    (args.reports / 'receipt.json').write_text(json.dumps(evidence, indent=2) + '\n')


if __name__ == '__main__':
    main()
