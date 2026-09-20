"""Host UI parsing/pairing safeguards; these do not substitute for RFCOMM execution."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pair_bluetooth_emulators as pairing


class PairingTests(unittest.TestCase):
    def test_bounds_support_different_screen_sizes(self):
        self.assertEqual(pairing.center(ET.Element('node', bounds='[40,190][280,232]')), (160, 211))
        with self.assertRaises(ValueError):
            pairing.center(ET.Element('node', bounds='[40,190][40,232]'))

    def test_code_requires_pairing_prompt_and_unique_six_digits(self):
        code = ET.Element('node', text='123456')
        self.assertIsNone(pairing.pairing_code([code]))
        title = ET.Element('node', text='Bluetooth pairing code')
        self.assertEqual(pairing.pairing_code([title, code]), '123456')
        with self.assertRaises(RuntimeError):
            pairing.pairing_code([title, code, ET.Element('node', text='654321')])

    def test_address_must_be_device_address_label(self):
        self.assertIsNone(pairing.address_from_ui([ET.Element('node', text='BB:BB:BB:00:00:01')]))
        self.assertEqual(pairing.address_from_ui([ET.Element('node', text="Phone's Bluetooth address: BB:BB:BB:00:00:01")]), 'BB:BB:BB:00:00:01')

    def test_bond_requires_bonded_section_not_discovery_or_logs(self):
        addr = 'BB:BB:BB:00:00:02'
        self.assertTrue(pairing.bonded('  Bonded devices:\n    '+addr+' [ DUAL ] synthetic\n\n Scan Mode Changes:', addr))
        self.assertFalse(pairing.bonded('  Bonded devices:\n\n logs:\n'+addr, addr))
        self.assertFalse(pairing.bonded('discovered '+addr, addr))

    def test_foreign_ui_cannot_supply_confirmation(self):
        self.assertEqual(pairing.nodes('<hierarchy><node package="evil" text="PAIR"/></hierarchy>'), [])

    def test_physical_device_refused_before_mutation(self):
        with tempfile.TemporaryDirectory() as folder:
            flow = pairing.Pairing('adb', ('emulator-5554', 'emulator-5556'), Path(folder), 60)
            with patch.object(flow, 'command', return_value='0') as command:
                with self.assertRaisesRegex(RuntimeError, 'not an emulator'):
                    flow.run()
                self.assertEqual(command.call_args.args, ('emulator-5554', 'shell', 'getprop', 'ro.kernel.qemu'))
                self.assertEqual(command.call_count, 1)

    def test_ambiguous_names_are_not_tapped(self):
        with tempfile.TemporaryDirectory() as folder:
            flow = pairing.Pairing('adb', ('emulator-5554', 'emulator-5556'), Path(folder), 60)
            row = ET.Element('node', text='same', enabled='true', bounds='[1,1][4,4]')
            with patch.object(flow, 'command') as command:
                self.assertFalse(flow.tap('emulator-5554', [row, row], 'same'))
                command.assert_not_called()
