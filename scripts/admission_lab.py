"""Explicitly invoked synthetic admission administrator, kept outside application builds.

Private authority exists only in this lab process. Requests contain public keys;
approval outputs contain no private material. Never use for production enrollment.
"""
import secrets
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "relay"))


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
