import sys
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from run_private_startup import restore_startup_wifi, wifi_control_summary, observe_startup_wifi, confirm_startup_recovery


class StartupRestoreTest(unittest.TestCase):
    def test_restores_only_original_wifi_without_early_readiness_deadline(self):
        with tempfile.TemporaryDirectory() as d,patch('run_private_startup.observe_owned_network') as observe,\
                patch('run_private_startup.observe_startup_wifi'),\
                patch('run_private_startup.subprocess.run',return_value=subprocess.CompletedProcess([],0)) as run,\
                patch('run_private_startup.time.sleep') as sleep,\
                patch('run_private_startup.wait_wifi_ipv4') as ready:
            root=Path(d)
            self.assertIsNone(restore_startup_wifi('adb','emulator-5554',root))
            run.assert_called_once_with(['adb','-s','emulator-5554','shell','svc','wifi','enable'],
                                        check=True,capture_output=True,timeout=3)
            sleep.assert_called_once_with(5)
            ready.assert_not_called()
            self.assertEqual(2,observe.call_count)

    def test_missing_native_readiness_proof_cannot_pass_route_or_release_barrier(self):
        from unittest.mock import Mock
        read=Mock(side_effect=RuntimeError('native fixture exited before locked'))
        with tempfile.TemporaryDirectory() as d,patch('run_private_startup.wait_wifi_ipv4') as ready:
            with self.assertRaisesRegex(RuntimeError,'native fixture exited'):
                confirm_startup_recovery('adb','emulator-5554',Path(d),read)
            ready.assert_not_called()
            read.assert_called_once_with('synthetic-startup-locked.json')

    def test_route_still_required_after_native_readiness_proof_without_new_wait(self):
        from unittest.mock import Mock
        events=[];read=Mock(side_effect=lambda name:events.append('native-proof'))
        def unavailable(*args,**kwargs):
            events.append('route-check');raise RuntimeError('synthetic missing route')
        with tempfile.TemporaryDirectory() as d,patch('run_private_startup.wait_wifi_ipv4',side_effect=unavailable) as ready:
            root=Path(d)
            with self.assertRaisesRegex(RuntimeError,'missing route'):
                confirm_startup_recovery('adb','emulator-5554',root,read)
            self.assertEqual(['native-proof','route-check'],events)
            ready.assert_called_once_with('adb','emulator-5554',root/'network-restored-route.json',timeout=0)

    def test_physical_target_rejected_before_radio_mutation(self):
        with tempfile.TemporaryDirectory() as d,patch('run_private_startup.subprocess.run') as run:
            with self.assertRaises(ValueError):restore_startup_wifi('adb','physical',Path(d))
            run.assert_not_called()

    def test_wifi_summary_retains_only_bounded_state_tokens(self):
        text='SSID=synthetic-secret\nPSK=not-a-real-key\ncurState=DisabledState\nmWifiState: 1\n'
        self.assertEqual([{'field':'curState','value':'DisabledState'},
                          {'field':'mWifiState','value':'1'}],wifi_control_summary(text))
        self.assertEqual(128,len(wifi_control_summary('curState=EnabledState\n'*129)))
        self.assertEqual([{'field':'NetworkSelectionStatus','value':'NETWORK_SELECTION_TEMPORARY_DISABLED'}],
                         wifi_control_summary('NetworkSelectionStatus NETWORK_SELECTION_TEMPORARY_DISABLED\nSSID=synthetic'))

    def test_wifi_diagnostic_rejects_non_emulator_before_inspection(self):
        with tempfile.TemporaryDirectory() as d,patch('run_private_startup.subprocess.run') as run:
            with self.assertRaises(ValueError):observe_startup_wifi('adb','physical',Path(d)/'state.json')
            run.assert_not_called()

    def test_wifi_diagnostic_is_read_only_and_omits_identity_fields(self):
        import json
        replies=[subprocess.CompletedProcess([],0,'1',''),
                 subprocess.CompletedProcess([],0,'Wifi is enabled\nWifi is not connected\nSSID=synthetic',''),
                 subprocess.CompletedProcess([],0,'curState=EnabledState\nPSK=synthetic-secret','')]
        with tempfile.TemporaryDirectory() as d,patch('run_private_startup.subprocess.run',side_effect=replies) as run:
            target=Path(d)/'state.json';observe_startup_wifi('adb','emulator-5554',target)
            data=json.loads(target.read_text());self.assertTrue(data['status']['enabled'])
            self.assertTrue(data['status']['disconnected']);self.assertNotIn('synthetic',target.read_text())
            self.assertEqual([['getprop','ro.kernel.qemu'],['cmd','wifi','status'],['dumpsys','wifi']],
                             [call.args[0][4:] for call in run.call_args_list])
