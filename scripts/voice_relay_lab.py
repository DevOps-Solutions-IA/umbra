"""Loopback HTTPS relay for AVD voice acceptance. No public listener or trust downgrade."""
from contextlib import contextmanager
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
import json
import time
from test_relay_integration import stop, wait_healthy
from admission_lab import AdmissionLab

ROOT = Path(__file__).resolve().parents[1]


@contextmanager
def voice_relay(diagnostics=None, *, live_diagnostics=False):
    with tempfile.TemporaryDirectory(prefix="umbra-voice-https-") as folder:
        root=Path(folder)
        cert,key=root/"ca.pem",root/"server.key"
        subprocess.run(["openssl","req","-x509","-newkey","rsa:2048","-nodes","-days","1",
                        "-subj","/CN=umbra-synthetic-voice", "-addext","subjectAltName=IP:127.0.0.1,IP:10.0.2.2",
                        "-keyout",str(key),"-out",str(cert)],check=True,timeout=30,
                       stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        key.chmod(0o600)
        sys.path.insert(0,str(ROOT/"relay"))
        from umbra_relay.app import Database
        database=Database(str(root/"relay.sqlite3"))
        invitations=[database.issue_invite() for _ in range(2)]
        admission=AdmissionLab()
        environment=dict(os.environ,UMBRA_DB=database.path,PYTHONPATH=str(ROOT/"relay"),UMBRA_ADMISSION_REALM=admission.realm.encode())
        with socket.socket(socket.AF_INET,socket.SOCK_STREAM) as listener, (root/"server.log").open("w") as log:
            listener.bind(("127.0.0.1",0)); listener.listen(16)
            port=listener.getsockname()[1]
            environment["UMBRA_ADMISSION_ORIGIN"]=f"https://10.0.2.2:{port}"
            from umbra_relay.admission_store import AdmissionStore
            admission.store=AdmissionStore(database,admission.realm.encode(),environment["UMBRA_ADMISSION_ORIGIN"])
            lifecycle=root/"http-lifecycle.json"
            server=subprocess.Popen([sys.executable,str(ROOT/"scripts/voice_relay_server.py"),
                "--fd",str(listener.fileno()),"--cert",str(cert),"--key",str(key),
                "--diagnostics",str(lifecycle),*(["--live-diagnostics"] if live_diagnostics else [])],env=environment,
                pass_fds=(listener.fileno(),),stdout=log,stderr=log)
            try:
                wait_healthy(server,f"https://127.0.0.1:{port}",ssl.create_default_context(cafile=str(cert)))
                yield {"base":f"https://10.0.2.2:{port}","certificate":cert.read_text(),"invitations":invitations,"admission":admission,"lifecycle":lifecycle}
                if server.poll() is not None:
                    raise RuntimeError("Isolated voice HTTPS relay died during acceptance")
            finally:
                scope_exit=time.monotonic_ns()
                alive=server.poll() is None
                stop(server)
                if diagnostics is not None:
                    diagnostics.parent.mkdir(parents=True,exist_ok=True)
                    value=json.loads(lifecycle.read_text()) if lifecycle.is_file() else {"available":False}
                    value["serverExit"]=server.returncode
                    value["scopeExitNanos"]=scope_exit
                    value["serverAliveAtScopeExit"]=alive
                    diagnostics.write_text(json.dumps(value,indent=2)+'\n')
