"""Host receipt regression, not Android execution evidence."""
import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from run_emergency_tests import valid_report
from run_voice_integration import valid_emergency_stop, valid_stop

class EmergencyReceiptTests(unittest.TestCase):
    def test_requires_all_cases_without_skip_crash_or_bad_exit(self):
        good='OK (6 tests)\nINSTRUMENTATION_CODE: -1\n'
        self.assertTrue(valid_report(good,0))
        self.assertFalse(valid_report(good,1))
        for bad in (good.replace('6 tests','0 tests'),good.replace('6 tests','3 tests'),
                    good+'Process crashed',good+'INSTRUMENTATION_STATUS_CODE: -3',
                    good.replace('INSTRUMENTATION_CODE: -1','')):
            self.assertFalse(valid_report(bad,0))

    def test_startup_is_observed_before_intentional_sensor_acquisition(self):
        workflow=(Path(__file__).resolve().parents[2]/'.github/workflows/emergency-lock.yml').read_text()
        self.assertLess(workflow.index('python scripts/run_private_startup.py'),workflow.index('python scripts/run_emergency_tests.py'))

    def test_emergency_media_receipt_has_its_own_strict_schema(self):
        report=dict(failedClosed=True,nativeCaptureQuietAfterMillis=1000,
                    nativeCaptureObservedMillis=500,lateCaptureCallbacks=0,expiredDeliveriesRejected=0,
                    emergencyState='CLOSED',requestedNanos=100,invalidatedNanos=101,
                    confirmedNanos=200,lateVideoCallbacks=0,lastAudioCaptureNanos=150,lastVideoCaptureNanos=0)
        self.assertFalse(valid_stop(report))  # old schema deliberately remains strict
        self.assertTrue(valid_emergency_stop(report))
        self.assertFalse(valid_emergency_stop(report,video=True))
        self.assertTrue(valid_emergency_stop({**report,'lastVideoCaptureNanos':180},video=True))
        for key,value in (('emergencyState','INCOMPLETE'),('lateVideoCallbacks',1),
                ('lastAudioCaptureNanos',201),('requestedNanos',False),('confirmedNanos',6_000_000_000),
                ('nativeCaptureObservedMillis',0),('lateCaptureCallbacks',1),('unknown',0)):
            self.assertFalse(valid_emergency_stop({**report,key:value}))
        for key in report:
            self.assertFalse(valid_emergency_stop({k:v for k,v in report.items() if k!=key}))
