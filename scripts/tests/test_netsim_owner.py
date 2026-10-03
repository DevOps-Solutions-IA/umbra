"""Owned fake processes and shell orchestration only; not native netsim/AVD capture evidence."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import netsim_owner as owner


DAEMON = '''import os,signal,socket,time
from pathlib import Path
root=Path(os.environ['ANDROID_TMP'])
s=socket.socket();s.bind(('127.0.0.1',0));s.listen()
(root/'runtime'/'netsim.ini').write_text('pid='+str(os.getpid())+'\\ngrpc.port='+str(s.getsockname()[1])+'\\n')
signal.signal(signal.SIGTERM,lambda *_: exit(0))
while True:
 connection,_=s.accept();connection.close()
'''


class NetsimOwnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); (self.root / 'runtime').mkdir(mode=0o700)
        self.state = self.root / 'state.json'; self.receipt = self.root / 'receipt.json'
        self.log = self.root / 'daemon.log'; self.log.write_text('netsim I startup\n')

    def daemon(self):
        stream = self.log.open('a'); self.addCleanup(stream.close)
        process = subprocess.Popen([sys.executable, '-c', DAEMON], env=dict(os.environ, ANDROID_TMP=str(self.root)),
                                   stdout=stream, stderr=subprocess.STDOUT)
        def cleanup():
            if process.poll() is None:
                process.kill(); process.wait(timeout=5)
        self.addCleanup(cleanup)
        return process

    def prepare(self, process):
        return owner.ready(process.pid, os.getpid(), Path(sys.executable), self.root, self.state, timeout=3)

    def test_readiness_exact_parent_and_executable_then_term_wait_and_receipt(self):
        process = self.daemon(); state = self.prepare(process)
        self.assertEqual(process.pid, state['pid']); self.assertEqual(os.getpid(), state['parentPid'])
        with self.assertRaises(RuntimeError): owner.closed(self.state, self.receipt, 0, self.log)
        owner.stop(self.state); owner.wait_exit(self.state, timeout=3)
        code = process.wait(timeout=3); self.assertEqual(0, code)
        receipt = owner.closed(self.state, self.receipt, code, self.log)
        self.assertEqual('CLOSED', receipt['state']); self.assertEqual(0, receipt['captureErrorCount'])
        self.assertTrue(receipt['graceful'])
        self.assertLessEqual(receipt['stopRequestedMonotonicNs'], receipt['closedMonotonicNs'])
        self.assertEqual(str(self.root), receipt['captureRoot'])
        self.assertNotIn('grpc.port', self.receipt.read_text())

    def test_wrong_parent_and_stale_identity_cannot_signal_process(self):
        process = self.daemon()
        with self.assertRaises(RuntimeError):
            owner.ready(process.pid, os.getpid() + 1, Path(sys.executable), self.root, self.state, timeout=1)
        state = self.prepare(process); state['startTicks'] += 1; self.state.write_text(json.dumps(state))
        with patch.object(owner.os, 'kill') as signal_process:
            with self.assertRaises(RuntimeError): owner.stop(self.state)
            signal_process.assert_not_called()
        self.assertIsNone(process.poll())

    def test_changed_executable_cannot_signal_process(self):
        process = self.daemon(); state = self.prepare(process); state['executable'] = '/not-owned'
        self.state.write_text(json.dumps(state))
        with patch.object(owner.os, 'kill') as signal_process:
            with self.assertRaises(RuntimeError): owner.stop(self.state)
            signal_process.assert_not_called()

    def test_discovery_for_another_pid_fails_closed(self):
        process = self.daemon(); self.prepare(process)
        (self.root / 'runtime' / 'netsim.ini').write_text('pid=2\ngrpc.port=12345\n')
        with self.assertRaises(RuntimeError): self.prepare(process)

    def test_capture_error_is_sanitized_and_prevents_closed_even_with_exit_zero(self):
        process = self.daemon(); self.prepare(process); owner.stop(self.state)
        owner.wait_exit(self.state, timeout=3); self.assertEqual(0, process.wait(timeout=3))
        self.log.write_text('netsim E 10-03 writer.rs:55 Failed to flush PCAP synthetic-sensitive-marker\n')
        with self.assertRaises(RuntimeError): owner.closed(self.state, self.receipt, 0, self.log)
        receipt = json.loads(self.receipt.read_text())
        self.assertEqual('FAILED', receipt['state']); self.assertEqual(1, receipt['captureErrorCount'])
        self.assertNotIn('synthetic-sensitive-marker', self.receipt.read_text())

    def test_missing_log_and_nonzero_avd_or_daemon_exit_never_close(self):
        process = self.daemon(); self.prepare(process); owner.stop(self.state)
        owner.wait_exit(self.state, timeout=3); process.wait(timeout=3)
        with self.assertRaises(RuntimeError): owner.closed(self.state, self.receipt, 0, self.log, avds_clean=False)
        self.assertEqual('FAILED', json.loads(self.receipt.read_text())['state'])
        with self.assertRaises(RuntimeError): owner.closed(self.state, self.receipt, 143, self.log)
        self.log.unlink()
        with self.assertRaises(RuntimeError): owner.closed(self.state, self.receipt, 0, self.log)

    def test_unrequested_death_cannot_be_presented_as_graceful(self):
        process = self.daemon(); self.prepare(process); process.terminate(); process.wait(timeout=3)
        with self.assertRaises(RuntimeError): owner.closed(self.state, self.receipt, 0, self.log)
        self.assertEqual('FAILED', json.loads(self.receipt.read_text())['state'])

    def test_shell_failed_readiness_cleans_owned_child_without_state(self):
        sdk = self.root / 'sdk'; (sdk / 'emulator').mkdir(parents=True)
        fake = sdk / 'emulator' / 'netsimd'
        fake.write_text('#!/bin/bash\nexec sleep 100\n'); fake.chmod(0o755)
        script = Path(__file__).resolve().parents[1] / 'ci_emulator.sh'
        command = 'source "$1"\npython() { return 1; }\numbra_start_capture_owner\n'
        environment = dict(os.environ, RUNNER_TEMP=str(self.root / 'temp'), ANDROID_HOME=str(sdk),
                           UMBRA_DEVICE_REPORTS=str(self.root / 'reports'), UMBRA_FINALIZED_CAPTURE='1')
        result = subprocess.run(['bash', '-c', command, 'fixture', str(script)], env=environment,
                                capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'temp' / 'umbra-netsim-private').exists())
        self.assertFalse((self.root / 'reports' / 'capture-owner.json').exists())

    def test_shell_opt_in_and_explicit_finalize_leave_raw_capture_until_exit_trap(self):
        sdk = self.root / 'sdk'; (sdk / 'emulator').mkdir(parents=True)
        fake = sdk / 'emulator' / 'netsimd'
        fake.write_text('#!/bin/bash\ntrap "exit 0" TERM\ntouch "$ANDROID_TMP/fake-ready"\nwhile :; do sleep 0.05; done\n')
        fake.chmod(0o755)
        script = Path(__file__).resolve().parents[1] / 'ci_emulator.sh'
        # Python boundary is mocked here; actual owner identity/receipt routines are
        # exercised with real owned fake processes in the tests above.
        command = r'''source "$1"
python() {
 case "$2" in
 ready) while [[ ! -f "$ANDROID_TMP/fake-ready" ]]; do sleep 0.01; done; echo ready;;
 check) builtin kill -0 "$UMBRA_CAPTURE_OWNER_PID";;
 stop) echo term; builtin kill -TERM "$UMBRA_CAPTURE_OWNER_PID";;
 wait-exit) while builtin kill -0 "$UMBRA_CAPTURE_OWNER_PID" 2>/dev/null; do sleep 0.01; done;;
 closed) echo closed;;
 *) return 1;;
 esac
}
umbra_start_capture_owner
[[ -z "$UMBRA_CAPTURE_OWNER_PID" ]]
export UMBRA_FINALIZED_CAPTURE=1
umbra_start_capture_owner
first="$UMBRA_CAPTURE_OWNER_PID"
umbra_start_capture_owner
[[ "$first" == "$UMBRA_CAPTURE_OWNER_PID" ]]
touch "$ANDROID_TMP/synthetic-capture.pcap"
umbra_finalize_capture
[[ -f "$ANDROID_TMP/synthetic-capture.pcap" ]]
[[ ${#UMBRA_EMULATOR_PIDS[@]} == 0 ]]
[[ ${#UMBRA_EMULATOR_SERIALS[@]} == 0 ]]
echo snapshot-before-next-batch
rm "$ANDROID_TMP/fake-ready"
umbra_start_capture_owner
[[ "$first" != "$UMBRA_CAPTURE_OWNER_PID" ]]
umbra_finalize_capture
echo done
'''
        environment = dict(os.environ, RUNNER_TEMP=str(self.root / 'temp'), ANDROID_HOME=str(sdk),
                           UMBRA_DEVICE_REPORTS=str(self.root / 'reports'), UMBRA_FINALIZED_CAPTURE='0')
        result = subprocess.run(['bash', '-c', command, 'fixture', str(script)], env=environment,
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['ready', 'term', 'closed', 'snapshot-before-next-batch', 'ready', 'term', 'closed', 'done'], result.stdout.splitlines())
        self.assertFalse((self.root / 'temp' / 'umbra-netsim-private').exists())


if __name__ == '__main__':
    unittest.main()
