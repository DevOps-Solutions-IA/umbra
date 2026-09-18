import base64
import secrets
import time
from uuid import uuid4
import pytest
from fastapi.testclient import TestClient
from umbra_relay.app import create_app, MAX_MESSAGES, MAX_TTL, MAX_BODY


@pytest.fixture
def app(tmp_path):
    return create_app(str(tmp_path / "relay.sqlite3"), rate_limit=10000)


@pytest.fixture
def client(app):
    with TestClient(app) as test:
        yield test


def register(client, app, **overrides):
    values = dict(id=str(uuid4()), read_token=secrets.token_urlsafe(32),
                  write_token=secrets.token_urlsafe(32), invitation=app.state.database.issue_invite())
    values.update(overrides)
    result = client.post("/v1/boxes", json=values)
    assert result.status_code == 201, result.text
    return values


def header(box, mode="read"):
    return {"Authorization": f"Bearer {box[mode + '_token']}"}


def envelope(**overrides):
    values = {"v": 1, "id": str(uuid4()), "from": "a" * 64, "to": "b" * 64,
              "type": 3, "ct": base64.b64encode(secrets.token_bytes(64)).decode(),
              "expires": int(time.time()) + 3600}
    values.update(overrides)
    return values


def put(client, box, message, mode="write"):
    return client.put(f"/v1/boxes/{box['id']}/messages/{message['id']}",
                      headers=header(box, mode), json=message)


def inbox(client, box, mode="read"):
    return client.get(f"/v1/boxes/{box['id']}/messages", headers=header(box, mode))


def test_roundtrip_requires_ack(client, app):
    box = register(client, app)
    message = envelope()
    assert put(client, box, message).status_code == 201
    assert inbox(client, box).json()["messages"] == [message]
    assert inbox(client, box).json()["messages"] == [message]
    assert client.delete(f"/v1/boxes/{box['id']}/messages/{message['id']}", headers=header(box)).status_code == 204
    assert inbox(client, box).json()["messages"] == []


def test_invitation_is_one_use(client, app):
    box = register(client, app)
    values = dict(box, id=str(uuid4()))
    assert client.post("/v1/boxes", json=values).status_code == 403


def test_registration_retry_is_idempotent(client, app):
    box = register(client, app)
    assert client.post("/v1/boxes", json=box).status_code == 200


def test_registration_cannot_overwrite_credentials(client, app):
    box = register(client, app)
    assert client.post("/v1/boxes", json=dict(box, read_token=secrets.token_urlsafe(32))).status_code == 409


def test_missing_invitation(client):
    values = dict(id=str(uuid4()), read_token=secrets.token_urlsafe(32),
                  write_token=secrets.token_urlsafe(32), invitation=secrets.token_urlsafe(32))
    assert client.post("/v1/boxes", json=values).status_code == 403


def test_expired_invitation(client, app):
    invite = app.state.database.issue_invite()
    with app.state.database.connect(write=True) as conn:
        conn.execute("UPDATE invites SET expires=0")
    values = dict(id=str(uuid4()), read_token=secrets.token_urlsafe(32),
                  write_token=secrets.token_urlsafe(32), invitation=invite)
    assert client.post("/v1/boxes", json=values).status_code == 403


def test_write_capability_cannot_read_or_delete(client, app):
    box = register(client, app)
    assert inbox(client, box, "write").status_code == 401
    assert client.delete(f"/v1/boxes/{box['id']}", headers=header(box, "write")).status_code == 401


def test_read_capability_cannot_write(client, app):
    box = register(client, app)
    assert put(client, box, envelope(), "read").status_code == 401


def test_no_capability(client, app):
    box = register(client, app)
    assert client.get(f"/v1/boxes/{box['id']}/messages").status_code == 401


def test_wrong_mailbox_capability(client, app):
    a, b = register(client, app), register(client, app)
    assert client.get(f"/v1/boxes/{a['id']}/messages", headers=header(b)).status_code == 401


def test_idempotent_put(client, app):
    box, message = register(client, app), envelope()
    assert put(client, box, message).status_code == 201
    assert put(client, box, message).status_code == 200
    assert len(inbox(client, box).json()["messages"]) == 1


def test_collision_is_not_overwritten(client, app):
    box, message = register(client, app), envelope()
    put(client, box, message)
    changed = dict(message, ct=base64.b64encode(b"changed" * 10).decode())
    assert put(client, box, changed).status_code == 409
    assert inbox(client, box).json()["messages"] == [message]


