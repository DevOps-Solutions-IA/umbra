"""Regression checks for orchestration failure propagation, not Android behavior."""
from contextlib import redirect_stdout
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_android_instrumentation as single
import run_bluetooth_emulation as nearby


class AndroidExecutionTests(unittest.TestCase):
    def run_single(self, report, returncode=0, flavor="offline"):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            for name in ('app.apk', 'test.apk'):
                (base / name).touch()
            args = ['runner', '--serial', 'emulator-synthetic', '--flavor', flavor,
                    '--app-apk', str(base / 'app.apk'), '--test-apk', str(base / 'test.apk'),
                    '--log', str(base / 'run.log'), '--adb', 'synthetic-adb']
            def execute(command, **kwargs):
                if 'instrument' in command:
                    kwargs['stdout'].write(report)
                    return subprocess.CompletedProcess(command, returncode)
                return subprocess.CompletedProcess(command, 0)
            with patch.object(sys, 'argv', args), patch.object(single.subprocess, 'run', side_effect=execute), redirect_stdout(io.StringIO()):
                single.main()

    def test_zero_tests_is_failure_even_with_successful_adb(self):
        with self.assertRaises(SystemExit):
            self.run_single('OK (0 tests)\nINSTRUMENTATION_CODE: -1\n')

    def test_skip_cannot_be_hidden_by_summary(self):
        with self.assertRaises(SystemExit):
            self.run_single('INSTRUMENTATION_STATUS_CODE: -3\nOK (25 tests)\nINSTRUMENTATION_CODE: -1\n')

    def test_missing_completion_is_failure(self):
        with self.assertRaises(SystemExit):
            self.run_single('OK (25 tests)\n')

    def test_adb_failure_is_not_overridden_by_test_summary(self):
        with self.assertRaises(SystemExit):
            self.run_single('OK (25 tests)\nINSTRUMENTATION_CODE: -1\n', returncode=1)

    def test_twenty_five_executed_checks_and_completion_pass(self):
        self.run_single('OK (25 tests)\nINSTRUMENTATION_CODE: -1\n')

    def test_connected_requires_new_surface_lifecycle_checks(self):
        with self.assertRaises(SystemExit):
            self.run_single('OK (25 tests)\nINSTRUMENTATION_CODE: -1\n',flavor='connected')
        self.run_single('OK (27 tests)\nINSTRUMENTATION_CODE: -1\n',flavor='connected')

    def test_physical_device_rejected_before_install_or_radio_changes(self):
        with tempfile.TemporaryDirectory() as folder:
            args = ['runner', '--serial-a', 'synthetic-physical', '--serial-b', 'emulator-synthetic',
                    '--address-a', 'BB:BB:BB:00:00:01', '--address-b', 'BB:BB:BB:00:00:02',
                    '--log-dir', folder, '--adb', 'synthetic-adb']
            with patch.object(sys, 'argv', args), patch.object(nearby.subprocess, 'run',
                    return_value=subprocess.CompletedProcess([], 0, '0\n', '')) as run:
                with self.assertRaisesRegex(RuntimeError, 'not an Android emulator'):
                    nearby.main()
                self.assertEqual(run.call_count, 1)
                self.assertEqual(run.call_args.args[0][-3:], ['shell', 'getprop', 'ro.kernel.qemu'])
