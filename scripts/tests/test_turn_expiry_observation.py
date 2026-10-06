"""Clock instrumentation contract only; no native media or real credential claim."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
LAB=ROOT/'android/app/src/androidTestConnected/java/app/umbra/media'

class TurnExpiryObservationTest(unittest.TestCase):
    def test_exact_clock_values_and_actual_turn_check_rejections(self):
        turn=(ROOT/'android/app/src/connected/java/app/umbra/media/TurnConfiguration.java').read_text()
        check=turn.split('    public synchronized void check() {',1)[1].split('\n    public synchronized void fail',1)[0]
        # Execute the actual production check body; unrelated native/configuration
        # dependencies are fixtures, not a claim that WebRTC ran on this JVM.
        program='''package app.umbra.media;
import java.util.function.LongSupplier;
public class Check {
 static void require(boolean b){if(!b)throw new AssertionError();}
 static class RelayOnlyContract {static final String FAILURE="fixed";boolean failed(){return false;}}
 static class Guard {
  LongSupplier elapsedMillis;long createdMillis,deadlineMillis;boolean closed;
  RelayOnlyContract contract=new RelayOnlyContract();
  Guard(LongSupplier c){elapsedMillis=c;createdMillis=c.getAsLong();deadlineMillis=createdMillis+100;}
  void close(){closed=true;}
  public synchronized void check() {'''+check+'''
 }
 static boolean rejected(Guard g){try{g.check();return false;}catch(SecurityException expected){return true;}}
 public static void main(String[] args){
  for(long[] values:new long[][]{{1000,1099,1100,1001},{1000,999,1001},{1000,1000,1099,1101}}){
   int[] a={0},b={0};
   LongSupplier raw=()->values[a[0]++];
   var observer=new TurnExpiryObservation(()->values[b[0]++],100);
   var direct=new Guard(raw);var observed=new Guard(observer);
   for(int i=1;i<values.length;i++)require(rejected(direct)==rejected(observed));
   require(a[0]==b[0]);
   long[] snapshot=observer.snapshot(2000);require(b[0]==values.length);
   require(snapshot[0]==100 && snapshot[1]==1000 && snapshot[3]==values.length);
  }
  long[] values={200,299,300,350,250};int[] count={0};
  var observer=new TurnExpiryObservation(()->values[count[0]++],100);
  for(long value:values)require(observer.getAsLong()==value);
  require(observer.snapshot(600)[2]==100 && count[0]==values.length);
  RuntimeException original=new IllegalStateException("synthetic");
  var broken=new TurnExpiryObservation(()->{throw original;},100);
  try{broken.getAsLong();throw new AssertionError();}catch(RuntimeException e){require(e==original);}
  require(broken.snapshot(600)[3]==0);
 }
}'''
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/'Check.java';source.write_text(program)
            subprocess.run(['javac','-d',folder,str(LAB/'TurnExpiryObservation.java'),str(source)],check=True,capture_output=True)
            subprocess.run(['java','-cp',folder,'app.umbra.media.Check'],check=True,capture_output=True,timeout=10)

    def test_actual_finally_preserves_primary_and_fails_success_after_cleanup(self):
        source=(LAB/'VoiceEngineFixtureListener.java').read_text()
        block=source.rsplit('        } finally {',1)[1].rsplit('\n    }',1)[0].rsplit('\n        }',1)[0]
        program='''package app.umbra.media;
public class Check {
 static boolean fail;static int cleanup,restore;
 static class SystemClock {static long elapsedRealtime(){return 100;}}
 static class Bundle {void putInt(String k,int v){} void putLong(String k,long v){} void putString(String k,String v){}}
 static class InstrumentationRegistry {
  static InstrumentationRegistry getInstrumentation(){return new InstrumentationRegistry();}
  void sendStatus(int code,Bundle b){if(fail)throw new IllegalStateException("private message");}
 }
 static class HttpsURLConnection {static void setDefaultSSLSocketFactory(Object o){restore++;}}
 static class Voice {long receivedAudioPackets(){return 7;}String failureStage(){return "none";}void close(){cleanup++;}}
 static void run(AssertionError primary) {
  Throwable turnFixtureFailure=null;var turnExpiryObservation=new TurnExpiryObservation(()->1,100);
  var voice=new Voice();Object original=null;
  var captured=new java.util.concurrent.atomic.AtomicInteger();var decoded=captured;var loud=captured;var modified=captured;
  try {if(primary!=null)throw primary;}
  catch(AssertionError failure){turnFixtureFailure=failure;throw failure;}
  finally {'''+block+'''
  }
 }
 public static void main(String[] args){
  for(boolean broken:new boolean[]{false,true})for(boolean primary:new boolean[]{false,true}) {
   fail=broken;cleanup=0;restore=0;AssertionError original=new AssertionError("primary");
   try {run(primary?original:null);if(primary||broken)throw new IllegalStateException("Accepted failure");}
   catch(AssertionError e){
    if(primary && e!=original)throw new IllegalStateException("Masked primary");
    if(primary && e.getSuppressed().length!=(broken?1:0))throw new IllegalStateException("Missing diagnostic");
    if(!primary && !e.getMessage().equals("TURN expiry diagnostic unavailable"))throw new IllegalStateException("Unsafe diagnostic");
   }
   if(cleanup!=1||restore!=1)throw new IllegalStateException("Missing cleanup");
  }
 }
}'''
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'Check.java';path.write_text(program)
            subprocess.run(['javac','-d',folder,str(LAB/'TurnExpiryObservation.java'),str(path)],check=True,capture_output=True)
            subprocess.run(['java','-cp',folder,'app.umbra.media.Check'],check=True,capture_output=True,timeout=10)

    def test_listener_fixed_diagnostics_precede_cleanup_without_replacing_failure(self):
        source=(LAB/'VoiceEngineFixtureListener.java').read_text()
        block=source.rsplit('        } finally {',1)[1]
        fields=['turnRemainingAtConfigMillis','turnObservedElapsedMillis','turnFirstExpiryReadMillis','turnClockReadCount','turnObservedFailureStage','videoReceivedAudioPackets']
        import re
        self.assertEqual(fields,re.findall(r'diagnostic.put(?:Long|String)\("([^"]+)"',block))
        self.assertLess(block.index('sendStatus'),block.index('voice.close()'))
        self.assertIn('catch(RuntimeException diagnosticUnavailable)',block)
        self.assertIn('turnFixtureFailure.addSuppressed(turnDiagnosticFailure)',block)
        self.assertIn('if(turnFixtureFailure==null && turnDiagnosticFailure!=null)throw turnDiagnosticFailure',block)
        self.assertNotIn('credential.',block)
        self.assertIn('remaining,turnExpiryObservation,credential.optString',source)
        self.assertIn('new TurnExpiryObservation(SystemClock::elapsedRealtime,remaining)',source)

if __name__=='__main__':unittest.main()
