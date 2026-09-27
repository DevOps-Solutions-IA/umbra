"""Actual SQLite transactions; not Android persistence or HTTP gate acceptance."""
import sqlite3
from concurrent.futures import ThreadPoolExecutor
import pytest
from umbra_relay.app import Database
from umbra_relay.admission_protocol import AdmissionError, digest
from umbra_relay.admission_store import AdmissionStore
from test_admission_protocol import objects, sign, NOW


@pytest.fixture
def store(tmp_path, objects):
    _, _, realm, request, credential = objects
    db = Database(str(tmp_path / "admission.sqlite3"))
    store = AdmissionStore(db, realm.encode(), "https://relay.test", clock=lambda: NOW)
    store.submit(request.wire)
    store.publish(credential.wire)
    return store


def test_duplicate_request_and_copied_credential_proof_rejected(store, objects):
    authority, device, _, request, credential = objects
    with pytest.raises(AdmissionError):
        store.submit(request.wire)
    operation = digest(b"synthetic operation")
    challenge = store.challenge(credential.wire, operation)
    with store.database.connect(write=True) as db:
        with pytest.raises(AdmissionError):
            store.consume(db, credential.wire, challenge.encode(), sign("proof", authority, challenge.fields), operation)
    proof = sign("proof", device, challenge.fields)
    with store.database.connect(write=True) as db:
        assert store.consume(db, credential.wire, challenge.encode(), proof, operation) == credential
    with store.database.connect(write=True) as db:
        with pytest.raises(AdmissionError):
            store.consume(db, credential.wire, challenge.encode(), proof, operation)


def test_concurrent_consumption_has_one_winner(store, objects):
    _, device, _, _, credential = objects
    operation = digest(b"synthetic concurrent operation")
    challenge = store.challenge(credential.wire, operation)
    proof = sign("proof", device, challenge.fields)
    def consume():
        try:
            with store.database.connect(write=True) as db:
                store.consume(db, credential.wire, challenge.encode(), proof, operation)
            return True
        except AdmissionError:
            return False
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(lambda _: consume(), range(2))) == [False, True]


def test_revocation_survives_restart_and_cancels_challenges(store, objects):
    authority, _, realm, _, credential = objects
    store.challenge(credential.wire, digest(b"operation"))
    revocation = sign("revocation", authority, [realm.realm_id, credential.credential_id, credential.device_public_key,
                                             realm.authority_key_id, "1", str(NOW), "device_lost"])
    store.revoke(revocation)
    restarted = AdmissionStore(Database(store.database.path), realm.encode(), "https://relay.test", clock=lambda: NOW)
    with pytest.raises(AdmissionError):
        restarted.challenge(credential.wire, digest(b"operation"))
    with pytest.raises(AdmissionError):
        restarted.publish(credential.wire)
    restarted.revoke(revocation)
    with restarted.database.connect() as db:
        assert db.execute("SELECT count(*) FROM admission_challenges").fetchone()[0] == 0
        assert db.execute("SELECT count(*) FROM admission_revocations").fetchone()[0] == 1


def test_restart_and_monotonic_expiry_invalidate_proof(store, objects):
    _, device, realm, _, credential = objects
    operation = digest(b"operation")
    challenge = store.challenge(credential.wire, operation)
    proof = sign("proof", device, challenge.fields)
    restarted = AdmissionStore(store.database, realm.encode(), "https://relay.test", clock=lambda: NOW)
    with restarted.database.connect(write=True) as db:
        with pytest.raises(AdmissionError):
            restarted.consume(db, credential.wire, challenge.encode(), proof, operation)
    store.elapsed = lambda: 10**12
    with store.database.connect(write=True) as db:
        with pytest.raises(AdmissionError):
            store.consume(db, credential.wire, challenge.encode(), proof, operation)


def test_transaction_rollback_does_not_consume_possession(store, objects):
    _, device, _, _, credential = objects
    operation = digest(b"rollback operation")
    challenge = store.challenge(credential.wire, operation)
    proof = sign("proof", device, challenge.fields)
    with pytest.raises(sqlite3.OperationalError):
        with store.database.connect(write=True) as db:
            store.consume(db, credential.wire, challenge.encode(), proof, operation)
            db.execute("INSERT INTO nonexistent_table VALUES (1)")
    with store.database.connect(write=True) as db:
        assert store.consume(db, credential.wire, challenge.encode(), proof, operation) == credential


