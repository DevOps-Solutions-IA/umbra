"""Call signaling remains opaque to relay storage and validation."""
import pytest
from test_relay import app, client, register, envelope, put, inbox


@pytest.mark.parametrize('extra', [
    {'kind': 'CALL_INVITE'},
    {'sdp': 'v=0\r\ns=synthetic\r\n'},
    {'ice': {'candidate': 'synthetic'}},
    {'callId': '00000000-0000-4000-8000-000000000001'},
])
def test_plaintext_call_fields_rejected_without_storage(client, app, extra):
    box = register(client, app)
    assert put(client, box, dict(envelope(), **extra)).status_code == 422
    assert inbox(client, box).json()['messages'] == []
