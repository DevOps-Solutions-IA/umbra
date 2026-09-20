"""Synthetic process/adb regression tests; not Android or Bluetooth execution."""
from contextlib import redirect_stdout
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from wait_emulator import wait_boot


class BootTests(unittest.TestCase):
    def check_boot(self, responses, alive=lambda: True):
        clock = [0.0]
        def sleep(seconds): clock[0] += seconds
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / 'emulator.log'; log.write_text('synthetic emulator diagnostic')
            runner = Mock(side_effect=responses)
            output = io.StringIO()
            with redirect_stdout(output):
                try:
                    wait_boot(123, 'emulator-5554', 'adb', log, 4, alive=alive,
                              run=runner, now=lambda: clock[0], sleep=sleep)
                except RuntimeError:
                    self.assertIn('synthetic emulator diagnostic', output.getvalue())
                    raise
            return runner

    def result(self, text): return subprocess.CompletedProcess([], 0, text, '')

    def test_dead_process_fails_before_adb(self):
        with self.assertRaisesRegex(RuntimeError, 'process died'):
            self.check_boot([], alive=lambda: False)

    def test_adb_not_connected_times_out(self):
        with self.assertRaisesRegex(RuntimeError, 'adb not connected'):
            self.check_boot([self.result('List of devices attached\n')] * 2)

    def test_boot_timeout_when_connected(self):
        with self.assertRaisesRegex(RuntimeError, 'sys.boot_completed'):
            self.check_boot([self.result('emulator-5554\tdevice\n'), self.result('0\n')] * 2)

    def test_correct_boot_requires_connected_device(self):
        runner = self.check_boot([self.result('emulator-5554\tdevice\n'), self.result('1\n')])
        self.assertEqual(runner.call_count, 2)

    def test_offline_adb_is_not_connected(self):
        with self.assertRaisesRegex(RuntimeError, 'adb not connected'):
            self.check_boot([self.result('emulator-5554\toffline\n')] * 2)

    def test_death_during_property_query_is_not_success(self):
        life = iter([True, False, False])
        with self.assertRaisesRegex(RuntimeError, 'process died'):
            self.check_boot([self.result('emulator-5554\tdevice\n'), self.result('1\n')], alive=lambda: next(life))

    def test_boot_reported_after_deadline_is_rejected(self):
        clock = [0.0]
        timeouts = []
        def run(command, **kwargs):
            timeouts.append(kwargs['timeout'])
            clock[0] += 3
            return self.result('emulator-5554\tdevice\n' if command[-1] == 'devices' else '1\n')
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / 'emulator.log'
            log.write_text('synthetic deadline diagnostic')
            output = io.StringIO()
            with redirect_stdout(output), self.assertRaisesRegex(RuntimeError, 'boot timeout'):
                wait_boot(123, 'emulator-5554', 'adb', log, 4, alive=lambda: True,
                          run=run, now=lambda: clock[0], sleep=lambda _: None)
            self.assertEqual(timeouts, [4, 1])
            self.assertNotIn('PASS boot', output.getvalue())
            self.assertIn('synthetic deadline diagnostic', output.getvalue())

    def test_deadline_after_device_query_does_not_start_property_query(self):
        clock = [0.0]
        def run(command, **kwargs):
            self.assertEqual(command, ['adb', 'devices'])
            self.assertEqual(kwargs['timeout'], 4)
            clock[0] = 4
            return self.result('emulator-5554\tdevice\n')
        with tempfile.TemporaryDirectory() as temporary:
            with redirect_stdout(io.StringIO()), self.assertRaisesRegex(RuntimeError, 'boot timeout'):
                wait_boot(123, 'emulator-5554', 'adb', Path(temporary) / 'missing.log', 4,
                          alive=lambda: True, run=run, now=lambda: clock[0], sleep=lambda _: None)

    def test_cleanup_pid_exit_race_preserves_failure_and_visits_second_emulator(self):
        import os
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            script = Path(__file__).resolve().parents[1] / 'ci_emulator.sh'
            command = r'''source "$1"
UMBRA_EMULATOR_PIDS=(111 222)
UMBRA_EMULATOR_SERIALS=(emulator-5554 emulator-5556)
probe_count=0
timeout() { return 1; }
kill() {
  if [[ "$1" == "-0" ]]; then
    probe_count=$((probe_count + 1))
    if (( probe_count == 1 )); then return 0; fi
    return 1
  fi
  # PID 111 exits between the initial probe and fallback termination.
  return 1
}
wait() { echo "waited:$1"; return 0; }
exit 42
'''
            env = dict(os.environ, RUNNER_TEMP=str(root / 'temp'), ANDROID_HOME=str(root / 'sdk'),
                       UMBRA_DEVICE_REPORTS=str(root / 'reports'))
            result = subprocess.run(['bash', '-c', command, 'test', str(script)], env=env,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 42, result.stderr)
            self.assertIn('waited:111', result.stdout)
            self.assertIn('waited:222', result.stdout)

    def test_avd_not_created_fails_before_launch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); sdk = root / 'sdk'; (sdk / 'emulator').mkdir(parents=True)
            # Source replaces only external boundaries; actual bash creation checks execute.
            (sdk / 'emulator/emulator').write_text('#!/bin/sh\nif [ "$1" = "-list-avds" ]; then exit 0; fi\nexit 0\n')
            (sdk / 'emulator/emulator').chmod(0o755)
            script = Path(__file__).resolve().parents[1] / 'ci_emulator.sh'
            command = '''source "$1"
# KVM boundary simulated only in this host regression.
test() { if [[ "$*" == *"/dev/kvm"* ]]; then return 0; fi; builtin test "$@"; }
avdmanager() { return 0; }
export -f avdmanager
timeout() { shift; "$@"; }
umbra_start_avd umbra-ci 5554
'''
            import os
            env = dict(os.environ, RUNNER_TEMP=str(root / 'temp'), ANDROID_HOME=str(sdk), UMBRA_DEVICE_REPORTS=str(root / 'reports'))
            result = subprocess.run(['bash', '-c', command, 'test', str(script)], env=env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('AVD creation did not produce registered configuration', result.stderr)


if __name__ == '__main__': unittest.main()
