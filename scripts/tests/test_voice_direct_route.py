"""Probe result validation; these doubles are not a network acceptance test."""
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import Mock,patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from voice_direct_route import probe_udp, wifi_ipv4_route, wait_wifi_ipv4

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
        with tempfile.TemporaryDirectory() as d,patch('voice_direct_route.subprocess.run',side_effect=[addr,absent,*diagnostics]):
            report=Path(d)/'readiness.json'
            with self.assertRaises(RuntimeError):wait_wifi_ipv4('adb','emulator-5554',report,timeout=0)
            receipt=json.loads(report.read_text())
            self.assertEqual(2,receipt['attempts'][0]['routeExit'])
            self.assertEqual({'addresses','rules','routes','connectivity','network_stack'},set(receipt['failureState']))
