"""One-use rendezvous capabilities; no contact card, identity key or message content."""
import hashlib
import hmac
import re
import time
from typing import Annotated

from fastapi import Header, HTTPException, Response
from pydantic import BaseModel, ConfigDict, field_validator, model_validator

MAX_PAIRING_PER_BOX = 32
MAX_PAIRING_TOTAL = 4096
MAX_PAIRING_TTL = 86400


def install_pairing(app, database, authorize, validate_token, token_hash):
    class Invitation(BaseModel):
        model_config = ConfigDict(extra="forbid", strict=True)
        id: str
        consume_token: str
        revoke_token: str
        expires: int

        @field_validator("id", "consume_token", "revoke_token")
        @classmethod
        def token(cls, value):
            return validate_token(value)

        @model_validator(mode="after")
        def distinct(self):
            if len({self.id, self.consume_token, self.revoke_token}) != 3:
                raise ValueError("Independent capabilities required")
            return self

    class Claim(BaseModel):
        model_config = ConfigDict(extra="forbid", strict=True)
        request_hash: str

        @field_validator("request_hash")
        @classmethod
        def digest(cls, value):
            if not re.fullmatch(r"[0-9a-f]{64}", value):
                raise ValueError("Invalid request digest")
            return value

    def authorized_invitation(conn, invitation_id, authorization, mode, now):
        candidate = (authorization or "").removeprefix("Bearer ")
        try:
            validate_token(invitation_id)
            validate_token(candidate)
        except ValueError:
            raise HTTPException(403, "Invitation unavailable") from None
        row = conn.execute("SELECT * FROM pairing_invites WHERE id_hash=?", (token_hash(invitation_id),)).fetchone()
        expected = row[mode + "_hash"] if row else "0" * 64
        valid = hmac.compare_digest(token_hash(candidate), expected)
        if not valid or not (authorization or "").startswith("Bearer ") or row is None or row["expires"] <= now:
            raise HTTPException(403, "Invitation unavailable")
        return row

    @app.post("/v1/boxes/{box}/pairing-invites", status_code=201)
    def create(box: str, data: Invitation, response: Response,
               authorization: Annotated[str | None, Header()] = None):
        now = int(time.time())
        with database.connect(write=True) as conn:
            authorize(conn, box, authorization, "read")
            database.purge(conn, now)
            old = conn.execute("SELECT * FROM pairing_invites WHERE id_hash=?", (token_hash(data.id),)).fetchone()
            if old:
                if (old["box"] == box and old["expires"] == data.expires and
                        hmac.compare_digest(old["consume_hash"], token_hash(data.consume_token)) and
                        hmac.compare_digest(old["revoke_hash"], token_hash(data.revoke_token))):
                    if old["revoked"]:
                        raise HTTPException(403, "Invitation unavailable")
                    response.status_code = 200
                    return {"registered": True}
                raise HTTPException(409, "Invitation unavailable")
            if not now + 60 <= data.expires <= now + MAX_PAIRING_TTL:
                raise HTTPException(400, "Invalid expiry")
            total = conn.execute("SELECT COUNT(*) FROM pairing_invites").fetchone()[0]
            count = conn.execute("SELECT COUNT(*) FROM pairing_invites WHERE box=?", (box,)).fetchone()[0]
            if total >= MAX_PAIRING_TOTAL or count >= MAX_PAIRING_PER_BOX:
                raise HTTPException(429, "Invitation quota exceeded")
            conn.execute("INSERT INTO pairing_invites VALUES (?,?,?,?,?,NULL,0)",
                         (token_hash(data.id), box, token_hash(data.consume_token), token_hash(data.revoke_token), data.expires))
        return {"registered": True}

    @app.post("/v1/pairing-invites/{invitation_id}/claim")
    def claim(invitation_id: str, data: Claim, authorization: Annotated[str | None, Header()] = None):
        with database.connect(write=True) as conn:
            row = authorized_invitation(conn, invitation_id, authorization, "consume", int(time.time()))
            if row["revoked"]:
                raise HTTPException(403, "Invitation unavailable")
            request_digest = hashlib.sha256(data.request_hash.encode("ascii")).hexdigest()
            if row["request_hash"] is not None and not hmac.compare_digest(row["request_hash"], request_digest):
                raise HTTPException(409, "Invitation already claimed")
            conn.execute("UPDATE pairing_invites SET request_hash=? WHERE id_hash=?", (request_digest, row["id_hash"]))
        return {"claimed": True}

    @app.delete("/v1/pairing-invites/{invitation_id}", status_code=204)
    def revoke(invitation_id: str, authorization: Annotated[str | None, Header()] = None):
        with database.connect(write=True) as conn:
            row = authorized_invitation(conn, invitation_id, authorization, "revoke", int(time.time()))
            conn.execute("UPDATE pairing_invites SET revoked=1 WHERE id_hash=?", (row["id_hash"],))
        return Response(status_code=204)
