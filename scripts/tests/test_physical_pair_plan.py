"""Offline selection regression; no ADB, phone or installation is executed."""
import copy
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import validate_physical_pair_plan as validator


def plan():
    return {'owner_confirmed_two_physical_devices': True, 'peers': [
        {'serial': 'synthetic-a', 'physical_asset_id': 'phone-a', 'transport': 'usb',
         'state': 'device', 'qemu': '0', 'api': 36},
        {'serial': 'synthetic-b', 'physical_asset_id': 'phone-b', 'transport': 'usb',
         'state': 'device', 'qemu': '0', 'api': 35}]}


class PhysicalPairPlanTests(unittest.TestCase):
    def test_distinct_declared_phones_only_produce_redacted_planning_result(self):
        value = plan()
        original = copy.deepcopy(value)
        result = validator.validate_plan(value)
        self.assertEqual(value, original)
        self.assertEqual(result['result'], 'PLAN_VALID_ONLY')
        self.assertFalse(result['device_execution'])
        self.assertFalse(result['installation_authorized'])
        self.assertFalse(result['physical_identity_attested'])
        self.assertTrue(result['fresh_preflight_required'])
        self.assertNotIn('synthetic-', str(result))
        self.assertNotIn('phone-', str(result))

    def test_same_phone_serial_alias_and_package_substitution_are_rejected(self):
        for field in ('serial', 'physical_asset_id'):
            value = plan()
            value['peers'][1][field] = value['peers'][0][field]
            with self.subTest(field=field), self.assertRaises(ValueError):
                validator.validate_plan(value)
        value = plan()
        for index, peer in enumerate(value['peers']):
            peer['package'] = 'app.synthetic.' + str(index)
        with self.assertRaises(ValueError):
            validator.validate_plan(value)

    def test_missing_extra_unauthorized_network_emulator_and_unknown_evidence_reject(self):
        mutations = [('serial', ''), ('serial', 'emulator-5554'), ('serial', '127.0.0.1:5555'),
                     ('serial', 'x;reboot'), ('physical_asset_id', ''), ('transport', 'tcp'),
                     ('state', 'unauthorized'), ('state', 'offline'), ('qemu', '1'),
                     ('qemu', ''), ('api', 30), ('api', True), ('api', '36')]
        for key, changed in mutations:
            value = plan(); value['peers'][1][key] = changed
            with self.subTest(key=key, changed=changed), self.assertRaises(ValueError):
                validator.validate_plan(value)
        for peers in ([], plan()['peers'][:1], plan()['peers'] * 2):
            value = plan(); value['peers'] = peers
            with self.assertRaises(ValueError):
                validator.validate_plan(value)
        for confirmation in (False, 1, 'true', None):
            value = plan(); value['owner_confirmed_two_physical_devices'] = confirmation
            with self.assertRaises(ValueError):
                validator.validate_plan(value)

    def test_cli_rejects_duplicate_keys_at_top_and_peer_depth(self):
        canonical = json.dumps(plan())
        ambiguous = [
            canonical.replace('"owner_confirmed_two_physical_devices": true',
                              '"owner_confirmed_two_physical_devices": false, "owner_confirmed_two_physical_devices": true'),
            canonical.replace('"serial": "synthetic-b"',
                              '"serial": "synthetic-a", "serial": "synthetic-b"'),
            canonical.replace('"physical_asset_id": "phone-b"',
                              '"physical_asset_id": "phone-a", "physical_asset_id": "phone-b"'),
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'private.json'
            for document in ambiguous:
                path.write_text(document)
                with self.subTest(document=document), patch.object(sys, 'argv', ['validator', str(path)]), contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit) as failure:
                        validator.main()
                    self.assertEqual(failure.exception.code, 2)

    def test_cli_never_prints_private_invalid_input(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'private.json'
            path.write_text('{"serial":"private-serial-do-not-log"')
            stderr = io.StringIO()
            with patch.object(sys, 'argv', ['validator', str(path)]), contextlib.redirect_stderr(stderr):
                with self.assertRaises(SystemExit) as failure:
                    validator.main()
            self.assertEqual(failure.exception.code, 2)
            self.assertNotIn('private-serial', stderr.getvalue())
