"""Real HTTP application and SQLite; synthetic Ed25519 administrator/device only."""
import secrets
import time
from uuid import uuid4
import pytest
from fastapi.testclient import TestClient
from nacl.signing import SigningKey
from umbra_relay.app import create_app
from umbra_relay.admission_protocol import encode, digest, CAPABILITIES, Challenge
from umbra_relay.admission_http import operation_hash
from test_admission_protocol import sign


@pytest.fixture
def admission(tmp_path):
    authority, device = SigningKey.generate(), SigningKey.generate()
    pub = encode(authority.verify_key.encode())
    realm_id = secrets.token_urlsafe(32)
    issuer = digest(authority.verify_key.encode())
    realm = f"umbra:realm:1:{realm_id}:{pub}:{issuer}"
    app = create_app(str(tmp_path / "relay.sqlite3"), realm_config=realm, verifier_origin="https://relay.test", rate_limit=10000)
    now = int(time.time())
    device_pub = encode(device.verify_key.encode())
    signal_pub = encode(b"\x05" + secrets.token_bytes(32))
    request_id, credential_id = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    request = sign("request", device, [realm_id, request_id, device_pub, signal_pub, str(now), str(now+600), secrets.token_urlsafe(32)])
    credential = sign("credential", authority, [realm_id, credential_id, device_pub, signal_pub, issuer,
                                               str(now), str(now), str(now+3600), CAPABILITIES, request_id])
    revoke = sign("revocation", authority, [realm_id, credential_id, device_pub, issuer, "1", str(now), "policy"])
    with TestClient(app) as client:
        yield app, client, device, request, credential, revoke


def authenticate(client, key, credential, request):
    operation = operation_hash(request.method, request.url.raw_path, request.content,
                               request.headers.get("authorization", "").encode("ascii"))
    response = client.post("/v1/admission/challenges", json={"credential": credential, "operation": operation})
    assert response.status_code == 200, response.text
    challenge = Challenge.parse(response.json()["challenge"])
    request.headers.update({"X-Umbra-Credential": credential, "X-Umbra-Challenge": challenge.encode(),
                            "X-Umbra-Proof": sign("proof", key, challenge.fields)})
    return request


@pytest.mark.parametrize("method,path", [
    ("POST", "/v1/boxes"), ("GET", "/v1/boxes/test/messages"), ("PUT", "/v1/boxes/test/messages/test"),
    ("DELETE", "/v1/boxes/test/messages/test"), ("DELETE", "/v1/boxes/test"),
    ("POST", "/v1/pairing-invites/test/claim"), ("DELETE", "/v1/pairing-invites/test"),
    ("PUT", "/v1/boxes/test/revocation"), ("POST", "/v1/turn/credentials"),
    ("POST", "/v1/future-private-operation"),
])
def test_unadmitted_rejected_even_for_future_private_routes(admission, method, path):
    _, client, _, _, _, _ = admission
    assert client.request(method, path).status_code == 403


def test_unconfigured_is_closed(tmp_path):
    with TestClient(create_app(str(tmp_path / "unconfigured.sqlite3"))) as client:
        assert client.get("/healthz").status_code == 200
        assert client.get("/v1/admission/realm").status_code == 503
        assert client.post("/v1/boxes", json={}).status_code == 403


