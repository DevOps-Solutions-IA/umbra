"""APK provenance gate; mocked ADB is not an installation or device test."""
from pathlib import Path
import hashlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from android_apk_install import ensure_apk

class ApkInstallationTest(unittest.TestCase):
    def test_same_digest_reuses_apk_but_changed_digest_installs(self):
        with tempfile.TemporaryDirectory() as directory:
            apk=Path(directory)/"synthetic.apk";apk.write_bytes(b"synthetic fixture")
            for deployed,expected_calls in ((hashlib.sha256(apk.read_bytes()).hexdigest(),2),("0"*64,3)):
                responses=[subprocess.CompletedProcess([],0,"package:/data/app/~~fixture/pkg/base.apk\n"),
                           subprocess.CompletedProcess([],0,deployed+"  /data/app/~~fixture/pkg/base.apk\n"),
                           subprocess.CompletedProcess([],0,"Success\n")]
                with patch("android_apk_install.subprocess.run",side_effect=responses) as run:
                    ensure_apk("adb","emulator-5554","app.umbra.privatechat.dev",apk)
                    self.assertEqual(expected_calls,run.call_count)
    def test_ambiguous_path_and_install_failure_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            apk=Path(directory)/"synthetic.apk";apk.write_bytes(b"synthetic")
            for responses in ([subprocess.CompletedProcess([],0,"package:/data/app/$(bad)/base.apk")],
                              [subprocess.CompletedProcess([],1,""),subprocess.CompletedProcess([],1,"Failure [synthetic]")]):
                with patch("android_apk_install.subprocess.run",side_effect=responses):
                    with self.assertRaises(RuntimeError): ensure_apk("adb","emulator-5554","app.umbra.privatechat.dev",apk)
