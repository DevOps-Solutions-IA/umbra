"""Location uses the existing opaque envelope; plaintext metadata is never an accepted API extension."""
import pytest
from test_relay import app, client, register, envelope, put, inbox


@pytest.mark.parametrize('extra', [
    {'latitude': 12.345678, 'longitude': 45.678912},
    {'kind': 'LOCATION_POINT'},
    {'location': {'latE7': 123456780, 'lonE7': 456789120}},
    {'map_url': 'https://invalid.example/synthetic'},
])
def test_plaintext_location_fields_are_rejected_without_storage(client, app, extra):
    box = register(client, app)
    assert put(client, box, dict(envelope(), **extra)).status_code == 422
    assert inbox(client, box).json()['messages'] == []
