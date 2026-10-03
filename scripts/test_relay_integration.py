#!/usr/bin/env python3
"""Exercise production JVM Engine/RelayClient against an ephemeral real HTTPS relay.

The only storage double is MemoryRecords; this is not Android Keystore, an APK
execution, or Bluetooth. TLS verification stays enabled in Python and Java.
"""
from __future__ import annotations

import argparse
import json
from admission_lab import AdmissionLab
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import re
import signal
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

from bootstrap_gradle import ROOT, bootstrap


def stop(process: subprocess.Popen | None) -> None:
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def wait_healthy(process: subprocess.Popen, url: str, context: ssl.SSLContext) -> None:
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Isolated relay exited with status {process.returncode}")
        try:
            with urllib.request.urlopen(url + "/healthz", context=context, timeout=1) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(0.05)
    raise RuntimeError("Isolated HTTPS relay health deadline exceeded")


def inspect_pairing_storage(database):
    """Inspect this real fixture's rows without emitting capabilities, codes or blob bytes."""
    import base64
    import hashlib
    def reject(condition):
        if not condition:
            raise RuntimeError("Pairing SQLite privacy/shape inspection failed")
    def url(value, length):
        raw = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
        reject(len(raw) == length and base64.urlsafe_b64encode(raw).decode().rstrip("=") == value)
        return raw
    def wrapper(value, direction, row):
        raw = base64.b64decode(value, validate=True)
        reject(base64.b64encode(raw).decode() == value and 28 <= len(raw) <= 65536)
        fields = raw.decode("ascii").split("\n")
        reject(len(fields) == 8 and fields[0] == "UMBRA-PAIR-BLOB-1" and fields[1] == direction and fields[7] == "")
        url(fields[2], 32)
        reject(hashlib.sha256(fields[2].encode()).hexdigest() == row["id_hash"])
        reject(re.fullmatch("[0-9a-f]{64}", fields[3]) is not None and fields[4] == str(row["expires"]))
        url(fields[5], 12)
        ciphertext = base64.urlsafe_b64decode(fields[6] + "=" * (-len(fields[6]) % 4))
        reject(16 <= len(ciphertext) <= 24016 and base64.urlsafe_b64encode(ciphertext).decode().rstrip("=") == fields[6])
        reject(all(marker not in raw for marker in (b"umbra:invite:", b"umbra:request:", b"umbra:ack:", b"Synthetic pairing")))
        return fields[3]
    with database.connect() as db:
        rendezvous = [dict(r) for r in db.execute("SELECT * FROM pairing_rendezvous")]
        candidates = [dict(r) for r in db.execute("SELECT * FROM pairing_candidates")]
    reject(len(rendezvous) == 3 and len(candidates) == 3)
    reject(all(set(r) == {"id_hash", "box", "owner_hash", "request_hash", "locator_hash", "code_hash",
                         "invite_blob", "expires", "code_claim_hash", "selected_hash", "ack_blob", "revoked"} for r in rendezvous))
    reject(all(set(r) == {"rendezvous", "request_hash", "ack_hash", "request_blob"} for r in candidates))
    for row in rendezvous + candidates:
        for value in row.values():
            if isinstance(value, str):
                reject(all(marker not in value for marker in ("umbra:invite:", "umbra:request:", "umbra:ack:", "Synthetic pairing")))
    invalid = 0
    wrapped = 0
    revoked = 0
    for row in rendezvous:
        if row["revoked"]:
            reject(row["ack_blob"] is None and row["selected_hash"] is None and row["code_claim_hash"] is None)
            reject(not any(c["rendezvous"] == row["id_hash"] for c in candidates))
            wrapper(row["invite_blob"], "INVITE", row)
            wrapped += 1; revoked += 1
            continue
        digest = wrapper(row["ack_blob"], "ACK", row); wrapped += 1
        if row["invite_blob"] is not None:
            reject(wrapper(row["invite_blob"], "INVITE", row) == digest); wrapped += 1
        for candidate in candidates:
            if candidate["rendezvous"] != row["id_hash"]:
                continue
            # The deliberate malformed first claimant is retained as a bounded rejected slot.
            if candidate["request_blob"] == base64.b64encode(bytes(40)).decode():
                reject(candidate["request_hash"] != row["selected_hash"])
                invalid += 1
            else:
                reject(wrapper(candidate["request_blob"], "REQUEST", row) == digest)
                reject(candidate["request_hash"] == row["selected_hash"]); wrapped += 1
    reject(invalid == 1 and wrapped == 6 and revoked == 1)
    print("PASS real pairing SQLite inspection: six encrypted wrappers, one revoked unclaimed code, one rejected synthetic invalid candidate; no raw signed transcripts or aliases. Public locator/digest/timing metadata remains observable.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path, help="Use an already resolved Gradle classpath")
    parser.add_argument("--pairing-only", action="store_true", help="Fresh isolated pairing fixture with unchanged production limits")
    args = parser.parse_args()
    if sys.version_info < (3, 12) or os.name != "posix":
        raise RuntimeError("Requires Python 3.12+ and POSIX socket descriptor inheritance")
    for program, expression in [("java", r'version "21\.'), ("javac", r'javac 21\.')]:
        version = subprocess.run([program, "-version"], capture_output=True, text=True, check=True)
        if not re.search(expression, version.stdout + version.stderr):
            raise RuntimeError(f"Select JDK 21 before running {program}")
    classpath_file = args.classpath_file
    if classpath_file is None:
        subprocess.run([str(bootstrap()), "--no-daemon", ":app:writeRelayIntegrationClasspath"],
                       cwd=ROOT / "android", check=True, timeout=300)
        classpath_file = ROOT / "android/app/build/integration/classpath.txt"
    classpath = classpath_file.read_text().strip()
    if not classpath or any(not Path(item).is_file() for item in classpath.split(os.pathsep)):
        raise RuntimeError("Resolved JVM dependency classpath is missing or invalid")
    sources = ROOT / "android/app/src/main/java/app/umbra"
    java_sources = sorted((sources / "core").glob("*.java")) + sorted((sources / "crypto").glob("*.java"))
    java_sources += sorted((sources / "pairing").glob("*.java"))
    # Domain content is required by Engine; Android codecs are exercised by the APK laboratory.
    java_sources += [sources / "content" / name for name in
                     ("ContentException.java", "RestrictedPayload.java", "RestrictedContentService.java")]
    java_sources += sorted((sources / "admission").glob("*.java"))
    java_sources += [p for p in sorted((sources / "connectivity").glob("*.java")) if not p.name.startswith("Android")]
    java_sources += [ROOT / "android/app/src/androidTest/java/app/umbra/AdmissionLab.java"]
    java_sources += sorted((sources / "devices").glob("*.java"))
    java_sources += sorted((sources / "calls").glob("*.java"))
    java_sources += [ROOT / "android/app/src/connected/java/app/umbra/calls/CallPlatform.java"]
    java_sources += [p for p in sorted((sources / "location").glob("*.java")) if not p.name.startswith("Android")]
    java_sources += sorted((sources / "verification").glob("*.java"))
    java_sources += [sources / "protocol/Wire.java", sources / "data/Records.java",
                     sources / "transport/RelayClient.java",
                     ROOT / "android/app/src/test/java/app/umbra/MemoryRecords.java",
                     ROOT / "scripts/RelayIntegrationTest.java", ROOT / "scripts/RestrictedRelayIntegration.java", ROOT / "scripts/DeviceRelayIntegration.java", ROOT / "scripts/AdmissionRelayIntegration.java", ROOT / "scripts/PairingRelayIntegration.java"]
    with tempfile.TemporaryDirectory(prefix="umbra-https-integration-") as directory:
        temporary = Path(directory)
        classes = temporary / "classes"
        classes.mkdir()
        subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes),
                        *map(str, java_sources)], check=True, timeout=120)
        cert, key, truststore = (temporary / name for name in ("localhost.pem", "localhost.key", "trust.p12"))
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                        "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
                        "-keyout", str(key), "-out", str(cert)], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        subprocess.run(["keytool", "-importcert", "-noprompt", "-alias", "synthetic-localhost",
                        "-file", str(cert), "-keystore", str(truststore), "-storetype", "PKCS12",
                        "-storepass", "integration-only"], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        sys.path.insert(0, str(ROOT / "relay"))
        from umbra_relay.app import Database
        database = Database(str(temporary / "relay.sqlite3"))
        exchange = temporary / "exchange"
        exchange.mkdir(mode=0o700)
        invitations = exchange / "invitations"
        invitations.write_text("".join(database.issue_invite() + "\n" for _ in range(12)))
        invitations.chmod(0o600)
        admission = AdmissionLab()
        (exchange / "admission-realm").write_text(admission.realm.encode())
        environment = dict(os.environ, UMBRA_DB=database.path, PYTHONPATH=str(ROOT / "relay"), UMBRA_ADMISSION_REALM=admission.realm.encode())
        context = ssl.create_default_context(cafile=str(cert))
        server = test = None
        release_fixture = threading.Event()

        class SlowResponse(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass  # Synthetic capability headers and paths never enter logs.

            def do_GET(self):
                (exchange / "pending-response").touch()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"{")
                self.wfile.flush()
                release_fixture.wait(30)
                try:
                    self.wfile.write(b"}")
                except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                    pass

        fixture = ThreadingHTTPServer(("127.0.0.1", 0), SlowResponse)
        server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server_context.load_cert_chain(cert, key)
        fixture.socket = server_context.wrap_socket(fixture.socket, server_side=True)
        fixture_thread = threading.Thread(target=fixture.serve_forever, daemon=True)
        fixture_thread.start()
        hostile_base = f"https://localhost:{fixture.server_port}"
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener, (temporary / "server.log").open("w") as server_log:
            listener.bind(("127.0.0.1", 0))
            listener.listen(128)
            base = f"https://localhost:{listener.getsockname()[1]}"

            environment["UMBRA_ADMISSION_ORIGIN"] = base
            from umbra_relay.admission_store import AdmissionStore
            admission.store = AdmissionStore(database, admission.realm.encode(), base)

            def start_server():
                return subprocess.Popen([sys.executable, "-m", "uvicorn", "umbra_relay.app:create_app",
                                         "--factory", "--fd", str(listener.fileno()), "--workers", "1",
                                         "--ssl-certfile", str(cert), "--ssl-keyfile", str(key),
                                         "--no-access-log", "--no-proxy-headers", "--log-level", "warning"],
                                        env=environment, cwd=ROOT, pass_fds=(listener.fileno(),),
                                        stdout=server_log, stderr=server_log)
            try:
                server = start_server()
                wait_healthy(server, base, context)
                test = subprocess.Popen(["java", f"-Djavax.net.ssl.trustStore={truststore}",
                                         "-Djavax.net.ssl.trustStorePassword=integration-only",
                                         "-cp", str(classes) + os.pathsep + classpath,
                                         "app.umbra.RelayIntegrationTest", base, str(exchange), hostile_base,
                                         *(["pairing-only"] if args.pairing_only else [])], cwd=ROOT)
                deadline = time.monotonic() + 120
                restarted = False
                while test.poll() is None:
                    if time.monotonic() > deadline:
                        raise RuntimeError("JVM HTTPS integration deadline exceeded")
                    if server.poll() is not None:
                        (exchange / "server-failed").touch()
                        raise RuntimeError(f"Isolated relay failed: {server.returncode}")
                    for request_path in exchange.glob("admission-*-request.json"):
                        approval = admission.approve(json.loads(request_path.read_text())["request"])
                        destination = request_path.with_name(request_path.name.replace("-request.json", "-credential.json"))
                        temporary_result = destination.with_suffix(".tmp")
                        temporary_result.write_text(json.dumps(approval))
                        temporary_result.replace(destination)
                        request_path.unlink(missing_ok=True)
                    revocation_request=exchange / "membership-revoke.json"
                    if revocation_request.exists():
                        result=admission.revoke(json.loads(revocation_request.read_text())["credential"])
                        temporary_result=exchange / "membership-revoked.tmp"
                        temporary_result.write_text(json.dumps(result))
                        temporary_result.replace(exchange / "membership-revoked.json")
                        revocation_request.unlink()
                    if not restarted and (exchange / "restart-request").exists():
                        stop(server)
                        # Uvicorn re-raises a captured SIGTERM after graceful shutdown.
                        if server.returncode not in (0, -signal.SIGTERM):
                            raise RuntimeError(f"Relay shutdown failed: {server.returncode}")
                        server = start_server()
                        wait_healthy(server, base, context)
                        (exchange / "restart-done").touch()
                        restarted = True
                    time.sleep(0.05)
                if test.returncode != 0:
                    return test.returncode
                if not restarted and not args.pairing_only:
                    raise RuntimeError("JVM exited without exercising relay restart")
                wait_healthy(server, base, context)
                if args.pairing_only:
                    inspect_pairing_storage(database)
                print("Real HTTPS integration succeeded with isolated SQLite, verified TLS and libsignal JNI.")
                return 0
            finally:
                stop(test)
                stop(server)
                release_fixture.set()
                fixture.shutdown()
                fixture.server_close()
                fixture_thread.join(timeout=5)


if __name__ == "__main__":
    try:
        result = main()
        if result == 0 and "--pairing-only" not in sys.argv[1:]:
            # Different fixture scope, database and process: legacy acceptance already uses
            # almost the full production per-IP ingress budget. Pairing gets the same
            # unchanged limits and its own 120-second deadline, never a 429 bypass/retry.
            result = subprocess.run([sys.executable, str(Path(__file__).resolve()),
                                     *sys.argv[1:], "--pairing-only"], check=False).returncode
        raise SystemExit(result)
    except (OSError, RuntimeError, subprocess.SubprocessError) as exc:
        print(f"HTTPS integration failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
