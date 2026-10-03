"""Lost client pools reuse bounded public challenges; proofs stay operation-bound and one-use."""
from concurrent.futures import ThreadPoolExecutor
import pytest
from umbra_relay.admission_protocol import AdmissionError, Challenge, digest
from test_admission_protocol import objects, sign, NOW
from test_admission_store import store


def consume(store, device, credential, challenge, operation):
    fields = list(challenge.fields)
    fields[5] = operation
    wire = Challenge(tuple(fields)).encode()
    with store.database.connect(write=True) as db:
        return store.consume(db, credential.wire, wire, sign('proof', device, fields), operation)


def rows(store):
    with store.database.connect() as db:
        return {r['nonce']: dict(r) for r in db.execute('SELECT * FROM admission_challenges')}


def test_lost_unused_pool_and_part_used_pool_recover_without_extending_ttl(store, objects):
    _, device, _, _, credential = objects
    first = store.batch(credential.wire)
    before = rows(store)
    store.clock = lambda: NOW + 5
    elapsed = store.elapsed()
    store.elapsed = lambda: elapsed + 5
    second = store.batch(credential.wire)
    assert {c.encode() for c in first} == {c.encode() for c in second}
    assert rows(store) == before
    consume(store, device, credential, first[0], digest(b'original client operation'))
    partial = rows(store)
    third = store.batch(credential.wire)
    assert len(third) == len({c.fields[3] for c in third}) == 8
    old_unused = {c.fields[3] for c in first[1:]}
    assert old_unused <= {c.fields[3] for c in third}
    after = rows(store)
    assert all(after[n] == partial[n] for n in before)
    assert len(after) == 9
    assert sum(not r['used'] for r in after.values()) == 8


def test_two_restarted_clients_share_pool_but_only_one_proof_wins(store, objects):
    _, device, _, _, credential = objects
    with ThreadPoolExecutor(max_workers=2) as pool:
        batches = list(pool.map(lambda _: store.batch(credential.wire), range(2)))
    assert {c.encode() for c in batches[0]} == {c.encode() for c in batches[1]}
    challenge = batches[0][0]
    def attempt(index):
        try:
            consume(store, device, credential, challenge, digest(f'operation {index}'.encode()))
            return True
        except AdmissionError:
            return False
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(attempt, range(2))) == [False, True]


def test_operation_bound_challenge_is_never_repurposed_or_invalidated(store, objects):
    _, device, _, _, credential = objects
    operation = digest(b'bound operation')
    bound = store.challenge(credential.wire, operation)
    before = rows(store)
    with pytest.raises(AdmissionError):
        store.batch(credential.wire)
    assert rows(store) == before
    consume(store, device, credential, bound, operation)


def test_reused_pool_still_requires_correct_possession_and_operation(store, objects):
    authority, device, _, _, credential = objects
    pool = store.batch(credential.wire)
    recovered = store.batch(credential.wire)
    same = next(c for c in recovered if c.fields[3] == pool[0].fields[3])
    operation = digest(b'authorized operation')
    with pytest.raises(AdmissionError):
        consume(store, authority, credential, same, operation)
    consume(store, device, credential, pool[0], operation)
    with pytest.raises(AdmissionError):
        consume(store, device, credential, same, digest(b'different operation'))


def test_expired_pool_is_replaced_without_reusing_nonce(store, objects):
    credential = objects[-1]
    first = store.batch(credential.wire)
    store.clock = lambda: NOW + 30
    second = store.batch(credential.wire)
    assert not {c.fields[3] for c in first} & {c.fields[3] for c in second}
    assert len(rows(store)) == 8


def test_partial_refill_failure_keeps_previous_pool(store, objects, monkeypatch):
    _, device, _, _, credential = objects
    first = store.batch(credential.wire)
    consume(store, device, credential, first[0], digest(b'one'))
    consume(store, device, credential, first[1], digest(b'two'))
    before = rows(store)
    monkeypatch.setattr('umbra_relay.admission_store.MAX_CHALLENGES', 9)
    with pytest.raises(AdmissionError):
        store.batch(credential.wire)
    assert rows(store) == before
