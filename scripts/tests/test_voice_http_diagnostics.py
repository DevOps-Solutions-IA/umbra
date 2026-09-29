import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from voice_http_diagnostics import ConnectionDiagnostics


class HttpDiagnosticsTest(unittest.TestCase):
    def test_lifecycle_distinguishes_idle_timeout_from_incomplete_transport_loss(self):
        ticks=iter(range(10,100));d=ConnectionDiagnostics(clock=lambda:next(ticks))
        a=d.opened();d.event(a,'received');d.event(a,'response');d.event(a,'keepalive');d.event(a,'closed')
        b=d.opened();d.event(b,'received');d.event(b,'closed',incomplete=True,error=True)
        first,second=d.snapshot()['connections']
        self.assertEqual(1,first['responses']);self.assertFalse(first['incompleteResponse'])
        self.assertLess(first['responseNanos'],first['keepaliveNanos'])
        self.assertNotIn('keepaliveNanos',second);self.assertTrue(second['incompleteResponse'])
        self.assertTrue(second['transportError'])
        self.assertEqual({'id','openedNanos','responses','receivedEvents','receivedNanos','responseNanos',
                          'keepaliveNanos','closedNanos','incompleteResponse','transportError'},set(first))

    def test_bounded_retention_reports_loss_and_rejects_unrecognized_fields(self):
        d=ConnectionDiagnostics(capacity=2)
        old=d.opened();d.opened();d.opened();d.event(old,'closed')
        value=d.snapshot();self.assertEqual(1,value['dropped']);self.assertEqual(2,len(value['connections']))
        with self.assertRaises(ValueError):d.event(old,'secret-header')
        with self.assertRaises(TypeError):d.event(old,'received',body='not accepted')
        value['connections'][0]['responses']=999
        self.assertEqual(0,d.snapshot()['connections'][0]['responses'])
