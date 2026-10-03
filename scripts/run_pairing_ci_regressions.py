#!/usr/bin/env python3
"""Focused R8/two-AVD media regressions before the full matrix; no retries or fallback."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import time

from run_voice_integration import valid_audio, require_staged_run

ROOT = Path(__file__).resolve().parents[1]
APK_NAMES = ('connected/mediaLab/app-connected-mediaLab.apk',
             'androidTest/connected/mediaLab/app-connected-mediaLab-androidTest.apk')
CASES = (('expired-auth', ('--scenario', 'expired-auth')),
         ('ipv6-tls', ('--turn-ipv6', '--turn-tls', 'valid')),
         ('degraded-network', ('--scenario', 'degraded-network')))
VOICE_FIELDS = {'apkSha256', 'synthetic', 'endpoints', 'observedSeconds', 'transport', 'turnTlsCase',
                'scenario', 'directIpv4Reachability', 'directBlockedDuringMedia', 'muteUnmute',
                'muteBarrier', 'network', 'audio', 'allocationExpiry', 'nativeCaptureClosure', 'networkImpairment'}


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def pair(value):
    return isinstance(value, list) and len(value) == 2 and all(isinstance(row, dict) for row in value)


def hashes(value):
    return (isinstance(value, dict) and set(value) == set(APK_NAMES)
            and all(isinstance(item, str) and re.fullmatch('[0-9a-f]{64}', item) for item in value.values()))


def valid_video(video, apk_hashes):
    if (not isinstance(video, dict) or video.get('apkSha256') != apk_hashes
            or video.get('synthetic') is not True or video.get('physicalCamera') is not False
            or video.get('nativeCodecPipeline') is not True or type(video.get('endpoints')) is not int
            or video['endpoints'] != 2 or set(video.get('phases', {})) != {'active', 'stopped', 'resumed'}):
        return False
    phases = video['phases']
    if not all(pair(rows) for rows in phases.values()):
        return False
    for name, generation in (('active', 2), ('resumed', 3)):
        for row in phases[name]:
            if (not integer(row.get('decodedRemotePatterns'), 20) or type(row.get('distinctPatternPhases')) is not int
                    or row['distinctPatternPhases'] != 2 or not integer(row.get('decodedAudioDuringVideo'), 50)
                    or not integer(row.get('capturedFrames'), 20) or row.get('sendPermitted') is not True
                    or row.get('receivePermitted') is not True or row.get('sdpAddressAudit') is not True
                    or type(row.get('generation')) is not int or row['generation'] != generation
                    or row.get('videoCodec') not in ('video/VP8', 'video/VP9', 'video/AV1')):
                return False
    return all(row.get('captureStopped') is True and row.get('captureWasAuthorized') is True
               and integer(row.get('decodedAudioAfterVideoOff'), 50)
               and type(row.get('captureCallbacksAfterRequest')) is int and row['captureCallbacksAfterRequest'] == 0
               and integer(row.get('observedAfterRequestMillis'), 1000) for row in phases['stopped'])


def valid_receipts(case, voice, video=None, *, expected_hashes=None):
    """Validate receipts, not an emulator substitute; tests using dictionaries prove only this predicate."""
    try:
        if (case not in dict(CASES) or not isinstance(voice, dict) or set(voice) not in (VOICE_FIELDS, VOICE_FIELDS | {'captureFinalization'})
                or not hashes(voice['apkSha256']) or expected_hashes is not None and voice['apkSha256'] != expected_hashes
                or voice['synthetic'] is not True or type(voice['endpoints']) is not int or voice['endpoints'] != 2
                or type(voice['observedSeconds']) not in (int, float) or not math.isfinite(voice['observedSeconds'])
                or voice['observedSeconds'] <= 0 or voice['directIpv4Reachability'] is not True
                or voice['directBlockedDuringMedia'] is not False or not pair(voice['network']) or not pair(voice['audio'])
                or voice['allocationExpiry'] is not None or voice['nativeCaptureClosure'] != []
                or case != 'degraded-network' and voice['networkImpairment'] != []):
            return False
        if case == 'expired-auth':
            if (voice['scenario'] != 'expired-auth' or voice['transport'] != 'native WebRTC through coturn UDP'
                    or voice['turnTlsCase'] is not None or voice['muteUnmute'] is not False or voice['muteBarrier'] is not None
                    or video is not None or not all(set(row) == {'rejectedBeforeCapture'}
                        and row['rejectedBeforeCapture'] is True for row in voice['audio'])):
                return False
            for index, row in enumerate(voice['network']):
                if (row.get('scope') != 'owned AVD outbound IPv4 UDP' or row.get('ipv6') != 'NOT_EXECUTED'
                        or not integer(row.get('turnPackets'), 1 if index == 0 else 0)
                        or not integer(row.get('turnStunPackets'), 1 if index == 0 else 0)
                        or row['turnStunPackets'] > row['turnPackets']
                        or any(type(row.get(key)) is not int or row[key] != 0
                               for key in ('nonTurnStunPackets', 'otherNonSystemUdpPackets'))
                        or row.get('mediaAttempt') != ('OBSERVED' if row['turnPackets'] else
                            'NOT_EXECUTED: no remote description after initiator rejection')):
                    return False
            return True
        degraded = case == 'degraded-network'
        if (voice['scenario'] != ('degraded-network' if degraded else 'audio')
                or voice['transport'] != ('native WebRTC through coturn UDP' if degraded else 'native WebRTC through coturn TLS')
                or voice['turnTlsCase'] != (None if degraded else 'valid') or voice['muteUnmute'] is not True):
            return False
        for row in voice['audio']:
            if (not valid_audio(row) or row.get('nativeRelayProtocol') != ('udp' if degraded else 'tls')
                    or not all(integer(row.get(key), 1) for key in ('decodedBuffers', 'capturedBuffers', 'receivedAudioPackets'))):
                return False
        for row in voice['network']:
            if degraded:
                if (row.get('scope') != 'owned AVD outbound IPv4 UDP' or row.get('ipv6') != 'NOT_EXECUTED'
                        or not integer(row.get('turnPackets'), 1) or not integer(row.get('turnStunPackets'), 1)
                        or row['turnStunPackets'] > row['turnPackets']
                        or any(type(row.get(key)) is not int or row[key] != 0
                               for key in ('nonTurnStunPackets', 'otherNonSystemUdpPackets'))):
                    return False
            elif (row.get('scope') != 'owned Wi-Fi IPv4+IPv6 TCP/UDP; IPv6 client-to-TURN, IPv4 relay allocation'
                    or row.get('transport') != 'TLS' or not integer(row.get('turnIpv6Packets'), 1)
                    or type(row.get('nonAuthorizedTcpUdpPackets')) is not int or row['nonAuthorizedTcpUdpPackets'] != 0
                    or row.get('allIpv6Allocation') != 'NOT_EXECUTED: pinned allocator uses default IPv4 allocation'):
                return False
        if degraded:
            if not pair(voice['networkImpairment']):
                return False
            for row in voice['networkImpairment']:
                if (set(row) != {'delayMillis', 'lossPercent', 'rateKbit', 'queuePacketLimit', 'processedPackets', 'droppedPackets'}
                        or any(type(row.get(key)) is not int or row[key] != value for key,value in
                            (('delayMillis',80),('lossPercent',2),('rateKbit',128),('queuePacketLimit',20)))
                        or not integer(row['processedPackets'],1) or not integer(row['droppedPackets'],1)):
                    return False
        mute = voice['muteBarrier']
        if not isinstance(mute, dict) or set(mute) != {'applied', 'observed'} or not all(pair(rows) for rows in mute.values()):
            return False
        if not all(row.get('applied') is True and integer(row.get('elapsedMillis'), 1) for row in mute['applied']):
            return False
        if not all(row.get('quiet') is True and integer(row.get('observedMillis')) and 1200 <= row['observedMillis'] <= 2500
                   and integer(row.get('decodedTones')) and row['decodedTones'] <= 3 for row in mute['observed']):
            return False
        return valid_video(video, voice['apkSha256'])
    except (KeyError, TypeError, ValueError, AttributeError, OverflowError):
        return False


def load_receipt(path):
    if not path.is_file() or path.stat().st_size > 262144:
        raise ValueError('Missing or oversized receipt')
    def unique_pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate receipt key')
            result[key] = value
        return result
    return json.loads(path.read_text(), object_pairs_hook=unique_pairs)


def execute(a, b, reports, apk_hashes):
    if a == b or any(not re.fullmatch(r'emulator-[0-9]+', serial) for serial in (a, b)):
        raise ValueError('Two distinct AVD serials required')
    if not hashes(apk_hashes):
        raise ValueError('Both mediaLab APK hashes required')
    reports = reports.resolve()
    reports.mkdir(parents=True, exist_ok=False)
    results = []
    for name, options in CASES:
        case_dir = reports / name
        command = [sys.executable, str(ROOT / 'scripts/run_voice_integration.py'), '--a', a, '--b', b,
                   '--video', '--optimized', *options, '--reports', str(case_dir)]
        start = time.monotonic()
        with (reports / (name + '-driver.log')).open('w') as log:
            completed = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        accepted = False
        try:
            voice = load_receipt(case_dir / 'voice-evidence.json')
            video_path = case_dir / 'video-evidence.json'
            video = load_receipt(video_path) if video_path.exists() else None
            accepted = valid_receipts(name, voice, video, expected_hashes=apk_hashes)
        except (OSError, ValueError):
            pass  # Explicit FAIL below; never turn missing/invalid receipts into acceptance.
        staged = False
        pending = case_dir / 'network-pending.json'
        if completed.returncode == 0 and pending.is_file():
            require_staged_run(pending)
            staged = True
        success = completed.returncode == 0 and accepted
        results.append({'case': name, 'exitCode': completed.returncode, 'receiptAccepted': accepted,
                        'status': 'PASS' if success else 'PENDING_CAPTURE_FINALIZATION' if staged else 'FAIL', 'seconds': round(time.monotonic() - start, 3)})
        (reports / 'regressions.json').write_text(json.dumps({'optimized': True, 'video': True, 'endpoints': 2,
            'apkSha256': apk_hashes, 'cases': results}, indent=2) + '\n')
        print(name + ': ' + results[-1]['status'], flush=True)
    return 0 if len(results) == len(CASES) and all(row['status'] in ('PASS', 'PENDING_CAPTURE_FINALIZATION') for row in results) else 1


def verify_finalized(reports, apk_hashes):
    receipt = load_receipt(reports / 'regressions.json')
    if [row['case'] for row in receipt['cases']] != [name for name, _ in CASES]:
        raise RuntimeError('Focused case inventory mismatch')
    for row in receipt['cases']:
        case = reports / row['case']
        voice = load_receipt(case / 'voice-evidence.json')
        video_path = case / 'video-evidence.json'
        video = load_receipt(video_path) if video_path.is_file() else None
        if row['exitCode'] != 0 or not valid_receipts(row['case'], voice, video, expected_hashes=apk_hashes):
            raise RuntimeError('Focused finalized receipt rejected')
        if row['case'] == 'degraded-network' and load_receipt(case / 'video-stop-barrier.json') != {
                'bothLocalStopsIssued': True, 'generation': 2, 'endpoints': 2}:
            raise RuntimeError('Focused local stop barrier missing')
        if voice.get('captureFinalization', {}).get('exitCode') != 0:
            raise RuntimeError('Focused capture not finalized')
        row['receiptAccepted'] = True
        row['status'] = 'PASS'
    (reports / 'regressions.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f'PASS {len(CASES)} focused R8 cases including finalized network evidence')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--a'); parser.add_argument('--b')
    parser.add_argument('--verify-finalized', action='store_true')
    parser.add_argument('--reports', required=True, type=Path)
    args = parser.parse_args()
    apk_hashes = {}
    for name in APK_NAMES:
        with (ROOT / 'android/app/build/outputs/apk' / name).open('rb') as stream:
            apk_hashes[name] = hashlib.file_digest(stream, 'sha256').hexdigest()
    if args.verify_finalized:
        return verify_finalized(args.reports, apk_hashes)
    return execute(args.a, args.b, args.reports, apk_hashes)


if __name__ == '__main__':
    sys.exit(main())
