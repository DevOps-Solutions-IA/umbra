"""Real loopback TLS framing probes; never an Android historical-EOF reproducer."""
import http.client
import json
from pathlib import Path
import ssl
import sys
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from voice_relay_lab import voice_relay


def connect(lab):
    return http.client.HTTPSConnection(
        '127.0.0.1', urlparse(lab['base']).port,
        context=ssl.create_default_context(cadata=lab['certificate']), timeout=7)


def test_reused_tls_socket_has_complete_frames_and_explicit_close(tmp_path):
    report = tmp_path / 'lifecycle.json'
    with voice_relay(report) as lab:
        connection = connect(lab)
        try:
            original = None
            for index in range(6):
                connection.request('GET', '/healthz')
                response = connection.getresponse()
                body = response.read()
                assert response.status == 200
                assert len(body) == int(response.getheader('Content-Length'))
                assert isinstance(json.loads(body), dict)
                if index == 0:
                    original = connection.sock
                    assert original is not None
                assert connection.sock is original
            connection.request('GET', '/healthz', headers={'Connection': 'close'})
            response = connection.getresponse()
            assert response.status == 200
            assert len(response.read()) == int(response.getheader('Content-Length'))
            assert response.will_close
            assert connection.sock is None
        finally:
            connection.close()
    evidence = json.loads(report.read_text())
    reused = [row for row in evidence['connections'] if row['responses'] == 7]
    assert len(reused) == 1
    assert reused[0]['incompleteResponse'] is False
    # http.client may close without TLS close_notify after Connection: close.
    # A transport-error flag alone does not contradict seven complete responses.
    assert isinstance(reused[0]['transportError'], bool)
    assert 'keepaliveNanos' not in reused[0]
    assert evidence['dropped'] == 0
    assert evidence['serverAliveAtScopeExit'] is True


def test_ambiguous_lengths_rejected_and_server_remains_healthy(tmp_path):
    report = tmp_path / 'lifecycle.json'
    with voice_relay(report) as lab:
        connection = connect(lab)
        try:
            connection.connect()
            # Deliberate synthetic invalid framing, no capability or payload.
            connection.sock.sendall(
                b'POST /healthz HTTP/1.1\r\nHost: localhost\r\n'
                b'Content-Length: 1\r\nContent-Length: 2\r\n\r\nx')
            response = http.client.HTTPResponse(connection.sock)
            response.begin()
            assert response.status == 400
            response.read()
            assert response.will_close
            assert connection.sock.recv(1) == b''
        finally:
            connection.close()
        healthy = connect(lab)
        try:
            healthy.request('GET', '/healthz', headers={'Connection': 'close'})
            response = healthy.getresponse()
            assert response.status == 200
            response.read()
        finally:
            healthy.close()
    evidence = json.loads(report.read_text())
    rejected = [row for row in evidence['connections'] if row['responses'] == 0]
    assert len(rejected) == 1
    assert rejected[0]['receivedEvents'] >= 1
    assert rejected[0]['incompleteResponse'] is False
    assert 'keepaliveNanos' not in rejected[0]
    assert evidence['serverAliveAtScopeExit'] is True
    assert evidence['dropped'] == 0
