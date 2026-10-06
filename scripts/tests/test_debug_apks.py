"""Synthetic rejection tests for the APK gate, not Android or cryptographic tests."""
import contextlib
import io
import hashlib
import json
from pathlib import Path
import sys
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_debug_apks as gate


class ApkGateTests(unittest.TestCase):
    def inspect(self, permissions, variant="offline", missing=None, desktop=False, testing=False, wrong_abi=False, duplicate=False):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / "synthetic.apk"
            pin = {"entries": {}}
            with zipfile.ZipFile(apk, "w") as package:
                if variant == "connected":
                    package.writestr("classes.dex", b"Lorg/jni_zero/JniZero;\nLorg/jni_zero/CommonApis;")
                for abi in gate.ABIS - {missing}:
                    machine = {"arm64-v8a": 183, "armeabi-v7a": 40, "x86": 3, "x86_64": 62}[abi]
                    elf_class = 2 if abi in {"arm64-v8a", "x86_64"} else 1
                    header = bytearray(64 if elf_class == 2 else 52)
                    header[:7] = b"\x7fELF" + bytes([elf_class, 1, 1])
                    struct.pack_into("<HHI", header, 16, 3, 65535 if wrong_abi else machine, 1)
                    name = f"lib/{abi}/libsignal_jni.so"
                    package.writestr(name, header)
                    if variant == "connected":
                        voice = f"jni/{abi}/libjingle_peerconnection_so.so"
                        pin["entries"][voice] = hashlib.sha256(header).hexdigest()
                        package.writestr(voice.replace("jni/", "lib/"), header)
                    if duplicate:
                        import warnings
                        with warnings.catch_warnings():
                            warnings.simplefilter("ignore", UserWarning)
                            package.writestr(name, header)
                if desktop:
                    package.writestr("libsignal_jni_amd64.so", b"synthetic")
                if testing:
                    package.writestr("lib/arm64-v8a/libsignal_jni_testing.so", b"synthetic")
            pin_path = root / "pin.json"
            pin_path.write_text(json.dumps(pin))
            decoded = "\n".join(f"uses-permission: name='{p}'" for p in permissions)
            with patch.object(gate, "ROOT", root), patch.object(gate, "PIN", pin_path), patch.object(gate.subprocess, "check_output", return_value=decoded), contextlib.redirect_stdout(io.StringIO()):
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

    def test_unexpected_sensitive_permission_rejected(self):
        with self.assertRaises(RuntimeError):
            self.inspect({"android.permission.BLUETOOTH_CONNECT", "android.permission.READ_CONTACTS"})

    def test_misspelled_variant_is_not_treated_as_offline(self):
        with self.assertRaises(ValueError):
            self.inspect({"android.permission.BLUETOOTH_CONNECT"}, variant="offlien")

    def test_wrong_machine_in_elf_header_rejected(self):
        with self.assertRaises(RuntimeError):
            self.inspect({"android.permission.BLUETOOTH_CONNECT"}, wrong_abi=True)

    def test_duplicate_zip_entry_rejected(self):
        with self.assertRaises(RuntimeError):
            self.inspect({"android.permission.BLUETOOTH_CONNECT"}, duplicate=True)
