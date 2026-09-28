import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('privacy_runner', Path(__file__).parents[1] / 'run_privacy_tests.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

class PrivacyReportsTest(unittest.TestCase):
    def test_restart_requires_domain_receipt_and_completed_jni_suite(self):
        good = 'restrictedRestart=PASS\nOK (3 tests)\nINSTRUMENTATION_CODE: -1\n'
        self.assertTrue(runner.valid_restart_report(good, 0))
        for bad in ('', good.replace('PASS', 'READY'), good.replace('3 tests', '0 tests'),
                    good + 'Process crashed', good + 'INSTRUMENTATION_STATUS_CODE: -2'):
            self.assertFalse(runner.valid_restart_report(bad, 0))
        self.assertFalse(runner.valid_restart_report(good, 1))

    def test_exact_completed_suite_required(self):
        good = 'OK (12 tests)\nINSTRUMENTATION_CODE: -1\n'
        self.assertTrue(runner.valid_report(good, 0))
        for bad in ('', 'OK (0 tests)\nINSTRUMENTATION_CODE: -1\n',
                    'OK (12 tests)\n', good + 'Process crashed',
                    good + 'INSTRUMENTATION_STATUS_CODE: -3'):
            self.assertFalse(runner.valid_report(bad, 0))
        self.assertFalse(runner.valid_report(good, 1))
        self.assertTrue(runner.valid_report(good.replace('12 tests', '13 tests'), 0, 13))
        self.assertFalse(runner.valid_report(good, 0, 13))
        self.assertFalse(runner.valid_report(good.replace('12 tests', '0 tests'), 0, 0))

    def test_r8_requires_all_exercised_entry_points_and_optimization(self):
        names = ('app.umbra.privacy.ImagePreparation',
                 'app.umbra.content.RestrictedContentService',
                 'app.umbra.content.RestrictedImages$Decoder',
                 'app.umbra.content.RestrictedAudio',
                 'app.umbra.content.RestrictedPlayback',
                 'app.umbra.content.RestrictedDocuments$Decoder')
        mapping = '\n'.join(f'{name} -> synthetic.c{index}:' for index, name in enumerate(names))
        self.assertEqual(6, len(runner.optimized_classes(mapping, '')))
        for bad in ('', mapping.replace(names[0], 'synthetic.Missing'),
                    mapping.replace('synthetic.c0', names[0])):
            with self.assertRaises(RuntimeError):
                runner.optimized_classes(bad, '')
        for disabled in ('-dontoptimize', '-dontobfuscate', '-dontshrink'):
            with self.assertRaises(RuntimeError):
                runner.optimized_classes(mapping, disabled)
