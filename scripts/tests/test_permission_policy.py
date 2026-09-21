"""Permission-gate tests use synthetic XML; they do not claim APK/runtime validation."""
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from permission_policy import COMMON, NETWORK, VOICE, VIDEO, manifest_permissions, validate_permissions


class PermissionPolicyTests(unittest.TestCase):
    def test_connected_and_offline_allowlists(self):
        validate_permissions(COMMON | NETWORK, "connected")
        validate_permissions(COMMON, "offline")

    def test_only_foreground_location_is_explicitly_allowed(self):
        expected = {"android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"}
        self.assertEqual({p for p in COMMON if "LOCATION" in p}, expected)
        for variant in ("connected", "offline"):
            validate_permissions(COMMON | (NETWORK if variant == "connected" else set()), variant)

    def test_voice_permissions_only_connected(self):
        validate_permissions(COMMON | NETWORK | VOICE, "connected")
        for permission in VOICE:
            with self.subTest(permission=permission), self.assertRaises(RuntimeError):
                validate_permissions(COMMON | {permission}, "offline")

    def test_camera_only_connected(self):
        validate_permissions(COMMON | NETWORK | VOICE | VIDEO, "connected")
        with self.assertRaises(RuntimeError):
            validate_permissions(COMMON | VIDEO, "offline")

    def test_added_sensitive_permission_is_rejected(self):
        for permission in ("READ_CONTACTS", "ACCESS_BACKGROUND_LOCATION", "FOREGROUND_SERVICE_LOCATION", "READ_SMS", "CAMERA", "RECORD_AUDIO"):
            with self.subTest(permission=permission), self.assertRaises(RuntimeError):
                validate_permissions(COMMON | {"android.permission." + permission}, "offline")

    def test_sdk23_permissions_are_not_invisible_to_gate(self):
        for tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
            with self.subTest(tag=tag):
                root = ET.fromstring(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android"><{tag} android:name="android.permission.INTERNET"/></manifest>')
                self.assertEqual(manifest_permissions(root), {"android.permission.INTERNET"})
                with self.assertRaises(RuntimeError):
                    validate_permissions(manifest_permissions(root), "offline")

    def test_each_network_permission_is_rejected_offline(self):
        for permission in NETWORK:
            with self.subTest(permission=permission), self.assertRaises(RuntimeError):
                validate_permissions(COMMON | {permission}, "offline")

    def test_connected_requires_both_network_permissions(self):
        for permission in NETWORK:
            with self.subTest(permission=permission), self.assertRaises(RuntimeError):
                validate_permissions(COMMON | {permission}, "connected")

    def test_unknown_variant_rejected(self):
        with self.assertRaises(ValueError):
            validate_permissions(COMMON, "offlien")

    def test_unnamed_xml_permission_rejected(self):
        with self.assertRaises(RuntimeError):
            manifest_permissions(ET.fromstring('<manifest><uses-permission-sdk-23/></manifest>'))