def test_real_provisioning_possession_replay_and_revocation(admission):
    app, client, device, request, credential, revoke = admission
    assert client.post("/v1/admission/requests", json={"wire": request}).status_code == 201
    assert client.post("/v1/admission/requests", json={"wire": request}).status_code == 403
    assert client.post("/v1/admission/credentials", json={"wire": credential}).status_code == 201
    box = {"id": str(uuid4()), "read_token": secrets.token_urlsafe(32), "write_token": secrets.token_urlsafe(32),
           "invitation": app.state.database.issue_invite()}
    target = client.build_request("POST", "/v1/boxes", json=box)
    assert client.send(target).status_code == 403
    copied = authenticate(client, SigningKey.generate(), credential, client.build_request("POST", "/v1/boxes", json=box))
    assert client.send(copied).status_code == 403
    authorized = authenticate(client, device, credential, target)
    assert client.send(authorized).status_code == 201
    assert client.send(authorized).status_code == 403  # exact proof replay, even for idempotent mailbox registration
    url = f"/v1/boxes/{box['id']}/messages"
    read = client.build_request("GET", url, headers={"Authorization": "Bearer " + box["read_token"]})
    assert client.send(authenticate(client, device, credential, read)).status_code == 200
    pending = authenticate(client, device, credential, client.build_request("GET", url, headers={"Authorization": "Bearer " + box["read_token"]}))
    assert client.post("/v1/admission/revocations", json={"wire": revoke}).status_code == 201
    assert client.send(pending).status_code == 403
    assert client.post("/v1/admission/challenges", json={"credential": credential, "operation": digest(b"new")}).status_code == 403
    with app.state.database.connect() as conn:
        assert conn.execute("SELECT count(*) FROM boxes").fetchone()[0] == 1  # revocation does not delete history


def test_capabilities_and_body_are_bound_not_replaced(admission):
    app, client, device, request, credential, _ = admission
    client.post("/v1/admission/requests", json={"wire": request})
    client.post("/v1/admission/credentials", json={"wire": credential})
    target = authenticate(client, device, credential, client.build_request("DELETE", "/v1/boxes/test", headers={"Authorization": "Bearer " + secrets.token_urlsafe(32)}))
    target.headers["Authorization"] = "Bearer " + secrets.token_urlsafe(32)
    assert client.send(target).status_code == 403
    invalid_capability = authenticate(client, device, credential, client.build_request("DELETE", "/v1/boxes/test"))
    assert client.send(invalid_capability).status_code == 401


def test_request_result_needs_own_key_and_rejection_is_signed(admission):
    _, client, device, request, credential, _ = admission
    assert client.post("/v1/admission/requests", json={"wire": request}).status_code == 201
    challenge_response = client.post("/v1/admission/result-challenge", json={"wire": request})
    assert challenge_response.status_code == 200
    challenge = Challenge.parse(challenge_response.json()["challenge"])
    data = {"wire": request, "challenge": challenge.encode(), "proof": sign("request-proof", SigningKey.generate(), challenge.fields)}
    assert client.post("/v1/admission/result", json=data).status_code == 403
    data["proof"] = sign("request-proof", device, challenge.fields)
    assert client.post("/v1/admission/result", json=data).json() == {"state": "REQUEST_PENDING"}
    assert client.post("/v1/admission/result", json=data).status_code == 403
    assert client.post("/v1/admission/credentials", json={"wire": credential}).status_code == 201
    fresh = Challenge.parse(client.post("/v1/admission/result-challenge", json={"wire": request}).json()["challenge"])
    data.update(challenge=fresh.encode(), proof=sign("request-proof", device, fresh.fields))
    result = client.post("/v1/admission/result", json=data)
    assert result.json() == {"state": "APPROVED", "credential": credential}


def test_pooled_challenge_proofs_bind_operation_and_remain_one_use(admission):
    app, client, device, request, credential, _ = admission
    client.post("/v1/admission/requests", json={"wire": request})
    client.post("/v1/admission/credentials", json={"wire": credential})
    response = client.post("/v1/admission/challenge-batch", json={"wire": credential})
    assert response.status_code == 200
    assert len(response.json()["challenges"]) == 8
    issued = Challenge.parse(response.json()["challenges"][0])
    target = client.build_request("GET", "/v1/boxes/unknown/messages")
    operation = operation_hash(target.method, target.url.raw_path, target.content, b"")
    fields = list(issued.fields); fields[5] = operation
    bound = Challenge(tuple(fields))
    target.headers.update({"X-Umbra-Credential": credential, "X-Umbra-Challenge": bound.encode(),
                           "X-Umbra-Proof": sign("proof", device, bound.fields)})
    assert client.send(target).status_code == 401  # Passed admission, missing mailbox capability.
    assert client.send(target).status_code == 403
