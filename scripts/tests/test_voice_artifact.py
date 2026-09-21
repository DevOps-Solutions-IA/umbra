"""Synthetic artifact rejection cases; not evidence of native execution."""
import hashlib
import io
import sys
import unittest
import zipfile
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_voice_artifact import ABIS, verify_apk


class VoiceArtifactTests(unittest.TestCase):
    def archive(self, voice=True, altered=False, extra=False, dex=False, missing=False, license_asset=False, tampered_license=False):
        data = io.BytesIO()
        pin = {"entries": {}}
        with zipfile.ZipFile(data, "w") as z:
            for abi in ABIS:
                z.writestr(f"lib/{abi}/libsignal_jni.so", b"synthetic Signal")
                if voice:
                    name = f"jni/{abi}/libjingle_peerconnection_so.so"
                    pin["entries"][name] = hashlib.sha256(b"synthetic WebRTC").hexdigest()
                    if not (missing and abi == "x86"):
                        z.writestr(name.replace("jni/", "lib/"), b"tampered" if altered else b"synthetic WebRTC")
            if license_asset:
                name = "assets/umbra-webrtc/LICENSE"
                pin["entries"][name] = hashlib.sha256(b"synthetic license").hexdigest()
                z.writestr(name, b"tampered" if tampered_license else b"synthetic license")
            if extra:
                z.writestr("lib/x86/libunexpected.so", b"extra")
            if dex:
                z.writestr("classes2.dex", b"Lorg/webrtc/PeerConnection;")
        return zipfile.ZipFile(data), pin

    def test_exact_connected_distribution(self):
        archive, pin = self.archive()
        with archive:
            verify_apk(archive, "connected", pin)

    def test_missing_tampered_and_unexpected_native_rejected(self):
        for kwargs in ({"missing": True}, {"altered": True}, {"extra": True}, {"voice": False}):
            with self.subTest(kwargs=kwargs):
                archive, pin = self.archive(**kwargs)
                with archive, self.assertRaises(RuntimeError):
                    verify_apk(archive, "connected", pin)

    def test_offline_rejects_native_or_java_webrtc(self):
        for kwargs in ({}, {"voice": False, "dex": True}):
            archive, pin = self.archive(**kwargs)
            with archive, self.assertRaises(RuntimeError):
                verify_apk(archive, "offline", pin)

    def test_offline_without_webrtc(self):
        archive, pin = self.archive(voice=False)
        with archive:
            verify_apk(archive, "offline", pin)

    def test_license_integrity_and_offline_isolation(self):
        archive, pin = self.archive(license_asset=True)
        with archive:
            verify_apk(archive, "connected", pin)
        archive, pin = self.archive(license_asset=True, tampered_license=True)
        with archive, self.assertRaisesRegex(RuntimeError, "license asset"):
            verify_apk(archive, "connected", pin)
        archive, pin = self.archive(voice=False, license_asset=True)
        with archive, self.assertRaisesRegex(RuntimeError, "assets leaked"):
            verify_apk(archive, "offline", pin)
