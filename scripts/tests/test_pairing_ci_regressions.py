"""Synthetic validator/runner unit tests only; never evidence of real audio or video."""
import copy
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_pairing_ci_regressions as runner

HASHES = dict(zip(runner.APK_NAMES, ('a' * 64, 'b' * 64)))


def receipts(case):
    voice = {'apkSha256': HASHES.copy(), 'synthetic': True, 'endpoints': 2, 'observedSeconds': 45.5,
             'transport': 'native WebRTC through coturn UDP', 'turnTlsCase': None, 'scenario': 'expired-auth',
             'directIpv4Reachability': True, 'directBlockedDuringMedia': False, 'muteUnmute': False,
             'muteBarrier': None, 'network': [], 'audio': [{'rejectedBeforeCapture': True} for _ in range(2)],
             'allocationExpiry': None, 'nativeCaptureClosure': [], 'networkImpairment': []}
    for packets in (10, 0):
        voice['network'].append({'scope': 'owned AVD outbound IPv4 UDP', 'turnPackets': packets,
            'turnStunPackets': packets, 'nonTurnStunPackets': 0, 'otherNonSystemUdpPackets': 0,
            'ipv6': 'NOT_EXECUTED', 'mediaAttempt': 'OBSERVED' if packets else
            'NOT_EXECUTED: no remote description after initiator rejection'})
    if case == 'expired-auth':
        return voice, None
    voice.update(transport='native WebRTC through coturn TLS', turnTlsCase='valid', scenario='audio', muteUnmute=True,
        muteBarrier={'applied': [{'applied': True, 'elapsedMillis': 100} for _ in range(2)],
                     'observed': [{'quiet': True, 'observedMillis': 1500, 'decodedTones': 0} for _ in range(2)]})
    voice['audio'] = [{'decodedBuffers': 120, 'capturedBuffers': 120, 'verifiedNativeTransport': True,
        'receivedAudioPackets': 70, 'codec': 'audio/opus', 'sdpAddressAudit': True, 'nativeRelayProtocol': 'tls'} for _ in range(2)]
    voice['network'] = [{'scope': 'owned Wi-Fi IPv4+IPv6 TCP/UDP; IPv6 client-to-TURN, IPv4 relay allocation',
        'turnIpv6Packets': 3000, 'nonAuthorizedTcpUdpPackets': 0, 'transport': 'TLS',
        'allIpv6Allocation': 'NOT_EXECUTED: pinned allocator uses default IPv4 allocation'} for _ in range(2)]
    video = {'apkSha256': HASHES.copy(), 'synthetic': True, 'physicalCamera': False,
             'nativeCodecPipeline': True, 'endpoints': 2, 'phases': {}}
    for name, generation in (('active', 2), ('resumed', 3)):
        video['phases'][name] = [{'decodedRemotePatterns': 25, 'distinctPatternPhases': 2, 'sendPermitted': True,
            'receivePermitted': True, 'decodedAudioDuringVideo': 60, 'capturedFrames': 25, 'generation': generation,
            'videoCodec': 'video/VP8', 'sdpAddressAudit': True} for _ in range(2)]
    video['phases']['stopped'] = [{'captureStopped': True, 'captureWasAuthorized': True,
        'captureCallbacksAfterRequest': 0, 'observedAfterRequestMillis': 2000, 'decodedAudioAfterVideoOff': 70} for _ in range(2)]
    return voice, video


