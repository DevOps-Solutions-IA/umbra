import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


class ImpairmentDiagnosticTest(unittest.TestCase):
    def module(self):
        self.assertIsNotNone(importlib.util.find_spec('voice_impairment_diagnostic'),
                             'Failure-time queue counters must survive teardown')
        import voice_impairment_diagnostic
        return voice_impairment_diagnostic

    def test_only_numeric_queue_evidence_survives(self):
        value = self.module().summarize('qdisc netem 8001: root limit 20 delay 80ms loss 2% rate 128Kbit\n'
            ' Sent 1234 bytes 100 pkt (dropped 7, overlimits 4 requeues 2)\n backlog 120b 3p\n'
            'secret=never-copy host=private.example')
        self.assertEqual({'status': 'OBSERVED', 'configuredProfile': True,
            'sentBytes': 1234, 'sentPackets': 100, 'droppedPackets': 7,
            'overlimits': 4, 'requeues': 2, 'backlogBytes': 120, 'backlogPackets': 3}, value)
        self.assertNotIn('secret', json.dumps(value))

    def test_zero_packets_is_observed_not_missing_or_success(self):
        value = self.module().summarize('qdisc netem root limit 20 delay 80ms loss 2% rate 128Kbit\n'
            ' Sent 0 bytes 0 pkt (dropped 0, overlimits 0 requeues 0) backlog 0b 0p')
        self.assertEqual('OBSERVED', value['status'])
        self.assertEqual(0, value['sentPackets'])
        self.assertNotIn('accepted', value)

    def test_missing_oversized_or_overflow_counters_are_unavailable(self):
        for raw in ('secret=do-not-log', 'x' * 4097,
                    'Sent 999999999999999999999999 bytes 2 pkt (dropped 1, overlimits 0 requeues 0) backlog 0b 0p'):
            self.assertEqual({'status': 'UNAVAILABLE'}, self.module().summarize(raw))

    def test_failed_collection_preserves_original_failure_and_records_both_roles(self):
        module = self.module()
        calls = []
        def collect(serial):
            calls.append(serial)
            raise RuntimeError('synthetic secret in error must not persist')
        with tempfile.TemporaryDirectory() as directory:
            failure = ValueError('original fixture failure')
            with self.assertRaises(ValueError) as raised:
                try:
                    raise failure
                except ValueError:
                    module.record(Path(directory), ('a', 'b'), collect, clock=lambda: 9)
                    raise
            self.assertIs(failure, raised.exception)
            value = json.loads((Path(directory) / 'impairment-failure.json').read_text())
            self.assertEqual(['a', 'b'], calls)
            self.assertEqual([{'role': 'A', 'status': 'UNAVAILABLE'},
                              {'role': 'B', 'status': 'UNAVAILABLE'}], value['endpoints'])
            self.assertEqual(9, value['observedMonotonicNanos'])
            self.assertNotIn('secret', json.dumps(value))
