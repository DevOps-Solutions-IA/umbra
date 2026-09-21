"""Report gates only; not multimedia acceptance evidence."""
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from run_voice_integration import valid_audio, valid_report
import voice_network_evidence as network

class VoiceEvidenceTest(unittest.TestCase):
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
