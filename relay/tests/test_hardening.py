import asyncio
import base64
import json
import os
import secrets
import sqlite3
import time
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from umbra_relay.app import Database, create_app
from umbra_relay.guard import RequestLimits, strict_json
from test_relay import register, envelope, header, put, inbox


@pytest.fixture
def app(tmp_path):
    return create_app(str(tmp_path / "hardened.sqlite3"), rate_limit=10000)


@pytest.fixture
def client(app):
    with TestClient(app) as client:
        yield client


@pytest.mark.parametrize("body", [
    b'{"id":1,"id":2}', b'{"id":1,"\\u0069d":2}', b'{"a":{"x":1,"x":2}}',
    b'{"x":NaN}', b'{"x":Infinity}', b'{"x":1e999}', b'{"x":1.5}', b'{"x":9223372036854775808}',
    b'{"x":-9223372036854775809}', b'{"x":"\\ud800"}', b'{"x":"\\udc00"}', b'{"x":"\xff"}',
    b'\xef\xbb\xbf{}', b'{"x":1,}', b'{x:1}', b"{'x':1}", b'[]', b'{}{}',
    b'{"x":' + b'[' * 14 + b'0' + b']' * 14 + b'}',
    b'{"x":[' + b'0,' * 4100 + b'0]}',
])
def test_strict_request_rejection_without_echo(client, body):
    response = client.post("/v1/boxes", content=body, headers={"Content-Type": "application/json"})
    assert response.status_code == 422
    assert response.json() == {"detail": "Invalid request schema"}
    assert response.headers["cache-control"] == "no-store"


def test_strict_parser_preserves_unicode():
    value = strict_json('{"texto":"Español 中文 🔐","n":42}'.encode())
    assert value == {"texto": "Español 中文 🔐", "n": 42}


def test_non_json_body_is_rejected(client):
    assert client.post("/v1/boxes", content=b"{}", headers={"Content-Type": "text/plain"}).status_code == 415


def test_compressed_request_is_not_decompressed(client):
    assert client.post("/v1/boxes", content=b"xx", headers={"Content-Encoding": "gzip"}).status_code == 415


def test_duplicate_auth_header_rejected(client, app):
    box = register(client, app)
    headers = [("Authorization", header(box)["Authorization"]), ("Authorization", "Bearer " + secrets.token_urlsafe(32))]
    response = client.get(f"/v1/boxes/{box['id']}/messages", headers=headers)
    assert response.status_code == 400
    assert box["read_token"] not in response.text


def test_inflated_header_is_bounded(client):
    assert client.get("/healthz", headers={"x-padding": "x" * 17000}).status_code == 431


def test_long_target_is_bounded(client):
    assert client.get("/healthz?x=" + "x" * 2100).status_code == 414


def test_populate_by_name_cannot_bypass_wire_alias(client, app):
    box = register(client, app); value = envelope(); value["sender"] = value.pop("from")
    assert put(client, box, value).status_code == 422


def test_sender_recipient_must_be_distinct(client, app):
    box = register(client, app)
    assert put(client, box, envelope(to="a" * 64)).status_code == 422


@pytest.mark.parametrize("change", [{"v": True}, {"type": "3"}, {"expires": "123"}, {"ct": "YQ"}, {"ct": "YR=="}])
def test_protocol_coercion_and_noncanonical_base64_rejected(client, app, change):
    box = register(client, app)
    assert put(client, box, envelope(**change)).status_code == 422


def test_cursors_never_reuse_deleted_sequence(client, app):
    box = register(client, app); first = envelope()
    put(client, box, first); cursor = inbox(client, box).json()["next_cursor"]
    client.delete(f"/v1/boxes/{box['id']}/messages/{first['id']}", headers=header(box))
    newer = envelope(); put(client, box, newer)
    page = client.get(f"/v1/boxes/{box['id']}/messages?after={cursor}", headers=header(box)).json()
    assert page["messages"] == [newer]
    assert page["next_cursor"] > cursor


