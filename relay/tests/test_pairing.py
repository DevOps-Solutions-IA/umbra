"""Synthetic one-use rendezvous capabilities against real SQLite transactions."""
from concurrent.futures import ThreadPoolExecutor
import secrets
import sqlite3
import time

import pytest
from fastapi.testclient import TestClient
from umbra_relay.app import Database, create_app
from test_relay import register, header, envelope, put, inbox


@pytest.fixture
def context(tmp_path):
    app = create_app(str(tmp_path / 'pairing.sqlite3'), rate_limit=10000)
    with TestClient(app, raise_server_exceptions=False) as client:
        yield app, client, register(client, app)


def invitation():
    return dict(id=secrets.token_urlsafe(32), consume_token=secrets.token_urlsafe(32),
                revoke_token=secrets.token_urlsafe(32), expires=int(time.time()) + 3600)


def create(client, box, data):
    return client.post(f"/v1/boxes/{box['id']}/pairing-invites", headers=header(box), json=data)


def claim(client, data, digest='a' * 64, token=None):
    return client.post(f"/v1/pairing-invites/{data['id']}/claim",
                       headers={'Authorization': 'Bearer ' + (token or data['consume_token'])},
                       json={'request_hash': digest})


def revoke(client, data, token=None):
    return client.delete(f"/v1/pairing-invites/{data['id']}",
                         headers={'Authorization': 'Bearer ' + (token or data['revoke_token'])})


def test_claim_retry_revocation_and_hash_only_storage(context):
    app, client, box = context
    data = invitation()
    assert create(client, box, data).status_code == 201
    assert create(client, box, data).status_code == 200
    assert claim(client, data).json() == {'claimed': True}
    assert claim(client, data).status_code == 200
    assert claim(client, data, 'b' * 64).status_code == 409
    with app.state.database.connect() as db:
        stored = str(tuple(db.execute('SELECT * FROM pairing_invites').fetchone()))
        assert all(data[k] not in stored for k in ('id', 'consume_token', 'revoke_token'))
        assert 'a' * 64 not in stored
    assert revoke(client, data).status_code == 204
    assert revoke(client, data).status_code == 204
    assert claim(client, data).status_code == 403
    assert create(client, box, data).status_code == 403


def test_concurrent_claim_has_exactly_one_winner(context):
    _, client, box = context
    data = invitation()
    assert create(client, box, data).status_code == 201
    digests = [f'{i:064x}' for i in range(8)]
    with ThreadPoolExecutor(max_workers=8) as workers:
        statuses = list(workers.map(lambda digest: claim(client, data, digest).status_code, digests))
    assert statuses.count(200) == 1
    assert statuses.count(409) == 7
    assert claim(client, data, digests[statuses.index(200)]).status_code == 200


def test_capabilities_and_box_isolation(context):
    app, client, box = context
    data = invitation()
    other = register(client, app)
    endpoint = f"/v1/boxes/{box['id']}/pairing-invites"
    for auth in ({}, header(box, 'write'), header(other)):
        assert client.post(endpoint, headers=auth, json=data).status_code == 401
    assert create(client, box, data).status_code == 201
    assert create(client, other, data).status_code == 409
    assert claim(client, data, token=data['revoke_token']).status_code == 403
    assert revoke(client, data, token=data['consume_token']).status_code == 403
    assert client.get(f"/v1/pairing-invites/{data['id']}").status_code == 405
    assert client.delete(f"/v1/boxes/{box['id']}", headers=header(box)).status_code == 204
    assert claim(client, data).status_code == 403


@pytest.mark.parametrize('changes', [{'expires': True}, {'expires': '1'}, {'id': 'bad'}, {'extra': 'secret'}])
def test_strict_schema(context, changes):
    _, client, box = context
    data = invitation(); data.update(changes)
    assert create(client, box, data).status_code == 422


@pytest.mark.parametrize('ttl', [0, 30, 86410])
def test_expiry_bounds(context, ttl):
    _, client, box = context
    data = invitation(); data['expires'] = int(time.time()) + ttl
    assert create(client, box, data).status_code == 400


