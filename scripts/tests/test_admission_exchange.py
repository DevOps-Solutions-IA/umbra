"""Synthetic harness isolation; not an Android/transport acceptance test."""
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from admission_lab import reset_exchange


class AdmissionExchangeTest(unittest.TestCase):
    def test_previous_failed_realm_exchange_removed_after_process_stop(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'files').mkdir()
            names = ['request.json', 'request.tmp', 'credential.json', 'credential.tmp']
            for name in names:
                (root/('files/synthetic-admission-'+name)).write_text('previous-realm')
            vault = root/'files/vault.db'
            vault.write_bytes(b'synthetic-vault-preserved')
            calls = []
            def run(serial, *args):
                self.assertEqual(serial, 'owned-avd')
                calls.append(args)
                if args[:3] == ('shell', 'am', 'force-stop'):
                    self.assertTrue(all((root/('files/synthetic-admission-'+n)).exists() for n in names))
                else:
                    self.assertEqual(calls[0], ('shell','am','force-stop','synthetic.package'))
                    self.assertEqual(args[:6], ('shell','run-as','synthetic.package','rm','-f', 'files/synthetic-admission-request.json'))
                    for name in args[5:]:
                        (root/name).unlink(missing_ok=True)
            reset_exchange(run, 'owned-avd', 'synthetic.package')
            self.assertEqual(len(calls), 2)
            self.assertEqual(list((root/'files').iterdir()), [vault])
            self.assertEqual(vault.read_bytes(), b'synthetic-vault-preserved')

    def test_stop_failure_prevents_cleanup_or_continuation(self):
        calls = []
        def run(*args):
            calls.append(args)
            raise RuntimeError('stop failed')
        with self.assertRaisesRegex(RuntimeError, 'stop failed'):
            reset_exchange(run, 'owned-avd', 'synthetic.package')
        self.assertEqual(len(calls), 1)
