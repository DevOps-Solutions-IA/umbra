"""A historical RED is evidence only when the exact behavioral assertion actually executed."""
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_vault_deadline_baseline import CLASS, METHOD, EXPECTED_ASSERTION, expected_red


class DeadlineBaselineTests(unittest.TestCase):
    def report(self):
        return (f'INSTRUMENTATION_STATUS: class={CLASS}\n'
                f'INSTRUMENTATION_STATUS: test={METHOD}\n'
                'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS_CODE: 1\n'
                f'INSTRUMENTATION_STATUS: class={CLASS}\n'
                f'INSTRUMENTATION_STATUS: test={METHOD}\n'
                f'INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: {EXPECTED_ASSERTION}\n'
                f' at {CLASS}.{METHOD}(DeviceVaultDeadlineProbeTest.java:42)\n'
                'INSTRUMENTATION_STATUS_CODE: -2\nFAILURES!!!\nTests run: 1,  Failures: 1\n'
                'INSTRUMENTATION_CODE: -1\n')

    def test_exact_behavioral_red_is_recognized(self):
        self.assertTrue(expected_red(self.report(), 0))

    def test_green_empty_or_runner_failure_never_proves_regression(self):
        for report, code in (('', 0), ('OK (1 test)\nINSTRUMENTATION_CODE: -1\n', 0),
                             (self.report(), 1), (self.report().replace('INSTRUMENTATION_CODE: -1', ''), 0)):
            with self.subTest(report=report[:24], code=code):
                self.assertFalse(expected_red(report, code))

    def test_other_assertions_or_multiple_failures_are_not_expected_red(self):
        report = self.report()
        for broken in (report.replace(EXPECTED_ASSERTION, 'expected true'),
                       report.replace('Tests run: 1,  Failures: 1', 'Tests run: 2,  Failures: 2'),
                       report + 'INSTRUMENTATION_STATUS_CODE: -2\n',
                       report + 'INSTRUMENTATION_STATUS_CODE: -3\n',
                       report + 'INSTRUMENTATION_STATUS: test=someOtherCase\n',
                       report.replace(f' at {CLASS}.{METHOD}', ' at OtherCase.method')):
            self.assertFalse(expected_red(broken, 0))

    def test_linkage_and_process_failures_are_never_product_red(self):
        for reason in ('VerifyError', 'ClassNotFoundException', 'NoClassDefFoundError', 'Process crashed',
                       'ExceptionInInitializerError', 'INSTRUMENTATION_FAILED'):
            with self.subTest(reason=reason):
                self.assertFalse(expected_red(self.report() + reason, 0))


if __name__ == '__main__':
    unittest.main()