def test_expiry_rejects_retry_and_purge_removes_record(context, monkeypatch):
    app, client, box = context
    data = invitation()
    assert create(client, box, data).status_code == 201
    monkeypatch.setattr('umbra_relay.pairing.time.time', lambda: data['expires'])
    assert claim(client, data).status_code == 403
    assert revoke(client, data).status_code == 403
    with app.state.database.connect(write=True) as db:
        app.state.database.purge(db, data['expires'])
        assert db.execute('SELECT COUNT(*) FROM pairing_invites').fetchone()[0] == 0


def test_claim_survives_restart(context):
    app, client, box = context
    data = invitation()
    assert create(client, box, data).status_code == 201
    assert claim(client, data).status_code == 200
    with TestClient(create_app(app.state.database.path)) as restarted:
        assert claim(restarted, data).status_code == 200
        assert claim(restarted, data, 'b' * 64).status_code == 409
        assert revoke(restarted, data).status_code == 204


def test_quotas_include_revoked_records(context, monkeypatch):
    app, client, box = context
    monkeypatch.setattr('umbra_relay.pairing.MAX_PAIRING_PER_BOX', 1)
    monkeypatch.setattr('umbra_relay.pairing.MAX_PAIRING_TOTAL', 2)
    data = invitation()
    assert create(client, box, data).status_code == 201
    assert revoke(client, data).status_code == 204
    assert create(client, box, invitation()).status_code == 429
    second = register(client, app)
    assert create(client, second, invitation()).status_code == 201
    third = register(client, app)
    assert create(client, third, invitation()).status_code == 429


def test_sqlite_failure_does_not_consume_claim(context):
    app, client, box = context
    data = invitation()
    assert create(client, box, data).status_code == 201
    with app.state.database.connect(write=True) as db:
        db.execute("CREATE TRIGGER fail_claim BEFORE UPDATE ON pairing_invites BEGIN SELECT RAISE(ABORT, 'synthetic disk failure'); END")
    response = claim(client, data)
    assert response.status_code == 503
    assert response.json() == {'detail': 'Storage temporarily unavailable'}
    with app.state.database.connect(write=True) as db:
        assert db.execute('SELECT request_hash FROM pairing_invites').fetchone()[0] is None
        db.execute('DROP TRIGGER fail_claim')
    assert claim(client, data, 'b' * 64).status_code == 200


def test_v2_migration_preserves_mailboxes_and_messages(context):
    app, client, box = context
    message = envelope()
    assert put(client, box, message).status_code == 201
    with app.state.database.connect(write=True) as db:
        db.execute('DROP TABLE pairing_invites')
        db.execute('PRAGMA user_version=2')
    migrated = create_app(app.state.database.path)
    with TestClient(migrated) as restarted:
        assert inbox(restarted, box).json()['messages'] == [message]
        assert create(restarted, box, invitation()).status_code == 201
    with migrated.state.database.connect() as db:
        assert db.execute('PRAGMA user_version').fetchone()[0] == 3


def test_missing_v3_pairing_table_rejected(context):
    app, _, _ = context
    with app.state.database.connect(write=True) as db:
        db.execute('DROP TABLE pairing_invites')
    with pytest.raises(ValueError, match='schema'):
        Database(app.state.database.path)


def test_v2_migration_failure_rolls_back_pairing_table(context):
    app, _, _ = context
    with app.state.database.connect(write=True) as db:
        db.execute('DROP TABLE pairing_invites')
        db.execute('PRAGMA user_version=2')
        db.execute('CREATE VIEW pairing_expiry AS SELECT 1')
    with pytest.raises(sqlite3.OperationalError):
        Database(app.state.database.path)
    with sqlite3.connect(app.state.database.path) as db:
        assert db.execute('PRAGMA user_version').fetchone()[0] == 2
        assert db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='pairing_invites'").fetchone() is None


@pytest.mark.parametrize('digest', ['A' * 64, 'a' * 63, 'g' * 64, 1])
def test_request_digest_strict(context, digest):
    _, client, box = context
    data = invitation()
    assert create(client, box, data).status_code == 201
    assert claim(client, data, digest).status_code == 422


def test_distinct_capabilities_required(context):
    _, client, box = context
    data = invitation()
    data['revoke_token'] = data['consume_token']
    assert create(client, box, data).status_code == 422
