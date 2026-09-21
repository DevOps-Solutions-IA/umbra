"""Report gates only; not multimedia acceptance evidence."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from run_voice_integration import valid_audio, valid_report, valid_stop
import voice_network_evidence as network

class VoiceEvidenceTest(unittest.TestCase):
    def test_ipv6_link_observation_also_rejects_other_ipv4_paths(self):
        sources=["fec0::10","fec0::20"]
        with patch.object(network,"count",side_effect=[100,0]) as counter:
            value=network.summarize_ipv6_turn(Path("synthetic.pcap"),"fd42::2","10.0.2.16",sources,43210,0,10,tls=False)
            self.assertEqual(100,value["turnIpv6Packets"])
            self.assertIn("IPv4 relay allocation",value["scope"])
            self.assertIn("src host 10.0.2.16",counter.call_args_list[1].args[1])
            for source in sources:self.assertIn("src host "+source,counter.call_args_list[1].args[1])
        for counts in ([0,0],[100,1]):
            with patch.object(network,"count",side_effect=counts):
                with self.assertRaises(RuntimeError):network.summarize_ipv6_turn(Path("synthetic.pcap"),"fd42::2","10.0.2.16",sources,43210,0,10,tls=True)

    def test_tls_capture_selects_device_not_entire_emulator_subnet(self):
        with patch.object(network,"count",side_effect=[100,0,0,0]) as counter:
            report=network.summarize_tls(Path("synthetic.pcap"),"172.20.0.2",43210,0,10,source_address="10.0.2.16")
            self.assertEqual(100,report["turnTlsPackets"])
            for call in counter.call_args_list:
                self.assertIn("src host 10.0.2.16",call.args[1])
                self.assertNotIn("src net",call.args[1])
        for values in ([0,0,0,0],[100,1,0,0],[100,0,1,0],[100,0,0,1]):
            with patch.object(network,"count",side_effect=values):
                with self.assertRaises(RuntimeError):
                    network.summarize_tls(Path("synthetic.pcap"),"172.20.0.2",43210,0,10,source_address="10.0.2.16")
        with self.assertRaises(ValueError):
            network.summarize_tls(Path("synthetic.pcap"),"172.20.0.2",43210,0,10,source_address="10.0.2.2")

    def test_ice_and_packet_counts_are_not_decoded_audio(self):
        good=dict(decodedBuffers=100,capturedBuffers=100,verifiedNativeTransport=True,receivedAudioPackets=50,codec="audio/opus",sdpAddressAudit=True)
        self.assertTrue(valid_audio(good))
        for field in good:
            bad=good.copy();del bad[field];self.assertFalse(valid_audio(bad))
        for field in ("decodedBuffers","capturedBuffers","receivedAudioPackets"):
            bad=good.copy();bad[field]=0;self.assertFalse(valid_audio(bad))

    def test_empty_crashed_or_failed_instrumentation_never_passes(self):
        good="engineVoice=PASS\nOK (3 tests)\nINSTRUMENTATION_CODE: -1\n"
        self.assertTrue(valid_report(good))
        for text in ("",good.replace("3 tests","0 tests"),good.replace("engineVoice=PASS",""),good+"INSTRUMENTATION_STATUS_CODE: -2",good+"Process crashed"):
            self.assertFalse(valid_report(text))

    def test_capture_requires_actual_turn_and_rejects_direct_attempts(self):
        with patch.object(network,"count",side_effect=[100,8,0,0]):
            self.assertEqual(0,network.summarize(Path("synthetic.pcap"),"172.20.0.2")["nonTurnStunPackets"])
        for counts in ([0,0,0,0],[100,0,0,0],[100,8,1,1],[100,8,0,1]):
            with patch.object(network,"count",side_effect=counts):
                with self.assertRaises(RuntimeError):network.summarize(Path("synthetic.pcap"),"172.20.0.2")

    def test_callee_without_remote_description_is_not_claimed_as_transport(self):
        with patch.object(network,"count",side_effect=[0,0,0,0]):
            report=network.summarize(Path("synthetic.pcap"),"172.20.0.2",require_turn=False)
            self.assertTrue(report["mediaAttempt"].startswith("NOT_EXECUTED"))
        with patch.object(network,"count",side_effect=[0,0,1,1]):
            with self.assertRaises(RuntimeError): network.summarize(Path("synthetic.pcap"),"172.20.0.2",require_turn=False)

    def test_state_flag_does_not_prove_native_capture_stopped(self):
        good={"failedClosed":True,"nativeCaptureQuietAfterMillis":1000,
              "nativeCaptureObservedMillis":500,"lateCaptureCallbacks":0}
        self.assertTrue(valid_stop(good))
        self.assertFalse(valid_stop({"failedClosed":True}))
        for field,value in (("lateCaptureCallbacks",1),("nativeCaptureObservedMillis",0),
                            ("nativeCaptureQuietAfterMillis",30000),("failedClosed",False)):
            bad={**good,field:value}
            self.assertFalse(valid_stop(bad))
