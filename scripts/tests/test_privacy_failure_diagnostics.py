"""Synthetic diagnostic parsers/process helpers, not Android crash reproduction."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_privacy_tests as runner

PACKAGE = 'app.umbra.privatechat.dev'


class PrivacyFailureDiagnosticsTest(unittest.TestCase):
    def test_exit_records_are_correlated_and_drop_messages_paths_and_identifiers(self):
        text = '''ApplicationExitInfo #0:
 timestamp=2026-10-03 05:20:53.123 pid=888 realUid=1001 packageUid=1001
 process=app.umbra.privatechat.dev reason=3 (LOW MEMORY) subreason=0 (UNKNOWN) status=9
 importance=100 pss=12M rss=24M description=private-secret state=24 bytes trace=/private/path
ApplicationExitInfo #1:
 timestamp=2026-10-03 05:21:53.123 pid=889
 process=other.package reason=4 (APP CRASH(EXCEPTION)) subreason=0 (UNKNOWN) status=0
 importance=100 pss=1M rss=2M description=other-private
'''
        records = runner.summarize_exit_info(text, PACKAGE)
        self.assertEqual([{'reason': 'LOW_MEMORY', 'status': 9, 'timestamp': '2026-10-03 05:20:53.123',
                           'importance': 100, 'pss': '12M', 'rss': '24M'}], records)
        result = json.dumps(records)
        for secret in ('private', '888', '1001', 'other.package'):
            self.assertNotIn(secret, result)
        with self.assertRaises(ValueError): runner.summarize_exit_info(text, 'other.package')

    def test_proc_status_keeps_only_fixed_numeric_fields(self):
        self.assertEqual({'Threads': 21, 'VmRSS': 1234, 'state': 'S'}, runner.summarize_proc_status(
            'Name:\tprivate-payload\nState:\tS (sleeping)\nThreads:\t21\nVmRSS:\t1234 kB\nUid:\t1001\n'))

    def test_reader_limits_output_time_and_nonzero_without_persisting_raw(self):
        text, status = runner.bounded_diagnostic([sys.executable, '-c', 'print("safe")'])
        self.assertEqual('safe\n', text); self.assertEqual('COMPLETE', status['state'])
        for code, limit, timeout, expected in (
            ('print("x" * 10000)', 64, 2, 'OUTPUT_LIMIT'),
            ('import time; time.sleep(5)', 64, .05, 'TIMEOUT'),
            ('import sys; print("private"); sys.exit(3)', 64, 2, 'NONZERO_EXIT')):
            text, status = runner.bounded_diagnostic([sys.executable, '-c', code], limit=limit, timeout=timeout)
            self.assertEqual('', text); self.assertEqual(expected, status['state'])

    def test_deadline_snapshot_precedes_host_cleanup_and_original_failure_remains(self):
        with tempfile.TemporaryDirectory() as folder:
            events = []; process = MagicMock(); process.poll.return_value = None
            process.wait.return_value = 0
            def diagnostic(*args, **kwargs): events.append(('diagnostic', args[4], kwargs['process_alive']))
            def run(*args, **kwargs): events.append(('cleanup',)); return subprocess.CompletedProcess([], 0)
            with patch.object(runner.subprocess, 'Popen', return_value=process), \
                 patch.object(runner.subprocess, 'run', side_effect=run), \
                 patch.object(runner.time, 'monotonic', side_effect=[0, 46]), \
                 patch.object(runner, 'collect_failure_diagnostics', side_effect=diagnostic):
                with self.assertRaisesRegex(RuntimeError, 'positive render/consume not reached'):
                    runner.consumption_restart(['adb'], PACKAGE, Path(folder))
            self.assertEqual([('diagnostic', 'DEADLINE', True), ('cleanup',)], events)
            self.assertFalse((Path(folder) / 'restricted-restart.json').exists())

    def test_exited_process_snapshot_is_distinct_from_host_deadline(self):
        with tempfile.TemporaryDirectory() as folder:
            process = MagicMock(); process.poll.return_value = 0
            with patch.object(runner.subprocess, 'Popen', return_value=process), \
                 patch.object(runner.time, 'monotonic', return_value=0), \
                 patch.object(runner, 'collect_failure_diagnostics') as collect, \
                 patch.object(runner.subprocess, 'run') as cleanup:
                with self.assertRaises(RuntimeError): runner.consumption_restart(['adb'], PACKAGE, Path(folder))
                self.assertEqual('PROCESS_EXIT', collect.call_args.args[4])
                self.assertFalse(collect.call_args.kwargs['process_alive'])
                cleanup.assert_not_called()

    def test_live_backtrace_exports_only_correlated_fixed_sites(self):
        def read(command):
            if 'pidof' in command:
                text = '123\n'
            elif 'debuggerd' in command:
                text = ('Cmd line: ' + PACKAGE + '\n/private/path libsecret.so private-token\n'
                        '#00 MediaCodec::stop private-argument\n#01 pthread_cond_wait\n')
            else:
                text = ''
            return text, {'state': 'COMPLETE', 'exitCode': 0}
        with tempfile.TemporaryDirectory() as folder, patch.object(runner, 'bounded_diagnostic', side_effect=read):
            runner.collect_failure_diagnostics(['adb'], PACKAGE, Path(folder), 'PREPARE', 'DEADLINE')
            text = (Path(folder) / 'privacy-failure-prepare.json').read_text()
            stack = json.loads(text)['liveNativeStack']
            self.assertEqual(['MediaCodec::stop', 'pthread_cond_wait'], stack['sites'])
            self.assertTrue(stack['ownedPackageCorrelated'])
            for secret in ('/private/path', 'private-token', 'private-argument', 'libsecret.so'):
                self.assertNotIn(secret, text)

    def test_failed_collection_still_writes_sanitized_failure_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(runner, 'bounded_diagnostic', side_effect=OSError('private-device-details')):
                runner.collect_failure_diagnostics(['adb'], PACKAGE, Path(folder), 'PREPARE', 'DEADLINE', process_alive=True)
            text = (Path(folder) / 'privacy-failure-prepare.json').read_text()
            result = json.loads(text)
            self.assertEqual('DEADLINE', result['failureReason'])
            self.assertTrue(result['beforeHostCleanup'])
            self.assertNotIn('private-device-details', text)
            self.assertEqual([], result['ownedExitRecords'])
            self.assertFalse(result['targetPidPresent'])


if __name__ == '__main__': unittest.main()
