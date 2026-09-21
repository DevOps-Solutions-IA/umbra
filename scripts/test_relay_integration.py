#!/usr/bin/env python3
"""Exercise production JVM Engine/RelayClient against an ephemeral real HTTPS relay.

The only storage double is MemoryRecords; this is not Android Keystore, an APK
execution, or Bluetooth. TLS verification stays enabled in Python and Java.
"""
from __future__ import annotations

import argparse
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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path, help="Use an already resolved Gradle classpath")
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
    java_sources += sorted((sources / "devices").glob("*.java"))
    java_sources += [p for p in sorted((sources / "location").glob("*.java")) if not p.name.startswith("Android")]
    java_sources += sorted((sources / "verification").glob("*.java"))
    java_sources += [sources / "protocol/Wire.java", sources / "data/Records.java",
                     sources / "transport/RelayClient.java",
                     ROOT / "android/app/src/test/java/app/umbra/MemoryRecords.java",
                     ROOT / "scripts/RelayIntegrationTest.java", ROOT / "scripts/DeviceRelayIntegration.java"]
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
        invitations.write_text("".join(database.issue_invite() + "\n" for _ in range(5)))
        invitations.chmod(0o600)
        environment = dict(os.environ, UMBRA_DB=database.path, PYTHONPATH=str(ROOT / "relay"))
        context = ssl.create_default_context(cafile=str(cert))
        server = test = None
        release_fixture = threading.Event()

        class SlowResponse(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass  # Synthetic capability headers and paths never enter logs.

            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.flush()
                (exchange / "pending-response").touch()
                release_fixture.wait(30)
                try:
                    self.wfile.write(b"{}")
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
                                         "app.umbra.RelayIntegrationTest", base, str(exchange), hostile_base], cwd=ROOT)
                deadline = time.monotonic() + 120
                restarted = False
                while test.poll() is None:
                    if time.monotonic() > deadline:
                        raise RuntimeError("JVM HTTPS integration deadline exceeded")
                    if server.poll() is not None:
                        (exchange / "server-failed").touch()
                        raise RuntimeError(f"Isolated relay failed: {server.returncode}")
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
                if not restarted:
                    raise RuntimeError("JVM exited without exercising relay restart")
                wait_healthy(server, base, context)
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
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.SubprocessError) as exc:
        print(f"HTTPS integration failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
