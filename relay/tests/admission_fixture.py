"""Explicit synthetic membership prerequisite for existing capability tests.

Signs every private request with a real Ed25519 key and consumes a real SQLite
challenge. Does not disable the production admission gate. Admission-specific
HTTP tests use the ordinary TestClient and exercise public provisioning routes.
"""
import secrets
import time
from fastapi.testclient import TestClient as BaseClient
from nacl.signing import SigningKey
from umbra_relay.app import create_app as production_app
from umbra_relay.admission_protocol import encode, digest, CAPABILITIES
from umbra_relay.admission_http import PUBLIC, operation_hash
from test_admission_protocol import sign

_realms = {}


def create_app(path, **kwargs):
    # Test-only key persistence across app object recreation, never serialized to relay storage.
    if path not in _realms:
        authority = SigningKey.generate()
        public = encode(authority.verify_key.encode())
        _realms[path] = (authority, f"umbra:realm:1:{secrets.token_urlsafe(32)}:{public}:{digest(authority.verify_key.encode())}")
    authority, realm_wire = _realms[path]
    app = production_app(path, realm_config=realm_wire, verifier_origin="https://relay.test", **kwargs)
    store = app.state.admission
    device = SigningKey.generate()
    public = encode(device.verify_key.encode())
    signal = encode(b"\x05" + secrets.token_bytes(32))
    now = int(time.time())
    request_id = secrets.token_urlsafe(32)
    store.submit(sign("request", device, [store.realm.realm_id, request_id, public, signal,
                                        str(now), str(now+600), secrets.token_urlsafe(32)]))
    credential = sign("credential", authority, [store.realm.realm_id, secrets.token_urlsafe(32), public, signal,
                                               store.realm.authority_key_id, str(now), str(now), str(now+3600), CAPABILITIES, request_id])
    store.publish(credential)
    app.state.synthetic_admission = device, credential
    return app


class TestClient(BaseClient):
    __test__ = False

    def send(self, request, **kwargs):
        if (request.method, request.url.path) not in PUBLIC:
            device, credential = self.app.state.synthetic_admission
            operation = operation_hash(request.method, request.url.raw_path, request.content,
                                       request.headers.get("authorization", "").encode("ascii"))
            challenge = self.app.state.admission.challenge(credential, operation)
            request.headers.update({"X-Umbra-Credential": credential, "X-Umbra-Challenge": challenge.encode(),
                                    "X-Umbra-Proof": sign("proof", device, challenge.fields)})
        return super().send(request, **kwargs)
