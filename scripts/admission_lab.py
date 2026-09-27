"""Explicitly invoked synthetic admission administrator, kept outside application builds.

Private authority exists only in this lab process. Requests contain public keys;
approval outputs contain no private material. Never use for production enrollment.
"""
import secrets
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "relay"))


def reset_exchange(run, serial, package):
    """After verifying an owned AVD, stop the old fixture before removing its public exchange.

    A failed previous installation can leave a credential from another realm. Never
    weaken realm validation or let that file race the next request. No vault is deleted.
    """
    run(serial, "shell", "am", "force-stop", package)
    run(serial, "shell", "run-as", package, "rm", "-f", *(
        "files/synthetic-admission-" + suffix for suffix in
        ("request.json", "request.tmp", "credential.json", "credential.tmp")))


class AdmissionLab:
    def __init__(self):
        from nacl.signing import SigningKey
        from umbra_relay.admission_protocol import Realm, encode, digest
        self._authority = SigningKey.generate()
        public = encode(self._authority.verify_key.encode())
        self.realm = Realm.parse(f"umbra:realm:1:{secrets.token_urlsafe(32)}:{public}:{digest(self._authority.verify_key.encode())}")
        self._requests = set()
        self.store = None

    def approve(self, wire):
        from umbra_relay.admission_protocol import Request, Credential, body, encode, CAPABILITIES
        request = Request.parse(wire, self.realm)
        now = int(time.time())
        request.current(now)
        if request.request_id in self._requests or request.nonce in self._requests:
            raise RuntimeError("Synthetic admission request replay")
        self._requests.update((request.request_id, request.nonce))
        fields = [self.realm.realm_id, secrets.token_urlsafe(32), request.device_public_key, request.signal_public_key,
                  self.realm.authority_key_id, str(now), str(now), str(now+3600), CAPABILITIES, request.request_id]
        raw = body("credential", fields)
        credential = "umbra:admission:credential:1:" + encode(raw) + "." + encode(self._authority.sign(raw).signature)
        Credential.parse(credential, self.realm)
        if self.store is not None:
            self.store.submit(request.wire)
            self.store.publish(credential)
        return {"credential": credential}

    def revoke(self, wire):
        from umbra_relay.admission_protocol import Credential, body, encode
        credential=Credential.parse(wire,self.realm)
        fields=[self.realm.realm_id,credential.credential_id,credential.device_public_key,self.realm.authority_key_id,
                "1",str(int(time.time())),"policy"]
        raw=body("revocation",fields)
        revoked="umbra:admission:revocation:1:"+encode(raw)+"."+encode(self._authority.sign(raw).signature)
        self.store.revoke(revoked)
        return {"revocation":revoked}
