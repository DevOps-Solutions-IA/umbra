"""Coordination regression: a peer's success must not close the other peer's receipt pump."""
import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from run_bluetooth_emulation import both_drained


class BluetoothDrainTests(unittest.TestCase):
    def test_requires_two_live_drained_peers(self):
        done="nearbyStage=verified\nnearbyStage=drained\n"
        self.assertTrue(both_drained([done,done]))
        for reports in ([],[done],[done,"nearbyStage=verified"],[done,done+"nearbyResult=FAIL: receipt missing"],
                        ["nearbyResult=PASS: old premature success",done]):
            self.assertFalse(both_drained(reports))