def test_retention_limit_reserves_capacity_for_acks(client, app, monkeypatch):
    monkeypatch.setattr("umbra_relay.app.MAX_RETAINED_IDS", 3)
    box = register(client, app)
    for _ in range(3):
        value = envelope(); assert put(client, box, value).status_code == 201
        assert client.delete(f"/v1/boxes/{box['id']}/messages/{value['id']}", headers=header(box)).status_code == 204
    assert put(client, box, envelope()).status_code == 429
    assert put(client, box, value).status_code == 200  # Idempotent replay remains possible at quota.
    with app.state.database.connect(write=True) as db:
        db.execute("UPDATE acknowledged SET expires=0")
    assert put(client, box, envelope()).status_code == 201


def test_registration_capacity_does_not_consume_invitation(client, app, monkeypatch):
    monkeypatch.setattr("umbra_relay.app.MAX_BOXES", 1)
    box = register(client, app)
    candidate = dict(id=str(uuid4()), read_token=secrets.token_urlsafe(32), write_token=secrets.token_urlsafe(32), invitation=app.state.database.issue_invite())
    assert client.post("/v1/boxes", json=candidate).status_code == 503
    client.delete(f"/v1/boxes/{box['id']}", headers=header(box))
    assert client.post("/v1/boxes", json=candidate).status_code == 201


def test_database_starts_private_and_at_v3(app):
    assert os.stat(app.state.database.path).st_mode & 0o777 == 0o600
    with app.state.database.connect() as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 3
        assert db.execute("PRAGMA foreign_keys").fetchone()[0] == 1
        assert db.execute("PRAGMA synchronous").fetchone()[0] == 2


def legacy_database(path, corrupt=False):
    box, identity = str(uuid4()), str(uuid4())
    with sqlite3.connect(path) as db:
        db.executescript("""
        CREATE TABLE boxes(id TEXT PRIMARY KEY,read_hash TEXT NOT NULL,write_hash TEXT NOT NULL,created INTEGER NOT NULL);
        CREATE TABLE messages(box TEXT NOT NULL,id TEXT NOT NULL,digest TEXT NOT NULL,envelope TEXT,size INTEGER NOT NULL,expires INTEGER NOT NULL,created INTEGER NOT NULL,PRIMARY KEY(box,id));
        CREATE INDEX messages_expiry ON messages(expires);
        PRAGMA user_version=1;
        """)
        db.execute("INSERT INTO boxes VALUES (?,?,?,?)", (box, "a" * 64, "b" * 64, 1))
        db.execute("INSERT INTO messages VALUES (?,?,?,?,?,?,?)", (box, identity, "c" * 64, None if corrupt else "{}", 2, 9999999999, 1))
    return box, identity


def test_legacy_database_migration_is_preserving_and_idempotent(tmp_path):
    path = str(tmp_path / "v1.sqlite3"); box, identity = legacy_database(path)
    database = Database(path); Database(path)
    with database.connect() as db:
        row = dict(db.execute("SELECT * FROM messages").fetchone())
        assert row["box"] == box and row["id"] == identity and row["envelope"] == "{}" and row["seq"] == 1
        assert db.execute("PRAGMA user_version").fetchone()[0] == 3
        assert not db.execute("SELECT 1 FROM sqlite_master WHERE name='messages_v1'").fetchone()


def test_failed_migration_rolls_back_original_table(tmp_path):
    path = str(tmp_path / "corrupt-v1.sqlite3"); _, identity = legacy_database(path, corrupt=True)
    with pytest.raises(sqlite3.IntegrityError):
        Database(path)
    with sqlite3.connect(path) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 1
        assert "seq" not in {r[1] for r in db.execute("PRAGMA table_info(messages)")}
        assert db.execute("SELECT id FROM messages").fetchone()[0] == identity
        assert not db.execute("SELECT 1 FROM sqlite_master WHERE name='messages_v1'").fetchone()


