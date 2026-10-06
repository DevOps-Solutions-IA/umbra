"""Execute the fixture's actual phase predicate; not native codec evidence."""
from pathlib import Path
import re,subprocess,tempfile,unittest
ROOT=Path(__file__).resolve().parents[2]
class VideoPhaseReadinessTest(unittest.TestCase):
 def test_send_phase_requires_existing_twenty_capture_threshold(self):
  source=(ROOT/'android/app/src/androidTestConnected/java/app/umbra/media/VoiceEngineFixtureListener.java').read_text()
  condition=re.search(r'if\((framesReady &&[^\n]+)\) \{',source).group(1)
  declaration=re.search(r'boolean localCaptureReady=[^;]+;',source)
  condition=condition.replace('voice.videoStatus().equals("ACTIVE")','true').replace('decoded.get()-videoAudioBaseline>=50','true')
  code='''import java.util.concurrent.atomic.AtomicInteger;
public class Check {public static void main(String[] args) {
 for(boolean oneWay:new boolean[]{false,true})for(boolean caller:new boolean[]{false,true})for(int count:new int[]{0,19,20,21}) {
  var videoCaptured=new AtomicInteger(count);int videoCaptureBaseline=0;boolean framesReady=true;
'''+(declaration.group(0) if declaration else '')+'\n boolean actual='+condition+''';
  boolean expected=(oneWay && !caller)||count>=20;
  if(actual!=expected)throw new AssertionError("Capture phase accepted below required threshold: "+count);
 }
}}'''
  with tempfile.TemporaryDirectory() as folder:
   p=Path(folder)/'Check.java';p.write_text(code)
   subprocess.run(['javac','-d',folder,str(p)],check=True,capture_output=True)
   result=subprocess.run(['java','-cp',folder,'Check'],capture_output=True,text=True)
   self.assertEqual(0,result.returncode,result.stderr)
