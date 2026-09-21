"""Synthetic XML/ZIP/mapping inputs exercise validation gates, not Android runtime."""
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_android_tests
import check_apk_policy


class GateRegressionTests(unittest.TestCase):
    def test_duplicate_suite_reports_cannot_inflate_executed_count(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            report = '<testsuite name="app.umbra.ExampleTest" tests="1"><testcase name="one"/></testsuite>'
            (directory / "TEST-A.xml").write_text(report)
            (directory / "TEST-B.xml").write_text(report)
            with self.assertRaises(RuntimeError):
                check_android_tests.validate(directory, {"app.umbra.ExampleTest"})

    def test_absent_primary_dex_cannot_pass_policy(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "missing.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"synthetic")
            with zipfile.ZipFile(apk) as archive:
                with self.assertRaisesRegex(RuntimeError, "primary DEX"):
                    check_apk_policy.validate_dex(archive)

    def test_r8_inlined_lab_and_test_origins_are_rejected(self):
        safe = "app.umbra.crypto.Engine -> a:\n    1:1:void receive():10:10 -> a\n"
        check_apk_policy.validate_mapping(safe)
        for origin in ("app.umbra.lab.SoftwareKeys.unlock", "androidx.test.runner.Helper.run",
                       "app.umbra.MemoryRecords.get", "app.umbra.DeviceMemoryRecords$Nested.get",
                       "app.umbra.VoiceNativeFixtureListener.run", "app.umbra.media.VoiceEngineFixtureListener.run"):
            with self.subTest(origin=origin):
                mapping = "app.umbra.crypto.Engine -> a:\n    1:1:void " + origin + "():10:10 -> a\n"
                with self.assertRaisesRegex(RuntimeError, "inlined"):
                    check_apk_policy.validate_mapping(mapping)

    def test_nested_test_storage_in_secondary_dex_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "nested.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"synthetic production descriptor")
                archive.writestr("classes2.dex", b"Lapp/umbra/MemoryRecords$Nested;")
            with zipfile.ZipFile(apk) as archive:
                with self.assertRaisesRegex(RuntimeError, "Test/lab"):
                    check_apk_policy.validate_dex(archive)

    def test_empty_r8_map_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Missing"):
            check_apk_policy.validate_mapping("# no classes mapped\n")


if __name__ == "__main__":
    unittest.main()
