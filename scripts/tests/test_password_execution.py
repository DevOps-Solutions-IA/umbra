"""Strict receipt verification; these are host tests, not Android acceptance."""
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_password_tests import valid_report
from run_password_restart import verified_report


class PasswordExecutionTests(unittest.TestCase):
    def test_complete_eight_cases_required(self):
        good = 'OK (8 tests)\nINSTRUMENTATION_CODE: -1\n'
        self.assertTrue(valid_report(good, 0))
        self.assertFalse(valid_report(good, 1))
        self.assertFalse(valid_report(good.replace('8 tests', '6 tests'), 0))
        self.assertFalse(valid_report(good + 'INSTRUMENTATION_STATUS_CODE: -3\n', 0))
        self.assertFalse(valid_report(good.replace('INSTRUMENTATION_CODE: -1', ''), 0))

    def test_restart_needs_explicit_assertion_and_successful_tests(self):
        good = 'passwordRestart=PASS\nOK (3 tests)\nINSTRUMENTATION_CODE: -1\n'
        self.assertTrue(verified_report(good, 0))
        for report in (good.replace('PASS', 'READY'), good.replace('3 tests', '0 tests'),
                       good + 'Process crashed', good + 'INSTRUMENTATION_STATUS_CODE: -3'):
            self.assertFalse(verified_report(report, 0))
