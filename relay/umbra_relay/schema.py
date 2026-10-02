"""Validate persisted constraints before adopting or migrating a relay database."""
import re
import sqlite3

# name, SQLite declared type, NOT NULL flag, primary-key position.
COLUMNS = {
    "device_revocations": [("box", "TEXT", 0, 1), ("cap_hash", "TEXT", 1, 0),
                           ("revoked", "INTEGER", 1, 0), ("created", "INTEGER", 1, 0)],
    "pairing_invites": [("id_hash", "TEXT", 0, 1), ("box", "TEXT", 1, 0),
                        ("consume_hash", "TEXT", 1, 0), ("revoke_hash", "TEXT", 1, 0),
                        ("expires", "INTEGER", 1, 0), ("request_hash", "TEXT", 0, 0),
                        ("revoked", "INTEGER", 1, 0)],
    "invites": [("token_hash", "TEXT", 0, 1), ("expires", "INTEGER", 1, 0)],
    "boxes": [("id", "TEXT", 0, 1), ("read_hash", "TEXT", 1, 0),
              ("write_hash", "TEXT", 1, 0), ("created", "INTEGER", 1, 0)],
    "acknowledged": [("box", "TEXT", 1, 1), ("id", "TEXT", 1, 2),
                     ("digest", "TEXT", 1, 0), ("expires", "INTEGER", 1, 0)],
    "messages": [("seq", "INTEGER", 0, 1), ("box", "TEXT", 1, 0),
                 ("id", "TEXT", 1, 0), ("digest", "TEXT", 1, 0),
                 ("envelope", "TEXT", 1, 0), ("size", "INTEGER", 1, 0),
                 ("expires", "INTEGER", 1, 0), ("created", "INTEGER", 1, 0)],
}
UNIQUE_KEYS = {"device_revocations": {("box",)}, "pairing_invites": {("id_hash",)}, "invites": {("token_hash",)}, "boxes": {("id",)},
               "acknowledged": {("box", "id")}, "messages": {("box", "id")}}

# Admission v5: only public signed material. No signing key column exists.
COLUMNS.update({
    "admission_realm": [("id", "INTEGER", 0, 1), ("config", "TEXT", 1, 0)],
    "admission_requests": [("id", "TEXT", 0, 1), ("nonce", "TEXT", 1, 0), ("wire", "TEXT", 1, 0),
                           ("expires", "INTEGER", 1, 0), ("decision", "TEXT", 0, 0)],
    "admission_credentials": [("id", "TEXT", 0, 1), ("wire", "TEXT", 1, 0), ("expires", "INTEGER", 1, 0)],
    "admission_revocations": [("id", "TEXT", 0, 1), ("wire", "TEXT", 1, 0), ("sequence", "INTEGER", 1, 0)],
    "admission_challenges": [("nonce", "TEXT", 0, 1), ("credential", "TEXT", 1, 0), ("wire", "TEXT", 1, 0),
                             ("expires", "INTEGER", 1, 0), ("deadline", "REAL", 1, 0), ("runtime", "TEXT", 1, 0),
                             ("used", "INTEGER", 1, 0)],
})
UNIQUE_KEYS.update({"admission_realm": set(), "admission_requests": {("id",), ("nonce",)},
                    "admission_credentials": {("id",)}, "admission_revocations": {("id",)},
                    "admission_challenges": {("nonce",)}})
ADMISSION_TABLES = {name for name in COLUMNS if name.startswith("admission_")}


RENDEZVOUS_TABLES = {"pairing_rendezvous", "pairing_candidates"}
COLUMNS.update({
    "pairing_rendezvous": [("id_hash", "TEXT", 0, 1), ("box", "TEXT", 1, 0),
        ("owner_hash", "TEXT", 1, 0), ("request_hash", "TEXT", 1, 0),
        ("locator_hash", "TEXT", 0, 0), ("code_hash", "TEXT", 0, 0),
        ("invite_blob", "TEXT", 0, 0), ("expires", "INTEGER", 1, 0),
        ("code_claim_hash", "TEXT", 0, 0), ("selected_hash", "TEXT", 0, 0),
        ("ack_blob", "TEXT", 0, 0), ("revoked", "INTEGER", 1, 0)],
    "pairing_candidates": [("rendezvous", "TEXT", 1, 1), ("request_hash", "TEXT", 1, 2),
        ("ack_hash", "TEXT", 1, 0), ("request_blob", "TEXT", 1, 0)],
})
UNIQUE_KEYS.update({"pairing_rendezvous": {("id_hash",), ("locator_hash",)},
                    "pairing_candidates": {("rendezvous", "request_hash")}})


