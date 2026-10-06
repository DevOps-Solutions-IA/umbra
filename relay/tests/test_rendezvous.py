"""Synthetic encrypted byte delivery, real admission and SQLite transactions."""
import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import secrets
import sqlite3
import time

import pytest
from fastapi.testclient import TestClient as RawClient
from admission_fixture import TestClient, create_app
from umbra_relay.app import Database
from test_relay import register, header
from test_schema_safety import snapshot


def token():
    return secrets.token_urlsafe(32)


def blob():
    return base64.b64encode(secrets.token_bytes(80)).decode()


def invitation():
    return dict(id=token(), owner_token=token(), request_token=token(), expires=int(time.time())+590,
                code_locator=token(), code_read_token=token(), invite_blob=blob())


def candidate():
    return dict(request_id=token(), ack_token=token(), request_blob=blob())


def auth(value):
    return {'Authorization': 'Bearer '+value}


@pytest.fixture
def context(tmp_path):
    app = create_app(str(tmp_path/'rendezvous.sqlite3'), rate_limit=10000)
    with TestClient(app, raise_server_exceptions=False) as client:
        box = register(client, app)
        data = invitation()
        endpoint = f"/v1/boxes/{box['id']}/pairing-rendezvous"
        assert client.post(endpoint, headers=header(box), json=data).status_code == 201
        yield app, client, box, data


def base(data):
    return '/v1/pairing-rendezvous/'+data['id']


def submit(client, data, request):
    return client.post(base(data)+'/requests', headers=auth(data['request_token']), json=request)


def select(client, data, request_hash, ack_blob):
    return client.post(base(data)+'/ack', headers=auth(data['owner_token']), json=dict(request_hash=request_hash, ack_blob=ack_blob))


def receive(client, data, request, digest):
    return client.get(base(data)+'/requests/'+digest+'/ack', headers=auth(request['ack_token']))


def test_candidates_local_owner_selection_retry_and_capability_isolation(context):
    app, client, box, data = context
    bad, good = candidate(), candidate()
    first = submit(client, data, bad)
    second = submit(client, data, good)
    assert first.status_code == second.status_code == 201
    assert submit(client, data, good).status_code == 200
    digest = second.json()['request_hash']
    assert digest == hashlib.sha256(good['request_id'].encode()).hexdigest()
    assert receive(client, data, good, digest).status_code == 204
    assert submit(client, data, dict(good, request_blob=blob())).status_code == 409
    assert client.get(base(data)+'/requests', headers=auth(data['request_token'])).status_code == 403
    rows = client.get(base(data)+'/requests', headers=auth(data['owner_token'])).json()['requests']
    assert len(rows) == 2
    ack = blob()
    assert select(client, data, digest, ack).status_code == 200
    assert select(client, data, digest, ack).status_code == 200
    assert select(client, data, digest, blob()).status_code == 409
    assert receive(client, data, good, digest).json() == {'ack_blob': ack}
    assert receive(client, data, bad, first.json()['request_hash']).status_code == 409
    assert submit(client, data, candidate()).status_code == 409
    with app.state.database.connect() as db:
        stored = str([tuple(r) for r in db.execute('SELECT * FROM pairing_rendezvous')]) + str([tuple(r) for r in db.execute('SELECT * FROM pairing_candidates')])
        for secret in [data['id'],data['owner_token'],data['request_token'],data['code_read_token'],data['code_locator'],good['request_id'],good['ack_token']]:
            assert secret not in stored
    with TestClient(create_app(app.state.database.path)) as restarted:
        assert receive(restarted, data, good, digest).json() == {'ack_blob': ack}
        assert select(restarted, data, first.json()['request_hash'], ack).status_code == 409


def test_code_claim_single_claimant_exact_retry(context):
    _, client, _, data = context
    path = '/v1/pairing-codes/'+data['code_locator']+'/claim'
    claimant = {'request_id': token()}
    assert client.post(path, headers=auth(data['request_token']), json=claimant).status_code == 403
    result = client.post(path, headers=auth(data['code_read_token']), json=claimant)
    assert result.json() == {'invite_blob':data['invite_blob'], 'expires':data['expires']}
    assert client.post(path, headers=auth(data['code_read_token']), json=claimant).json() == result.json()
    assert client.post(path, headers=auth(data['code_read_token']), json={'request_id':token()}).status_code == 409
    # Code reservation itself never selects a pairing request.
    assert client.get(base(data)+'/requests', headers=auth(data['owner_token'])).json() == {'requests':[]}


