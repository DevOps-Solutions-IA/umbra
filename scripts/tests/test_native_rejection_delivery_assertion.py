"""Lab rejection classification only; not native fingerprint acceptance evidence."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]

class NativeRejectionDeliveryTest(unittest.TestCase):
    def test_exact_binding_rejection_only(self):
        source=ROOT/'android/app/src/androidTestConnected/java/app/umbra/media/NativeRejectionDeliveryAssertion.java'
        harness='''package app.umbra.media;
public final class Check {
 public static void main(String[] args) {
  SecurityException original=new SecurityException("Call interrupted");
  NativeRejectionDeliveryAssertion.check(original,true,"FAILED","native-certificate-binding",0,0,0,"call","call");
  reject(original,false,"FAILED","native-certificate-binding",0,0,0,"call","call");
  for(String state:new String[]{"ACTIVE","NEGOTIATING","ENDED",null})
   reject(original,true,state,"native-certificate-binding",0,0,0,"call","call");
  for(String reason:new String[]{"native-connection-failed","authorization-cancelled","local-watchdog",null})
   reject(original,true,"FAILED",reason,0,0,0,"call","call");
  for(int index=0;index<3;index++)for(int count:new int[]{-1,1})
   reject(original,true,"FAILED","native-certificate-binding",index==0?count:0,index==1?count:0,index==2?count:0,"call","call");
  for(String call:new String[]{null,"","other"}) {
   reject(original,true,"FAILED","native-certificate-binding",0,0,0,call,"call");
   reject(original,true,"FAILED","native-certificate-binding",0,0,0,"call",call);
  }
  for(String reason:new String[]{"Delivery expired","Call expired","Call unavailable","Identity changed",null})
   reject(new SecurityException(reason),true,"FAILED","native-certificate-binding",0,0,0,"call","call");
 }
 static void reject(SecurityException original,boolean expected,String state,String reason,int captured,int decoded,int video,String call,String queued) {
  try {NativeRejectionDeliveryAssertion.check(original,expected,state,reason,captured,decoded,video,call,queued);}
  catch(SecurityException failure){if(failure!=original)throw new AssertionError("Replaced rejection");return;}
  throw new AssertionError("Unrelated failure hidden");
 }
}'''
        with tempfile.TemporaryDirectory() as folder:
            test=Path(folder)/'Check.java';test.write_text(harness)
            subprocess.run(['javac','-d',folder,str(source),str(test)],check=True,capture_output=True)
            subprocess.run(['java','-cp',folder,'app.umbra.media.Check'],check=True,capture_output=True)
