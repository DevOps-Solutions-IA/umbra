"""Instrumentation receipts must prove execution, not merely a successful adb exit."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('admission_runner', Path(__file__).resolve().parents[1] / 'run_admission_tests.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class AdmissionReceiptTest(unittest.TestCase):
    def test_four_successful_cases(self):
        self.assertTrue(runner.valid_report('OK (4 tests)\nINSTRUMENTATION_CODE: -1\n', 0))

    def test_restart_signal_suite_has_separate_exact_inventory(self):
        signal = 'OK (3 tests)\nINSTRUMENTATION_CODE: -1\n'
        admission = 'OK (4 tests)\nINSTRUMENTATION_CODE: -1\n'
        self.assertTrue(runner.valid_report(signal, 0, expected=3))
        self.assertFalse(runner.valid_report(signal, 0))
        self.assertFalse(runner.valid_report(admission, 0, expected=3))

    def test_empty_partial_skipped_crashed_and_failed_are_rejected(self):
        for text, code in [('', 0), ('OK (0 tests)\nINSTRUMENTATION_CODE: -1', 0),
                           ('OK (2 tests)\nINSTRUMENTATION_CODE: -1', 0),
                           ('OK (3 tests)\nINSTRUMENTATION_CODE: -1', 0),
                           ('OK (4 tests)\nINSTRUMENTATION_CODE: -1', 1),
                           ('OK (4 tests)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_STATUS_CODE: -3', 0),
                           ('OK (4 tests)\nINSTRUMENTATION_CODE: -1\nProcess crashed', 0)]:
            with self.subTest(text=text, code=code):
                self.assertFalse(runner.valid_report(text, code))
