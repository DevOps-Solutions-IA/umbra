"""Synthetic delegated revocation against real SQLite, including durable tombstones."""
from concurrent.futures import ThreadPoolExecutor
import secrets
import sqlite3

import pytest
from fastapi.testclient import TestClient
from umbra_relay.app import Database, create_app
from test_relay import register, header, envelope, put, inbox


@pytest.fixture
def context(tmp_path):
    app = create_app(str(tmp_path / 'devices.sqlite3'), rate_limit=10000)
    with TestClient(app, raise_server_exceptions=False) as client:
        yield app, client, register(client, app)


def delegate(client, box, cap):
    return client.put(f"/v1/boxes/{box['id']}/revocation", headers=header(box), json={'token': cap})


def revoke(client, box, cap):
    return client.delete(f"/v1/boxes/{box['id']}/revocation", headers={'Authorization': 'Bearer ' + cap})


def test_deletion_only_capability_and_immutable_idempotent_delegation(context):
    app, client, box = context
    cap = secrets.token_urlsafe(32)
    assert delegate(client, box, cap).status_code == 201
    assert delegate(client, box, cap).status_code == 200
    assert delegate(client, box, secrets.token_urlsafe(32)).status_code == 409
    for token in (box['read_token'], box['write_token']):
        assert revoke(client, box, token).status_code == 401
    auth = {'Authorization': 'Bearer ' + cap}
    assert client.get(f"/v1/boxes/{box['id']}/messages", headers=auth).status_code == 401
    assert client.delete(f"/v1/boxes/{box['id']}", headers=auth).status_code == 401
    with app.state.database.connect() as db:
        assert cap not in str(tuple(db.execute('SELECT * FROM device_revocations').fetchone()))
    assert revoke(client, box, cap).status_code == 204
    assert revoke(client, box, cap).status_code == 204
    assert inbox(client, box).status_code == 401
    assert put(client, box, envelope()).status_code == 401
    assert delegate(client, box, cap).status_code == 401


def test_revocation_survives_restart_and_registration_cannot_resurrect(context):
    app, client, box = context
    cap = secrets.token_urlsafe(32)
    assert delegate(client, box, cap).status_code == 201
    assert revoke(client, box, cap).status_code == 204
    restarted = create_app(app.state.database.path)
    with TestClient(restarted) as other:
        assert revoke(other, box, cap).status_code == 204
        data = dict(box, invitation=restarted.state.database.issue_invite())
        assert other.post('/v1/boxes', json=data).status_code == 409
        assert inbox(other, box).status_code == 401


def test_bad_capabilities_and_isolation(context):
    app, client, box = context
    cap = secrets.token_urlsafe(32)
    assert delegate(client, box, cap).status_code == 201
    other = register(client, app)
    assert revoke(client, other, cap).status_code == 401
    endpoint = f"/v1/boxes/{other['id']}/revocation"
    for auth in ({}, header(box), header(other, 'write')):
        assert client.put(endpoint, headers=auth, json={'token': cap}).status_code == 401
    for value in ('short', '!'*43, 123):
        assert delegate(client, other, value).status_code == 422
    for key in ('read_token', 'write_token'):
        assert delegate(client, other, other[key]).status_code == 400


def test_concurrent_delegation_has_one_winner(context):
    _, client, box = context
    caps = [secrets.token_urlsafe(32) for _ in range(8)]
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda c: delegate(client, box, c).status_code, caps))
    assert results.count(201) == 1
    assert results.count(409) == 7


def test_storage_failure_rolls_back_revocation_and_deletion(context):
    app, client, box = context
    cap = secrets.token_urlsafe(32)
    assert delegate(client, box, cap).status_code == 201
    with app.state.database.connect(write=True) as db:
        db.execute("CREATE TRIGGER fail_delete BEFORE DELETE ON boxes BEGIN SELECT RAISE(ABORT, 'synthetic disk failure'); END")
    assert revoke(client, box, cap).status_code == 503
    with app.state.database.connect() as db:
        assert db.execute('SELECT revoked FROM device_revocations').fetchone()[0] == 0
    assert inbox(client, box).status_code == 200
    with app.state.database.connect(write=True) as db:
        db.execute('DROP TRIGGER fail_delete')
    assert revoke(client, box, cap).status_code == 204


def test_schema_three_migration_preserves_mailboxes(context):
    app, client, box = context
    with app.state.database.connect(write=True) as db:
        db.execute('DROP TABLE device_revocations')
        db.execute('PRAGMA user_version=3')
    migrated = Database(app.state.database.path)
    with migrated.connect() as db:
        assert db.execute('PRAGMA user_version').fetchone()[0] == 4
        assert db.execute('SELECT id FROM boxes').fetchone()[0] == box['id']
        assert db.execute('SELECT COUNT(*) FROM device_revocations').fetchone()[0] == 0
