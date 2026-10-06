#!/usr/bin/env python3
"""Synthetic local verification/SQLite overhead; no private material or wires in output."""
import json
from pathlib import Path
import secrets
import statistics
import sys
import tempfile
import time
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'relay'))
from nacl.signing import SigningKey
from admission_lab import AdmissionLab
from umbra_relay.admission_protocol import body, encode, Credential, digest
from umbra_relay.admission_store import AdmissionStore
from umbra_relay.app import Database


def signed(kind, key, fields):
    raw=body(kind, fields)
    return f'umbra:admission:{kind}:1:{encode(raw)}.{encode(key.sign(raw).signature)}'


def main():
    admin=AdmissionLab(); device=SigningKey.generate(); now=int(time.time())
    request=signed('request',device,[admin.realm.realm_id,secrets.token_urlsafe(32),encode(device.verify_key.encode()),
        encode(b'\x05'+secrets.token_bytes(32)),str(now),str(now+600),secrets.token_urlsafe(32)])
    with tempfile.TemporaryDirectory(prefix='umbra-admission-bench-') as folder:
        store=AdmissionStore(Database(str(Path(folder)/'synthetic.db')),admin.realm.encode(),'https://synthetic.test')
        admin.store=store; wire=admin.approve(request)['credential']; operation=digest(b'synthetic operation')
        samples={name:[] for name in ('credentialVerifyMs','proofVerifyMs','sqliteAuthorizationMs')}
        for _ in range(100):
            start=time.perf_counter_ns(); credential=Credential.parse(wire,admin.realm)
            samples['credentialVerifyMs'].append((time.perf_counter_ns()-start)/1e6)
            challenge=store.challenge(wire,operation); proof=signed('proof',device,challenge.fields)
            start=time.perf_counter_ns(); challenge.verify_proof(credential,proof)
            samples['proofVerifyMs'].append((time.perf_counter_ns()-start)/1e6)
            start=time.perf_counter_ns()
            with store.database.connect(write=True) as conn: store.consume(conn,wire,challenge.encode(),proof,operation)
            samples['sqliteAuthorizationMs'].append((time.perf_counter_ns()-start)/1e6)
        print(json.dumps({'samples':100,'environment':'local synthetic Python/libsodium/SQLite; not Android or load test',
            'timings':{name:{'median':statistics.median(values),'p95':sorted(values)[94],'max':max(values)} for name,values in samples.items()}},indent=2))


if __name__=='__main__': main()
