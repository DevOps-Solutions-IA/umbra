"""Admission persistence in the relay transaction. Stores public signed objects only."""
import secrets
import time
from .admission_protocol import AdmissionError, Realm, Request, Credential, Revocation, Challenge, ChallengeUnavailable, digest

MAX_REQUESTS = 4096
MAX_CREDENTIALS = 4096
MAX_CHALLENGES = 1024


class AdmissionStore:
    def __init__(self, database, realm_config, verifier_origin, *, clock=time.time, elapsed=time.monotonic):
        self.database, self.clock, self.elapsed = database, clock, elapsed
        self.runtime = secrets.token_urlsafe(32)
        self.realm = Realm.parse(realm_config)
        if not verifier_origin.startswith("https://") or any(c in verifier_origin for c in "\r\n?#"):
            raise ValueError("Admission requires an explicit HTTPS verifier origin")
        from urllib.parse import urlsplit
        parsed = urlsplit(verifier_origin)
        if not parsed.hostname or parsed.username or parsed.password or parsed.path not in ("", "/"):
            raise ValueError("Invalid admission verifier origin")
        self.verifier = digest(verifier_origin.rstrip("/").encode("ascii"))
        with database.connect(write=True) as conn:
            row = conn.execute("SELECT config FROM admission_realm WHERE id=1").fetchone()
            if row and row[0] != self.realm.encode():
                raise AdmissionError()
            if not row:
                for table in ("admission_requests", "admission_credentials", "admission_revocations", "admission_challenges"):
                    if conn.execute(f"SELECT 1 FROM {table} LIMIT 1").fetchone():
                        raise AdmissionError()
                conn.execute("INSERT INTO admission_realm VALUES (1,?)", (self.realm.encode(),))

    def submit(self, wire):
        request = Request.parse(wire, self.realm)
        with self.database.connect(write=True) as conn:
            request.current(int(self.clock()))
            if conn.execute("SELECT 1 FROM admission_requests WHERE id=? OR nonce=?", (request.request_id, request.nonce)).fetchone():
                raise AdmissionError()
            if conn.execute("SELECT count(*) FROM admission_requests").fetchone()[0] >= MAX_REQUESTS:
                raise AdmissionError()
            conn.execute("INSERT INTO admission_requests VALUES (?,?,?,?,NULL)",
                         (request.request_id, request.nonce, wire, request.expires_at))
        return request.request_id

    def publish(self, wire):
        credential = Credential.parse(wire, self.realm)
        with self.database.connect(write=True) as conn:
            return self._publish(conn, credential)

    def _publish(self, conn, credential):
        wire = credential.wire
        credential.current(int(self.clock()))
        old = conn.execute("SELECT wire FROM admission_credentials WHERE id=?", (credential.credential_id,)).fetchone()
        if old:
            if old[0] != wire:
                raise AdmissionError()
            self.authorize(conn, credential)
            return
        row = conn.execute("SELECT wire,decision FROM admission_requests WHERE id=?", (credential.request_id,)).fetchone()
        if not row or row["decision"] is not None:
            raise AdmissionError()
        request = Request.parse(row["wire"], self.realm)
        if (request.device_public_key != credential.device_public_key or request.signal_public_key != credential.signal_public_key or
                not request.created_at <= credential.issued_at < request.expires_at):
            raise AdmissionError()
        if conn.execute("SELECT 1 FROM admission_revocations WHERE id=?", (credential.credential_id,)).fetchone():
            raise AdmissionError()
        if conn.execute("SELECT count(*) FROM admission_credentials").fetchone()[0] >= MAX_CREDENTIALS:
            raise AdmissionError()
        conn.execute("INSERT INTO admission_credentials VALUES (?,?,?)", (credential.credential_id, wire, credential.expires_at))
        conn.execute("UPDATE admission_requests SET decision=? WHERE id=?", (credential.credential_id, credential.request_id))

    def revoke(self, wire):
        revoked = Revocation.parse(wire, self.realm)
        with self.database.connect(write=True) as conn:
            return self._revoke(conn, revoked)

    def _revoke(self, conn, revoked):
        wire = revoked.wire
        old = conn.execute("SELECT wire FROM admission_revocations WHERE id=?", (revoked.credential_id,)).fetchone()
        if old:
            if old[0] != wire:
                raise AdmissionError()
            return
        row = conn.execute("SELECT wire FROM admission_credentials WHERE id=?", (revoked.credential_id,)).fetchone()
        if row and Credential.parse(row[0], self.realm).device_public_key != revoked.device_public_key:
            raise AdmissionError()
        # Do not reject an older sequence for another device: delivery may be reordered.
        if conn.execute("SELECT count(*) FROM admission_revocations").fetchone()[0] >= MAX_CREDENTIALS:
            raise AdmissionError()
        conn.execute("INSERT INTO admission_revocations VALUES (?,?,?)", (revoked.credential_id, wire, revoked.sequence))
        conn.execute("DELETE FROM admission_challenges WHERE credential=?", (revoked.credential_id,))

    def authorize(self, conn, credential):
        credential.current(int(self.clock()))
        row = conn.execute("SELECT wire FROM admission_credentials WHERE id=?", (credential.credential_id,)).fetchone()
        if not row or row[0] != credential.wire or conn.execute("SELECT 1 FROM admission_revocations WHERE id=?", (credential.credential_id,)).fetchone():
            raise AdmissionError()

    def _challenge(self, conn, credential, operation):
        self.authorize(conn, credential)
        now, elapsed = int(self.clock()), self.elapsed()
        conn.execute("DELETE FROM admission_challenges WHERE expires<=? OR runtime!=? OR deadline<=?", (now, self.runtime, elapsed))
        total = conn.execute("SELECT count(*) FROM admission_challenges").fetchone()[0]
        own = conn.execute("SELECT count(*) FROM admission_challenges WHERE credential=? AND used=0", (credential.credential_id,)).fetchone()[0]
        if total >= MAX_CHALLENGES or own >= 8:
            raise AdmissionError()
        nonce = secrets.token_urlsafe(32)
        challenge = Challenge((credential.realm_id, credential.credential_id, digest(credential.wire.encode("ascii")), nonce,
                               self.verifier, operation, str(now), str(now+30)))
        conn.execute("INSERT INTO admission_challenges VALUES (?,?,?,?,?,?,?)",
                     (nonce, credential.credential_id, challenge.encode(), now+30, elapsed+30, self.runtime, 0))
        return challenge

    def challenge(self, wire, operation):
        from .admission_protocol import hash_field
        hash_field(operation)
        credential = Credential.parse(wire, self.realm)
        with self.database.connect(write=True) as conn:
            return self._challenge(conn, credential, operation)

    def batch(self, wire):
        # One atomic bounded pool; no partial issuance or increased ingress rate limit.
        credential = Credential.parse(wire, self.realm)
        with self.database.connect(write=True) as conn:
            return [self._challenge(conn, credential, "0" * 64) for _ in range(8)]

    def consume(self, conn, credential_wire, challenge_wire, proof, operation):
        credential = Credential.parse(credential_wire, self.realm)
        self.authorize(conn, credential)
        challenge = Challenge.parse(challenge_wire)
        row = conn.execute("SELECT * FROM admission_challenges WHERE nonce=?", (challenge.fields[3],)).fetchone()
        if (not row or row["runtime"] != self.runtime or row["used"] or row["deadline"] <= self.elapsed()):
            raise ChallengeUnavailable()
        issued = Challenge.parse(row["wire"])
        expected = list(issued.fields)
        if expected[5] == "0" * 64:
            expected[5] = operation
        if tuple(expected) != challenge.fields:
            raise AdmissionError()
        challenge.validate(credential, self.verifier, operation, int(self.clock()))
        challenge.verify_proof(credential, proof)
        # Caller owns an IMMEDIATE transaction. Invalid proof never grants a principal.
        conn.execute("UPDATE admission_challenges SET used=1 WHERE nonce=?", (challenge.fields[3],))
        return credential

    def reject(self, wire):
        from .admission_protocol import Rejection
        rejection = Rejection.parse(wire, self.realm)
        with self.database.connect(write=True) as conn:
            row = conn.execute("SELECT wire,decision FROM admission_requests WHERE id=?", (rejection.request_id,)).fetchone()
            if not row:
                raise AdmissionError()
            rejection.validate(Request.parse(row["wire"], self.realm))
            if row["decision"] is not None and row["decision"] != wire:
                raise AdmissionError()
            conn.execute("UPDATE admission_requests SET decision=? WHERE id=?", (wire, rejection.request_id))

    def result_challenge(self, wire):
        request = Request.parse(wire, self.realm)
        with self.database.connect(write=True) as conn:
            row = conn.execute("SELECT wire,expires FROM admission_requests WHERE id=?", (request.request_id,)).fetchone()
            now, elapsed = int(self.clock()), self.elapsed()
            if not row or row["wire"] != wire or now < request.created_at or now >= row["expires"] + 604800:
                raise AdmissionError()
            conn.execute("DELETE FROM admission_challenges WHERE expires<=? OR runtime!=? OR deadline<=?", (now, self.runtime, elapsed))
            owner = "request:" + request.request_id
            if (conn.execute("SELECT count(*) FROM admission_challenges").fetchone()[0] >= MAX_CHALLENGES or
                    conn.execute("SELECT count(*) FROM admission_challenges WHERE credential=? AND used=0", (owner,)).fetchone()[0] >= 8):
                raise AdmissionError()
            nonce = secrets.token_urlsafe(32)
            challenge = Challenge((request.realm_id, request.request_id, digest(wire.encode("ascii")), nonce, self.verifier,
                                   digest(b"UMBRA-ADMISSION-RESULT-1"), str(now), str(now+30)))
            conn.execute("INSERT INTO admission_challenges VALUES (?,?,?,?,?,?,?)",
                         (nonce, owner, challenge.encode(), now+30, elapsed+30, self.runtime, 0))
            return challenge

    def result(self, wire, challenge_wire, proof):
        from .admission_protocol import unpack, verify, Rejection, number
        request = Request.parse(wire, self.realm)
        challenge = Challenge.parse(challenge_wire)
        with self.database.connect(write=True) as conn:
            stored = conn.execute("SELECT * FROM admission_challenges WHERE nonce=?", (challenge.fields[3],)).fetchone()
            row = conn.execute("SELECT wire,decision FROM admission_requests WHERE id=?", (request.request_id,)).fetchone()
            now = int(self.clock())
            if (not row or row["wire"] != wire or not stored or stored["wire"] != challenge_wire or stored["used"] or
                    stored["runtime"] != self.runtime or stored["deadline"] <= self.elapsed() or
                    stored["credential"] != "request:" + request.request_id or
                    not number(challenge.fields[6]) <= now < number(challenge.fields[7])):
                raise AdmissionError()
            fields, signature = unpack("request-proof", proof, 8)
            if tuple(fields) != challenge.fields:
                raise AdmissionError()
            verify("request-proof", fields, signature, request.device_public_key)
            conn.execute("UPDATE admission_challenges SET used=1 WHERE nonce=?", (challenge.fields[3],))
            decision = row["decision"]
            if decision is None:
                return {"state": "EXPIRED" if now >= request.expires_at else "REQUEST_PENDING"}
            if decision.startswith("umbra:admission:rejection:"):
                Rejection.parse(decision, self.realm).validate(request)
                return {"state": "REJECTED", "rejection": decision}
            credential = conn.execute("SELECT wire FROM admission_credentials WHERE id=?", (decision,)).fetchone()
            if not credential:
                raise AdmissionError()
            revoked = conn.execute("SELECT wire FROM admission_revocations WHERE id=?", (decision,)).fetchone()
            if revoked:
                return {"state": "REVOKED", "revocation": revoked[0]}
            return {"state": "APPROVED", "credential": credential[0]}

    def renew(self, credential_wire, revocation_wire):
        credential = Credential.parse(credential_wire, self.realm)
        revocation = Revocation.parse(revocation_wire, self.realm)
        with self.database.connect(write=True) as conn:
            previous = conn.execute("SELECT wire FROM admission_credentials WHERE id=?", (revocation.credential_id,)).fetchone()
            if not previous:
                raise AdmissionError()
            old = Credential.parse(previous[0], self.realm)
            if (old.credential_id == credential.credential_id or old.request_id == credential.request_id or
                    old.device_public_key != credential.device_public_key or old.signal_public_key != credential.signal_public_key or
                    revocation.device_public_key != old.device_public_key):
                raise AdmissionError()
            self._publish(conn, credential)
            self._revoke(conn, revocation)
