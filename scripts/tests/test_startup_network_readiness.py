"""Fixture synchronization regressions; these are not Android network acceptance."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
LISTENER = ROOT / 'android/app/src/androidTest/java/app/umbra/PrivateStartupFixtureListener.java'
SOURCE = LISTENER.with_name('StartupNetworkReadiness.java')


class StartupNetworkReadinessTest(unittest.TestCase):
    def test_initial_connect_and_recovery_use_same_readiness_gate(self):
        # Wiring guard only: behavioral coverage is the executable Java harness below.
        source = LISTENER.read_text()
        first = source.index('AndroidConnectivity.connect(context,engine.connectivity(),base,true);')
        self.assertIn('awaitDefaultNetwork(context,engine,trap,StartupNetworkReadiness.Stage.INITIAL)', source[:first])
        recovery = source.index('checkpoint("network-lost");denied(engine,trap);')
        self.assertIn('awaitDefaultNetwork(context,engine,trap,StartupNetworkReadiness.Stage.RECOVERY)', source[recovery:])

    def run_java(self, body):
        with tempfile.TemporaryDirectory() as folder:
            harness = Path(folder) / 'Check.java'
            harness.write_text('''package app.umbra;
import java.util.*;
public final class Check {
 static void check(boolean value) {if(!value)throw new AssertionError("Harness assertion");}
 public static void main(String[] args) throws Exception {
''' + body + '\n}}')
            subprocess.run(['javac', '-d', folder, str(SOURCE), str(harness)], check=True, capture_output=True)
            result = subprocess.run(['java', '-cp', folder, 'app.umbra.Check'], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_delayed_default_preserves_denial_and_precedes_single_explicit_action(self):
        self.run_java('''
 for(var stage:StartupNetworkReadiness.Stage.values()) {
  long[] clock={0};int[] denied={0},observed={0},connected={0};
  var events=new ArrayList<String>();
  StartupNetworkReadiness.await(stage,()->clock[0],()->{events.add("observe");observed[0]++;return clock[0]>=100;},
   ()->{denied[0]++;events.add("denied");},millis->{check(millis==50);clock[0]+=millis;},receipt->{
    check(receipt.stage()==stage && receipt.elapsedMillis()==100 && receipt.defaultNetworkPresent());
    check(receipt.outcome()==StartupNetworkReadiness.Outcome.READY && connected[0]==0);events.add("receipt");
   });
  connected[0]++;
  check(connected[0]==1 && denied[0]==3 && observed[0]==3);
  check(events.equals(List.of("denied","observe","denied","observe","denied","observe","receipt")));
 }
''')

    def test_unavailable_or_available_only_at_deadline_never_reaches_connect(self):
        self.run_java('''
 for(boolean lateAvailability:new boolean[]{false,true}) {
  long[] clock={0};int[] sleeps={0},connected={0},receipts={0},denied={0};
  try {
   StartupNetworkReadiness.await(StartupNetworkReadiness.Stage.INITIAL,()->clock[0],
    ()->lateAvailability && clock[0]>=15000,()->denied[0]++,millis->{check(millis==50);clock[0]+=millis;sleeps[0]++;},receipt->{
     receipts[0]++;check(receipt.elapsedMillis()==15000 && receipt.outcome()==StartupNetworkReadiness.Outcome.TIMEOUT);
     check(receipt.defaultNetworkPresent()==lateAvailability);
    });
   connected[0]++;
  } catch(AssertionError expected) {check(expected.getMessage().contains("readiness budget"));}
  check(connected[0]==0 && receipts[0]==1 && sleeps[0]==300 && denied[0]==301 && clock[0]==15000);
 }
''')

    def test_denial_failure_propagates_before_observation_or_connect(self):
        self.run_java('''
 var sentinel=new SecurityException("synthetic denied failure");int[] observed={0},connected={0},receipts={0};
 try {
  StartupNetworkReadiness.await(StartupNetworkReadiness.Stage.INITIAL,()->0L,()->{observed[0]++;return true;},
   ()->{throw sentinel;},millis->{throw new AssertionError("slept");},receipt->{
    receipts[0]++;check(receipt.outcome()==StartupNetworkReadiness.Outcome.FAILED);
   });
  connected[0]++;
 } catch(SecurityException failure) {check(failure==sentinel);}
 check(observed[0]==0 && connected[0]==0 && receipts[0]==1);
''')

    def test_diagnostic_failure_cannot_mask_denial_or_permit_connect(self):
        self.run_java('''
 var sentinel=new SecurityException("denial");var diagnostic=new IllegalStateException("receipt");
 try {
  StartupNetworkReadiness.await(StartupNetworkReadiness.Stage.INITIAL,()->0L,()->true,
   ()->{throw sentinel;},millis->{},receipt->{throw diagnostic;});
  throw new AssertionError("reached connect");
 } catch(SecurityException failure) {
  check(failure==sentinel && failure.getSuppressed().length==1 && failure.getSuppressed()[0]==diagnostic);
 }
 try {
  StartupNetworkReadiness.await(StartupNetworkReadiness.Stage.INITIAL,()->0L,()->true,
   ()->{},millis->{},receipt->{throw diagnostic;});
  throw new AssertionError("reached connect despite failed receipt");
 } catch(IllegalStateException failure) {check(failure==diagnostic);}
''')

    def test_observation_failure_and_interruption_never_become_ready(self):
        self.run_java('''
 for(boolean interrupt:new boolean[]{false,true}) {
  int[] connected={0},receipts={0};
  try {
   StartupNetworkReadiness.await(StartupNetworkReadiness.Stage.RECOVERY,()->0L,
    ()->{if(!interrupt)throw new IllegalStateException("no manager");return false;},()->{},
    millis->{throw new InterruptedException("stop fixture");},receipt->{
     receipts[0]++;check(receipt.outcome()==StartupNetworkReadiness.Outcome.FAILED);
    });
   connected[0]++;
  } catch(IllegalStateException expected) {check(!interrupt);}
    catch(InterruptedException expected) {check(interrupt);}
  check(connected[0]==0 && receipts[0]==1);
 }
''')

    def test_immediate_default_is_observed_after_denial_without_sleep(self):
        self.run_java('''
 int[] denied={0},receipts={0};
 StartupNetworkReadiness.await(StartupNetworkReadiness.Stage.INITIAL,()->25L,
  ()->{check(denied[0]==1);return true;},()->denied[0]++,millis->{throw new AssertionError("unnecessary sleep");},
  receipt->{receipts[0]++;check(receipt.elapsedMillis()==0 && receipt.outcome()==StartupNetworkReadiness.Outcome.READY);});
 check(receipts[0]==1);
''')


if __name__ == '__main__':
    unittest.main()
