"""Real SQLite transactions and synthetic API data, no production service."""
import base64
from contextlib import contextmanager
import sqlite3

from fastapi.testclient import TestClient
from umbra_relay.app import create_app
from test_relay import envelope, header, inbox, put, register


def test_queue_ack_and_revocation_survive_restart(tmp_path):
    path = str(tmp_path / "restart.sqlite3")
    first = create_app(path)
    with TestClient(first) as client:
        box = register(client, first)
        message = envelope()
        assert put(client, box, message).status_code == 201
    with TestClient(create_app(path)) as client:
        assert inbox(client, box).json()["messages"] == [message]
        assert put(client, box, message).status_code == 200
        assert client.delete(f"/v1/boxes/{box['id']}/messages/{message['id']}", headers=header(box)).status_code == 204
    with TestClient(create_app(path)) as client:
        assert put(client, box, message).status_code == 200
        assert inbox(client, box).json()["messages"] == []
        assert client.delete(f"/v1/boxes/{box['id']}", headers=header(box)).status_code == 204
    with TestClient(create_app(path)) as client:
        assert inbox(client, box).status_code == 401
        assert put(client, box, message).status_code == 401


def test_sqlite_full_returns_opaque_retryable_error_without_accepting_message(tmp_path, monkeypatch):
    app = create_app(str(tmp_path / "full.sqlite3"))
    with TestClient(app, raise_server_exceptions=False) as client:
        box = register(client, app)
        database = app.state.database
        original = database.connect
        errors = []

        @contextmanager
        def limited_storage(write=False):
            with original(write=write) as conn:
                pages = conn.execute("PRAGMA page_count").fetchone()[0]
                conn.execute(f"PRAGMA max_page_count={pages}")
                try:
                    yield conn
                except sqlite3.OperationalError as exc:
                    errors.append(exc.sqlite_errorcode)
                    raise

        monkeypatch.setattr(database, "connect", limited_storage)
        message = envelope(ct=base64.b64encode(b"synthetic" * 50_000).decode())
        response = put(client, box, message)
        assert errors == [sqlite3.SQLITE_FULL]
        assert response.status_code == 503
        assert response.json() == {"detail": "Storage temporarily unavailable"}
        assert response.headers["retry-after"] == "60"
        assert response.headers["cache-control"] == "no-store"
        assert client.get("/healthz").status_code == 503
        monkeypatch.setattr(database, "connect", original)
        assert inbox(client, box).json()["messages"] == []
        assert put(client, box, message).status_code == 201


def test_ack_failure_preserves_pending_message_and_rolls_back_tombstone(tmp_path, caplog):
    app = create_app(str(tmp_path / "ack.sqlite3"))
    with TestClient(app, raise_server_exceptions=False) as client:
        box = register(client, app)
        message = envelope()
        assert put(client, box, message).status_code == 201
        with app.state.database.connect(write=True) as conn:
            conn.execute("""CREATE TRIGGER fail_delete BEFORE DELETE ON messages BEGIN
                         SELECT RAISE(ABORT, 'synthetic private storage diagnostic'); END""")
        response = client.delete(f"/v1/boxes/{box['id']}/messages/{message['id']}", headers=header(box))
        assert response.status_code == 503
        assert response.json() == {"detail": "Storage temporarily unavailable"}
        storage_records = [r for r in caplog.records if r.name == "umbra_relay.storage"]
        assert len(storage_records) == 1
        assert storage_records[0].getMessage() == f"Relay storage operation failed (SQLite code {sqlite3.SQLITE_CONSTRAINT_TRIGGER})"
        assert storage_records[0].exc_info is None
        assert "synthetic private storage diagnostic" not in caplog.text
        assert box["read_token"] not in caplog.text
        with app.state.database.connect() as conn:
            assert conn.execute("SELECT COUNT(*) FROM acknowledged").fetchone()[0] == 0
        assert inbox(client, box).json()["messages"] == [message]
        with app.state.database.connect(write=True) as conn:
            conn.execute("DROP TRIGGER fail_delete")
        assert client.delete(f"/v1/boxes/{box['id']}/messages/{message['id']}", headers=header(box)).status_code == 204
        assert inbox(client, box).json()["messages"] == []


def test_registration_storage_failure_does_not_consume_invitation(tmp_path):
    import secrets
    from uuid import uuid4

    app = create_app(str(tmp_path / "register.sqlite3"))
    with TestClient(app, raise_server_exceptions=False) as client:
        candidate = dict(id=str(uuid4()), read_token=secrets.token_urlsafe(32),
                         write_token=secrets.token_urlsafe(32),
                         invitation=app.state.database.issue_invite())
        with app.state.database.connect(write=True) as conn:
            conn.execute("""CREATE TRIGGER fail_register BEFORE INSERT ON boxes BEGIN
                         SELECT RAISE(ABORT, 'synthetic private storage diagnostic'); END""")
        response = client.post("/v1/boxes", json=candidate)
        assert response.status_code == 503
        assert response.json() == {"detail": "Storage temporarily unavailable"}
        with app.state.database.connect(write=True) as conn:
            assert conn.execute("SELECT COUNT(*) FROM invites").fetchone()[0] == 1
            assert conn.execute("SELECT COUNT(*) FROM boxes").fetchone()[0] == 0
            conn.execute("DROP TRIGGER fail_register")
        assert client.post("/v1/boxes", json=candidate).status_code == 201