def test_challenges_are_bounded_per_credential(store, objects):
    credential = objects[-1]
    for _ in range(8):
        store.challenge(credential.wire, digest(b"operation"))
    with pytest.raises(AdmissionError):
        store.challenge(credential.wire, digest(b"operation"))


@pytest.mark.parametrize("table", ["admission_realm", "admission_requests", "admission_credentials", "admission_revocations", "admission_challenges"])
def test_missing_admission_schema_is_not_recreated(tmp_path, table):
    path = str(tmp_path / "damaged.sqlite3")
    Database(path)
    with sqlite3.connect(path) as db:
        db.execute(f"DROP TABLE {table}")  # Fixed test parameters.
    with pytest.raises(ValueError, match="schema"):
        Database(path)
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT 1 FROM sqlite_master WHERE name=?", (table,)).fetchone() is None


def test_pool_failure_rolls_back_partial_issuance(store, objects):
    credential = objects[-1]
    store.challenge(credential.wire, digest(b"existing outstanding challenge"))
    with pytest.raises(AdmissionError):
        store.batch(credential.wire)
    with store.database.connect() as db:
        assert db.execute("SELECT count(*) FROM admission_challenges WHERE used=0").fetchone()[0] == 1


def test_rejection_result_requires_request_possession(tmp_path, objects):
    authority, device, realm, request, _ = objects
    store = AdmissionStore(Database(str(tmp_path / "reject.sqlite3")), realm.encode(), "https://relay.test", clock=lambda: NOW)
    store.submit(request.wire)
    rejection = sign("rejection", authority, [realm.realm_id, request.request_id, digest(request.wire.encode()),
                                             request.device_public_key, realm.authority_key_id, str(NOW)])
    store.reject(rejection)
    challenge = store.result_challenge(request.wire)
    with pytest.raises(AdmissionError):
        store.result(request.wire, challenge.encode(), sign("request-proof", authority, challenge.fields))
    assert store.result(request.wire, challenge.encode(), sign("request-proof", device, challenge.fields)) == {"state": "REJECTED", "rejection": rejection}
    with pytest.raises(AdmissionError):
        store.result(request.wire, challenge.encode(), sign("request-proof", device, challenge.fields))


def test_atomic_renewal_keeps_other_devices_independent(store, objects):
    from test_admission_protocol import token
    from umbra_relay.admission_protocol import CAPABILITIES
    authority, device, realm, request, old = objects
    request_id = token()
    renewal_request = sign("request", device, [realm.realm_id, request_id, old.device_public_key, old.signal_public_key,
                                              str(NOW), str(NOW+600), token()])
    store.submit(renewal_request)
    next_credential = sign("credential", authority, [realm.realm_id, token(), old.device_public_key, old.signal_public_key,
                                                    realm.authority_key_id, str(NOW), str(NOW), str(NOW+3600), CAPABILITIES, request_id])
    revocation = sign("revocation", authority, [realm.realm_id, old.credential_id, old.device_public_key,
                                              realm.authority_key_id, "1", str(NOW), "policy"])
    with store.database.connect(write=True) as db:
        db.execute("CREATE TRIGGER fail_revocation BEFORE INSERT ON admission_revocations BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
    with pytest.raises(sqlite3.IntegrityError, match="synthetic failure"):
        store.renew(next_credential, revocation)
    with store.database.connect(write=True) as db:
        assert db.execute("SELECT count(*) FROM admission_credentials").fetchone()[0] == 1
        assert db.execute("SELECT decision FROM admission_requests WHERE id=?", (request_id,)).fetchone()[0] is None
        db.execute("DROP TRIGGER fail_revocation")
    store.renew(next_credential, revocation)
    store.renew(next_credential, revocation)  # exact administrative retry is idempotent
    with pytest.raises(AdmissionError):
        store.challenge(old.wire, digest(b"operation"))
    store.challenge(next_credential, digest(b"operation"))


def test_missing_pin_with_existing_records_cannot_reinitialize(store, objects):
    with store.database.connect(write=True) as db:
        db.execute("DELETE FROM admission_realm")
    with pytest.raises(AdmissionError):
        AdmissionStore(store.database, objects[2].encode(), "https://relay.test")
    with store.database.connect() as db:
        assert db.execute("SELECT count(*) FROM admission_realm").fetchone()[0] == 0
