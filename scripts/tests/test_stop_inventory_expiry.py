"""Existing Engine must filter expired controls; never extend their TTL for a fixture."""
from pathlib import Path
import unittest

class StopInventoryExpiryTest(unittest.TestCase):
    def test_observer_reads_raw_records_not_mutating_outbox(self):
        text=(Path(__file__).resolve().parents[2]/'android/app/src/androidTestConnected/java/app/umbra/media/VoiceEngineFixtureListener.java').read_text()
        observer=text.split('private void stopInventoryDiagnostic(',1)[1].split('private void videoStopIssued(',1)[0]
        self.assertNotIn('engine.outbox()',observer)
        self.assertNotIn('db.put(',observer)
        self.assertNotIn('db.remove(',observer)
        self.assertIn('db.transaction(',observer)
        self.assertIn('engine.get("outbox",key)',observer)
        self.assertIn('getLong("expires")<=now',observer)

    def test_audio_diagnostic_is_emitted_before_existing_failure(self):
        text=(Path(__file__).resolve().parents[2]/'android/app/src/androidTestConnected/java/app/umbra/media/VoiceEngineFixtureListener.java').read_text()
        diagnostic=text.split('var audioFailure=new AssertionError("Missing native audio or mute/unmute evidence");',1)[1].split('throw audioFailure;',1)[0]
        for required in ('audioMuteApiReturned','audioResumeApiReturned','audioResumeBaseline',
                         'audioDecodedAfterResume','audioTransmitAllowed','audioDeadlineReached'):
            self.assertIn(required,diagnostic)
        self.assertIn('sendStatus(0,diagnostic)',diagnostic)
        for forbidden in ('password','fingerprint','callId','peer','credential','sdp','voice.mute(', 'deadline='):
            self.assertNotIn(forbidden,diagnostic)