def test_database_symlink_rejected(tmp_path):
    target = tmp_path / "target.sqlite3"; target.touch()
    link = tmp_path / "link.sqlite3"; link.symlink_to(target)
    with pytest.raises(ValueError):
        Database(str(link))


async def invoke(middleware, receive, headers=(), path=b"/v1/boxes"):
    messages = []
    scope = {"type": "http", "method": "POST", "path": path.decode(), "raw_path": path,
             "query_string": b"", "headers": list(headers), "client": ("192.0.2.10", 1234), "http_version": "1.1"}
    async def send(message): messages.append(message)
    await middleware(scope, receive, send)
    return messages


async def ok_app(scope, receive, send):
    await receive()
    await send({"type": "http.response.start", "status": 204, "headers": []})
    await send({"type": "http.response.body", "body": b""})


def test_slow_body_has_absolute_deadline_and_releases_slot():
    async def case():
        middleware = RequestLimits(ok_app, body_timeout=0.01)
        async def slow(): await asyncio.sleep(1); return {"type": "http.request", "body": b""}
        response = await invoke(middleware, slow)
        assert response[0]["status"] == 408 and middleware._active == 0
    asyncio.run(case())


def test_chunked_oversize_rejected_before_app():
    async def case():
        middleware = RequestLimits(ok_app, max_body=4)
        chunks = iter([{"type": "http.request", "body": b"abc", "more_body": True}, {"type": "http.request", "body": b"de", "more_body": False}])
        async def receive(): return next(chunks)
        result = await invoke(middleware, receive)
        assert result[0]["status"] == 413 and middleware._active == 0
    asyncio.run(case())


def test_disconnect_and_cancellation_release_slot():
    async def case():
        middleware = RequestLimits(ok_app)
        async def disconnect(): return {"type": "http.disconnect"}
        assert await invoke(middleware, disconnect) == []
        assert middleware._active == 0
        async def cancel(): raise asyncio.CancelledError()
        with pytest.raises(asyncio.CancelledError): await invoke(middleware, cancel)
        assert middleware._active == 0
    asyncio.run(case())


def test_content_length_mismatch_rejected():
    async def case():
        async def receive(): return {"type": "http.request", "body": b"{}"}
        messages = await invoke(RequestLimits(ok_app), receive, [(b"content-length", b"9"), (b"content-type", b"application/json")])
        assert messages[0]["status"] == 400
    asyncio.run(case())


def test_transfer_encoding_content_length_ambiguity_rejected():
    async def case():
        async def receive(): raise AssertionError("Should not consume ambiguous body")
        messages = await invoke(RequestLimits(ok_app), receive, [(b"content-length", b"2"), (b"transfer-encoding", b"chunked")])
        assert messages[0]["status"] == 400
    asyncio.run(case())


def test_active_request_cap_is_fail_closed():
    async def case():
        middleware = RequestLimits(ok_app, max_active=1)
        entered, release = asyncio.Event(), asyncio.Event()
        async def waiting(): entered.set(); await release.wait(); return {"type": "http.request", "body": b""}
        task = asyncio.create_task(invoke(middleware, waiting)); await entered.wait()
        async def unused(): raise AssertionError("Busy request must not be read")
        second = await invoke(middleware, unused)
        assert second[0]["status"] == 503
        release.set(); first = await task
        assert first[0]["status"] == 204 and middleware._active == 0
    asyncio.run(case())


def test_rate_cache_has_only_short_lived_digests():
    async def case():
        middleware = RequestLimits(ok_app)
        async def receive(): return {"type": "http.request", "body": b""}
        await invoke(middleware, receive)
        assert "192.0.2.10" not in repr(middleware._buckets)
        assert all(isinstance(key, bytes) and len(key) == 32 for key in middleware._buckets)
        key = next(iter(middleware._buckets)); middleware._buckets[key] = (time.monotonic() - 61, 999)
        response = await invoke(middleware, receive)
        assert response[0]["status"] == 204 and next(iter(middleware._buckets.values()))[1] == 1
    asyncio.run(case())