def test_acknowledged_retry_does_not_requeue(client, app):
    box, message = register(client, app), envelope()
    put(client, box, message)
    client.delete(f"/v1/boxes/{box['id']}/messages/{message['id']}", headers=header(box))
    assert put(client, box, message).status_code == 200
    assert inbox(client, box).json()["messages"] == []


@pytest.mark.parametrize("ttl", [-1, MAX_TTL + 3600])
def test_invalid_ttl(client, app, ttl):
    box = register(client, app)
    assert put(client, box, envelope(expires=int(time.time()) + ttl)).status_code == 400


@pytest.mark.parametrize("changes", [{"v": 2}, {"type": 1}, {"ct": "this is plaintext"},
                                      {"from": "anonymous"}, {"to": "invalid"}, {"extra": 1}])
def test_invalid_envelopes(client, app, changes):
    box = register(client, app)
    assert put(client, box, envelope(**changes)).status_code == 422


def test_expired_messages_are_removed(client, app):
    box, message = register(client, app), envelope()
    put(client, box, message)
    with app.state.database.connect(write=True) as conn:
        conn.execute("UPDATE messages SET expires=0")
    assert inbox(client, box).json()["messages"] == []


def test_quota(client, app):
    box = register(client, app)
    for _ in range(MAX_MESSAGES):
        assert put(client, box, envelope()).status_code == 201
    assert put(client, box, envelope()).status_code == 429


def test_body_limit(client):
    response = client.post("/v1/boxes", content=b"x" * (MAX_BODY + 1))
    assert response.status_code == 413


def test_rate_limit(tmp_path):
    with TestClient(create_app(str(tmp_path / "rate.sqlite3"), rate_limit=2)) as c:
        assert c.get("/healthz").status_code == 200
        assert c.get("/healthz").status_code == 200
        assert c.get("/healthz").status_code == 429


def test_delete_account_cascades(client, app):
    box = register(client, app)
    put(client, box, envelope())
    assert client.delete(f"/v1/boxes/{box['id']}", headers=header(box)).status_code == 204
    assert inbox(client, box).status_code == 401
    with app.state.database.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM messages").fetchone()[0] == 0


def test_errors_do_not_echo_secrets(client):
    secret = "private-secret-that-must-not-appear"
    result = client.post("/v1/boxes", json={"read_token": secret})
    assert result.status_code == 422
    assert secret not in result.text


def test_no_debug_docs(client):
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404
    assert client.get("/healthz").headers["cache-control"] == "no-store"


def test_database_contains_only_hashes_of_capabilities(client, app):
    box = register(client, app)
    with app.state.database.connect() as conn:
        row = dict(conn.execute("SELECT * FROM boxes").fetchone())
    assert box["read_token"] not in str(row)
    assert box["write_token"] not in str(row)


def test_invitation_consumption_is_atomic(client, app):
    from concurrent.futures import ThreadPoolExecutor
    invitation = app.state.database.issue_invite()
    def attempt(_):
        data = dict(id=str(uuid4()), read_token=secrets.token_urlsafe(32),
                    write_token=secrets.token_urlsafe(32), invitation=invitation)
        return client.post("/v1/boxes", json=data).status_code
    with ThreadPoolExecutor(max_workers=5) as pool:
        statuses = list(pool.map(attempt, range(5)))
    assert statuses.count(201) == 1
    assert statuses.count(403) == 4


def test_pagination_survives_acknowledging_previous_page(client, app):
    box = register(client, app)
    for _ in range(6):
        assert put(client, box, envelope()).status_code == 201
    first = inbox(client, box).json()
    assert len(first["messages"]) == 5 and first["more"]
    for message in first["messages"]:
        client.delete(f"/v1/boxes/{box['id']}/messages/{message['id']}", headers=header(box))
    remaining = client.get(f"/v1/boxes/{box['id']}/messages?after={first['next_cursor']}", headers=header(box)).json()
    assert len(remaining["messages"]) == 1 and not remaining["more"]


def test_separate_read_write_tokens_required(client, app):
    token = secrets.token_urlsafe(32)
    data = dict(id=str(uuid4()), read_token=token, write_token=token, invitation=app.state.database.issue_invite())
    assert client.post("/v1/boxes", json=data).status_code == 400
    data["write_token"] = secrets.token_urlsafe(32)
    assert client.post("/v1/boxes", json=data).status_code == 201


def test_cursor_does_not_overflow_sqlite(client, app):
    box = register(client, app)
    result = client.get(f"/v1/boxes/{box['id']}/messages?after={10**100}", headers=header(box))
    assert result.status_code == 422
