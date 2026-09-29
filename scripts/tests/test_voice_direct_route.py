"""Probe result validation; these doubles are not a network acceptance test."""
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import Mock,patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from voice_direct_route import probe_udp, wifi_ipv4_route, wait_wifi_ipv4, observe_owned_network, initialize_owned_wifi

class DirectRouteProbeTest(unittest.TestCase):
    def test_only_exact_challenge_from_bound_receiver_proves_delivery(self):
        expected=('umbra-owned-route-'+'a'*32).encode()
        for received,proved in ((expected,True),(b'unrelated synthetic datagram',False),(b'',False)):
            listener=Mock(returncode=0)
            listener.poll.side_effect=[None,0]
            listener.communicate.return_value=(received,b'')
            replies=[subprocess.CompletedProcess([],0,'sl local_address\n0: 00000000:9C40\n'),subprocess.CompletedProcess([],0,b'')]
            with patch('voice_direct_route.secrets.randbelow',return_value=0),patch('voice_direct_route.secrets.token_hex',return_value='a'*32),patch('voice_direct_route.subprocess.Popen',return_value=listener),patch('voice_direct_route.subprocess.run',side_effect=replies):
                self.assertEqual(proved,probe_udp('adb','emulator-5554','emulator-5556','10.0.2.17'))
    def test_failed_listener_is_an_error_not_proof_that_firewall_blocks(self):
        listener=Mock(returncode=1);listener.poll.return_value=1
        with patch('voice_direct_route.subprocess.Popen',return_value=listener):
            with self.assertRaises(RuntimeError): probe_udp('adb','emulator-5554','emulator-5556','10.0.2.17')
        with self.assertRaises(ValueError): probe_udp('adb','physical','emulator-5556','10.0.2.17')

    def test_receiver_tool_failure_preserves_bounded_escaped_diagnostic(self):
        listener=Mock(returncode=1);listener.poll.side_effect=[None,1]
        listener.communicate.return_value=(b'',b'nc: synthetic failure\n'+b'x'*300)
        replies=[subprocess.CompletedProcess([],0,'sl local_address\n0: 00000000:9C40\n'),subprocess.CompletedProcess([],0,b'')]
        with patch('voice_direct_route.secrets.randbelow',return_value=0),patch('voice_direct_route.subprocess.Popen',return_value=listener),patch('voice_direct_route.subprocess.run',side_effect=replies):
            with self.assertRaises(RuntimeError) as failure: probe_udp('adb','emulator-5554','emulator-5556','10.0.2.17')
        diagnostic=str(failure.exception)
        self.assertIn('nc: synthetic failure',diagnostic)
        self.assertNotIn('\n',diagnostic)
        self.assertLess(len(diagnostic),450)

    def test_address_without_a_matching_ipv4_wifi_route_is_not_ready(self):
        self.assertTrue(wifi_ipv4_route('10.0.2.2 dev wlan0 src 10.0.2.16 uid 2000','10.0.2.16'))
        for route in ('RTNETLINK answers: Network is unreachable',
                      '10.0.2.2 dev eth0 src 10.0.2.16',
                      '10.0.2.2 dev wlan0 src 10.0.2.17',
                      'default dev wlan0','blackhole dev wlan0 src 10.0.2.16'):
            self.assertFalse(wifi_ipv4_route(route,'10.0.2.16'))

    def test_waits_for_route_not_just_dhcp_address_and_records_attempts(self):
        import tempfile,json
        addr=subprocess.CompletedProcess([],0,'inet 10.0.2.16/24','')
        absent=subprocess.CompletedProcess([],2,'','Network is unreachable')
        present=subprocess.CompletedProcess([],0,'10.0.2.2 dev wlan0 src 10.0.2.16','')
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',side_effect=[addr,absent,addr,present]),patch('voice_direct_route.time.sleep'):
            report=Path(d)/'readiness.json'
            self.assertEqual('10.0.2.16',wait_wifi_ipv4('adb','emulator-5554',report))
            self.assertEqual(2,len(json.loads(report.read_text())['attempts']))

    def test_missing_route_fails_and_preserves_diagnostics(self):
        import tempfile,json
        addr=subprocess.CompletedProcess([],0,'inet 10.0.2.16/24','')
        absent=subprocess.CompletedProcess([],2,'','Network is unreachable')
        diagnostics=[subprocess.CompletedProcess([],0,'synthetic control-plane state','') for _ in range(5)]
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',side_effect=[addr,absent,*diagnostics,subprocess.CompletedProcess([],0,'1',''),*diagnostics[:2]]):
            report=Path(d)/'readiness.json'
            with self.assertRaises(RuntimeError):wait_wifi_ipv4('adb','emulator-5554',report,timeout=0)
            receipt=json.loads(report.read_text())
            self.assertEqual(2,receipt['attempts'][0]['routeExit'])
            self.assertEqual({'addresses','rules','routes','connectivity','network_stack','wifi_service'},set(receipt['failureState']))

    def test_missing_wifi_diagnostic_does_not_replace_original_route_failure(self):
        import tempfile,json
        ok=subprocess.CompletedProcess([],0,'','')
        missing=subprocess.CompletedProcess([],2,'','Network is unreachable')
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',side_effect=[ok,missing,*([ok]*5)]),\
                patch('voice_direct_route.observe_owned_wifi',side_effect=ValueError('disconnected emulator')):
            report=Path(d)/'route.json'
            with self.assertRaisesRegex(RuntimeError,'policy route unavailable'):
                wait_wifi_ipv4('adb','emulator-5554',report,timeout=0)
            self.assertEqual({'error':'diagnostic_unavailable'},json.loads(report.read_text())['failureState']['wifi_service'])

    def test_setup_observation_records_missing_route_without_repair_or_acceptance(self):
        import tempfile,json
        replies=[subprocess.CompletedProcess([],0,'1\n',''),
                 subprocess.CompletedProcess([],2,'','Network unreachable'),
                 *[subprocess.CompletedProcess([],0,'synthetic control plane','') for _ in range(3)]]
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',side_effect=replies) as run:
            report=Path(d)/'new'/'before.json'
            self.assertIsNone(observe_owned_network('adb','emulator-5554',report))
            value=json.loads(report.read_text())
            self.assertEqual(2,value['commands']['route']['exit'])
            self.assertEqual(5,run.call_count)
            for call in run.call_args_list:
                self.assertEqual(3,call.kwargs['timeout'])
                self.assertNotIn('svc',call.args[0])
                self.assertNotIn('add',call.args[0])
                self.assertNotIn('flush',call.args[0])

    def test_setup_observation_rejects_physical_and_unverified_targets(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run') as run:
            with self.assertRaises(ValueError):observe_owned_network('adb','physical',Path(d)/'x')
            run.assert_not_called()
            run.return_value=subprocess.CompletedProcess([],0,'0\n','')
            with self.assertRaises(ValueError):observe_owned_network('adb','emulator-5554',Path(d)/'x')
            self.assertEqual(1,run.call_count)

    def test_initialization_waits_for_old_address_removal_before_enabling(self):
        import tempfile,json
        ok=lambda text='':subprocess.CompletedProcess([],0,text,'')
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',
                side_effect=[ok('1'),ok('inet 10.0.2.16/24'),ok(''),ok(''),ok(),ok('inet 10.0.2.16/24'),ok('wlan0 DOWN'),ok()]) as run,patch('voice_direct_route.time.sleep'),patch('voice_direct_route.select_owned_wifi') as select:
            report=Path(d)/'init.json'
            self.assertTrue(initialize_owned_wifi('adb','emulator-5554',report))
            calls=[c.args[0][4:] for c in run.call_args_list]
            self.assertEqual(['svc','wifi','disable'],calls[4])
            self.assertEqual(['svc','wifi','enable'],calls[-1])
            select.assert_not_called()
            self.assertEqual([False,True],[x['addressCleared'] for x in json.loads(report.read_text())['observations']])

    def test_initialization_timeout_never_claims_ready_or_enables_over_old_state(self):
        import tempfile
        ok=lambda text='':subprocess.CompletedProcess([],0,text,'')
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',
                side_effect=[ok('1'),ok('inet 10.0.2.16/24'),ok(''),ok(''),ok(),ok('inet 10.0.2.16/24')]) as run,patch('voice_direct_route.time.monotonic',side_effect=[0,11,11]):
            with self.assertRaisesRegex(RuntimeError,'did not disconnect'):
                initialize_owned_wifi('adb','emulator-5554',Path(d)/'init.json')
            self.assertEqual(6,run.call_count)

    def test_initialization_rejects_non_emulator_before_radio_mutation(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',
                return_value=subprocess.CompletedProcess([],0,'0','')) as run:
            with self.assertRaises(ValueError):initialize_owned_wifi('adb','physical',Path(d)/'init.json')
            run.assert_not_called()
            with self.assertRaises(ValueError):initialize_owned_wifi('adb','emulator-5554',Path(d)/'init.json')
            self.assertEqual(1,run.call_count)

    def test_initialization_preserves_an_already_routed_interface(self):
        import tempfile,json
        def execute(command,**kwargs):
            arguments=command[4:]
            if arguments==['getprop','ro.kernel.qemu']: value='1'
            elif arguments==['ip','-4','addr','show','wlan0']: value='inet 10.0.2.16/24'
            elif arguments==['ip','-4','route','get','10.0.2.2']: value='10.0.2.2 dev wlan0 src 10.0.2.16'
            elif arguments[:2]==['ip','-6']: value='inet6 fec0::16/64 scope global'
            else: raise AssertionError('Healthy association must not be cycled: '+str(arguments))
            return subprocess.CompletedProcess(command,0,value,'')
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',side_effect=execute):
            report=Path(d)/'init.json'
            initialize_owned_wifi('adb','emulator-5554',report)
            self.assertEqual('preserved-existing-route',json.loads(report.read_text())['action'])

    def test_virtual_ap_selection_requires_runtime_support_and_never_claims_readiness(self):
        import tempfile,json
        from voice_direct_route import select_owned_wifi
        ok=lambda text='':subprocess.CompletedProcess([],0,text,'')
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',
                side_effect=[ok('1'),ok('connect-network <ssid> open|owe|wpa2|wpa3'),ok('Wifi is enabled'),ok('Connection initiated')]) as run:
            report=Path(d)/'selection.json'
            select_owned_wifi('adb','emulator-5554',report)
            self.assertEqual(['su','0','cmd','wifi','connect-network','AndroidWifi','open'],run.call_args.args[0][4:])
            self.assertEqual(0,json.loads(report.read_text())['exit'])

    def test_virtual_ap_selection_rejects_physical_unsupported_and_failed_commands(self):
        import tempfile
        from voice_direct_route import select_owned_wifi
        ok=lambda text='':subprocess.CompletedProcess([],0,text,'')
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run') as run:
            report=Path(d)/'selection.json'
            with self.assertRaises(ValueError):select_owned_wifi('adb','physical',report)
            run.assert_not_called()
            run.side_effect=[ok('0')]
            with self.assertRaises(ValueError):select_owned_wifi('adb','emulator-5554',report)
            run.reset_mock();run.side_effect=[ok('1'),ok('unrelated command')]
            with self.assertRaisesRegex(RuntimeError,'does not support'):
                select_owned_wifi('adb','emulator-5554',report)
            self.assertEqual(2,run.call_count)
            run.side_effect=[ok('1'),ok('connect-network <ssid> open|owe'),ok('Wifi is enabled'),ok('Connection failed')]
            with self.assertRaisesRegex(RuntimeError,'selection failed'):
                select_owned_wifi('adb','emulator-5554',report)

    def test_selection_waits_for_enabled_radio_then_requires_route_in_same_budget(self):
        import tempfile
        ok=lambda text='':subprocess.CompletedProcess([],0,text,'')
        replies=[ok(),ok(),ok('Wifi is disabled'),ok(),ok(),ok('Wifi is enabled'),
                 ok('inet 10.0.2.16/24'),ok('10.0.2.2 dev wlan0 src 10.0.2.16')]
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',side_effect=replies),\
                patch('voice_direct_route.select_owned_wifi') as select,patch('voice_direct_route.time.sleep'):
            report=Path(d)/'ready.json'
            self.assertEqual('10.0.2.16',wait_wifi_ipv4('adb','emulator-5554',report,associate=True))
            select.assert_called_once_with('adb','emulator-5554',Path(d)/'ready-selection.json')

    def test_virtual_ap_selection_never_runs_before_radio_enabled(self):
        import tempfile
        from voice_direct_route import select_owned_wifi
        ok=lambda text='':subprocess.CompletedProcess([],0,text,'')
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',
                side_effect=[ok('1'),ok('connect-network <ssid> open|owe'),ok('Wifi is disabled')]) as run:
            with self.assertRaisesRegex(RuntimeError,'radio is not enabled'):
                select_owned_wifi('adb','emulator-5554',Path(d)/'selection.json')
            self.assertFalse(any('connect-network' in call.args[0] for call in run.call_args_list))

    def test_android_help_minus_one_exit_with_exact_supported_syntax_is_not_a_failed_mutation(self):
        import tempfile,json
        from voice_direct_route import select_owned_wifi
        ok=lambda text='':subprocess.CompletedProcess([],0,text,'')
        help_result=subprocess.CompletedProcess([],255,'Wifi commands:\n  connect-network <ssid> open|owe|wpa2|wpa3 [<passphrase>]\n','')
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',
                side_effect=[ok('1'),help_result,ok('Wifi is enabled'),ok('Connection initiated')]):
            report=Path(d)/'selection.json'
            select_owned_wifi('adb','emulator-5554',report)
            self.assertEqual(255,json.loads(report.read_text())['helpExit'])
