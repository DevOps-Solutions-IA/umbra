#!/usr/bin/env python3
"""Synthetic Android codecs + two Engine stores + real loopback HTTPS; no physical audio."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time


def valid_report(text, code):
    return (code == 0 and 'restrictedHttps=PASS real HTTPS admission Signal AAC and isolated PDF both directions and consumption' in text
            and re.search(r'^OK \(3 tests\)$', text, re.M)
            and 'INSTRUMENTATION_CODE: -1' in text
            and not re.search(r'INSTRUMENTATION_STATUS_CODE: -(?:1|2|3|4)\b', text)
            and not any(x in text for x in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--optimized', action='store_true')
    parser.add_argument('--reports', required=True, type=Path)
    args = parser.parse_args()
    adb = [str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'), '-s', args.serial]
    package = 'app.umbra.privatechat.' + ('vaultlab' if args.optimized else 'dev')
    def run(*command, **kwargs):
        return subprocess.run([*adb, *command], check=True, capture_output=True, timeout=30, **kwargs)
    if run('shell', 'getprop', 'ro.kernel.qemu').stdout.strip() != b'1':
        raise RuntimeError('Restricted HTTPS requires owned disposable AOSP AVD')
    listed = run('shell', 'cmd', 'package', 'list', 'packages', '-U', package).stdout.decode()
    matches = re.findall(r'^package:'+re.escape(package)+r' uid:(\d+)$', listed, re.M)
    if len(matches) != 1 or not 10000 <= int(matches[0]) <= 19999:
        raise RuntimeError('Unknown isolated fixture UID')
    def app(script, data=None, check=True):
        # AOSP laboratory UID only. No debuggable/R8/production policy change.
        command = [*adb, 'shell', 'su', matches[0], 'sh', '-c',
                   shlex.quote('cd /data/user/0/'+package+' && '+script)]
        return subprocess.run(command, input=data, capture_output=True, check=check, timeout=15)
    def write(name, value):
        app('umask 077; cat > files/'+name+'.tmp && mv files/'+name+'.tmp files/'+name,
            json.dumps(value).encode())
    names = ['synthetic-restricted-https.json', 'synthetic-restricted-https.json.tmp']
    for role in ('a', 'b'):
        names += ['synthetic-note-'+role+'-'+suffix for suffix in
                  ('request.json', 'request.tmp', 'credential.json', 'credential.json.tmp')]
    run('shell', 'am', 'force-stop', package)
    app('mkdir -p files')
    app('rm -f '+' '.join('files/'+name for name in names))
    args.reports.mkdir(parents=True, exist_ok=True)
    log = args.reports/'restricted-https.log'
    process = None
    from voice_relay_lab import voice_relay
    try:
        with voice_relay() as relay, log.open('w') as stream:
            write('synthetic-restricted-https.json', {'base': relay['base'], 'certificate': relay['certificate'],
                'realm': relay['admission'].realm.encode(), 'invitations': relay['invitations']})
            process = subprocess.Popen([*adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                'app.umbra.DeviceSignalTest', '-e', 'listener', 'app.umbra.RestrictedHttpsFixtureListener',
                package+'.test/androidx.test.runner.AndroidJUnitRunner'], stdout=stream, stderr=subprocess.STDOUT)
            for role in ('a', 'b'):
                deadline = time.monotonic()+40
                while True:
                    if process.poll() is not None or time.monotonic() >= deadline:
                        raise RuntimeError('Restricted enrollment failed; inspect sanitized instrumentation log')
                    response = app('cat files/synthetic-note-'+role+'-request.json', check=False)
                    if response.returncode == 0:
                        request = json.loads(response.stdout)
                        write('synthetic-note-'+role+'-credential.json', relay['admission'].approve(request['request']))
                        break
                    if response.returncode != 1:
                        raise RuntimeError('Restricted admission exchange failed')
                    time.sleep(0.1)
            code = process.wait(timeout=90)
        if not valid_report(log.read_text(), code):
            raise RuntimeError('Restricted HTTPS/codec acceptance failed; inspect '+str(log))
        (args.reports/'restricted-https.json').write_text(json.dumps({
            'result': 'PASS', 'engines': 2, 'androidProcesses': 1, 'transport': 'real isolated HTTPS',
            'admissionProof': True, 'signal': True, 'nativeAac': True, 'isolatedPdfPages': True, 'directions': 2,
            'physicalAudio': False, 'storage': 'synthetic-plaintext-SQLite-test-adapter'}, indent=2)+'\n')
    finally:
        try:
            run('shell', 'am', 'force-stop', package)
        finally:
            if process is not None and process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
            app('rm -f '+' '.join('files/'+name for name in names))


if __name__ == '__main__':
    main()
