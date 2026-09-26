"""Probe result validation; these doubles are not a network acceptance test."""
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import Mock,patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from voice_direct_route import probe_udp

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
