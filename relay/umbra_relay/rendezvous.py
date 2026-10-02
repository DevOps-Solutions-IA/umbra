"""Bounded opaque pairing delivery. Selection is owner's decision, never identity proof."""
import base64
import hmac
import re
import sqlite3
import time
from typing import Annotated

from fastapi import Header, HTTPException, Response
from pydantic import BaseModel, ConfigDict, field_validator, model_validator

MAX_PER_BOX = 32
MAX_TOTAL = 4096
MAX_CANDIDATES = 8
MAX_BLOB = 65536


def install_rendezvous(app, database, authorize, validate_token, token_hash):
    class Strict(BaseModel):
        model_config = ConfigDict(extra="forbid", strict=True)

    def blob(value):
        if value is None:
            return value
        if len(value) > ((MAX_BLOB + 2) // 3) * 4:
            raise ValueError("Invalid encrypted blob")
        try:
            raw = base64.b64decode(value, validate=True)
        except (ValueError, UnicodeError):
            raise ValueError("Invalid encrypted blob") from None
        if not 28 <= len(raw) <= MAX_BLOB or base64.b64encode(raw).decode('ascii') != value:
            raise ValueError("Invalid encrypted blob")
        return value

    class Publish(Strict):
        id: str
        owner_token: str
        request_token: str
        expires: int
        code_locator: str | None = None
        code_read_token: str | None = None
        invite_blob: str | None = None

        @field_validator('id', 'owner_token', 'request_token', 'code_locator', 'code_read_token')
        @classmethod
        def token(cls, value):
            return validate_token(value) if value is not None else None

        _blob = field_validator('invite_blob')(blob)

        @model_validator(mode='after')
        def independent(self):
            optional = (self.code_locator, self.code_read_token, self.invite_blob)
            if any(x is not None for x in optional) and not all(x is not None for x in optional):
                raise ValueError('Incomplete code')
            tokens = [self.id, self.owner_token, self.request_token]
            if self.code_locator is not None:
                tokens += [self.code_locator, self.code_read_token]
            if len(set(tokens)) != len(tokens):
                raise ValueError('Independent capabilities required')
            return self

    class CodeClaim(Strict):
        request_id: str
        _token = field_validator('request_id')(validate_token)

    class Candidate(CodeClaim):
        ack_token: str
        request_blob: str
        _ack = field_validator('ack_token')(validate_token)
        _blob = field_validator('request_blob')(blob)

    class Ack(Strict):
        request_hash: str
        ack_blob: str
        _blob = field_validator('ack_blob')(blob)

        @field_validator('request_hash')
        @classmethod
        def digest(cls, value):
            if not re.fullmatch('[0-9a-f]{64}', value):
                raise ValueError('Invalid request hash')
            return value

    def bearer(authorization):
        try:
            if not authorization or not authorization.startswith('Bearer '):
                raise ValueError()
            return token_hash(validate_token(authorization[7:]))
        except ValueError:
            raise HTTPException(403, 'Rendezvous unavailable') from None

    def lookup(conn, invitation_id, authorization, mode, allow_revoked=False):
        cap = bearer(authorization)
        try:
            hashed = token_hash(validate_token(invitation_id))
        except ValueError:
            raise HTTPException(403, 'Rendezvous unavailable') from None
        row = conn.execute('SELECT * FROM pairing_rendezvous WHERE id_hash=?', (hashed,)).fetchone()
        if (row is None or not hmac.compare_digest(row[mode + '_hash'], cap) or
                row['expires'] <= int(time.time()) or (row['revoked'] and not allow_revoked)):
            raise HTTPException(403, 'Rendezvous unavailable')
        return row

    @app.post('/v1/boxes/{box}/pairing-rendezvous', status_code=201)
    def publish(box: str, data: Publish, response: Response,
                authorization: Annotated[str | None, Header()] = None):
        values = (token_hash(data.id), box, token_hash(data.owner_token), token_hash(data.request_token),
                  token_hash(data.code_locator) if data.code_locator else None,
                  token_hash(data.code_read_token) if data.code_read_token else None,
                  data.invite_blob, data.expires)
        with database.connect(write=True) as conn:
            authorize(conn, box, authorization, 'read')
            now = int(time.time())
            database.purge(conn, now)
            old = conn.execute('SELECT * FROM pairing_rendezvous WHERE id_hash=?', (values[0],)).fetchone()
            if old:
                if old['revoked']:
                    raise HTTPException(403, 'Rendezvous unavailable')
                if tuple(old)[:8] != values:
                    raise HTTPException(409, 'Rendezvous unavailable')
                response.status_code = 200
                return {'registered': True}
            if not now < data.expires <= now + 600:
                raise HTTPException(400, 'Invalid expiry')
            if (conn.execute('SELECT COUNT(*) FROM pairing_rendezvous').fetchone()[0] >= MAX_TOTAL or
                    conn.execute('SELECT COUNT(*) FROM pairing_rendezvous WHERE box=?', (box,)).fetchone()[0] >= MAX_PER_BOX):
                raise HTTPException(429, 'Rendezvous quota exceeded')
            try:
                conn.execute('INSERT INTO pairing_rendezvous VALUES (?,?,?,?,?,?,?,?,NULL,NULL,NULL,0)', values)
            except sqlite3.IntegrityError:
                raise HTTPException(409, 'Rendezvous unavailable') from None
        return {'registered': True}

    @app.post('/v1/pairing-codes/{locator}/claim')
    def code_claim(locator: str, data: CodeClaim, authorization: Annotated[str | None, Header()] = None):
        cap = bearer(authorization)
        try:
            locator_hash = token_hash(validate_token(locator))
        except ValueError:
            raise HTTPException(403, 'Rendezvous unavailable') from None
        with database.connect(write=True) as conn:
            row = conn.execute('SELECT * FROM pairing_rendezvous WHERE locator_hash=?', (locator_hash,)).fetchone()
            if (row is None or row['revoked'] or row['expires'] <= int(time.time()) or
                    not hmac.compare_digest(row['code_hash'], cap)):
                raise HTTPException(403, 'Rendezvous unavailable')
            request_hash = token_hash(data.request_id)
            if row['code_claim_hash'] not in (None, request_hash):
                raise HTTPException(409, 'Code already claimed')
            conn.execute('UPDATE pairing_rendezvous SET code_claim_hash=? WHERE id_hash=?', (request_hash, row['id_hash']))
            return {'invite_blob': row['invite_blob'], 'expires': row['expires']}

    @app.post('/v1/pairing-rendezvous/{invitation_id}/requests', status_code=201)
    def candidate(invitation_id: str, data: Candidate, response: Response,
                  authorization: Annotated[str | None, Header()] = None):
        with database.connect(write=True) as conn:
            row = lookup(conn, invitation_id, authorization, 'request')
            request_hash, ack_hash = token_hash(data.request_id), token_hash(data.ack_token)
            if ack_hash in (row['owner_hash'], row['request_hash'], row['code_hash'], request_hash, row['id_hash']):
                raise HTTPException(400, 'Independent capabilities required')
            old = conn.execute('SELECT * FROM pairing_candidates WHERE rendezvous=? AND request_hash=?', (row['id_hash'], request_hash)).fetchone()
            if old:
                if old['ack_hash'] != ack_hash or old['request_blob'] != data.request_blob:
                    raise HTTPException(409, 'Request is immutable')
                response.status_code = 200
            else:
                if row['selected_hash'] is not None:
                    raise HTTPException(409, 'Rendezvous already selected')
                if conn.execute('SELECT COUNT(*) FROM pairing_candidates WHERE rendezvous=?', (row['id_hash'],)).fetchone()[0] >= MAX_CANDIDATES:
                    raise HTTPException(429, 'Candidate quota exceeded')
                conn.execute('INSERT INTO pairing_candidates VALUES (?,?,?,?)', (row['id_hash'], request_hash, ack_hash, data.request_blob))
            return {'request_hash': request_hash}

    @app.get('/v1/pairing-rendezvous/{invitation_id}/requests')
    def pending(invitation_id: str, authorization: Annotated[str | None, Header()] = None):
        with database.connect() as conn:
            row = lookup(conn, invitation_id, authorization, 'owner')
            rows = conn.execute('SELECT request_hash,request_blob FROM pairing_candidates WHERE rendezvous=? ORDER BY request_hash', (row['id_hash'],))
            return {'requests': [dict(r) for r in rows]}

    @app.post('/v1/pairing-rendezvous/{invitation_id}/ack')
    def select(invitation_id: str, data: Ack, authorization: Annotated[str | None, Header()] = None):
        with database.connect(write=True) as conn:
            row = lookup(conn, invitation_id, authorization, 'owner')
            if not conn.execute('SELECT 1 FROM pairing_candidates WHERE rendezvous=? AND request_hash=?', (row['id_hash'], data.request_hash)).fetchone():
                raise HTTPException(409, 'Unknown candidate')
            if row['selected_hash'] is not None and (row['selected_hash'] != data.request_hash or row['ack_blob'] != data.ack_blob):
                raise HTTPException(409, 'Selection is immutable')
            conn.execute('UPDATE pairing_rendezvous SET selected_hash=?,ack_blob=? WHERE id_hash=?', (data.request_hash, data.ack_blob, row['id_hash']))
        return {'selected': True}

    @app.get('/v1/pairing-rendezvous/{invitation_id}/requests/{request_hash}/ack')
    def receive_ack(invitation_id: str, request_hash: str, authorization: Annotated[str | None, Header()] = None):
        cap = bearer(authorization)
        try:
            hashed = token_hash(validate_token(invitation_id))
        except ValueError:
            raise HTTPException(403, 'Rendezvous unavailable') from None
        with database.connect() as conn:
            row = conn.execute('SELECT * FROM pairing_rendezvous WHERE id_hash=?', (hashed,)).fetchone()
            candidate = conn.execute('SELECT ack_hash FROM pairing_candidates WHERE rendezvous=? AND request_hash=?', (hashed, request_hash)).fetchone()
            if (row is None or row['revoked'] or row['expires'] <= int(time.time()) or candidate is None or
                    not hmac.compare_digest(candidate['ack_hash'], cap)):
                raise HTTPException(403, 'Rendezvous unavailable')
            if row['selected_hash'] is None:
                return Response(status_code=204)
            if row['selected_hash'] != request_hash:
                raise HTTPException(409, 'Another candidate selected')
            return {'ack_blob': row['ack_blob']}

    @app.delete('/v1/pairing-rendezvous/{invitation_id}', status_code=204)
    def revoke(invitation_id: str, authorization: Annotated[str | None, Header()] = None):
        with database.connect(write=True) as conn:
            row = lookup(conn, invitation_id, authorization, 'owner', allow_revoked=True)
            conn.execute('UPDATE pairing_rendezvous SET revoked=1 WHERE id_hash=?', (row['id_hash'],))
        return Response(status_code=204)