def test_owner_race_has_one_selection(context):
    _, client, _, data = context
    requests = [candidate() for _ in range(8)]
    digests = [submit(client,data,r).json()['request_hash'] for r in requests]
    acknowledgments = [blob() for _ in requests]
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda i:select(client,data,digests[i],acknowledgments[i]).status_code,range(8)))
    assert results.count(200) == 1
    assert results.count(409) == 7


def test_revocation_tombstone_quota_and_cascade(context, monkeypatch):
    app, client, box, data = context
    req = candidate(); digest = submit(client,data,req).json()['request_hash']
    assert client.delete(base(data),headers=auth(data['request_token'])).status_code == 403
    assert client.delete(base(data),headers=auth(data['owner_token'])).status_code == 204
    assert client.delete(base(data),headers=auth(data['owner_token'])).status_code == 204
    assert submit(client,data,req).status_code == 403
    assert receive(client,data,req,digest).status_code == 403
    monkeypatch.setattr('umbra_relay.rendezvous.MAX_PER_BOX',1)
    path = f"/v1/boxes/{box['id']}/pairing-rendezvous"
    assert client.post(path,headers=header(box),json=data).status_code == 403
    assert client.post(path,headers=header(box),json=invitation()).status_code == 429
    assert client.delete(f"/v1/boxes/{box['id']}",headers=header(box)).status_code == 204
    with app.state.database.connect() as db:
        assert db.execute('SELECT COUNT(*) FROM pairing_candidates').fetchone()[0] == 0


