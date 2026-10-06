"""Matrix construction only, not evidence of Bluetooth transport."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_nearby_regressions as nearby


class AdmissionNearbyMatrixTest(unittest.TestCase):
    def test_preserves_six_positive_runs_and_requires_two_negative_runs(self):
        with tempfile.TemporaryDirectory() as folder:
            pairing=Path(folder)/'pairing.json'
            pairing.write_text(json.dumps({'serial_a':'emulator-a','serial_b':'emulator-b',
                'address_a':'BB:BB:BB:00:00:01','address_b':'BB:BB:BB:00:00:02'}))
            with patch.object(sys,'argv',['runner','--pairing',str(pairing),'--reports',folder]), patch.object(nearby,'execute',return_value=0) as execute:
                self.assertEqual(nearby.main(),0)
            commands=execute.call_args.args[0]
            self.assertEqual(len(commands),8)
            self.assertEqual(len({name for name,_ in commands}),8)
            self.assertEqual(sum('--unadmitted-dialer' in command for _,command in commands),2)
            for flavor in ('connected','offline'):
                self.assertEqual({name for name,_ in commands if name.startswith('nearby-'+flavor+'-')},
                    {f'nearby-{flavor}-{n}' for n in (1,2,3)} | {f'nearby-{flavor}-unadmitted'})
