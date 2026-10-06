import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('restricted_https', Path(__file__).parents[1]/'run_restricted_https.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RestrictedHttpsReportTest(unittest.TestCase):
    def test_positive_receipt_and_real_completed_suite_required(self):
        good = 'restrictedHttps=PASS real HTTPS admission Signal AAC isolated PDF and AVC both directions and consumption\nOK (3 tests)\nINSTRUMENTATION_CODE: -1\n'
        self.assertTrue(runner.valid_report(good, 0))
        for bad in ('', good.replace(' isolated PDF', ''), good.replace(' and AVC', ''), good.replace('PASS', 'READY'), good.replace('3 tests', '0 tests'),
                    good+'Process crashed', good+'INSTRUMENTATION_STATUS_CODE: -2'):
            self.assertFalse(runner.valid_report(bad, 0))
        self.assertFalse(runner.valid_report(good, 1))
