import sys
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from run_private_startup import restore_startup_wifi


class StartupRestoreTest(unittest.TestCase):
    def test_restores_only_original_wifi_and_requires_route_without_extending_delay(self):
        with tempfile.TemporaryDirectory() as d,patch('run_private_startup.observe_owned_network') as observe,\
                patch('run_private_startup.subprocess.run',return_value=subprocess.CompletedProcess([],0)) as run,\
                patch('run_private_startup.wait_wifi_ipv4',return_value='10.0.2.16') as ready:
            root=Path(d)
            self.assertEqual('10.0.2.16',restore_startup_wifi('adb','emulator-5554',root))
            run.assert_called_once_with(['adb','-s','emulator-5554','shell','svc','wifi','enable'],
                                        check=True,capture_output=True,timeout=3)
            ready.assert_called_once_with('adb','emulator-5554',root/'network-restored-route.json',timeout=5)
            self.assertEqual(2,observe.call_count)

    def test_missing_route_preserves_failure_and_after_snapshot(self):
        with tempfile.TemporaryDirectory() as d,patch('run_private_startup.observe_owned_network') as observe,\
                patch('run_private_startup.subprocess.run'),\
                patch('run_private_startup.wait_wifi_ipv4',side_effect=RuntimeError('synthetic missing route')):
            with self.assertRaisesRegex(RuntimeError,'missing route'):
                restore_startup_wifi('adb','emulator-5554',Path(d))
            self.assertEqual(2,observe.call_count)

    def test_physical_target_rejected_before_radio_mutation(self):
        with tempfile.TemporaryDirectory() as d,patch('run_private_startup.subprocess.run') as run:
            with self.assertRaises(ValueError):restore_startup_wifi('adb','physical',Path(d))
            run.assert_not_called()
