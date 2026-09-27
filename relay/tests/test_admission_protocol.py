"""Synthetic keys only. Cryptographic parser tests, not relay authorization acceptance."""
import secrets
import pytest
from nacl.signing import SigningKey
from umbra_relay.admission_protocol import (
    AdmissionError, Realm, Request, Credential, Revocation, Challenge,
    encode, decode, digest, body, unpack, CAPABILITIES,
)

NOW = 1_790_000_000


def token():
    return secrets.token_urlsafe(32)


def sign(kind, key, fields):
    data = body(kind, fields)
    return f"umbra:admission:{kind}:1:{encode(data)}.{encode(key.sign(data).signature)}"


@pytest.fixture
def objects():
    authority, device = SigningKey.generate(), SigningKey.generate()
    public = encode(authority.verify_key.encode())
    realm = Realm.parse(f"umbra:realm:1:{token()}:{public}:{digest(decode(public))}")
    device_public = encode(device.verify_key.encode())
    # A synthetic serialized Signal public-key-shaped value: not a libsignal integration test.
    signal_public = encode(b"\x05" + secrets.token_bytes(32))
    request_fields = [realm.realm_id, token(), device_public, signal_public, str(NOW), str(NOW+600), token()]
    request = Request.parse(sign("request", device, request_fields), realm)
    fields = [realm.realm_id, token(), device_public, signal_public, realm.authority_key_id,
              str(NOW), str(NOW), str(NOW+3600), CAPABILITIES, request.request_id]
    credential = Credential.parse(sign("credential", authority, fields), realm)
    return authority, device, realm, request, credential


def test_roundtrip_and_expiry(objects):
    _, _, realm, request, credential = objects
    assert Realm.parse(realm.encode()) == realm
    request.current(NOW)
    credential.current(NOW)
    for time in (NOW-1, NOW+3600):
        with pytest.raises(AdmissionError):
            credential.current(time)
    with pytest.raises(AdmissionError):
        request.current(NOW+600)


@pytest.mark.parametrize("field", range(10))
def test_every_credential_field_is_authenticated(objects, field):
    _, _, realm, _, credential = objects
    fields, signature = unpack("credential", credential.wire, 10)
    fields[field] += "1"
    altered = "umbra:admission:credential:1:" + encode(body("credential", fields)) + "." + encode(signature)
    with pytest.raises(AdmissionError):
        Credential.parse(altered, realm)


def test_wrong_authority_and_forgery(objects):
    _, device, realm, _, credential = objects
    fields, _ = unpack("credential", credential.wire, 10)
    with pytest.raises(AdmissionError):
        Credential.parse(sign("credential", device, fields), realm)
    other = Realm(token(), realm.authority_public_key, realm.authority_key_id)
    with pytest.raises(AdmissionError):
        Credential.parse(credential.wire, other)


@pytest.mark.parametrize("suffix", ["=", "\n", ".", "\x00", "é"])
def test_noncanonical_format(objects, suffix):
    _, _, realm, _, credential = objects
    with pytest.raises(AdmissionError):
        Credential.parse(credential.wire + suffix, realm)


def test_unknown_versions_and_purposes(objects):
    _, _, realm, request, credential = objects
    for wire in (request.wire, credential.wire.replace(":1:", ":2:"), "A"*4097):
        with pytest.raises(AdmissionError):
            Credential.parse(wire, realm)


def test_revocation_and_capability_rejection(objects):
    authority, _, realm, _, credential = objects
    fields = [realm.realm_id, credential.credential_id, credential.device_public_key,
              realm.authority_key_id, "1", str(NOW), "device_lost"]
    revocation = Revocation.parse(sign("revocation", authority, fields), realm)
    assert revocation.credential_id == credential.credential_id
    fields[-1] = "delete_everything"
    with pytest.raises(AdmissionError):
        Revocation.parse(sign("revocation", authority, fields), realm)
    fields, _ = unpack("credential", credential.wire, 10)
    fields[8] = "admin"
    with pytest.raises(AdmissionError):
        Credential.parse(sign("credential", authority, fields), realm)


def test_possession_binds_credential_context_and_device(objects):
    _, device, realm, _, credential = objects
    verifier, operation = digest(b"synthetic verifier"), digest(b"synthetic operation")
    challenge = Challenge((realm.realm_id, credential.credential_id, digest(credential.wire.encode()), token(),
                           verifier, operation, str(NOW), str(NOW+30)))
    assert Challenge.parse(challenge.encode()) == challenge
    challenge.validate(credential, verifier, operation, NOW)
    challenge.verify_proof(credential, sign("proof", device, challenge.fields))
    with pytest.raises(AdmissionError):
        challenge.verify_proof(credential, sign("proof", SigningKey.generate(), challenge.fields))
    for args in ((verifier, operation, NOW+30), (digest(b"other"), operation, NOW), (verifier, digest(b"other"), NOW)):
        with pytest.raises(AdmissionError):
            challenge.validate(credential, *args)


def test_public_objects_do_not_log_their_contents(objects):
    _, _, realm, request, credential = objects
    assert realm.realm_id not in repr(realm)
    assert request.wire not in repr(request)
    assert credential.wire not in repr(credential)

@pytest.mark.parametrize("key", [bytes(32), b"\x01" + bytes(31)])
def test_degenerate_public_keys_cannot_prove_possession(objects, key):
    # Reproduced with the initially evaluated OpenSSL-backed verify-only API.
    # The application requires full point validation, matching the Android verifier.
    _, _, realm, request, _ = objects
    fields = [realm.realm_id, token(), encode(key), request.signal_public_key, str(NOW), str(NOW+600), token()]
    forged = "umbra:admission:request:1:" + encode(body("request", fields)) + "." + encode(b"\x01" + bytes(63))
    with pytest.raises(AdmissionError):
        Request.parse(forged, realm)
    with pytest.raises(AdmissionError):
        Realm.parse(f"umbra:realm:1:{token()}:{encode(key)}:{digest(key)}")
