import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from instrumentation_progress import run


class InstrumentationProgressTest(unittest.TestCase):
    def test_progress_preserves_output_and_does_not_count_fixture_status_as_completion(self):
        header='INSTRUMENTATION_STATUS: class=app.umbra.SyntheticTest\nINSTRUMENTATION_STATUS: test=synthetic\n'
        output=header+'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: vaultFixtureKeystoreSecurityLevel=0\nINSTRUMENTATION_STATUS_CODE: 0\n'+header+'INSTRUMENTATION_STATUS_CODE: 0\n'
        with tempfile.TemporaryDirectory() as d:
            stream=io.StringIO();report=Path(d)/'timing.json'
            result=run([sys.executable,'-c','print('+repr(output)+',end="")'],stream,report,timeout=2)
            self.assertEqual(0,result.returncode);self.assertEqual(output,stream.getvalue())
            v=json.loads(report.read_text());self.assertEqual(1,len(v['completed']));self.assertIsNone(v['active'])
            self.assertLessEqual(v['completed'][0]['startedNanos'],v['completed'][0]['finishedNanos'])

    def test_timeout_remains_failure_and_retains_active_test_age(self):
        output='INSTRUMENTATION_STATUS: class=app.umbra.SyntheticTest\nINSTRUMENTATION_STATUS: test=blocked\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: secret=not-retained\nINSTRUMENTATION_STATUS_CODE: 0\n'
        with tempfile.TemporaryDirectory() as d:
            report=Path(d)/'timing.json'
            with self.assertRaises(subprocess.TimeoutExpired):
                run([sys.executable,'-c','import time;print('+repr(output)+',flush=True);time.sleep(5)'],io.StringIO(),report,timeout=.3)
            v=json.loads(report.read_text());self.assertTrue(v['timedOut']);self.assertEqual('blocked',v['active']['test'])
            self.assertEqual([],v['completed']);self.assertNotIn('not-retained',report.read_text())

    def test_nonzero_exit_is_not_success(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(7,run([sys.executable,'-c','raise SystemExit(7)'],io.StringIO(),Path(d)/'timing.json',2).returncode)


if __name__=='__main__':unittest.main()
