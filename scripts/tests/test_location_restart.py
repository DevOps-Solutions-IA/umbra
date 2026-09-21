"""Host report validation, not an Android lifecycle simulation."""
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_location_restart import verified_report


class LocationRestartReports(unittest.TestCase):
    good = 'locationRestart=PASS no backlog\nOK (3 tests)\nINSTRUMENTATION_CODE: -1\n'

    def test_positive_receipt_requires_executed_suite_and_fixture(self):
        self.assertTrue(verified_report(self.good, 0))

    def test_failure_skip_empty_or_missing_phase_never_pass(self):
        for report in ('', self.good.replace('3 tests', '0 tests'),
                       self.good.replace('locationRestart=PASS', 'locationRestart=READY'),
                       self.good.replace('INSTRUMENTATION_CODE: -1', ''),
                       self.good + 'INSTRUMENTATION_STATUS_CODE: -3\n',
                       self.good + 'FAILURES!!!\n', self.good + 'Process crashed\n'):
            with self.subTest(report=report):
                self.assertFalse(verified_report(report, 0))
        self.assertFalse(verified_report(self.good, 1))
