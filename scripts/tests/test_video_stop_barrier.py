"""Host barrier unit tests; not native-video or physical acceptance."""
import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from run_voice_integration import coordinate_video_stop

class VideoStopBarrierTest(unittest.TestCase):
    def fixtures(self):
        return [{'issued':True,'generation':2,'stopNonce':f'00000000-0000-4000-8000-{i:012d}','stopEnvelopeIds':[f'11111111-0000-4000-8000-{i:012d}']} for i in (1,2)]

    def test_both_local_stops_precede_any_release_and_nonces_stay_out_of_receipt(self):
        events=[];values=self.fixtures();before=copy.deepcopy(values)
        def read(serial,name,process,deadline):
            events.append(('read',serial));self.assertEqual(99,deadline)
            self.assertEqual('synthetic-voice-video-stop-issued.json',name)
            return values[0 if serial=='a' else 1]
        def write(serial,name,value):
            events.append(('write',serial));self.assertEqual('synthetic-voice-video-stop-release.json',name)
            self.assertEqual({'release':True,'generation':2,'stopNonce':values[0 if serial=='a' else 1]['stopNonce'],'peerStopIds':values[1 if serial=='a' else 0]['stopEnvelopeIds']},value)
        receipt=coordinate_video_stop(('a','b'),{'a':1,'b':2},99,write,read)
        self.assertEqual([('read','a'),('read','b'),('write','a'),('write','b')],events)
        self.assertEqual(before,values)
        self.assertEqual({'bothLocalStopsIssued':True,'generation':2,'endpoints':2},receipt)

    def test_multiple_authorized_stop_envelopes_all_released_without_selection(self):
        values=self.fixtures()
        for i,row in enumerate(values):
            row.pop('stopEnvelopeId',None)
            row['stopEnvelopeIds']=[f'11111111-0000-4000-8000-{i*2+j:012d}' for j in (1,2)]
        outputs=[]
        def read(serial,*args):return values[0 if serial=='a' else 1]
        coordinate_video_stop(('a','b'),{'a':1,'b':2},99,lambda serial,name,value:outputs.append(value),read)
        self.assertEqual(values[1]['stopEnvelopeIds'],outputs[0]['peerStopIds'])
        self.assertEqual(values[0]['stopEnvelopeIds'],outputs[1]['peerStopIds'])

    def test_missing_corrupt_stale_or_duplicate_confirmation_never_releases(self):
        for bad in (None,{},dict(self.fixtures()[1],issued=False),dict(self.fixtures()[1],generation=True),
                    dict(self.fixtures()[1],generation=3),dict(self.fixtures()[1],stopNonce='invalid'),
                    dict(self.fixtures()[1],extra=1),dict(self.fixtures()[1],stopEnvelopeIds=[]),
                    dict(self.fixtures()[1],stopEnvelopeIds=['11111111-0000-4000-8000-000000000001']*2),
                    dict(self.fixtures()[1],stopEnvelopeIds=[f'11111111-0000-4000-8000-{i:012d}' for i in range(129)]),dict(self.fixtures()[1],stopEnvelopeIds='invalid'),
                    dict(self.fixtures()[1],stopEnvelopeIds=self.fixtures()[0]['stopEnvelopeIds']),self.fixtures()[0]):
            calls=[];values=[self.fixtures()[0],bad]
            with self.subTest(bad=bad),self.assertRaises(RuntimeError):
                coordinate_video_stop(('a','b'),{'a':1,'b':2},99,lambda *a:calls.append(a),lambda *a:values.pop(0))
            self.assertEqual([],calls)

    def test_second_endpoint_failure_does_not_release_first(self):
        calls=[]
        def read(serial,*ignored):
            if serial=='b':raise RuntimeError('Synthetic endpoint failed')
            return self.fixtures()[0]
        with self.assertRaisesRegex(RuntimeError,'Synthetic endpoint failed'):
            coordinate_video_stop(('a','b'),{'a':1,'b':2},99,lambda *a:calls.append(a),read)
        self.assertEqual([],calls)

    def test_duplicate_endpoint_rejected_before_io(self):
        def forbidden(*args):raise AssertionError('Unexpected fixture I/O')
        with self.assertRaises(RuntimeError):coordinate_video_stop(('a','a'),{},99,forbidden,forbidden)
