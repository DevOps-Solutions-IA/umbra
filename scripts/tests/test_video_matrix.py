"""Runner error propagation, not media evidence."""
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import run_video_matrix as matrix

class VideoMatrixTest(unittest.TestCase):
    def test_runs_later_independent_cases_without_hiding_an_earlier_failure(self):
        with tempfile.TemporaryDirectory() as root:
            commands=[('failure',[sys.executable,'-c','raise SystemExit(7)']),('success',[sys.executable,'-c','pass'])]
            self.assertEqual(1,matrix.execute(commands,Path(root)))
            import json
            result=json.loads((Path(root)/'matrix.json').read_text())
            self.assertEqual([7,0],[row['exitCode'] for row in result])
            self.assertEqual(['FAIL','PASS'],[row['status'] for row in result])
    def test_empty_matrix_is_not_success_and_cases_are_unique(self):
        with tempfile.TemporaryDirectory() as root:self.assertEqual(1,matrix.execute([],Path(root)))
        names=[name for name,_ in matrix.cases()]
        self.assertEqual(len(names),len(set(names)))
        self.assertIn('wrong-fingerprint',names);self.assertIn('ipv6-tls',names)