def test_candidate_quota_and_failure_rollback(context, monkeypatch):
    app, client, _, data = context
    req = candidate()
    with app.state.database.connect(write=True) as db:
        db.execute("CREATE TRIGGER fail_candidate BEFORE INSERT ON pairing_candidates BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
    assert submit(client,data,req).status_code == 503
    with app.state.database.connect(write=True) as db:
        assert db.execute('SELECT COUNT(*) FROM pairing_candidates').fetchone()[0] == 0
        db.execute('DROP TRIGGER fail_candidate')
    monkeypatch.setattr('umbra_relay.rendezvous.MAX_CANDIDATES',1)
    assert submit(client,data,req).status_code == 201
    assert submit(client,data,req).status_code == 200
    assert submit(client,data,candidate()).status_code == 429


@pytest.mark.parametrize('change', [dict(expires=True),dict(expires='1'),dict(id='x'),dict(owner_token='x'),
    dict(invite_blob='a'*100000),dict(invite_blob='AA=='),dict(code_locator=None),dict(unknown='secret')])
def test_strict_schema(context, change):
    _,client,box,_ = context
    data = invitation();data.update(change)
    assert client.post(f"/v1/boxes/{box['id']}/pairing-rendezvous",headers=header(box),json=data).status_code == 422


@pytest.mark.parametrize('ttl',[0,-1,601])
def test_expiry_bounds(context,ttl):
    _,client,box,_ = context
    data=invitation();data['expires']=int(time.time())+ttl
    assert client.post(f"/v1/boxes/{box['id']}/pairing-rendezvous",headers=header(box),json=data).status_code == 400


def test_expiry_rejects_exact_retry_and_purges_children(context, monkeypatch):
    app,client,box,data=context
    req=candidate();digest=submit(client,data,req).json()['request_hash']
    monkeypatch.setattr('umbra_relay.rendezvous.time.time',lambda:data['expires'])
    assert submit(client,data,req).status_code == 403
    assert receive(client,data,req,digest).status_code == 403
    assert client.post(f"/v1/boxes/{box['id']}/pairing-rendezvous",headers=header(box),json=data).status_code == 400
    with app.state.database.connect(write=True) as db:
        app.state.database.purge(db,data['expires'])
        assert db.execute('SELECT COUNT(*) FROM pairing_candidates').fetchone()[0] == 0


def test_admission_is_mandatory(context):
    app,_,box,data=context
    with RawClient(app) as client:
        assert client.post(f"/v1/boxes/{box['id']}/pairing-rendezvous",headers=header(box),json=invitation()).status_code == 403
        assert client.get(base(data)+'/requests',headers=auth(data['owner_token'])).status_code == 403
        assert client.post('/v1/pairing-codes/'+data['code_locator']+'/claim',headers=auth(data['code_read_token']),json={'request_id':token()}).status_code == 403


def test_v5_migration_is_additive_and_idempotent(tmp_path):
    path=str(tmp_path/'v5.sqlite3');database=Database(path);issued=database.issue_invite()
    with database.connect(write=True) as db:
        db.execute('DROP TABLE pairing_candidates');db.execute('DROP TABLE pairing_rendezvous');db.execute('PRAGMA user_version=5')
    migrated=Database(path)
    with migrated.connect() as db:
        assert db.execute('PRAGMA user_version').fetchone()[0] == 6
        assert db.execute('SELECT COUNT(*) FROM invites').fetchone()[0] == 1
    before=snapshot(path);Database(path);assert snapshot(path)==before
    assert issued


@pytest.mark.parametrize('table',['pairing_candidates','pairing_rendezvous'])
def test_incomplete_v6_fails_closed(tmp_path,table):
    path=str(tmp_path/'bad.sqlite3');Database(path)
    with sqlite3.connect(path) as db: db.execute('DROP TABLE '+table)
    before=snapshot(path)
    with pytest.raises(ValueError,match='schema'): Database(path)
    assert snapshot(path)==before


def test_v5_migration_failure_atomic(tmp_path):
    path=str(tmp_path/'bad.sqlite3');database=Database(path)
    with database.connect(write=True) as db:
        db.execute('DROP TABLE pairing_candidates');db.execute('DROP TABLE pairing_rendezvous');db.execute('PRAGMA user_version=5')
        db.execute('CREATE VIEW rendezvous_expiry AS SELECT 1')
    before=snapshot(path)
    with pytest.raises(sqlite3.OperationalError): Database(path)
    assert snapshot(path)==before


def test_code_race_reserves_exactly_one_request_id(context):
    _,client,_,data=context
    path='/v1/pairing-codes/'+data['code_locator']+'/claim'
    requests=[{'request_id':token()} for _ in range(8)]
    with ThreadPoolExecutor(max_workers=8) as pool:
        results=list(pool.map(lambda r:client.post(path,headers=auth(data['code_read_token']),json=r).status_code,requests))
    assert results.count(200)==1
    assert results.count(409)==7


def test_global_quota_and_ingress_rate_limit(context, monkeypatch):
    app,client,_,_=context
    monkeypatch.setattr('umbra_relay.rendezvous.MAX_TOTAL',1)
    other=register(client,app)
    assert client.post(f"/v1/boxes/{other['id']}/pairing-rendezvous",headers=header(other),json=invitation()).status_code==429
    from umbra_relay.guard import RequestLimits
    middleware=app.middleware_stack
    while not isinstance(middleware,RequestLimits):
        middleware=middleware.app
    middleware.per_minute=1
    assert client.get('/v1/pairing-rendezvous/'+token()+'/requests',headers=auth(token())).status_code==429


@pytest.mark.parametrize('table,old,new',[
    ('pairing_rendezvous','locator_hash TEXT UNIQUE','locator_hash TEXT'),
    ('pairing_rendezvous','owner_hash TEXT NOT NULL','owner_hash TEXT'),
    ('pairing_rendezvous','ON DELETE CASCADE','ON DELETE NO ACTION'),
    ('pairing_candidates','ON DELETE CASCADE','ON DELETE NO ACTION'),
    ('pairing_candidates','PRIMARY KEY(rendezvous, request_hash)','PRIMARY KEY(request_hash)'),
])
def test_v6_corrupt_constraints_preserved(tmp_path,table,old,new):
    path=str(tmp_path/'damaged.sqlite3');Database(path)
    with sqlite3.connect(path) as db:
        ddl=db.execute('SELECT sql FROM sqlite_master WHERE name=?',(table,)).fetchone()[0]
        assert old in ddl
        db.execute('DROP TABLE '+table)
        db.execute(ddl.replace(old,new))
    before=snapshot(path)
    with pytest.raises(ValueError,match='schema'):Database(path)
    assert snapshot(path)==before


def test_ack_disk_failure_rolls_back_selection(context):
    app,client,_,data=context
    request=candidate();digest=submit(client,data,request).json()['request_hash']
    with app.state.database.connect(write=True) as db:
        db.execute("CREATE TRIGGER fail_selection BEFORE UPDATE ON pairing_rendezvous BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
    ack=blob()
    assert select(client,data,digest,ack).status_code==503
    assert receive(client,data,request,digest).status_code==204
    with app.state.database.connect(write=True) as db:db.execute('DROP TRIGGER fail_selection')
    assert select(client,data,digest,ack).status_code==200


def test_optional_code_and_locator_collision(context):
    app,client,box,data=context
    endpoint=f"/v1/boxes/{box['id']}/pairing-rendezvous"
    assert client.post(endpoint,headers=header(box),json=data).status_code==200
    assert client.post(endpoint,headers=header(box),json=dict(data,invite_blob=blob())).status_code==409
    other=invitation();other['code_locator']=data['code_locator']
    assert client.post(endpoint,headers=header(box),json=other).status_code==409
    direct=invitation()
    for key in ('code_locator','code_read_token','invite_blob'):direct.pop(key)
    assert client.post(endpoint,headers=header(box),json=direct).status_code==201