class PairingCiReceiptTest(unittest.TestCase):
    def test_complete_synthetic_receipt_shapes_are_accepted(self):
        for case, _ in runner.CASES:
            self.assertTrue(runner.valid_receipts(case, *receipts(case), expected_hashes=HASHES))

    def test_missing_complete_fields_endpoints_and_forged_numeric_types_fail(self):
        for case, _ in runner.CASES:
            voice, video = receipts(case)
            for field in runner.VOICE_FIELDS:
                bad = copy.deepcopy(voice); del bad[field]
                self.assertFalse(runner.valid_receipts(case, bad, video), field)
            for field, value in [('endpoints', True), ('endpoints', 1), ('observedSeconds', True),
                                 ('observedSeconds', 0), ('observedSeconds', float('nan')), ('observedSeconds', float('inf')),
                                 ('network', []), ('audio', [voice['audio'][0]]), ('network', [None, None])]:
                bad = copy.deepcopy(voice); bad[field] = value
                self.assertFalse(runner.valid_receipts(case, bad, video), field)

    def test_debug_or_changed_apk_and_other_scenario_are_rejected(self):
        for case, _ in runner.CASES:
            voice, video = receipts(case)
            wrong = {key: 'c' * 64 for key in HASHES}
            self.assertFalse(runner.valid_receipts(case, voice, video, expected_hashes=wrong))
            voice['apkSha256'] = {key.replace('mediaLab', 'debug'): value for key, value in HASHES.items()}
            self.assertFalse(runner.valid_receipts(case, voice, video))
        voice, video = receipts('ipv6-tls'); voice['scenario'] = 'expired-auth'
        self.assertFalse(runner.valid_receipts('ipv6-tls', voice, video))

    def test_expired_auth_requires_exact_native_rejection_and_observed_initiator_turn(self):
        voice, _ = receipts('expired-auth')
        for replacement in ({'rejectedBeforeCapture': 1}, {'rejectedBeforeCapture': True, 'decodedBuffers': 100},
                            {'rejectedBeforeCapture': False}, {'failedClosed': True}):
            bad = copy.deepcopy(voice); bad['audio'][0] = replacement
            self.assertFalse(runner.valid_receipts('expired-auth', bad))
        for field, value in [('turnPackets', 0), ('turnStunPackets', 0), ('nonTurnStunPackets', 1),
                             ('otherNonSystemUdpPackets', 1), ('nonTurnStunPackets', False)]:
            bad = copy.deepcopy(voice); bad['network'][0][field] = value
            self.assertFalse(runner.valid_receipts('expired-auth', bad))
        self.assertFalse(runner.valid_receipts('expired-auth', voice, receipts('ipv6-tls')[1]))

    def test_ipv6_tls_requires_two_decoded_audio_endpoints_tls_and_zero_direct(self):
        voice, video = receipts('ipv6-tls')
        for index in range(2):
            for field, value in [('decodedBuffers', 99), ('capturedBuffers', 99), ('receivedAudioPackets', 49),
                                 ('verifiedNativeTransport', False), ('sdpAddressAudit', False),
                                 ('codec', 'audio/other'), ('nativeRelayProtocol', 'udp')]:
                bad = copy.deepcopy(voice); bad['audio'][index][field] = value
                self.assertFalse(runner.valid_receipts('ipv6-tls', bad, video))
            for field, value in [('turnIpv6Packets', 0), ('transport', 'UDP'), ('nonAuthorizedTcpUdpPackets', 1),
                                 ('nonAuthorizedTcpUdpPackets', False), ('scope', 'owned AVD outbound IPv4 UDP')]:
                bad = copy.deepcopy(voice); bad['network'][index][field] = value
                self.assertFalse(runner.valid_receipts('ipv6-tls', bad, video))

    def test_video_missing_or_partial_phase_cannot_pass(self):
        voice, video = receipts('ipv6-tls')
        self.assertFalse(runner.valid_receipts('ipv6-tls', voice))
        for name in ('active', 'stopped', 'resumed'):
            bad = copy.deepcopy(video); del bad['phases'][name]
            self.assertFalse(runner.valid_receipts('ipv6-tls', voice, bad))
        for name, field, value in [('active', 'decodedRemotePatterns', 19), ('resumed', 'generation', 2),
                                  ('active', 'videoCodec', 'video/fake'), ('active', 'capturedFrames', 0),
                                  ('stopped', 'captureStopped', False), ('stopped', 'captureCallbacksAfterRequest', 1),
                                  ('stopped', 'decodedAudioAfterVideoOff', 49)]:
            bad = copy.deepcopy(video); bad['phases'][name][1][field] = value
            self.assertFalse(runner.valid_receipts('ipv6-tls', voice, bad))

    def test_missing_mute_barrier_or_remote_tones_cannot_pass(self):
        voice, video = receipts('ipv6-tls')
        for value in (None, {}, {'applied': [], 'observed': []}):
            bad = copy.deepcopy(voice); bad['muteBarrier'] = value
            self.assertFalse(runner.valid_receipts('ipv6-tls', bad, video))
        voice['muteBarrier']['observed'][1]['decodedTones'] = 4
        self.assertFalse(runner.valid_receipts('ipv6-tls', voice, video))

    def test_bounded_json_and_duplicate_fields_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'receipt.json'
            for data in ('{"audio":[],"audio":[]}', 'x' * 262145):
                path.write_text(data)
                with self.assertRaises(ValueError): runner.load_receipt(path)

    def test_runner_uses_two_r8_video_commands_once_and_preserves_process_failure(self):
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / 'focused'
            commands = []
            def synthetic_process(command, **kwargs):
                # This mock tests orchestration only, not native media acceptance.
                commands.append(command)
                folder = Path(command[command.index('--reports') + 1]); folder.mkdir()
                voice, video = receipts(folder.name)
                (folder / 'voice-evidence.json').write_text(json.dumps(voice))
                if video is not None: (folder / 'video-evidence.json').write_text(json.dumps(video))
                return SimpleNamespace(returncode=7 if folder.name == 'expired-auth' else 0)
            with patch.object(runner.subprocess, 'run', side_effect=synthetic_process), redirect_stdout(io.StringIO()):
                self.assertEqual(1, runner.execute('emulator-5554', 'emulator-5556', output, HASHES))
            self.assertEqual(2, len(commands))
            for command in commands:
                self.assertIn('--video', command); self.assertIn('--optimized', command)
            self.assertIn('expired-auth', commands[0]); self.assertIn('--turn-ipv6', commands[1])
            summary = json.loads((output / 'regressions.json').read_text())
            self.assertEqual(['FAIL', 'PASS'], [row['status'] for row in summary['cases']])

    def test_finalized_receipts_require_closure_and_preserve_failure(self):
        with tempfile.TemporaryDirectory() as root:
            output = Path(root)
            rows = []
            for case, _ in runner.CASES:
                folder = output / case; folder.mkdir()
                voice, video = receipts(case)
                voice['captureFinalization'] = {'exitCode': 0}
                (folder / 'voice-evidence.json').write_text(json.dumps(voice))
                if video is not None: (folder / 'video-evidence.json').write_text(json.dumps(video))
                rows.append({'case': case, 'exitCode': 0, 'status': 'PENDING_CAPTURE_FINALIZATION'})
            (output / 'regressions.json').write_text(json.dumps({'cases': rows}))
            with redirect_stdout(io.StringIO()):
                self.assertEqual(0, runner.verify_finalized(output, HASHES))
            self.assertTrue(all(row['status'] == 'PASS' for row in json.loads((output / 'regressions.json').read_text())['cases']))
            voice, _ = receipts('expired-auth')
            (output / 'expired-auth/voice-evidence.json').write_text(json.dumps(voice))
            with self.assertRaisesRegex(RuntimeError, 'not finalized'):
                runner.verify_finalized(output, HASHES)

    def test_zero_exit_without_receipts_is_failure_and_prior_outputs_are_preserved(self):
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / 'focused'
            with patch.object(runner.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as process, redirect_stdout(io.StringIO()):
                self.assertEqual(1, runner.execute('emulator-5554', 'emulator-5556', output, HASHES))
                self.assertEqual(2, process.call_count)
                with self.assertRaises(FileExistsError): runner.execute('emulator-5554', 'emulator-5556', output, HASHES)
                self.assertEqual(2, process.call_count)
            self.assertTrue(all(row['status'] == 'FAIL' for row in json.loads((output / 'regressions.json').read_text())['cases']))


if __name__ == '__main__':
    unittest.main()
