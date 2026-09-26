"""Negative guard tests for lab evidence classification, not media acceptance."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]

class ExpiredDeliveryTest(unittest.TestCase):
    def test_only_expected_terminal_same_call_rejection_is_evidence(self):
        source=ROOT/'android/app/src/androidTestConnected/java/app/umbra/media/ExpiredDeliveryAssertion.java'
        harness='''package app.umbra.media;
public final class Check {
  public static void main(String[] args) {
    SecurityException rejection=new SecurityException("Call interrupted");
    ExpiredDeliveryAssertion.check(rejection,true,true,"synthetic-call","synthetic-call");
    reject(rejection,false,true,"synthetic-call","synthetic-call");
    reject(rejection,true,false,"synthetic-call","synthetic-call");
    reject(rejection,true,true,"synthetic-call","other-call");
    reject(rejection,true,true,"","");
    reject(new SecurityException("Identity changed"),true,true,"synthetic-call","synthetic-call");
  }
  static void reject(SecurityException original,boolean expired,boolean terminal,String expected,String queued) {
    try { ExpiredDeliveryAssertion.check(original,expired,terminal,expected,queued); }
    catch(SecurityException failure) { if(failure!=original)throw new AssertionError("Rejection replaced"); return; }
    throw new AssertionError("Unexpected rejection hidden");
  }
}'''
        with tempfile.TemporaryDirectory() as folder:
            test=Path(folder)/'Check.java';test.write_text(harness)
            subprocess.run(['javac','-d',folder,str(source),str(test)],check=True,capture_output=True)
            subprocess.run(['java','-cp',folder,'app.umbra.media.Check'],check=True,capture_output=True)
