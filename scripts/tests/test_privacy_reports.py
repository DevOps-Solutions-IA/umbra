import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('privacy_runner', Path(__file__).parents[1] / 'run_privacy_tests.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

class PrivacyReportsTest(unittest.TestCase):
    def test_exact_completed_suite_required(self):
        good = 'OK (6 tests)\nINSTRUMENTATION_CODE: -1\n'
        self.assertTrue(runner.valid_report(good, 0))
        for bad in ('', 'OK (0 tests)\nINSTRUMENTATION_CODE: -1\n',
                    'OK (6 tests)\n', good + 'Process crashed',
                    good + 'INSTRUMENTATION_STATUS_CODE: -3'):
            self.assertFalse(runner.valid_report(bad, 0))
        self.assertFalse(runner.valid_report(good, 1))
