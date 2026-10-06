import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location('pairing_restart', Path(__file__).resolve().parents[1] / 'run_pairing_restart.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PairingRestartReportTest(unittest.TestCase):
    def test_final_requires_listener_marker_exact_baseline_count_and_success(self):
        report = 'pairingRestart=PASS synthetic\nOK (3 tests)\nINSTRUMENTATION_CODE: -1\n'
        self.assertTrue(MODULE.verified_report(report, 0))
        for invalid in (report.replace('PASS', 'READY'), report.replace('3 tests', '2 tests'),
                        report.replace('CODE: -1', 'CODE: 0'), report + 'FAILURES!!!',
                        report + 'INSTRUMENTATION_STATUS_CODE: -2', report + 'Process crashed'):
            self.assertFalse(MODULE.verified_report(invalid, 0))
        self.assertFalse(MODULE.verified_report(report, 1))

    def test_baseline_does_not_require_pairing_listener(self):
        report = 'OK (3 tests)\nINSTRUMENTATION_CODE: -1\n'
        self.assertTrue(MODULE.verified_report(report, 0, final=False))
        self.assertFalse(MODULE.verified_report(report, 0))

    def test_only_isolated_lab_targets_and_four_committed_stages(self):
        self.assertEqual(MODULE.PHASES, ('invite', 'request', 'accept', 'complete'))
        self.assertEqual(MODULE.target_package('connected', False), 'app.umbra.privatechat.dev')
        self.assertEqual(MODULE.target_package('offline', True), 'app.umbra.privatechat.offline.vaultlab')
        with self.assertRaises(ValueError):
            MODULE.target_package('release', False)


if __name__ == '__main__':
    unittest.main()