def migrate_rendezvous(conn):
    conn.execute("""CREATE TABLE pairing_rendezvous(
        id_hash TEXT PRIMARY KEY, box TEXT NOT NULL REFERENCES boxes(id) ON DELETE CASCADE,
        owner_hash TEXT NOT NULL, request_hash TEXT NOT NULL, locator_hash TEXT UNIQUE,
        code_hash TEXT, invite_blob TEXT, expires INTEGER NOT NULL, code_claim_hash TEXT,
        selected_hash TEXT, ack_blob TEXT, revoked INTEGER NOT NULL)""")
    conn.execute("""CREATE TABLE pairing_candidates(
        rendezvous TEXT NOT NULL REFERENCES pairing_rendezvous(id_hash) ON DELETE CASCADE,
        request_hash TEXT NOT NULL, ack_hash TEXT NOT NULL, request_blob TEXT NOT NULL,
        PRIMARY KEY(rendezvous, request_hash))""")
    conn.execute("CREATE INDEX rendezvous_expiry ON pairing_rendezvous(expires)")
    conn.execute("CREATE INDEX rendezvous_box ON pairing_rendezvous(box)")


def migrate_admission(conn):
    """Called only after full old schema validation inside the enclosing transaction."""
    conn.execute("CREATE TABLE admission_realm(id INTEGER PRIMARY KEY, config TEXT NOT NULL)")
    conn.execute("CREATE TABLE admission_requests(id TEXT PRIMARY KEY, nonce TEXT NOT NULL UNIQUE, wire TEXT NOT NULL, expires INTEGER NOT NULL, decision TEXT)")
    conn.execute("CREATE TABLE admission_credentials(id TEXT PRIMARY KEY, wire TEXT NOT NULL, expires INTEGER NOT NULL)")
    conn.execute("CREATE TABLE admission_revocations(id TEXT PRIMARY KEY, wire TEXT NOT NULL, sequence INTEGER NOT NULL)")
    conn.execute("CREATE TABLE admission_challenges(nonce TEXT PRIMARY KEY, credential TEXT NOT NULL, wire TEXT NOT NULL, expires INTEGER NOT NULL, deadline REAL NOT NULL, runtime TEXT NOT NULL, used INTEGER NOT NULL)")


def validate_constraints(conn: sqlite3.Connection, tables: set[str], version: int) -> None:
    """Versions 2/3 need full invariants; legacy messages are checked by migration.

    Legacy messages allowed a nullable envelope and had no foreign key; copying
    them into v2 enforces those constraints inside the migration transaction.
    Other tables retain the same schema across supported versions.
    """
    for table in tables:
        if table == "messages" and version < 2:
            continue
        # Table names come from the fixed allowlist validated by Database.
        columns = [(r["name"], r["type"].upper(), r["notnull"], r["pk"])
                   for r in conn.execute(f'PRAGMA table_info("{table}")')]
        if columns != COLUMNS[table]:
            raise ValueError("Incompatible database schema columns or primary key")
        keys = set()
        for index in conn.execute(f'PRAGMA index_list("{table}")'):
            if index["unique"] and not index["partial"]:
                # The index name is stored data; bind it, never interpolate it.
                keys.add(tuple(r["name"] for r in conn.execute(
                    "SELECT name FROM pragma_index_info(?) ORDER BY seqno", (index["name"],))))
        if keys != UNIQUE_KEYS[table]:
            raise ValueError("Incompatible database schema uniqueness constraints")
        foreign_keys = [(r["table"], r["from"], r["to"], r["on_update"], r["on_delete"])
                        for r in conn.execute(f'PRAGMA foreign_key_list("{table}")')]
        expected = [("boxes", "box", "id", "NO ACTION", "CASCADE")] if table in ("messages", "acknowledged", "pairing_invites", "pairing_rendezvous") else []
        if table == "pairing_candidates":
            expected = [("pairing_rendezvous", "rendezvous", "id_hash", "NO ACTION", "CASCADE")]
        if foreign_keys != expected:
            raise ValueError("Incompatible database schema foreign keys")
    if version >= 2:
        sql = conn.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name='messages'").fetchone()[0]
        # Column/PK metadata alone cannot distinguish reusable ROWIDs from the
        # AUTOINCREMENT sequence required by the polling cursor contract.
        # Strip complete quoted tokens before looking for the keyword: a CHECK
        # string or a quoted constraint named AUTOINCREMENT is not a sequence.
        sql = re.sub(r"'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|`(?:``|[^`])*`|\[[^\]]*\]|/\*.*?\*/|--[^\n]*",
                     " ", sql, flags=re.DOTALL)
        if not re.search(r"\bAUTOINCREMENT\b", sql, flags=re.IGNORECASE):
            raise ValueError("Incompatible database schema cursor sequence")
