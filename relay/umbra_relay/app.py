from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import os
import re
import sqlite3
import time
from contextlib import asynccontextmanager, contextmanager
from pathlib import Path
from typing import Annotated
from uuid import UUID

from fastapi import FastAPI, Header, HTTPException, Request, Response, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from .guard import RequestLimits
from .maintenance import RetentionHealth, retention_loop
from .schema import validate_constraints
from .pairing import install_pairing
from .devices import install_devices

MAX_BODY = 1_000_000
MAX_CIPHERTEXT = 720_000
MAX_TTL = 7 * 86400
MAX_MESSAGES = 128
MAX_MAILBOX_BYTES = 16_000_000
MAX_RETAINED_IDS = 8192
MAX_BOXES = 1024
TOKEN = re.compile(r"^[A-Za-z0-9_-]{43}$")
IDENTITY = re.compile(r"^[0-9a-f]{64}$")


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("ascii")).hexdigest()


class Database:
    def __init__(self, path: str):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        # Set permissions at file creation, not after sensitive tokens have been written.
        try:
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            os.close(fd)
        except FileExistsError:
            if Path(path).is_symlink():
                raise ValueError("Database must not be a symlink")
        os.chmod(path, 0o600)
        with self.connect() as conn:
            self.validate_schema(conn)
            conn.execute("PRAGMA journal_mode=WAL")
        with self.connect(write=True) as conn:
            self.validate_schema(conn)
            conn.execute("CREATE TABLE IF NOT EXISTS invites(token_hash TEXT PRIMARY KEY, expires INTEGER NOT NULL)")
            conn.execute("CREATE TABLE IF NOT EXISTS boxes(id TEXT PRIMARY KEY, read_hash TEXT NOT NULL, write_hash TEXT NOT NULL, created INTEGER NOT NULL)")
            conn.execute("CREATE TABLE IF NOT EXISTS acknowledged(box TEXT NOT NULL REFERENCES boxes(id) ON DELETE CASCADE, id TEXT NOT NULL, digest TEXT NOT NULL, expires INTEGER NOT NULL, PRIMARY KEY(box,id))")
            exists = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='messages'").fetchone()
            columns = {r["name"] for r in conn.execute("PRAGMA table_info(messages)")} if exists else set()
            migrating = exists and "seq" not in columns
            if migrating:
                conn.execute("ALTER TABLE messages RENAME TO messages_v1")
                conn.execute("DROP INDEX IF EXISTS messages_expiry")
            conn.execute("""CREATE TABLE IF NOT EXISTS messages(
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                box TEXT NOT NULL REFERENCES boxes(id) ON DELETE CASCADE,
                id TEXT NOT NULL, digest TEXT NOT NULL, envelope TEXT NOT NULL,
                size INTEGER NOT NULL, expires INTEGER NOT NULL, created INTEGER NOT NULL,
                UNIQUE(box,id))""")
            if migrating:
                conn.execute("INSERT INTO messages(box,id,digest,envelope,size,expires,created) SELECT box,id,digest,envelope,size,expires,created FROM messages_v1 ORDER BY rowid")
                conn.execute("DROP TABLE messages_v1")
            conn.execute("CREATE INDEX IF NOT EXISTS messages_expiry ON messages(expires)")
            conn.execute("CREATE INDEX IF NOT EXISTS messages_box_seq ON messages(box,seq)")
            conn.execute("CREATE INDEX IF NOT EXISTS acknowledged_expiry ON acknowledged(expires)")
            conn.execute("""CREATE TABLE IF NOT EXISTS pairing_invites(
                id_hash TEXT PRIMARY KEY, box TEXT NOT NULL REFERENCES boxes(id) ON DELETE CASCADE,
                consume_hash TEXT NOT NULL, revoke_hash TEXT NOT NULL, expires INTEGER NOT NULL,
                request_hash TEXT, revoked INTEGER NOT NULL)""")
            conn.execute("CREATE INDEX IF NOT EXISTS pairing_expiry ON pairing_invites(expires)")
            conn.execute("CREATE INDEX IF NOT EXISTS pairing_box ON pairing_invites(box)")
            conn.execute("CREATE TABLE IF NOT EXISTS device_revocations(box TEXT PRIMARY KEY, cap_hash TEXT NOT NULL, revoked INTEGER NOT NULL, created INTEGER NOT NULL)")
            conn.execute("PRAGMA user_version=4")

    @staticmethod
    def validate_schema(conn: sqlite3.Connection) -> None:
        """Refuse destructive schema adoption/downgrade before any migration or WAL change."""
        version = conn.execute("PRAGMA user_version").fetchone()[0]
        expected = {"invites", "boxes", "acknowledged", "messages"}
        if version >= 3:
            expected.add("pairing_invites")
        if version >= 4:
            expected.add("device_revocations")
        tables = {row[0] for row in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
        if version not in (0, 1, 2, 3, 4) or not tables <= expected:
            raise ValueError("Unsupported database schema; explicit migration required")
        if version == 0 and not tables:
            return  # The only path allowed to initialize an empty database.
        if not {"boxes", "messages"} <= tables or (version >= 2 and tables != expected):
            raise ValueError("Incomplete database schema; refusing silent recreation")
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(messages)")}
        legacy = {"box", "id", "digest", "envelope", "size", "expires", "created"}
        required = legacy | {"seq"} if version >= 2 else legacy
        if columns != required:
            raise ValueError("Incompatible message schema; explicit migration required")
        validate_constraints(conn, tables, version)

    @contextmanager
    def connect(self, write: bool = False):
        connection = sqlite3.connect(self.path, timeout=15, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA secure_delete=ON")
        connection.execute("PRAGMA synchronous=FULL")
        connection.execute("PRAGMA temp_store=MEMORY")
        try:
            if write:
                connection.execute("BEGIN IMMEDIATE")
            yield connection
            if write:
                connection.commit()
        except BaseException:
            if write:
                connection.rollback()
            raise
        finally:
            connection.close()

    @staticmethod
    def purge(db: sqlite3.Connection, now: int):
        db.execute("DELETE FROM messages WHERE expires<=?", (now,))
        db.execute("DELETE FROM acknowledged WHERE expires<=?", (now,))
        db.execute("DELETE FROM invites WHERE expires<=?", (now,))
        db.execute("DELETE FROM pairing_invites WHERE expires<=?", (now,))

    def issue_invite(self, ttl: int = 3600) -> str:
        import secrets
        if not 60 <= ttl <= 7 * 86400:
            raise ValueError("Invitation TTL must be 60..604800 seconds")
        token = secrets.token_urlsafe(32)
        with self.connect(write=True) as db:
            self.purge(db, int(time.time()))
            db.execute("INSERT INTO invites VALUES (?,?)", (token_hash(token), int(time.time()) + ttl))
        return token


def validate_token(value: str) -> str:
    if not TOKEN.fullmatch(value):
        raise ValueError("Expected a 256-bit base64url token")
    if base64.urlsafe_b64encode(base64.urlsafe_b64decode(value + "=")).decode().rstrip("=") != value:
        raise ValueError("Noncanonical token")
    return value


class Register(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    id: str
    read_token: str
    write_token: str
    invitation: str

    @field_validator("id")
    @classmethod
    def box_id(cls, value: str) -> str:
        if str(UUID(value)) != value:
            raise ValueError("Noncanonical UUID")
        return value

    @field_validator("read_token", "write_token", "invitation")
    @classmethod
    def secret(cls, value: str) -> str:
        return validate_token(value)


class Envelope(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, populate_by_name=False)
    v: int = Field(ge=1, le=1)
    id: str
    sender: str = Field(alias="from")
    to: str
    type: int
    ct: str = Field(min_length=16, max_length=960_000)
    expires: int

    @field_validator("id")
    @classmethod
    def message_id(cls, value: str) -> str:
        if str(UUID(value)) != value:
            raise ValueError("Noncanonical UUID")
        return value

    @field_validator("sender", "to")
    @classmethod
    def identity(cls, value: str) -> str:
        if not IDENTITY.fullmatch(value):
            raise ValueError("Invalid identity")
        return value

    @field_validator("type")
    @classmethod
    def cipher_type(cls, value: int) -> int:
        if value not in (2, 3):
            raise ValueError("Only Signal message or pre-key message allowed")
        return value

    @field_validator("ct")
    @classmethod
    def ciphertext(cls, value: str) -> str:
        try:
            data = base64.b64decode(value, validate=True)
        except Exception as exc:
            raise ValueError("Invalid base64") from exc
        if not 16 <= len(data) <= MAX_CIPHERTEXT:
            raise ValueError("Invalid ciphertext length")
        if base64.b64encode(data).decode() != value:
            raise ValueError("Noncanonical base64")
        return value

    @model_validator(mode="after")
    def distinct_identities(self):
        if self.sender == self.to:
            raise ValueError("Sender and recipient must differ")
        return self


def create_app(database_path: str | None = None, *, rate_limit: int = 240) -> FastAPI:
    db = Database(database_path or os.environ.get("UMBRA_DB", "/data/umbra.sqlite3"))

    @asynccontextmanager
    async def lifespan(app):
        import asyncio
        def cleanup():
            with db.connect(write=True) as conn:
                db.purge(conn, int(time.time()))
        task = asyncio.create_task(retention_loop(cleanup, app.state.retention_health))
        try:
            yield
        finally:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

    app = FastAPI(title="UMBRA Relay", version="0.2.0", docs_url=None,
                  redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.database = db
    app.state.retention_health = RetentionHealth()
    app.add_middleware(RequestLimits, per_minute=rate_limit, max_body=MAX_BODY)

    def authorize(conn, box: str, authorization: str | None, mode: str):
        row = conn.execute("SELECT read_hash,write_hash FROM boxes WHERE id=?", (box,)).fetchone()
        candidate = (authorization or "").removeprefix("Bearer ")
        valid_format = TOKEN.fullmatch(candidate) is not None and (authorization or "").startswith("Bearer ")
        actual = token_hash(candidate) if valid_format else "0" * 64
        expected = row[f"{mode}_hash"] if row else "1" * 64
        if not hmac.compare_digest(actual, expected):
            raise HTTPException(401, "Unauthorized")

    install_pairing(app, db, authorize, validate_token, token_hash)
    install_devices(app, db, authorize, validate_token, token_hash)

    @app.get("/healthz")
    def health():
        if not app.state.retention_health.healthy:
            return JSONResponse({"status": "degraded"}, status_code=503)
        return {"status": "ok"}

    @app.post("/v1/boxes", status_code=201)
    def register(data: Register, response: Response):
        if hmac.compare_digest(data.read_token, data.write_token):
            raise HTTPException(400, "Separate read/write capabilities required")
        now = int(time.time())
        with db.connect(write=True) as conn:
            db.purge(conn, now)
            existing = conn.execute("SELECT * FROM boxes WHERE id=?", (data.id,)).fetchone()
            if existing:
                # Idempotent registration after an ambiguous network failure.
                if (hmac.compare_digest(existing["read_hash"], token_hash(data.read_token)) and
                        hmac.compare_digest(existing["write_hash"], token_hash(data.write_token))):
                    response.status_code = 200
                    return {"id": data.id, "registered": True}
                raise HTTPException(409, "Mailbox unavailable")
            if conn.execute("SELECT 1 FROM device_revocations WHERE box=?", (data.id,)).fetchone():
                raise HTTPException(409, "Mailbox unavailable")
            if conn.execute("SELECT COUNT(*) FROM boxes").fetchone()[0] >= MAX_BOXES:
                raise HTTPException(503, "Mailbox capacity reached")
            deleted = conn.execute("DELETE FROM invites WHERE token_hash=? AND expires>?",
                                   (token_hash(data.invitation), now)).rowcount
            if deleted != 1:
                raise HTTPException(403, "Invalid or expired invitation")
            conn.execute("INSERT INTO boxes VALUES (?,?,?,?)",
                         (data.id, token_hash(data.read_token), token_hash(data.write_token), now))
        return {"id": data.id, "registered": True}

    @app.put("/v1/boxes/{box}/messages/{message_id}", status_code=201)
    def put(box: str, message_id: str, message: Envelope, response: Response,
            authorization: Annotated[str | None, Header()] = None):
        now = int(time.time())
        if message.id != message_id:
            raise HTTPException(400, "Message id mismatch")
        if not now < message.expires <= now + MAX_TTL:
            raise HTTPException(400, "Invalid expiry")
        serialized = json.dumps(message.model_dump(by_alias=True), sort_keys=True, separators=(",", ":"))
        digest = hashlib.sha256(serialized.encode()).hexdigest()
        with db.connect(write=True) as conn:
            authorize(conn, box, authorization, "write")
            db.purge(conn, now)
            old = conn.execute("SELECT digest FROM messages WHERE box=? AND id=? UNION ALL "
                               "SELECT digest FROM acknowledged WHERE box=? AND id=?",
                               (box, message_id, box, message_id)).fetchone()
            if old:
                if not hmac.compare_digest(old["digest"], digest):
                    raise HTTPException(409, "Message id collision")
                response.status_code = 200
                return {"accepted": True, "duplicate": True}
            usage = conn.execute("SELECT COUNT(*) AS n,COALESCE(SUM(size),0) AS bytes FROM messages WHERE box=?",
                                 (box,)).fetchone()
            if usage["n"] >= MAX_MESSAGES or usage["bytes"] + len(serialized) > MAX_MAILBOX_BYTES:
                raise HTTPException(429, "Mailbox quota exceeded")
            retained = conn.execute("SELECT (SELECT COUNT(*) FROM acknowledged WHERE box=?) + (SELECT COUNT(*) FROM messages WHERE box=?)", (box, box)).fetchone()[0]
            if retained >= MAX_RETAINED_IDS:
                raise HTTPException(429, "Mailbox retention quota exceeded")
            conn.execute("INSERT INTO messages(box,id,digest,envelope,size,expires,created) VALUES (?,?,?,?,?,?,?)",
                         (box, message_id, digest, serialized, len(serialized), message.expires, now))
        return {"accepted": True, "duplicate": False}

    @app.get("/v1/boxes/{box}/messages")
    def get(box: str, authorization: Annotated[str | None, Header()] = None, after: int = Query(default=0, ge=0, le=9223372036854775807)):
        with db.connect(write=True) as conn:
            authorize(conn, box, authorization, "read")
            db.purge(conn, int(time.time()))
            # A single large attachment must not inflate a response beyond the client limit.
            rows = conn.execute("SELECT seq AS cursor,envelope FROM messages WHERE box=? AND seq>? ORDER BY seq LIMIT 5",
                                (box, after)).fetchall()
        return {"messages": [json.loads(r["envelope"]) for r in rows],
                "next_cursor": rows[-1]["cursor"] if rows else after, "more": len(rows) == 5}

    @app.delete("/v1/boxes/{box}/messages/{message_id}", status_code=204)
    def acknowledge(box: str, message_id: str,
                    authorization: Annotated[str | None, Header()] = None):
        with db.connect(write=True) as conn:
            authorize(conn, box, authorization, "read")
            row = conn.execute("SELECT digest,expires FROM messages WHERE box=? AND id=?",
                               (box, message_id)).fetchone()
            if row:
                conn.execute("INSERT OR REPLACE INTO acknowledged VALUES (?,?,?,?)",
                             (box, message_id, row["digest"], max(row["expires"], int(time.time()) + MAX_TTL)))
                conn.execute("DELETE FROM messages WHERE box=? AND id=?", (box, message_id))
        return Response(status_code=204)

    @app.delete("/v1/boxes/{box}", status_code=204)
    def delete_box(box: str, authorization: Annotated[str | None, Header()] = None):
        with db.connect(write=True) as conn:
            authorize(conn, box, authorization, "read")
            conn.execute("UPDATE device_revocations SET revoked=1 WHERE box=?", (box,))
            conn.execute("DELETE FROM boxes WHERE id=?", (box,))
        return Response(status_code=204)

    @app.exception_handler(sqlite3.Error)
    async def storage_error(request: Request, exc: sqlite3.Error):
        # Transactions have already rolled back. Do not expose SQL diagnostics to
        # clients or let the ASGI server log request-associated tracebacks.
        app.state.retention_health.healthy = False
        logging.getLogger("umbra_relay.storage").warning(
            "Relay storage operation failed (SQLite code %s)", getattr(exc, "sqlite_errorcode", None))
        return JSONResponse({"detail": "Storage temporarily unavailable"}, status_code=503,
                            headers={"Retry-After": "60"})

    # Never return validation errors that echo secrets or ciphertext from request input.
    from fastapi.exceptions import RequestValidationError
    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError):
        return JSONResponse({"detail": "Invalid request schema"}, status_code=422)
    return app
