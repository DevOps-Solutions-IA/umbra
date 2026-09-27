"""Canonical admission v1 public-object verification. No authority signing API."""
import base64
import hashlib
import re
from dataclasses import dataclass
from nacl.exceptions import BadSignatureError
from nacl.signing import VerifyKey
from nacl.bindings import crypto_core_ed25519_is_valid_point

MAX_WIRE = 4096
CREDENTIAL_TTL, REQUEST_TTL, CHALLENGE_TTL = 604800, 600, 30
CAPABILITIES = "relay.nearby.turn"


class AdmissionError(ValueError):
    def __init__(self):
        super().__init__("Admission unavailable")


class ChallengeUnavailable(AdmissionError):
    """Expired/consumed or prior-process verifier state; never authorizes a replay."""


def encode(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def decode(value, length=None):
    if not isinstance(value, str) or len(value) > MAX_WIRE or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise AdmissionError()
    try:
        raw = base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
    except ValueError:
        raise AdmissionError() from None
    if encode(raw) != value or (length is not None and len(raw) != length):
        raise AdmissionError()
    return raw


def public_key(value):
    raw = decode(value, 32)
    if not crypto_core_ed25519_is_valid_point(raw):
        raise AdmissionError()
    return raw


def digest(value):
    return hashlib.sha256(value).hexdigest()


def number(value):
    if not re.fullmatch(r"[1-9][0-9]{0,11}", value):
        raise AdmissionError()
    return int(value)


def hash_field(value):
    if not re.fullmatch(r"[a-f0-9]{64}", value):
        raise AdmissionError()
    return value


def body(kind, fields):
    if any(not re.fullmatch(r"[A-Za-z0-9_:/.-]{1,512}", field) for field in fields):
        raise AdmissionError()
    raw = (f"UMBRA-ADMISSION-{kind}-1\n" + "\n".join(fields) + "\n").encode("ascii")
    if len(raw) > 2048:
        raise AdmissionError()
    return raw


def unpack(kind, wire, count):
    prefix = f"umbra:admission:{kind}:1:"
    if not isinstance(wire, str) or len(wire) > MAX_WIRE or not wire.startswith(prefix):
        raise AdmissionError()
    parts = wire[len(prefix):].split(".")
    if len(parts) != 2:
        raise AdmissionError()
    raw, signature = decode(parts[0]), decode(parts[1], 64)
    try:
        lines = raw.decode("ascii").split("\n")
    except UnicodeDecodeError:
        raise AdmissionError() from None
    if len(lines) != count + 2 or lines[0] != f"UMBRA-ADMISSION-{kind}-1" or lines[-1]:
        raise AdmissionError()
    fields = lines[1:-1]
    if body(kind, fields) != raw:
        raise AdmissionError()
    return fields, signature


def verify(kind, fields, signature, public):
    try:
        VerifyKey(public_key(public)).verify(body(kind, fields), signature)
    except (BadSignatureError, ValueError):
        raise AdmissionError() from None


def interval(start, end, maximum):
    if start <= 0 or end <= start or end - start > maximum or end > 999999999999:
        raise AdmissionError()


def signal_key(value):
    if decode(value, 33)[0] != 5:
        raise AdmissionError()


@dataclass(frozen=True, repr=False)
class Realm:
    realm_id: str
    authority_public_key: str
    authority_key_id: str

    @classmethod
    def parse(cls, wire):
        if not isinstance(wire, str) or len(wire) > 256:
            raise AdmissionError()
        p = wire.split(":")
        if len(p) != 6 or p[:3] != ["umbra", "realm", "1"]:
            raise AdmissionError()
        decode(p[3], 32)
        if digest(public_key(p[4])) != p[5]:
            raise AdmissionError()
        return cls(*p[3:])

    def encode(self):
        return f"umbra:realm:1:{self.realm_id}:{self.authority_public_key}:{self.authority_key_id}"


@dataclass(frozen=True, repr=False)
class Request:
    wire: str
    realm_id: str
    request_id: str
    device_public_key: str
    signal_public_key: str
    created_at: int
    expires_at: int
    nonce: str

    @classmethod
    def parse(cls, wire, realm):
        p, sig = unpack("request", wire, 7)
        for value in (p[0], p[1], p[2], p[6]):
            decode(value, 32)
        public_key(p[2])
        signal_key(p[3])
        start, end = number(p[4]), number(p[5])
        interval(start, end, REQUEST_TTL)
        if p[0] != realm.realm_id or p[1] == p[6]:
            raise AdmissionError()
        verify("request", p, sig, p[2])
        return cls(wire, *p[:4], start, end, p[6])

    def current(self, now):
        if not self.created_at <= now < self.expires_at:
            raise AdmissionError()


@dataclass(frozen=True, repr=False)
class Credential:
    wire: str
    realm_id: str
    credential_id: str
    device_public_key: str
    signal_public_key: str
    issuer_key_id: str
    issued_at: int
    not_before: int
    expires_at: int
    capabilities: str
    request_id: str

    @classmethod
    def parse(cls, wire, realm):
        p, sig = unpack("credential", wire, 10)
        for value in (p[0], p[1], p[2], p[9]):
            decode(value, 32)
        public_key(p[2])
        signal_key(p[3])
        issued, start, end = number(p[5]), number(p[6]), number(p[7])
        interval(issued, end, CREDENTIAL_TTL)
        if p[0] != realm.realm_id or p[4] != realm.authority_key_id or p[8] != CAPABILITIES or not issued <= start < end:
            raise AdmissionError()
        verify("credential", p, sig, realm.authority_public_key)
        return cls(wire, *p[:5], issued, start, end, p[8], p[9])

    def current(self, now):
        if not self.not_before <= now < self.expires_at:
            raise AdmissionError()

    @property
    def device_id(self):
        return digest(decode(self.signal_public_key, 33))


@dataclass(frozen=True, repr=False)
class Revocation:
    wire: str
    realm_id: str
    credential_id: str
    device_public_key: str
    issuer_key_id: str
    sequence: int
    revoked_at: int
    reason: str

    @classmethod
    def parse(cls, wire, realm):
        p, sig = unpack("revocation", wire, 7)
        for value in p[:3]:
            decode(value, 32)
        public_key(p[2])
        sequence, revoked = number(p[4]), number(p[5])
        if p[0] != realm.realm_id or p[3] != realm.authority_key_id or p[6] not in ("owner_request", "device_lost", "policy"):
            raise AdmissionError()
        verify("revocation", p, sig, realm.authority_public_key)
        return cls(wire, *p[:4], sequence, revoked, p[6])


@dataclass(frozen=True, repr=False)
class Challenge:
    fields: tuple[str, ...]

    @classmethod
    def parse(cls, wire):
        prefix = "umbra:challenge:1:"
        if not isinstance(wire, str) or len(wire) > 2048 or not wire.startswith(prefix):
            raise AdmissionError()
        try:
            p = decode(wire[len(prefix):]).decode("ascii").split("\n")
        except UnicodeDecodeError:
            raise AdmissionError() from None
        if len(p) != 10 or p[0] != "UMBRA-ADMISSION-proof-1" or p[-1]:
            raise AdmissionError()
        for value in (p[1], p[2], p[4]):
            decode(value, 32)
        for value in (p[3], p[5], p[6]):
            hash_field(value)
        interval(number(p[7]), number(p[8]), CHALLENGE_TTL)
        result = cls(tuple(p[1:-1]))
        if result.encode() != wire:
            raise AdmissionError()
        return result

    def encode(self):
        return "umbra:challenge:1:" + encode(body("proof", self.fields))

    def validate(self, credential, verifier, operation, now):
        p = self.fields
        if (p[0] != credential.realm_id or p[1] != credential.credential_id or
                p[2] != digest(credential.wire.encode("ascii")) or p[4] != verifier or p[5] != operation or
                not number(p[6]) <= now < number(p[7])):
            raise AdmissionError()

    def verify_proof(self, credential, wire):
        fields, signature = unpack("proof", wire, 8)
        if tuple(fields) != self.fields:
            raise AdmissionError()
        verify("proof", fields, signature, credential.device_public_key)


@dataclass(frozen=True, repr=False)
class Rejection:
    wire: str
    realm_id: str
    request_id: str
    request_hash: str
    device_public_key: str
    issuer_key_id: str
    rejected_at: int

    @classmethod
    def parse(cls, wire, realm):
        p, signature = unpack("rejection", wire, 6)
        decode(p[0], 32); decode(p[1], 32); hash_field(p[2]); public_key(p[3])
        when = number(p[5])
        if p[0] != realm.realm_id or p[4] != realm.authority_key_id:
            raise AdmissionError()
        verify("rejection", p, signature, realm.authority_public_key)
        return cls(wire, *p[:5], when)

    def validate(self, request):
        if (self.realm_id != request.realm_id or self.request_id != request.request_id or
                self.request_hash != digest(request.wire.encode("ascii")) or self.device_public_key != request.device_public_key or
                not request.created_at <= self.rejected_at < request.expires_at):
            raise AdmissionError()
