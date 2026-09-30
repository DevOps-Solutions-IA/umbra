#!/usr/bin/env python3
"""Bounded GHSA-mqwm-5m85-gmcv regression using two owned loopback repositories.

No executable dependency is downloaded: the fallback serves a synthetic text
artifact. Success requires Gradle to fail resolution without contacting fallback.
"""
import argparse
import http.server
import json
import pathlib
import socket
import shutil
import subprocess
import tempfile
import threading


def check(gradle: str, report: pathlib.Path) -> int:
    executable = shutil.which(gradle)
    if executable is None:
        raise ValueError("Gradle executable is unavailable")
    counts = {"primary": 0, "fallback": 0}

    class Primary(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            counts["primary"] += 1
            self.connection.shutdown(socket.SHUT_RDWR)
            self.connection.close()

        do_HEAD = do_GET

        def log_message(self, *_):
            pass

    class Fallback(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            counts["fallback"] += 1
            body = b"owned synthetic non-executable dependency\n"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_HEAD(self):
            counts["fallback"] += 1
            self.send_response(200)
            self.send_header("Content-Length", str(len(b"owned synthetic non-executable dependency\n")))
            self.end_headers()

        def log_message(self, *_):
            pass

    servers = [http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
               for handler in (Primary, Fallback)]
    threads = [threading.Thread(target=s.serve_forever, daemon=True) for s in servers]
    for thread in threads:
        thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="umbra-gradle-repository-") as directory:
            root = pathlib.Path(directory)
            (root / "settings.gradle").write_text("rootProject.name = 'owned-repository-probe'\n")
            repos = "\n".join(
                "ivy { url = uri('http://127.0.0.1:%d'); allowInsecureProtocol = true; "
                "patternLayout { artifact '[artifact]-[revision].[ext]' }; metadataSources { artifact() } }"
                % server.server_port for server in servers)
            (root / "build.gradle").write_text(
                "repositories {\n" + repos + "\n}\nconfigurations { probe }\n"
                "dependencies { probe 'synthetic.umbra:probe:1@txt' }\n"
                "tasks.register('resolveProbe') { doLast { configurations.probe.resolve() } }\n")
            completed = subprocess.run(
                [str(pathlib.Path(executable).resolve()), "--no-daemon", "--console=plain",
                 "--max-workers=1", "--refresh-dependencies", "resolveProbe"],
                cwd=root, capture_output=True, text=True, timeout=90)
            passed = completed.returncode != 0 and counts["primary"] > 0 and counts["fallback"] == 0
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(json.dumps({"result": "PASS" if passed else "FAIL",
                "gradleExit": completed.returncode, "requests": counts,
                "scope": "owned loopback HTTP build repositories; no app transport",
                "advisory": "GHSA-mqwm-5m85-gmcv"}, indent=2) + "\n")
            report.with_suffix(".log").write_text(completed.stdout + completed.stderr)
            print(report.read_text(), end="")
            return 0 if passed else 1
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()
        for thread in threads:
            thread.join(timeout=2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", required=True)
    parser.add_argument("--report", type=pathlib.Path, required=True)
    args = parser.parse_args()
    raise SystemExit(check(args.gradle, args.report))
