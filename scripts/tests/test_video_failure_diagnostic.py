from pathlib import Path
import subprocess,tempfile,re
import unittest

class VideoFailureDiagnosticTest(unittest.TestCase):
    def test_actual_fixed_diagnostic_precedes_and_preserves_failure(self):
        source=(Path(__file__).resolve().parents[2]/'android/app/src/androidTestConnected/java/app/umbra/media/VoiceEngineFixtureListener.java').read_text()
        a=source.index('                try {\n                    // Fixed metadata only.')
        b=source.index('                throw videoEvidenceFailure;',a)+len('                throw videoEvidenceFailure;')
        block=source[a:b]
        expected={'videoFixtureFailure','videoFixtureStage','videoStopGatePresent','videoStopReleased','videoPeerStopApplied','videoOwnStopCount','videoExpectedPeerStopCount','videoAppliedPeerStopCount','videoAudioBaseline','videoDecodedAudioDelta','videoFixtureElapsedMillis','videoFixtureDeadlineReached'}
        assert set(re.findall(r'diagnostic\.put\w+\("([^"]+)"',block))==expected
        assert block.index('sendStatus')<block.index('throw videoEvidenceFailure')
        assert '.nonce(' not in block and '.stopEnvelopeIds(' not in block and 'peerStopIds' not in block
        harness='''import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
        public class Check {
         static class Bundle extends HashMap<String,Object> {
          void putString(String k,String v){put(k,v);} void putInt(String k,int v){put(k,v);}
          void putLong(String k,long v){put(k,v);} void putBoolean(String k,boolean v){put(k,v);}
         }
         static class SystemClock {static long elapsedRealtime(){return 140100;}}
         static class Gate {boolean released(){return true;}boolean peerStopApplied(){return false;}
          int ownStopCount(){return 2;}int expectedPeerStopCount(){return 2;}int appliedPeerStopCount(){return 1;}}
         static class InstrumentationRegistry {
          static boolean fail;static Bundle observed;
          static InstrumentationRegistry getInstrumentation(){return new InstrumentationRegistry();}
          void sendStatus(int code,Bundle value){observed=value;if(fail)throw new IllegalStateException("private-payload");}
         }
         static AssertionError videoEvidenceFailure;
         static void execute(Gate videoStopGate) {
          int videoStage=6,videoAudioBaseline=100;var decoded=new AtomicInteger(170);
          long fixtureStarted=100,deadline=140000;
          videoEvidenceFailure=new AssertionError("original-red");
        '''+block+'''
         }
         public static void main(String[] args){
          for(boolean fail:new boolean[]{false,true})for(boolean present:new boolean[]{false,true}){
           InstrumentationRegistry.fail=fail;InstrumentationRegistry.observed=null;
           try {execute(present?new Gate():null);throw new AssertionError("False pass");}
           catch(AssertionError error){
            if(error!=videoEvidenceFailure)throw new AssertionError("Original failure replaced");
            if(error.getSuppressed().length!=(fail?1:0))throw new AssertionError("Diagnostic failure accounting");
            for(Throwable detail:error.getSuppressed())if(detail.toString().contains("private-payload"))throw new AssertionError("Leak");
           }
           var report=InstrumentationRegistry.observed;
           if(report==null || !report.get("videoDecodedAudioDelta").equals(70) ||
               !report.get("videoAppliedPeerStopCount").equals(present?1:0) ||
               !report.get("videoFixtureDeadlineReached").equals(true) || report.toString().contains("private-payload"))
              throw new AssertionError("Missing or unsafe diagnostic");
          }
         }
        }'''
        with tempfile.TemporaryDirectory() as folder:
         path=Path(folder);(path/'Check.java').write_text(harness)
         subprocess.run(['javac','-d',folder,str(path/'Check.java')],check=True,capture_output=True)
         subprocess.run(['java','-cp',folder,'Check'],check=True,capture_output=True)

if __name__ == "__main__": unittest.main()
