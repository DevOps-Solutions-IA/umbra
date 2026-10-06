"""Permission evidence must be explicit and unambiguous, never inferred from exit."""
import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import run_voice_integration as media

class PermissionEvidenceTest(unittest.TestCase):
    def test_requires_exact_runtime_permission_result(self):
        name='android.permission.CAMERA'
        self.assertTrue(media.permission_granted('  '+name+': granted=true, flags=[ USER_SET ]',name))
        self.assertFalse(media.permission_granted('  '+name+': granted=false, flags=[]',name))
        for text in ('', name, name+': granted=yes', name+': granted=true\n'+name+': granted=false'):
            with self.assertRaises(ValueError):media.permission_granted(text,name)
