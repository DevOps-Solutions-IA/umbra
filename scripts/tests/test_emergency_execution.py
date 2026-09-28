"""Host receipt regression, not Android execution evidence."""
import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from run_emergency_tests import valid_report

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
