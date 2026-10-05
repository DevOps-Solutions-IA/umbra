"""The release smoke gate must check the visible lock contract, not a historical copy (no device needed).

smoke_release_launch.py runs on import (argparse, adb), so it is read with ast instead of imported.
"""
from pathlib import Path
import ast
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SMOKE = ROOT / 'scripts/smoke_release_launch.py'
JAVA = ROOT / 'android/app/src'
ENTRY = JAVA / 'main/java/app/umbra/ui/screens/EntryScreens.java'
ACCESS = JAVA / 'main/java/app/umbra/ui/model/AccessPresentation.java'
LOCKED_TEST = JAVA / 'androidTest/java/app/umbra/LockedActivityTest.java'
HISTORICAL = 'Bóveda bloqueada'


def smoke_contract():
    """LOCKED_STATUS and locked_ui_visible exactly as defined in the smoke script, nothing else executed."""
    tree = ast.parse(SMOKE.read_text(encoding='utf-8'))
    keep = [n for n in tree.body
            if (isinstance(n, ast.Assign) and any(getattr(t, 'id', None) == 'LOCKED_STATUS' for t in n.targets))
            or (isinstance(n, ast.FunctionDef) and n.name == 'locked_ui_visible')]
    namespace = {}
    exec(compile(ast.Module(body=keep, type_ignores=[]), str(SMOKE), 'exec'), namespace)
    return namespace['LOCKED_STATUS'], namespace['locked_ui_visible']


def dump(*texts):
    nodes = ''.join(f'<node text="{t}" />' for t in texts)
    return ET.fromstring(f'<hierarchy><node text="">{nodes}</node></hierarchy>')


class ReleaseSmokeLockContractTests(unittest.TestCase):
    def test_smoke_status_matches_cold_start_lock_screen(self):
        status, _ = smoke_contract()
        entry = ENTRY.read_text(encoding='utf-8')
        default = re.search(r's\.reason\(\) != null \? s\.reason\(\) : "([^"]+)"', entry)
        self.assertIsNotNone(default, 'EntryScreens.lock status line changed; update this contract test')
        self.assertEqual(default.group(1), status)

    def test_smoke_status_matches_locked_presentation_and_instrumentation(self):
        status, _ = smoke_contract()
        self.assertIn(f'case LOCKED, ANDROID_AUTH_REQUIRED -> new Copy("{status}"', ACCESS.read_text(encoding='utf-8'))
        self.assertIn(f'containsText(activity.getWindow().getDecorView(), "{status}")',
                      LOCKED_TEST.read_text(encoding='utf-8'))

    def test_gate_uses_contract_and_not_historical_copy(self):
        source = SMOKE.read_text(encoding='utf-8')
        self.assertNotIn(HISTORICAL, source)
        self.assertIn('assert locked_ui_visible(tree)', source)

    def test_locked_ui_visible_requires_exact_current_text(self):
        status, visible = smoke_contract()
        self.assertTrue(visible(dump('UMBRA', status, 'Desbloquear')))
        self.assertFalse(visible(dump('UMBRA', HISTORICAL, 'Desbloquear')))
        self.assertFalse(visible(dump('UMBRA', status + '.', status.upper())))
        self.assertFalse(visible(dump()))


if __name__ == '__main__':
    unittest.main()
