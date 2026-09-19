"""Synthetic rejection tests for the APK gate, not Android or cryptographic tests."""
import contextlib
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_debug_apks as gate


class ApkGateTests(unittest.TestCase):
    def inspect(self, permissions, variant="offline", missing=None, desktop=False, testing=False):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / "synthetic.apk"
            with zipfile.ZipFile(apk, "w") as package:
                for abi in gate.ABIS - {missing}:
                    package.writestr(f"lib/{abi}/libsignal_jni.so", b"\x7fELFsynthetic")
                if desktop:
                    package.writestr("libsignal_jni_amd64.so", b"synthetic")
                if testing:
                    package.writestr("lib/arm64-v8a/libsignal_jni_testing.so", b"synthetic")
            decoded = "\n".join(f"uses-permission: name='{p}'" for p in permissions)
            with patch.object(gate, "ROOT", root), patch.object(gate.subprocess, "check_output", return_value=decoded), contextlib.redirect_stdout(io.StringIO()):
                gate.inspect(apk, Path("aapt"), variant)

    def test_offline_bluetooth_only(self):
        self.inspect({"android.permission.BLUETOOTH_CONNECT"})

    def test_offline_rejects_each_network_permission(self):
        for permission in gate.NETWORK:
            with self.subTest(permission=permission), self.assertRaises(RuntimeError):
                self.inspect({permission})

    def test_connected_requires_both_network_permissions(self):
        self.inspect(gate.NETWORK, "connected")
        with self.assertRaises(RuntimeError):
            self.inspect({"android.permission.INTERNET"}, "connected")

    def test_missing_jni_abi_rejected(self):
        with self.assertRaises(KeyError):
            self.inspect({"android.permission.BLUETOOTH_CONNECT"}, missing="arm64-v8a")

    def test_desktop_jni_rejected(self):
        with self.assertRaises(RuntimeError):
            self.inspect({"android.permission.BLUETOOTH_CONNECT"}, desktop=True)

    def test_empty_decoder_output_rejected(self):
        with self.assertRaises(RuntimeError):
            self.inspect(set())

    def test_client_testing_jni_rejected(self):
        with self.assertRaises(RuntimeError):
            self.inspect({"android.permission.BLUETOOTH_CONNECT"}, testing=True)
