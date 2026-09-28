import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).parents[1]))
from media_crash_diagnostic import summarize

class CrashDiagnosticTest(unittest.TestCase):
    def test_owned_java_type_without_message_or_foreign_process(self):
        text='FATAL EXCEPTION: main\nProcess: app.umbra.privatechat.dev, PID: 7\njava.lang.SecurityException: synthetic-secret\nFATAL EXCEPTION: main\nProcess: other.app, PID: 8\njava.lang.IllegalStateException: foreign-secret\n'
        result=summarize(text,'app.umbra.privatechat.dev')
        self.assertEqual(['java.lang.SecurityException'],result['ownedCrashRecords'][0]['exceptionTypes'])
        self.assertNotIn('secret',json.dumps(result));self.assertEqual(1,len(result['ownedCrashRecords']))
    def test_owned_native_metadata_only_and_no_crash_is_not_invented(self):
        text='*** *** ***\npid: 8 >>> app.umbra.privatechat.medialab <<<\nsignal 6 (SIGABRT)\nAbort message: Check failed: synthetic-secret\n#00 pc abc /lib/libjingle_peerconnection_so.so (private-detail)\n'
        result=summarize(text,'app.umbra.privatechat.medialab')
        self.assertEqual([6],result['ownedCrashRecords'][0]['signals'])
        self.assertTrue(result['ownedCrashRecords'][0]['nativeCheckFailure'])
        self.assertNotIn('secret',json.dumps(result));self.assertNotIn('private-detail',json.dumps(result))
        self.assertEqual([],summarize(text,'app.umbra.privatechat.dev')['ownedCrashRecords'])
    def test_production_or_arbitrary_package_is_rejected(self):
        with self.assertRaises(ValueError):summarize('','app.umbra.privatechat')
