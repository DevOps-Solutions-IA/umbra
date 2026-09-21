"""Lab controller regression tests. Mocked Docker here is not a real TURN allocation."""
import base64
import hashlib
import hmac
import json
from pathlib import Path
import sys
import unittest
import tempfile
import subprocess
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import turn_lab


class TurnLabTests(unittest.TestCase):
    def test_generated_tls_cases_keep_certificate_validation_meaningful(self):
        # Real OpenSSL certificate verification, not native WebRTC transport proof.
        for mode in ("valid","wrong-name","expired","untrusted"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory:
                lab=turn_lab.TurnLab(tls_mode=mode)
                root=Path(directory);lab._create_tls(root)
                trust=root/"fixture-trust.pem";trust.write_text(lab.ca)
                result=subprocess.run(["openssl","verify","-CAfile",str(trust),"-verify_hostname","umbra-turn.invalid",str(root/"server.crt")],capture_output=True,timeout=10)
                self.assertEqual(mode=="valid",result.returncode==0)

    def test_rest_credentials_are_short_lived_unique_and_do_not_export_master(self):
        lab = turn_lab.TurnLab()
        lab.container = True
        lab.address = "192.0.2.10"
        with patch.object(turn_lab.time, "time", return_value=1000):
            a, b = lab.credentials(), lab.credentials()
            self.assertEqual(1180, a["expires"])
            self.assertNotEqual(a["username"], b["username"])
            expected = base64.b64encode(hmac.new(lab.secret.hex().encode(), a["username"].encode(), hashlib.sha1).digest()).decode()
            self.assertEqual(expected, a["password"])
            self.assertNotIn(lab.secret.hex(), json.dumps(a))
            for ttl in (0, -1, 181):
                with self.assertRaises(ValueError): lab.credentials(ttl)
        lab.container = False
        with self.assertRaises(ValueError): lab.credentials()

    def test_failure_after_daemon_creates_container_still_cleans_owned_resources(self):
        lab = turn_lab.TurnLab()
        calls = []
        def docker(*args):
            calls.append(args)
            if args[:2] == ("network", "inspect"):
                return json.dumps([{"IPAM": {"Config": [{"Subnet": "172.31.0.0/24"}]}}])
            if args[0] == "run": raise RuntimeError("Synthetic daemon failure after create")
            if args[0] == "ps": return lab.name
            if args[:2] == ("image", "ls"): return lab.image_tag
            return "synthetic-image"
        with patch.object(turn_lab, "docker", side_effect=docker):
            with self.assertRaisesRegex(RuntimeError, "Synthetic daemon"):
                lab.__enter__()
        self.assertIn(("rm", "--force", lab.name), calls)
        self.assertIn(("network", "rm", lab.name), calls)
        self.assertIsNone(lab.directory)
        self.assertEqual(b"", lab.secret)

    def test_cleanup_error_is_not_swallowed(self):
        lab = turn_lab.TurnLab()
        lab.network = True
        with patch.object(turn_lab, "docker", side_effect=RuntimeError("Synthetic failure")):
            with self.assertRaisesRegex(RuntimeError, "cleanup failed"): lab.close()

    def test_image_source_is_pinned_and_does_not_require_capabilities(self):
        text = Path(turn_lab.__file__).with_name("turn-lab").joinpath("Dockerfile").read_text()
        self.assertIn("FROM " + turn_lab.IMAGE, text)
        self.assertIn("USER nobody:nogroup", text)
        self.assertNotIn("setcap cap_", text)
