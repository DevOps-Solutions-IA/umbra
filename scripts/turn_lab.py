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
    def __init__(self, *, alternate_port: int | None = None):
        if alternate_port not in (None,3479): raise ValueError("Only the isolated synthetic redirect endpoint is permitted")
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
            docker("network", "create", "--internal", self.name)
            self.network = True
            net = json.loads(docker("network", "inspect", self.name))[0]
            subnet = ipaddress.ip_network(net["IPAM"]["Config"][0]["Subnet"])
            if subnet.version != 4 or not subnet.is_private:
                raise RuntimeError("Expected private IPv4 TURN network")
            self.address = str(subnet.network_address + 2)
            # Deny every peer except this server's own relay allocations. No route to
            # Internet or arbitrary private peers, even with valid temporary credentials.
            config = "\n".join([
                "relay-threads=1", "realm=umbra.invalid", "server-name=umbra.invalid", "listening-port=3478",
                "listening-ip=" + self.address, "relay-ip=" + self.address,
                "min-port=49160", "max-port=49179", "use-auth-secret",
                "static-auth-secret=" + self.secret.hex(), "user-quota=4", "total-quota=8",
                "max-bps=128000", "bps-capacity=1024000", "max-allocate-lifetime=180",
                "stale-nonce=60", "no-cli", "no-tls", "no-dtls", "no-tcp-relay",
                "no-multicast-peers", "no-stun", "no-rfc5780", "no-software-attribute",
                "denied-peer-ip=0.0.0.0-255.255.255.255", "denied-peer-ip=::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
                "allowed-peer-ip=" + self.address, "log-file=/dev/null", "pidfile=/tmp/turn.pid", ""
            ])
            if self.alternate_port is not None:
                config += f"alternate-server={self.address}:{self.alternate_port}\n"
            path = Path(self.directory.name) / "turnserver.conf"
            path.write_text(config)
            path.chmod(0o644)  # parent directory is 0700; container runs as nobody.
            docker("run", "--detach", "--name", self.name, "--network", self.name,
                   "--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m",
                   "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                   "--pids-limit", "64", "--memory", "128m",
                   "--mount", f"type=bind,source={path},target=/etc/coturn/turnserver.conf,readonly",
                   self.image)
            self.container = True
            state = json.loads(docker("inspect", self.name))[0]
            if not state["State"]["Running"]:
                diagnostic = docker("logs", "--tail", "20", self.name).replace(self.secret.hex(), "[REDACTED]")
                raise RuntimeError("TURN failed to start: " + diagnostic)
            if state["NetworkSettings"]["Networks"][self.name]["IPAddress"] != self.address:
                raise RuntimeError("TURN network assignment differs: expected " + self.address + ", assigned " + state["NetworkSettings"]["Networks"][self.name]["IPAddress"] + ", status " + state["State"]["Status"])
            return self
        except BaseException:
            self.close()
            raise

    def credentials(self, lifetime: int = 180) -> dict:
        if not self.container or not 1 <= lifetime <= 180:
            raise ValueError("TURN credential lifetime must be 1..180 seconds")
        expires = int(time.time()) + lifetime
        username = str(expires) + ":" + secrets.token_hex(16)
        # coturn TURN REST API HMAC-SHA1 is a protocol credential, not media encryption.
        password = base64.b64encode(hmac.new(self.secret.hex().encode(), username.encode(), hashlib.sha1).digest()).decode()
        return {"urls": [f"turn:{self.address}:3478?transport=udp"],
                "username": username, "password": password, "expires": expires}

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
