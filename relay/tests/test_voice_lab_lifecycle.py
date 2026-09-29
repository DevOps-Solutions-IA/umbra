"""Actual isolated TLS server lifecycle; not Android pooling or media acceptance."""
import http.client
import json
from pathlib import Path
import ssl
import sys
import time
from urllib.parse import urlparse

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'scripts'))
from voice_relay_lab import voice_relay


def test_keepalive_closure_is_recorded_without_payload_or_timeout_change(tmp_path):
    report=tmp_path/'lifecycle.json'
    with voice_relay(report) as lab:
        context=ssl.create_default_context(cadata=lab['certificate'])
        connection=http.client.HTTPSConnection('127.0.0.1',urlparse(lab['base']).port,context=context,timeout=7)
        try:
            connection.request('GET','/healthz')
            response=connection.getresponse();assert response.status==200;response.read()
            assert connection.sock is not None
            began=time.monotonic()
            assert connection.sock.recv(1)==b''
            assert time.monotonic()-began>=4.5
        finally:connection.close()
    evidence=json.loads(report.read_text())
    idle=[row for row in evidence['connections'] if 'keepaliveNanos' in row]
    assert len(idle)==1
    row=idle[0]
    assert row['responses']==1 and row['receivedEvents']>=1
    assert row['keepaliveNanos']-row['responseNanos']>=4_500_000_000
    assert row['closedNanos']>=row['keepaliveNanos']
    assert row['incompleteResponse'] is False
    assert row['transportError'] is False
    assert evidence['dropped']==0
    text=report.read_text()
    for forbidden in ('127.0.0.1','/healthz','certificate','password','credential','Authorization'):
        assert forbidden not in text
