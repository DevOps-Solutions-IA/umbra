"""Real Java sampler tests; synthetic counters only, not multimedia acceptance."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]


class DecodedWindowTest(unittest.TestCase):
    def test_callback_clock_is_independent_of_coordinator_and_fails_closed(self):
        code='''
import app.umbra.media.DecodedAudioWindow;
public final class WindowRegression {
  static void check(boolean value) {if(!value)throw new AssertionError();}
  public static void main(String[] args) {
    var w=new DecodedAudioWindow(100,1200,2500,2000,3500);
    check(w.result()==null); // No callbacks cannot be reported as silence.
    w.sample(1300,10,20,30,40);
    check(w.result()==null);
    w.sample(3299,20,40,60,80);
    check(w.result()==null); // A short window is not evidence.
    w.sample(3300,50,22,70,45);
    // Controller may read hours later: timestamps/counters came from decoded callbacks.
    var r=w.result();check(r.failure().isEmpty() && r.settleMillis()==1200 && r.observedMillis()==2000);
    check(r.natural()==40 && r.modified()==2 && r.loud()==40 && r.video()==5);
    w.sample(999999,10000,10000,10000,10000);check(w.result()==r);
    var late=new DecodedAudioWindow(100,1200,2500,2000,3500);
    late.sample(2601,0,0,0,0);check(!late.result().failure().isEmpty());
    var over=new DecodedAudioWindow(100,1200,2500,2000,3500);
    over.sample(1300,0,0,0,0);over.sample(4801,0,0,0,0);check(!over.result().failure().isEmpty());
    var reset=new DecodedAudioWindow(100,1200,2500,2000,3500);
    reset.sample(1300,10,0,0,0);reset.sample(1400,9,0,0,0);check(!reset.result().failure().isEmpty());
    try {new DecodedAudioWindow(0,0,2500,2000,3500);throw new AssertionError();}
    catch(IllegalArgumentException expected) {}
  }
}
'''
        with tempfile.TemporaryDirectory(prefix="umbra-window-") as temporary:
            path=Path(temporary);source=path/"WindowRegression.java";source.write_text(code)
            subprocess.run(["javac","-d",str(path),str(ROOT/"android/app/src/androidTestConnected/java/app/umbra/media/DecodedAudioWindow.java"),str(source)],check=True,capture_output=True)
            subprocess.run(["java","-cp",str(path),"WindowRegression"],check=True,capture_output=True)
