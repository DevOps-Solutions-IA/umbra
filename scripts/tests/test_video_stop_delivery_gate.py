"""Deterministic harness coordination/clock tests; no claim of native media acceptance."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'android/app/src/androidTestConnected/java/app/umbra/media/VideoStopDeliveryGate.java'


class VideoStopDeliveryGateTest(unittest.TestCase):
    def run_java(self, body):
        with tempfile.TemporaryDirectory() as folder:
            test = Path(folder) / 'Check.java'
            test.write_text('''package app.umbra.media;
public final class Check {
 static void check(boolean value) { if(!value)throw new AssertionError(); }
 static void reject(Runnable action) {
   try {action.run();} catch(SecurityException expected) {return;}
   throw new AssertionError("Accepted stale or premature release");
 }
 public static void main(String[] args) {''' + body + '}}')
            subprocess.run(['javac', '-d', folder, str(SOURCE), str(test)], check=True, capture_output=True)
            return subprocess.run(['java', '-cp', folder, 'app.umbra.media.Check'], capture_output=True, text=True)

    def test_diagnostic_counts_only_expected_peer_stop_intersection(self):
        result=self.run_java('''
 var gate=new VideoStopDeliveryGate("call",2,"nonce");
 check(gate.ownStopCount()==0 && gate.expectedPeerStopCount()==0 && gate.appliedPeerStopCount()==0);
 gate.issued(java.util.List.of("own-a","own-b"));
 gate.received("unrelated");gate.received("peer-a");
 check(gate.ownStopCount()==2 && gate.expectedPeerStopCount()==0 && gate.appliedPeerStopCount()==0);
 gate.release(true,2,"nonce",java.util.List.of("peer-a","peer-b"));
 check(gate.expectedPeerStopCount()==2 && gate.appliedPeerStopCount()==1 && !gate.peerStopApplied());
 gate.received("unrelated");gate.received("peer-a");
 check(gate.appliedPeerStopCount()==1);
 gate.received("peer-b");
 check(gate.appliedPeerStopCount()==2 && gate.peerStopApplied());
''')
        self.assertEqual(0,result.returncode,result.stderr)

    def test_original_uncoordinated_measurement_reproduces_negative_interval_red(self):
        result = self.run_java('''
 // Exact first-CAS ordering: peer A stops; its message invalidates B at t=110.
 // B's later local request cannot replace that recorded first invalidation.
 long remoteInvalidation=110, localRequest=200, closed=150;
 check(remoteInvalidation-localRequest<0);
 check(VideoStopDeliveryGate.withinBounds(localRequest,remoteInvalidation,120,closed));
''')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('AssertionError', result.stderr)

    def test_both_local_requests_precede_release_and_immutable_delivery(self):
        result = self.run_java('''
 var a=new VideoStopDeliveryGate("call",2,"nonce-a");
 var b=new VideoStopDeliveryGate("call",2,"nonce-b");
 byte[] ciphertext={1,2,3};byte[] original=ciphertext.clone();int transported=0;
 // Before the controlled local action, automatic VIDEO_STOP is never hidden.
 check(!a.defer("VIDEO_STOP","call",2));
 a.issued(java.util.List.of("stop-a"));
 if(!a.defer("VIDEO_STOP","call",2))transported++;
 check(transported==0);check(java.util.Arrays.equals(ciphertext,original));
 check(!a.defer("ICE","call",2));check(!a.defer("VIDEO_STOP","other",2));
 check(!a.defer("VIDEO_STOP","call",3));
 reject(()->b.release(true,2,"nonce-b",java.util.List.of("stop-a")));
 b.issued(java.util.List.of("stop-b"));
 reject(()->a.release(true,3,"nonce-a",java.util.List.of("stop-b")));reject(()->a.release(true,2,"nonce-b",java.util.List.of("stop-b")));
 reject(()->a.release(false,2,"nonce-a",java.util.List.of("stop-b")));
 reject(()->a.release(true,2,"nonce-a",java.util.List.of("stop-a")));
 // Host releases only after observing both issued acknowledgments.
 a.release(true,2,"nonce-a",java.util.List.of("stop-b"));b.release(true,2,"nonce-b",java.util.List.of("stop-a"));
 if(!a.defer("VIDEO_STOP","call",2))transported++;
 check(transported==1);check(java.util.Arrays.equals(ciphertext,original));
 check(!b.defer("VIDEO_STOP","call",2));
 check(!a.peerStopApplied());check(!b.peerStopApplied());
 a.received("wrong-envelope");a.received("stop-a");check(!a.peerStopApplied());
 // Release is not proof of receipt; a delayed peer keeps resume blocked.
 a.received("stop-b");check(a.peerStopApplied());check(!b.peerStopApplied());
 b.received("stop-a");check(b.peerStopApplied());
 reject(()->a.release(true,2,"nonce-a",java.util.List.of("replacement-stop")));
 check(VideoStopDeliveryGate.withinBounds(100,110,120,150));
 check(VideoStopDeliveryGate.withinBounds(200,210,220,250));
 // A stale first-generation release cannot unlock a fresh generation gate.
 var next=new VideoStopDeliveryGate("call",3,"nonce-next");next.issued(java.util.List.of("stop-next"));
 reject(()->next.release(true,2,"nonce-a",java.util.List.of("stop-a")));check(next.defer("VIDEO_STOP","call",3));
 // A can transmit after host releases A but before host releases B.
 var early=new VideoStopDeliveryGate("call",2,"nonce-early");early.issued(java.util.List.of("stop-early"));
 early.received("peer-already-applied");check(!early.peerStopApplied());
 early.release(true,2,"nonce-early",java.util.List.of("peer-already-applied"));check(early.peerStopApplied());
 var wrongEarly=new VideoStopDeliveryGate("call",2,"nonce-wrong");wrongEarly.issued(java.util.List.of("stop-wrong"));
 wrongEarly.received("unrelated");wrongEarly.release(true,2,"nonce-wrong",java.util.List.of("required-peer-stop"));
 check(!wrongEarly.peerStopApplied());

''')
        self.assertEqual(0, result.returncode, result.stderr)

    def test_bounded_sets_require_every_peer_control_without_dropping_duplicates(self):
        result = self.run_java('''
 var g=new VideoStopDeliveryGate("call",2,"nonce");
 g.requireAnnounced("VIDEO_STOP","call",2,"automatic-before-issued");
 g.issued(java.util.List.of("a1","a2"));
 g.requireAnnounced("VIDEO_STOP","call",2,"a1");
 g.requireAnnounced("VIDEO_STOP","other",2,"unrelated");
 g.requireAnnounced("VIDEO_STOP","call",3,"later-generation");
 boolean guarded=false;
 try {g.requireAnnounced("VIDEO_STOP","call",2,"late-stop");}
 catch(AssertionError expected) {guarded=true;check(!expected.getMessage().contains("late-stop"));}
 check(guarded);

 g.received("b1");
 g.release(true,2,"nonce",java.util.List.of("b1","b2"));
 check(!g.peerStopApplied());g.received("unrelated");check(!g.peerStopApplied());
 g.received("b2");check(g.peerStopApplied());
 check(g.stopEnvelopeIds().size()==2);
 reject(()->new VideoStopDeliveryGate("call",2,"n").issued(java.util.List.of("dup","dup")));
 reject(()->new VideoStopDeliveryGate("call",2,"n").issued(java.util.List.of()));
 reject(()->g.release(true,2,"nonce",java.util.List.of("b1","b1")));
 reject(()->g.release(true,2,"nonce",java.util.List.of("a1","b2")));
 var many=new java.util.ArrayList<String>();for(int i=0;i<128;i++)many.add("s"+i);
 var bounded=new VideoStopDeliveryGate("call",2,"bounded");bounded.issued(many);
 many.add("overflow");reject(()->new VideoStopDeliveryGate("call",2,"over").issued(many));
''')
        self.assertEqual(0, result.returncode, result.stderr)

    def test_exact_bounds_and_all_over_bounds_remain_rejected(self):
        result = self.run_java('''
 long t=10_000_000_000L;
 check(VideoStopDeliveryGate.withinBounds(t,t+500_000_000L,t+1_500_000_000L,t+2_000_000_000L));
 check(!VideoStopDeliveryGate.withinBounds(t,t+500_000_001L,t,t));
 check(!VideoStopDeliveryGate.withinBounds(t,t,t+1_500_000_001L,t));
 check(!VideoStopDeliveryGate.withinBounds(t,t,t,t+2_000_000_001L));
 check(!VideoStopDeliveryGate.withinBounds(t,t-1,t,t));
 check(!VideoStopDeliveryGate.withinBounds(t,t,t,t-1));
 check(!VideoStopDeliveryGate.withinBounds(t,0,0,0));
 check(VideoStopDeliveryGate.withinBounds(t,t,t-1,t));
''')
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == '__main__': unittest.main()
