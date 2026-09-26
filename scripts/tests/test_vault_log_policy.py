"""Static sink guard regressions, not runtime secrecy or cryptographic tests."""
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_source_policy import SECRET_PATHS, secret_logging_findings

class VaultLogPolicyTest(unittest.TestCase):
    def test_missing_or_logging_sensitive_source_is_rejected_without_echoing_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            self.assertEqual(list(SECRET_PATHS), secret_logging_findings(root))
            for name in SECRET_PATHS:
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
                path.write_text('class Synthetic {}')
            self.assertEqual([],secret_logging_findings(root))
            for sink in ('Log.d("vault", password);', 'System.err.println(derived);',
                         'failure.printStackTrace();', 'Logger.info(key);',
                         'android.util.Log.i("vault", plaintext);'):
                (root/SECRET_PATHS[0]).write_text(sink)
                self.assertEqual([SECRET_PATHS[0]],secret_logging_findings(root))
