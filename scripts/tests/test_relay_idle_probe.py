import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from relay_idle_probe import associate, idle_closed, coordinate


class RelayIdleProbeTest(unittest.TestCase):
    def warm(self):return {'id':3,'responses':1,'receivedEvents':1,'responseNanos':100}

    def test_exact_new_warm_connection_required(self):
        self.assertEqual(self.warm(),associate({'connections':[{'id':1},self.warm()]},{1}))
        for rows in ([],[self.warm(),dict(self.warm(),id=4)], [dict(self.warm(),responses=2)],
                     [dict(self.warm(),keepaliveNanos=200)]):
            with self.assertRaises(RuntimeError):associate({'connections':rows},{1})

    def test_idle_event_is_required_and_ordered(self):
        self.assertIsNone(idle_closed({'connections':[self.warm()]},self.warm()))
        row=dict(self.warm(),keepaliveNanos=200)
        self.assertEqual(200,idle_closed({'connections':[row]},self.warm())['keepaliveNanos'])
        for row in (dict(row,receivedEvents=2),dict(row,responses=2),dict(row,keepaliveNanos=99)):
            with self.assertRaises(RuntimeError):idle_closed({'connections':[row]},self.warm())

    def test_missing_owned_connection_fails(self):
        with self.assertRaises(RuntimeError):idle_closed({'connections':[]},self.warm())

    def test_complete_controlled_receipt_only(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'live.json';report=Path(d)/'result.json';writes=[]
            path.write_text(json.dumps({'connections':[self.warm()]}))
            def read(name):
                if name.endswith('warmed.json'):return {'warmed':True}
                if name.endswith('blocked.json'):
                    path.write_text(json.dumps({'connections':[dict(self.warm(),keepaliveNanos=200)]}))
                    return {'blocked':True}
                return {'androidEof':True,'networkRevoked':True,'freshSocketVerified':True}
            coordinate(path,set(),read,lambda n,v:writes.append(n),report)
            self.assertEqual('REPRODUCED',json.loads(report.read_text())['result'])
            self.assertEqual(['synthetic-http-associated.json','synthetic-http-closed.json'],writes)

    def test_no_idle_event_never_counts_as_reproduction(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'live.json';report=Path(d)/'result.json';times=iter([0,9])
            path.write_text(json.dumps({'connections':[self.warm()]}))
            with self.assertRaisesRegex(RuntimeError,'not observed'):
                coordinate(path,set(),lambda n:{'warmed':True} if 'warmed' in n else {'blocked':True},
                           lambda *args:None,report,clock=lambda:next(times))
            self.assertFalse(report.exists())

    def test_missing_positive_transport_proof_rejects(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'live.json';report=Path(d)/'result.json'
            path.write_text(json.dumps({'connections':[self.warm()]}))
            def read(name):
                if name.endswith('warmed.json'):return {'warmed':True}
                if name.endswith('blocked.json'):
                    path.write_text(json.dumps({'connections':[dict(self.warm(),keepaliveNanos=200)]}))
                    return {'blocked':True}
                return {'androidEof':True,'networkRevoked':True}
            with self.assertRaises(RuntimeError):coordinate(path,set(),read,lambda *args:None,report)
            self.assertFalse(report.exists())

    def test_test_only_source_requires_real_eof_and_cleanup(self):
        root=Path(__file__).resolve().parents[2]
        source=(root/'android/app/src/androidTestConnected/java/app/umbra/transport/RelayIdleReuseProbe.java').read_text()
        for fragment in ('gate.created.get()!=1','gate.intercepted.get()!=1','failure instanceof IOException',
                         'unexpected end of stream','isNetworkSessionAllowed()', 'relay.close()',
                         'HttpsURLConnection.setDefaultSSLSocketFactory(original)'):
            self.assertIn(fragment,source)
        self.assertNotIn('setHostnameVerifier',source)
        self.assertNotIn('TrustAll',source)
        self.assertIn('new LegacyPooledRelayClient(',source)
        self.assertIn('observer.created.get()!=2',source)
        self.assertIn('relay.publishAdmissionCredential(credential)',source)
        legacy=(root/'android/app/src/androidTestConnected/java/app/umbra/transport/LegacyPooledRelayClient.java').read_text()
        self.assertIn('fe6ac9be27019cdb6ff1b66472ce6713ac8a9d7d',legacy)
        self.assertNotIn('setRequestProperty("Connection", "close")',legacy)
        production=(root/'android/app/src/main/java/app/umbra/transport/RelayClient.java').read_text()
        self.assertIn('setRequestProperty("Connection", "close")',production)



if __name__=='__main__':unittest.main()
