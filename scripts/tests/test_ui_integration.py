"""Fail-closed checks of the Claude UI integration runner (no device needed)."""
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_ui_integration as ui

ROOT = Path(__file__).resolve().parents[2]
CONFIG = '-keepattributes Signature\n'
MAPPING = '\n'.join([
    'app.umbra.ui.flow.RestrictedFlow$Viewer -> a.b:', 'app.umbra.ui.design.ProtectedFrameView -> a.c:',
    'app.umbra.ui.screens.ContentScreens -> a.d:', 'app.umbra.ui.model.EmergencyPresentation -> a.e:',
    'app.umbra.ui.design.QrCodes -> a.f:', 'com.google.zxing.qrcode.decoder.Version -> b.a:',
    'com.google.zxing.qrcode.encoder.Encoder -> b.b:']) + '\n'


class UiIntegrationRunnerTests(unittest.TestCase):
    def test_optimized_mapping_requires_renamed_ui_and_present_qr_encoder(self):
        result = ui.optimized_ui(MAPPING, CONFIG)
        self.assertEqual('a.f', result['app.umbra.ui.design.QrCodes'])
        self.assertEqual('b.b', result['com.google.zxing.qrcode.encoder.Encoder'])

    def test_class_inlined_facades_are_allowed(self):
        # MultiFormatWriter/QRCodeWriter absent (class-inlined) is fine while the encoder package survives.
        self.assertNotIn('com.google.zxing.MultiFormatWriter', ui.optimized_ui(MAPPING, CONFIG))

    def test_ci_2313e54_mapping_with_inlined_qrcodewriter_passes(self):
        # Real R8 outcome on 2313e54: MultiFormatWriter kept, QRCodeWriter class-inlined into it.
        ci = MAPPING + 'com.google.zxing.MultiFormatWriter -> b.c:\n'
        result = ui.optimized_ui(ci, CONFIG)
        self.assertNotIn('com.google.zxing.qrcode.QRCodeWriter', result)
        self.assertIn('com.google.zxing.qrcode.encoder.Encoder', result)

    def test_removed_zxing_encoder_fails(self):
        stripped = '\n'.join(l for l in MAPPING.splitlines() if not l.startswith('com.google.zxing.')) + '\n'
        with self.assertRaises(RuntimeError):
            ui.optimized_ui(stripped, CONFIG)
        with self.assertRaises(RuntimeError):  # a non-QR ZXing class alone is not the encoder
            ui.optimized_ui(stripped + 'com.google.zxing.common.BitMatrix -> c.a:\n', CONFIG)

    def test_unobfuscated_or_disabled_optimization_fails(self):
        with self.assertRaises(RuntimeError):
            ui.optimized_ui(MAPPING.replace('app.umbra.ui.design.QrCodes -> a.f:', 'app.umbra.ui.design.QrCodes -> app.umbra.ui.design.QrCodes:'), CONFIG)
        with self.assertRaises(RuntimeError):
            ui.optimized_ui(MAPPING, '-dontoptimize\n')

    def test_notification_icon_must_survive_resource_shrinking(self):
        self.assertEqual(['drawable/ic_notification_umbra'], ui.resources_present('resource 0x7f0800a1 drawable/ic_notification_umbra\n'))
        with self.assertRaises(RuntimeError):
            ui.resources_present('resource 0x7f0800a2 drawable/umbra_symbol\n')

    def test_resource_keep_file_is_exact(self):
        keep = ROOT / 'android/app/src/main/res/raw/umbra_resource_keep.xml'
        root = ET.parse(keep).getroot()
        tools = '{http://schemas.android.com/tools}'
        self.assertEqual('@drawable/ic_notification_umbra', root.attrib[tools + 'keep'])
        self.assertNotIn(tools + 'shrinkMode', root.attrib)
        self.assertNotIn('*', root.attrib[tools + 'keep'])

    def test_counts_are_fixed(self):
        self.assertEqual({False: 35, True: 32}, ui.EXPECTED)
        self.assertTrue(ui.valid('OK (32 tests)\nINSTRUMENTATION_CODE: -1\n', 0, 32))
        self.assertFalse(ui.valid('OK (31 tests)\nINSTRUMENTATION_CODE: -1\n', 0, 32))
        self.assertFalse(ui.valid('INSTRUMENTATION_STATUS_CODE: -3\nOK (32 tests)\nINSTRUMENTATION_CODE: -1\n', 0, 32))


if __name__ == '__main__':
    unittest.main()
