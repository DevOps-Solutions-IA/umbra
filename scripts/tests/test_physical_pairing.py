import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import run_physical_pairing as runner


class PhysicalPairingTests(unittest.TestCase):
    serials = ('fixtureA', 'fixtureB')
    listing = 'List of devices attached\nfixtureA device usb:1-1\nfixtureB device usb:1-2\n'
    artifact = {'package': runner.PACKAGES[1], 'sha256': 'a' * 64,
                'versionCode': '1', 'versionName': 'test'}

    def test_requires_exactly_two_distinct_usb_rows(self):
        self.assertEqual(self.serials, runner.select_pair(self.listing, self.serials))
        for listing, serials in (
            (self.listing, ('fixtureA',)), (self.listing, ('fixtureA', 'fixtureA')),
            (self.listing + 'third device usb:1-3\n', self.serials),
            (self.listing.replace('usb:1-1', 'transport_id:1'), self.serials),
            (self.listing.replace('fixtureB', 'emulator-5554'), ('fixtureA', 'emulator-5554')),
            (self.listing.replace('fixtureB', '127.0.0.1:5555'), ('fixtureA', '127.0.0.1:5555')),
            (self.listing.replace('fixtureB', 'fixtureA'), self.serials),
            (self.listing.replace('device usb:1-2', 'unauthorized usb:1-2'), self.serials),
        ):
            with self.subTest(listing=listing, serials=serials), self.assertRaises(ValueError):
                runner.select_pair(listing, serials)

    def fake_run(self, command, **kwargs):
        if command[-2:] == ['devices', '-l']:
            return self.listing
        self.assertIn(command[-1], ('ro.kernel.qemu', 'ro.boot.qemu', 'ro.build.version.sdk', 'ro.product.model'))
        return {'ro.kernel.qemu': '0', 'ro.boot.qemu': '', 'ro.build.version.sdk': '36',
                'ro.product.model': 'Synthetic fixture'}[command[-1]]

    def test_metadata_only_and_exact_installed_hash(self):
        with patch.object(runner, 'run', side_effect=self.fake_run), patch.object(runner, 'installed_digest', return_value='a' * 64):
            phones = runner.preflight('adb', None, self.serials, self.artifact)
        self.assertEqual(2, len(phones))
        self.assertEqual(hashlib.sha256(b'fixtureA').hexdigest(), phones[0]['deviceSha256'])
        self.assertEqual('EXACT_BYTES_PRESENT', phones[0]['installation'])
        self.assertNotIn('fixtureA', str(phones))

    def test_absent_is_pending_and_mismatch_rejected(self):
        for installed in (None, 'b' * 64):
            with patch.object(runner, 'run', side_effect=self.fake_run), patch.object(runner, 'installed_digest', return_value=installed):
                if installed:
                    with self.assertRaises(ValueError):
                        runner.preflight('adb', None, self.serials, self.artifact)
                else:
                    self.assertEqual('NOT_INSTALLED', runner.preflight('adb', None, self.serials, self.artifact)[0]['installation'])

    def test_qemu_and_old_api_rejected(self):
        for key, value in (('ro.kernel.qemu', '1'), ('ro.boot.qemu', '1'), ('ro.build.version.sdk', '30')):
            def fake(command):
                return value if command[-1] == key else self.fake_run(command)
            with patch.object(runner, 'run', side_effect=fake), self.assertRaises(ValueError):
                runner.preflight('adb', None, self.serials, self.artifact)

    def test_unapproved_package_and_wrong_hash_rejected_before_tools(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'fixture.apk'
            apk.write_bytes(b'fixture')
            with patch.object(runner, 'run') as command:
                for package in ('app.umbra.privatechat', runner.PACKAGES[0]):
                    with self.assertRaises(ValueError):
                        runner.inspect_artifact(apk, package, '0' * 64, '1' * 64, Path(directory))
                command.assert_not_called()

    def test_failure_receipt_never_logs_serial_or_tool_error(self):
        with tempfile.TemporaryDirectory() as directory:
            reports = Path(directory) / 'attempt'
            arguments = ['--adb', 'adb', '--serial', 'fixtureA', '--serial', 'fixtureB',
                         '--package', runner.PACKAGES[1], '--app-apk', 'missing.apk',
                         '--apk-sha256', 'a' * 64, '--signer-sha256', 'b' * 64,
                         '--build-tools', directory, '--reports', str(reports)]
            with patch.object(runner, 'inspect_artifact', side_effect=RuntimeError('fixtureA secret-output')), patch('builtins.print'):
                self.assertEqual(1, runner.main(arguments))
            text = (reports / 'preflight.json').read_text()
            self.assertNotIn('fixtureA', text)
            self.assertNotIn('secret-output', text)
            receipt = json.loads(text)
            self.assertEqual('BLOCKED_PREFLIGHT', receipt['result'])
            self.assertFalse(receipt['physicalAcceptance'])
            self.assertEqual({'MANUAL_PENDING'}, set(receipt['pending'].values()))
            original = text
            with patch('builtins.print'):
                self.assertEqual(1, runner.main(arguments))
            self.assertEqual(original, (reports / 'preflight.json').read_text())

    def test_no_execution_or_install_option(self):
        with patch('sys.stderr'):
            with self.assertRaises(SystemExit):
                runner.main(['--execute'])


if __name__ == '__main__':
    unittest.main()
