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


class DenialDiagnosticsTest(unittest.IsolatedAsyncioTestCase):
    async def response(self, diagnostic, status, body, path='/v1/boxes/secret-box/messages/secret-id', method='DELETE', headers=None):
        sent=[];received=[]
        request={'type':'http.request','body':b'secret-request'}
        async def receive():received.append(request);return request
        async def send(message):sent.append(message)
        messages=[{'type':'http.response.start','status':status,'headers':headers if headers is not None else [(b'x-secret',b'secret-value')]},
                  {'type':'http.response.body','body':body[:4],'more_body':True},
                  {'type':'http.response.body','body':body[4:]}]
        async def app(scope,recv,emit):
            self.assertIs(await recv(),request)
            for message in messages:await emit(message)
        await diagnostic.wrap(app)({'type':'http','path':path,'method':method},receive,send)
        self.assertEqual(messages,sent)
        self.assertTrue(all(a is b for a,b in zip(messages,sent)))
        self.assertEqual([request],received)

    async def test_closed_categories_and_transparent_delivery(self):
        from voice_http_diagnostics import DenialDiagnostics
        import json
        d=DenialDiagnostics(clock=iter(range(10)).__next__)
        await self.response(d,403,b'{"detail":"Admission unavailable"}')
        self.assertEqual('ADMISSION_UNAVAILABLE',d.snapshot()['responses'][0]['denial'])
        self.assertEqual('MESSAGE_ACK',d.snapshot()['responses'][0]['route'])
        await self.response(d,200,b'secret-success')
        self.assertEqual(1,d.snapshot()['total'])
        self.assertNotIn('secret',json.dumps(d.snapshot()))

    async def test_unknown_oversized_and_extra_fields_redacted_with_capacity(self):
        from voice_http_diagnostics import DenialDiagnostics
        import json
        d=DenialDiagnostics(capacity=2)
        for body in (b'{"detail":"secret-error"}',b'x'*300,
                     b'{"detail":"Admission unavailable","secret":"secret-value"}'):
            await self.response(d,403,body,path='/secret-path')
        value=d.snapshot()
        self.assertEqual(3,value['total']);self.assertEqual(1,value['dropped'])
        self.assertEqual({403:3},value['statusCounts'])
        self.assertTrue(all(r['denial']=='OTHER' and r['route']=='OTHER' for r in value['responses']))
        self.assertNotIn('secret',json.dumps(value))
        value['responses'][0]['route']='mutated'
        self.assertEqual('OTHER',d.snapshot()['responses'][0]['route'])

    async def test_only_exact_retry_header_is_reduced_to_boolean(self):
        from voice_http_diagnostics import DenialDiagnostics
        import json
        key=b'x-umbra-admission-retry'
        cases=[(403,[(key,b'fresh-challenge')],True),
               (403,[(key.upper(),b'fresh-challenge')],True),
               (403,[(b'x-secret',b'fresh-challenge')],False),
               (403,[(key,b'fresh-challenge secret')],False),
               (403,[(key,b'Fresh-Challenge')],False),
               (403,[(key,b'fresh-challenge'),(key,b'fresh-challenge')],False),
               (401,[(key,b'fresh-challenge')],False)]
        for status,headers,expected in cases:
            with self.subTest(status=status,expected=expected,headers=headers):
                d=DenialDiagnostics()
                await self.response(d,status,b'{"detail":123456789}',headers=headers)
                value=d.snapshot();row=value['responses'][0]
                self.assertIs(expected,row['freshChallengeRequired'])
                self.assertEqual('OTHER',row['denial'])
                serialized=json.dumps(value)
                for forbidden in ('123456789','secret','fresh-challenge','x-umbra'):
                    self.assertNotIn(forbidden,serialized)
