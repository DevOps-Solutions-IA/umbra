import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_android
import check_android_tests
import check_apk_policy as policy


class BuildValidationTests(unittest.TestCase):
    def test_java_home_mismatch_rejected_even_with_java21_on_path(self):
        with patch.dict(os.environ, {"JAVA_HOME": "/synthetic/jdk17"}), patch.object(build_android.shutil, "which", return_value="/synthetic/jdk21/bin/java"), patch.object(build_android.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, '', 'java version "17.0.1"')) as run:
            with self.assertRaises(RuntimeError):
                build_android.java_home()
            self.assertIn("jdk17", run.call_args.args[0][0])

    def test_java_home_checks_compiler_as_well(self):
        versions = [subprocess.CompletedProcess([], 0, '', 'java version "21.0.1"'), subprocess.CompletedProcess([], 0, 'javac 17.0.1', '')]
        with patch.dict(os.environ, {"JAVA_HOME": "/synthetic/jdk"}), patch.object(build_android.subprocess, "run", side_effect=versions):
            with self.assertRaises(RuntimeError):
                build_android.java_home()

    def test_reports_missing_empty_and_skipped_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            with self.assertRaises(RuntimeError):
                check_android_tests.validate(directory, {"app.umbra.Test"})
            path = directory / "TEST-example.xml"
            for content in ('<testsuite name="app.umbra.Test" tests="0"/>', '<testsuite name="app.umbra.Test" tests="1"><testcase><skipped/></testcase></testsuite>', '<testsuite name="app.umbra.Test" tests="1"><testcase><failure/></testcase></testsuite>'):
                path.write_text(content)
                with self.assertRaises(RuntimeError):
                    check_android_tests.validate(directory, {"app.umbra.Test"})
            path.write_text('<testsuite name="app.umbra.Test" tests="1"><testcase/></testsuite>')
            self.assertEqual(check_android_tests.validate(directory, {"app.umbra.Test"}), 1)

    def manifest(self):
        root = ET.Element("manifest", package="app.umbra.privatechat.dev")
        app = ET.SubElement(root, "application", {"android:allowBackup": "false", "android:fullBackupContent": "false", "android:usesCleartextTraffic": "false", "android:debuggable": "true", "android:networkSecurityConfig": "@0x7f040001", "android:dataExtractionRules": "@0x7f040000"})
        activity = ET.SubElement(app, "activity", {"android:name": "app.umbra.ui.MainActivity", "android:exported": "true"})
        intent = ET.SubElement(activity, "intent-filter")
        ET.SubElement(intent, "action", {"android:name": "android.intent.action.MAIN"})
        ET.SubElement(intent, "category", {"android:name": "android.intent.category.LAUNCHER"})
        return root

    def test_unsafe_binary_manifest_flags_and_components_rejected(self):
        policy.validate_manifest(self.manifest(), "connected", "debug")
        for flag in ("allowBackup", "fullBackupContent", "usesCleartextTraffic", "testOnly"):
            root = self.manifest()
            root.find("application").set("android:" + flag, "true")
            with self.assertRaises(RuntimeError):
                policy.validate_manifest(root, "connected", "debug")
        root = self.manifest()
        ET.SubElement(root.find("application"), "provider")
        with self.assertRaises(RuntimeError):
            policy.validate_manifest(root, "connected", "debug")
        with self.assertRaises(RuntimeError):
            policy.validate_manifest(self.manifest(), "connected", "release")

    def test_binary_xml_boolean_and_resource_decoding(self):
        root = policy.decode_tree('E: application (line=1)\n  A: android:allowBackup(0x1010280)=(type 0x12)0x0\n  A: android:debuggable(0x101000f)=(type 0x12)0xffffffff\n  A: android:networkSecurityConfig(0x1010527)=@0x7f040001')
        self.assertEqual(root.get("android:allowBackup"), "false")
        self.assertEqual(root.get("android:debuggable"), "true")
        self.assertEqual(root.get("android:networkSecurityConfig"), "@0x7f040001")

    def test_extra_tls_trust_anchor_rejected(self):
        network = ET.fromstring('<network-security-config><base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="system"/><certificates src="user"/></trust-anchors></base-config></network-security-config>')
        with self.assertRaises(RuntimeError):
            policy.validate_resources(network, ET.Element("data-extraction-rules"))


if __name__ == "__main__":
    unittest.main()
