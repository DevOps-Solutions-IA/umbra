from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_optimized_media import optimized_classes


class OptimizedMediaEvidenceTest(unittest.TestCase):
    mapping = ('app.umbra.media.NativeVoiceSession -> a.a:\n'
               'app.umbra.calls.CallService -> a.b:\n'
               'app.umbra.crypto.Engine -> a.c:\n')

    def test_missing_unobfuscated_or_disabled_optimization_rejected(self):
        self.assertEqual(3, len(optimized_classes(self.mapping, '')))
        for mapping, config in (
            ('', ''),
            (self.mapping.replace(' -> a.a:', ' -> app.umbra.media.NativeVoiceSession:'), ''),
            (self.mapping, '-dontoptimize'),
            (self.mapping, '-dontobfuscate'),
            (self.mapping, '-dontshrink'),
            (self.mapping + 'app.umbra.lab.SqliteDeviceRecords -> a.d:\n', ''),
        ):
            with self.subTest(mapping=mapping, config=config):
                with self.assertRaises((ValueError, RuntimeError)):
                    optimized_classes(mapping, config)
