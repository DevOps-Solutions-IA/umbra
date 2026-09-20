"""Opaque, deletion-only delegated mailbox capabilities. Never authorizes Signal keys."""
import hmac
import time
from typing import Annotated
from uuid import UUID
from fastapi import Header, HTTPException, Response
from pydantic import BaseModel, ConfigDict, field_validator

MAX_DELEGATIONS = 4096


def install_devices(app, db, authorize, validate_token, token_hash):
    class Delegation(BaseModel):
        model_config = ConfigDict(extra="forbid", strict=True)
        token: str

        @field_validator("token")
        @classmethod
        def capability(cls, value):
            return validate_token(value)

    def box_id(value):
        try:
            if str(UUID(value)) != value:
                raise ValueError()
        except ValueError:
            raise HTTPException(400, "Invalid mailbox") from None

    @app.put("/v1/boxes/{box}/revocation", status_code=201)
    def delegate(box: str, data: Delegation, response: Response,
                 authorization: Annotated[str | None, Header()] = None):
        box_id(box)
        with db.connect(write=True) as conn:
            authorize(conn, box, authorization, "read")
            digest = token_hash(data.token)
            mailbox = conn.execute("SELECT read_hash,write_hash FROM boxes WHERE id=?", (box,)).fetchone()
            if any(hmac.compare_digest(digest, mailbox[k]) for k in ("read_hash", "write_hash")):
                raise HTTPException(400, "Separate capabilities required")
            old = conn.execute("SELECT cap_hash,revoked FROM device_revocations WHERE box=?", (box,)).fetchone()
            if old:
                if old["revoked"] or not hmac.compare_digest(old["cap_hash"], digest):
                    raise HTTPException(409, "Delegation unavailable")
                response.status_code = 200
            else:
                if conn.execute("SELECT COUNT(*) FROM device_revocations").fetchone()[0] >= MAX_DELEGATIONS:
                    raise HTTPException(503, "Delegation capacity reached")
                conn.execute("INSERT INTO device_revocations VALUES (?,?,0,?)", (box, digest, int(time.time())))
        return {"delegated": True}

    @app.delete("/v1/boxes/{box}/revocation", status_code=204)
    def revoke(box: str, authorization: Annotated[str | None, Header()] = None):
        box_id(box)
        candidate = (authorization or "").removeprefix("Bearer ")
        try:
            validate_token(candidate)
            if not (authorization or "").startswith("Bearer "):
                raise ValueError()
        except ValueError:
            raise HTTPException(401, "Unauthorized") from None
        with db.connect(write=True) as conn:
            row = conn.execute("SELECT cap_hash FROM device_revocations WHERE box=?", (box,)).fetchone()
            if row is None or not hmac.compare_digest(row["cap_hash"], token_hash(candidate)):
                raise HTTPException(401, "Unauthorized")
            conn.execute("UPDATE device_revocations SET revoked=1 WHERE box=?", (box,))
            conn.execute("DELETE FROM boxes WHERE id=?", (box,))
        return Response(status_code=204)
