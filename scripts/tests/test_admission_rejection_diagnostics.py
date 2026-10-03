import json
import sys
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
sys.path.insert(0,str(ROOT/'relay'))
from umbra_relay.admission_protocol import AdmissionError, Challenge, ChallengeUnavailable, digest
from voice_http_diagnostics import AdmissionRejectionDiagnostics

class AdmissionRejectionDiagnosticsTest(unittest.TestCase):
    def test_actual_wall_expiry_in_monotonic_live_window_and_same_exception(self):
        from types import SimpleNamespace
        fields=('realm','credential',digest(b'secret'),'nonce','verifier','operation','100','130')
        credential=SimpleNamespace(realm_id='realm',credential_id='credential',wire='secret')
        # All binding fields match; the original validation fails at the wall-clock boundary.
        d=AdmissionRejectionDiagnostics()
        original=[]
        def consume():
            try:Challenge(fields).validate(credential,'verifier','operation',130)
            except AdmissionError as error:
                original.append(error);raise
        with self.assertRaises(AdmissionError) as error:d.wrap_consume(consume)()
        self.assertIs(original[0],error.exception)
        self.assertEqual('CHALLENGE_WALL_EXPIRED',d.snapshot()['rejections'][0]['stage'])
        self.assertNotIn('secret',json.dumps(d.snapshot()))

    def test_success_transparency_and_unknown_rejection_remains_failure(self):
        d=AdmissionRejectionDiagnostics()
        result=object()
        self.assertIs(result,d.wrap_consume(lambda:result)())
        rejected=AdmissionError()
        def consume():raise rejected
        with self.assertRaises(AdmissionError) as error:d.wrap_consume(consume)()
        self.assertIs(rejected,error.exception)
        self.assertEqual('OTHER',d.snapshot()['rejections'][0]['stage'])

    def test_unavailable_capacity_and_snapshot_are_bounded(self):
        d=AdmissionRejectionDiagnostics(capacity=2)
        def consume():raise ChallengeUnavailable()
        for _ in range(3):
            with self.assertRaises(ChallengeUnavailable):d.wrap_consume(consume)()
        value=d.snapshot()
        self.assertEqual(3,value['total']);self.assertEqual(1,value['dropped'])
        self.assertEqual(2,len(value['rejections']))
        self.assertTrue(all(row['stage']=='CHALLENGE_UNAVAILABLE' for row in value['rejections']))
        value['rejections'][0]['stage']='mutated'
        self.assertEqual('CHALLENGE_UNAVAILABLE',d.snapshot()['rejections'][0]['stage'])

    def test_diagnostic_failure_cannot_replace_admission_rejection(self):
        def broken_clock():raise RuntimeError('secret diagnostic failure')
        d=AdmissionRejectionDiagnostics(clock=broken_clock)
        rejected=AdmissionError()
        def consume():raise rejected
        with self.assertRaises(AdmissionError) as error:d.wrap_consume(consume)()
        self.assertIs(rejected,error.exception)
        self.assertEqual(1,d.snapshot()['diagnosticFailures'])
        self.assertNotIn('secret',json.dumps(d.snapshot()))

    def test_threaded_recording_and_snapshot_remain_consistent(self):
        from concurrent.futures import ThreadPoolExecutor
        d=AdmissionRejectionDiagnostics(capacity=2)
        def record():
            def consume():raise AdmissionError()
            for _ in range(100):
                with self.assertRaises(AdmissionError):d.wrap_consume(consume)()
        with ThreadPoolExecutor(max_workers=3) as workers:
            futures=[workers.submit(record) for _ in range(2)]
            for _ in range(1000):
                snapshot=d.snapshot()
                self.assertEqual(snapshot['total']-snapshot['dropped'],len(snapshot['rejections']))
            for future in futures:future.result()
        self.assertEqual(200,d.snapshot()['total'])
