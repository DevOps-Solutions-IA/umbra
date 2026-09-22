#!/usr/bin/env python3
"""Ephemeral private coturn. No published ports, external STUN discovery or production secrets."""
from __future__ import annotations
import base64
import hashlib
import hmac
import ipaddress
import json
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid

IMAGE = "coturn/coturn@sha256:bbefd3e1fdfdc0d58770fe01b581fd8b00d9f3a5580d00acb77cf719a6bc78e3"


def docker(*args: str) -> str:
    # Never print commands: temporary capabilities can be passed to child processes.
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError("Private TURN laboratory Docker operation failed")
    return (result.stdout + (result.stderr if args[0] == "logs" else "")).strip()


class TurnLab:
    def __init__(self, *, alternate_port: int | None = None, allocation_lifetime: int = 180, tls_mode: str | None = None, ipv6: bool = False):
        if alternate_port not in (None,3479): raise ValueError("Only the isolated synthetic redirect endpoint is permitted")
        if allocation_lifetime not in (20,180): raise ValueError("Unsupported bounded laboratory allocation lifetime")
        if tls_mode not in (None,"valid","wrong-name","expired","untrusted"): raise ValueError("Unsupported TLS laboratory case")
        self.tls_mode=tls_mode
        self.ipv6=ipv6
        self.allocation_lifetime=allocation_lifetime
        self.alternate_port=alternate_port
        self.name = "umbra-turn-" + uuid.uuid4().hex[:12]
        self.container = False
        self.network = False
        self.directory = None
        self.secret = secrets.token_bytes(32)
        self.address = None
        self.image_tag = None

    def __enter__(self):
        try:
            self.image_tag = self.name + ":lab"
            docker("build", "--tag", self.image_tag, str(Path(__file__).with_name("turn-lab")))
            self.image = docker("image", "inspect", self.image_tag, "--format", "{{.Id}}")
            self.directory = tempfile.TemporaryDirectory(prefix="umbra-turn-private-")
            network_options=["--ipv6","--subnet",f"fd42:756d:{secrets.randbelow(65536):x}:1::/64"] if self.ipv6 else []
            docker("network", "create", "--internal", *network_options,self.name)
            self.network = True
            net = json.loads(docker("network", "inspect", self.name))[0]
            subnets=[ipaddress.ip_network(row["Subnet"]) for row in net["IPAM"]["Config"]]
            subnet=next(value for value in subnets if value.version==4)
            if subnet.version != 4 or not subnet.is_private:
                raise RuntimeError("Expected private IPv4 TURN network")
            self.relay_address=str(subnet.network_address+2)
            # The pinned allocator requests IPv4 relayed addresses. IPv6 here
            # validates the AVD-to-TURN link, not an all-IPv6 relay allocation.
            self.address = str(next(value for value in subnets if value.version==6).network_address+2) if self.ipv6 else self.relay_address
            # Deny every peer except this server's own relay allocations. No route to
            # Internet or arbitrary private peers, even with valid temporary credentials.
            config = "\n".join([
                "relay-threads=1", "realm=umbra.invalid", "server-name=umbra.invalid", "listening-port=3478",
                "listening-ip=" + self.address, "relay-ip=" + self.relay_address,
                "min-port=49160", "max-port=49179", "use-auth-secret",
                "static-auth-secret=" + self.secret.hex(), "user-quota=4", "total-quota=8",
                "max-bps=128000", "bps-capacity=1024000", "max-allocate-lifetime=" + str(self.allocation_lifetime),
                "stale-nonce=60", "no-cli", "no-tls", "no-dtls", "no-tcp-relay",
                "no-multicast-peers", "no-stun", "no-rfc5780", "no-software-attribute",
                "denied-peer-ip=0.0.0.0-255.255.255.255", "denied-peer-ip=::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "allowed-peer-ip=" + self.relay_address, "log-file=/dev/null", "pidfile=/tmp/turn.pid", ""
            ])
            if self.alternate_port is not None:
                config += f"alternate-server={'['+self.address+']' if self.ipv6 else self.address}:{self.alternate_port}\n"
            tls_mounts=[]
            if self.tls_mode:
                root=Path(self.directory.name)
                self._create_tls(root)
                config=config.replace("no-tls\n", "no-udp\nno-tcp\ntls-listening-port=5349\ncert=/etc/coturn/lab.crt\npkey=/etc/coturn/lab.key\n")
                for source,target in (("server.crt","lab.crt"),("server.key","lab.key")):
                    file=root/source;file.chmod(0o644)  # Private 0700 host directory; individual read-only mounts for nobody.
                    tls_mounts.extend(["--mount",f"type=bind,source={file},target=/etc/coturn/{target},readonly"])
            path = Path(self.directory.name) / "turnserver.conf"
            path.write_text(config)
            path.chmod(0o644)  # parent directory is 0700; container runs as nobody.
            docker("run", "--detach", "--name", self.name, "--network", self.name,
                   "--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m",
                   "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                   "--pids-limit", "64", "--memory", "128m",
                   "--mount", f"type=bind,source={path},target=/etc/coturn/turnserver.conf,readonly",
                   *tls_mounts,self.image)
            self.container = True
            state = json.loads(docker("inspect", self.name))[0]
            if not state["State"]["Running"]:
                diagnostic = docker("logs", "--tail", "20", self.name).replace(self.secret.hex(), "[REDACTED]")
                raise RuntimeError("TURN failed to start: " + diagnostic)
            if state["NetworkSettings"]["Networks"][self.name]["GlobalIPv6Address" if self.ipv6 else "IPAddress"] != self.address:
                raise RuntimeError("TURN network assignment differs: expected " + self.address + ", assigned " + state["NetworkSettings"]["Networks"][self.name]["IPAddress"] + ", status " + state["State"]["Status"])
            return self
        except BaseException:
            self.close()
            raise

    def _create_tls(self, root: Path) -> None:
        def openssl(*args):
            result=subprocess.run(["openssl",*args],cwd=root,capture_output=True,timeout=30)
            if result.returncode: raise RuntimeError("Synthetic TURN TLS certificate generation failed")
        openssl("req","-x509","-newkey","rsa:2048","-nodes","-days","1","-subj","/CN=umbra-synthetic-turn-root",
                "-addext","basicConstraints=critical,CA:TRUE","-keyout","ca.key","-out","ca.crt")
        openssl("req","-new","-newkey","rsa:2048","-nodes","-subj","/CN=umbra-synthetic-turn",
                "-keyout","server.key","-out","server.csr")
        hostname="umbra-turn.invalid" if self.tls_mode!="wrong-name" else "wrong-turn.invalid"
        (root/"extensions.cnf").write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectAltName=DNS:"+hostname+"\n")
        if self.tls_mode=="expired":
            (root/"index").write_text("");(root/"serial").write_text("01\n")
            (root/"expiry.cnf").write_text("[ca]\ndefault_ca=local\n[local]\nnew_certs_dir=.\ndatabase=index\nserial=serial\ncertificate=ca.crt\nprivate_key=ca.key\ndefault_md=sha256\npolicy=subject\n[subject]\ncommonName=supplied\n[v3_server]\n"+(root/"extensions.cnf").read_text())
            openssl("ca","-batch","-config","expiry.cnf","-startdate","20000101000000Z","-enddate","20000102000000Z",
                    "-in","server.csr","-out","server.crt","-notext","-extensions","v3_server")
        else:
            openssl("x509","-req","-in","server.csr","-CA","ca.crt","-CAkey","ca.key","-set_serial","1",
                    "-days","1","-extfile","extensions.cnf","-out","server.crt")
        self.ca=(root/"ca.crt").read_text()
        if self.tls_mode=="untrusted":
            openssl("req","-x509","-newkey","rsa:2048","-nodes","-days","1","-subj","/CN=umbra-other-synthetic-root",
                    "-addext","basicConstraints=critical,CA:TRUE","-keyout","other.key","-out","other.crt")
            self.ca=(root/"other.crt").read_text()
        for key in root.glob("*.key"): key.chmod(0o600)

    def allocation_count(self) -> int:
        # Only this isolated network namespace, no process credentials or traffic bodies.
        table=docker("exec",self.name,"cat","/proc/net/udp")
        return sum(49160 <= int(row.split()[1].split(":")[1],16) <= 49179
                   for row in table.splitlines()[1:] if row.strip())

    def credentials(self, lifetime: int = 180) -> dict:
        if not self.container or not 1 <= lifetime <= 180:
            raise ValueError("TURN credential lifetime must be 1..180 seconds")
        expires = int(time.time()) + lifetime
        username = str(expires) + ":" + secrets.token_hex(16)
        # coturn TURN REST API HMAC-SHA1 is a protocol credential, not media encryption.
        password = base64.b64encode(hmac.new(self.secret.hex().encode(), username.encode(), hashlib.sha1).digest()).decode()
        endpoint='['+self.address+']' if self.ipv6 else self.address
        result={"urls": [f"turns:{endpoint}:5349?transport=tcp" if self.tls_mode else f"turn:{endpoint}:3478?transport=udp"],
                "username": username, "password": password, "expires": expires}
        if self.ipv6: result["relayAddress"]=self.relay_address
        if self.tls_mode: result.update(certificate=self.ca,hostname="umbra-turn.invalid")
        return result

    def close(self):
        errors = []
        if self.network:
            # Also catches a daemon failure after Docker created the container but
            # before its run response reached us. Only the exact unique name is owned.
            try:
                exists = docker("ps", "-a", "--filter", "name=^/" + self.name + "$", "--format", "{{.Names}}")
                if exists == self.name: docker("rm", "--force", self.name)
            except Exception as exc: errors.append(exc)
            self.container = False
        if self.network:
            try: docker("network", "rm", self.name)
            except Exception as exc: errors.append(exc)
            self.network = False
        if self.image_tag:
            try:
                exists = docker("image", "ls", "--filter", "reference=" + self.image_tag, "--format", "{{.Repository}}:{{.Tag}}")
                if exists == self.image_tag: docker("image", "rm", self.image_tag)
            except Exception as exc: errors.append(exc)
            self.image_tag = None
        self.secret = b""
        if self.directory:
            self.directory.cleanup()
            self.directory = None
        if errors:
            raise RuntimeError("Private TURN cleanup failed") from errors[0]

    def __exit__(self, *args):
        self.close()
