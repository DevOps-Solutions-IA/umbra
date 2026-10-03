import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from finalize_media_capture import immutable_snapshot, require_closed, finalize
from test_pcap_integrity import header, record, udp4


class CaptureFinalizationTest(unittest.TestCase):
    def test_all_media_workflow_consumers_finalize_owned_capture(self):
        workflows = Path(__file__).resolve().parents[2] / '.github/workflows'
        consumers = 0
        # Inspect literal shell run blocks; no third-party YAML dependency is
        # installed in the repository-guard job. This is not a YAML validator.
        for path in workflows.glob('*.yml'):
            lines = path.read_text().splitlines()
            blocks = []
            for index, line in enumerate(lines):
                if line.strip() != 'run: |':
                    continue
                indentation = len(line) - len(line.lstrip())
                block = []
                for following in lines[index + 1:]:
                    if following.strip() and len(following) - len(following.lstrip()) <= indentation:
                        break
                    block.append(following)
                blocks.append('\n'.join(block))
            for script in blocks:
                # Verify invokes the voice scenarios through this owning-shell helper.
                if 'source scripts/ci_bluetooth.sh' in script:
                    script += '\n' + (workflows.parents[1] / 'scripts/ci_bluetooth.sh').read_text()
                if any('python scripts/' + name in script for name in (
                        'run_voice_integration.py', 'run_video_matrix.py', 'run_media_regressions.py')):
                    consumers += 1
                    self.assertIn('export UMBRA_FINALIZED_CAPTURE=1', script, path.name)
                    self.assertIn('umbra_finalize_capture', script, path.name)
                    self.assertIn('python scripts/finalize_media_capture.py', script, path.name)
        self.assertEqual(6, consumers)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        process = subprocess.Popen([sys.executable, '-c', 'pass'])
        self.assertEqual(0, process.wait(timeout=5))
        self.owner = {'state': 'CLOSED', 'pid': process.pid, 'exitCode': 0, 'captureErrorCount': 0,
                      'stopRequestedMonotonicNs': 1, 'closedMonotonicNs': 2,
                      'captureRoot': str(self.root), 'stoppedWallTime': 3}
        self.source = self.root / 'owned.pcap'
        self.source.write_bytes(header() + record(udp4()))

    def stage(self, voice=None):
        reports = self.root / 'reports'; reports.mkdir()
        case = reports / 'case'; case.mkdir()
        voice = voice if voice is not None else {'synthetic': True, 'endpoints': 2, 'observedSeconds': 1,
                                               'audio': [{'rejectedBeforeCapture': True}]*2, 'network': []}
        plan = {'status': 'PENDING_CAPTURE_FINALIZATION', 'ownerPid': self.owner['pid'],
                'capturePaths': [str(self.source)]*2, 'since': 0, 'until': 2,
                'scenario': 'expired-auth', 'turnAddress': '172.20.0.2', 'tls': False, 'ipv6': False,
                'relayPort': 43210, 'addresses': ['10.0.2.16', '10.0.2.17'], 'addresses6': [],
                'rejection': True, 'voice': voice}
        (case/'network-pending.json').write_text(json.dumps(plan))
        (reports/'matrix.json').write_text(json.dumps([{'case': 'case', 'exitCode': 0,
                                                       'status': 'PENDING_CAPTURE_FINALIZATION'}]))
        owner_path = self.root/'owner.json'; owner_path.write_text(json.dumps(self.owner))
        return reports, owner_path

    def test_active_writer_cannot_be_considered_finalized(self):
        process = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        try:
            owner = {**self.owner, 'pid': process.pid}
            with self.assertRaisesRegex(RuntimeError, 'still active'):
                immutable_snapshot(self.source, self.root/'snapshot.pcap', owner)
            self.assertFalse((self.root/'snapshot.pcap').exists())
        finally:
            process.terminate(); process.wait(timeout=5)

    def test_finalized_capture_snapshot_is_complete_and_read_only(self):
        target = self.root/'snapshot.pcap'
        details = immutable_snapshot(self.source, target, self.owner)
        self.assertEqual(1, details['records'])
        self.assertEqual(self.source.read_bytes(), target.read_bytes())
        self.assertEqual(0, target.stat().st_mode & 0o222)

    def test_nonzero_exit_missing_close_or_logged_write_error_rejects(self):
        for bad in ({'exitCode': 1}, {'state': 'ACTIVE'}, {'captureErrorCount': 1},
                    {'closedMonotonicNs': 0}, {'captureErrorCount': None}):
            with self.assertRaises(RuntimeError): require_closed({**self.owner, **bad})

    def test_foreign_path_rejected(self):
        with tempfile.TemporaryDirectory() as outside:
            foreign = Path(outside)/'foreign.pcap'; foreign.write_bytes(header())
            with self.assertRaises(RuntimeError): immutable_snapshot(foreign, self.root/'snapshot', self.owner)

    def test_finalizer_accepts_only_after_closure_and_whole_capture_parse(self):
        reports, owner = self.stage()
        receipt = finalize(reports, owner)
        self.assertEqual('PASS', receipt['status'])
        self.assertEqual('PASS', json.loads((reports/'matrix.json').read_text())[0]['status'])
        evidence = json.loads((reports/'case/voice-evidence.json').read_text())
        self.assertEqual(2, len(evidence['network']))
        self.assertEqual(0, evidence['captureFinalization']['exitCode'])

    def test_truncated_closed_capture_still_fails_not_zero_packets(self):
        reports, owner = self.stage()
        self.source.write_bytes(self.source.read_bytes()[:-1])
        with self.assertRaises(RuntimeError): finalize(reports, owner)
        self.assertFalse((reports/'case/voice-evidence.json').exists())
        self.assertEqual('FAIL', json.loads((reports/'case/capture-failure.json').read_text())['status'])

    def test_incomplete_native_evidence_is_not_published_as_accepted(self):
        reports, owner = self.stage(voice={'network': []})
        with self.assertRaises(RuntimeError): finalize(reports, owner)
        self.assertFalse((reports/'case/voice-evidence.json').exists())

    def test_no_empty_acceptance(self):
        reports = self.root/'reports'; reports.mkdir()
        owner = self.root/'owner.json'; owner.write_text(json.dumps(self.owner))
        with self.assertRaises(RuntimeError): finalize(reports, owner)


if __name__ == '__main__': unittest.main()
