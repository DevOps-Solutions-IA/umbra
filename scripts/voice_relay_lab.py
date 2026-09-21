"""Loopback HTTPS relay for AVD voice acceptance. No public listener or trust downgrade."""
from contextlib import contextmanager
import os
from pathlib import Path
import socket
import ssl
import subprocess
import sys
import tempfile
from test_relay_integration import stop, wait_healthy

ROOT = Path(__file__).resolve().parents[1]


@contextmanager
def voice_relay():
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
        environment=dict(os.environ,UMBRA_DB=database.path,PYTHONPATH=str(ROOT/"relay"))
        with socket.socket(socket.AF_INET,socket.SOCK_STREAM) as listener, (root/"server.log").open("w") as log:
            listener.bind(("127.0.0.1",0)); listener.listen(16)
            port=listener.getsockname()[1]
            server=subprocess.Popen([sys.executable,"-m","uvicorn","umbra_relay.app:create_app","--factory",
                "--fd",str(listener.fileno()),"--workers","1","--ssl-certfile",str(cert),"--ssl-keyfile",str(key),
                "--no-access-log","--no-proxy-headers","--log-level","warning"],env=environment,
                pass_fds=(listener.fileno(),),stdout=log,stderr=log)
            try:
                wait_healthy(server,f"https://127.0.0.1:{port}",ssl.create_default_context(cafile=str(cert)))
                yield {"base":f"https://10.0.2.2:{port}","certificate":cert.read_text(),"invitations":invitations}
                if server.poll() is not None:
                    raise RuntimeError("Isolated voice HTTPS relay died during acceptance")
            finally:
                stop(server)
