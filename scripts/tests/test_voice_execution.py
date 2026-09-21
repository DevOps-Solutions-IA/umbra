"""An underlying JUnit suite must not turn a missing/failed native probe green."""
import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_voice_probe import verified_report


class VoiceExecutionTests(unittest.TestCase):
    def test_requires_probe_and_nonempty_suite(self):
        good = "INSTRUMENTATION_STATUS: nativeVoice=PASS synthetic probe\nOK (3 tests)\nINSTRUMENTATION_CODE: -1\n"
        self.assertTrue(verified_report(good, 0))
        for bad in ("", good.replace("nativeVoice=PASS", "nativeVoice=PENDING"),
                    good.replace("3 tests", "0 tests"), good + "Process crashed\n",
                    good + "INSTRUMENTATION_STATUS_CODE: -2\n", good + "FAILURES!!!\n"):
            self.assertFalse(verified_report(bad, 0))
        self.assertFalse(verified_report(good, 1))
