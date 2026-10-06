"""Damaged constraints must be rejected before startup changes persistent state."""
import sqlite3

import pytest
from umbra_relay.app import Database


@pytest.mark.parametrize("table,old,new", [
    ("boxes", "read_hash TEXT NOT NULL", "read_hash TEXT"),
    ("boxes", "id TEXT PRIMARY KEY", "id TEXT"),
    ("invites", "expires INTEGER NOT NULL", "expires TEXT NOT NULL"),
    ("acknowledged", "ON DELETE CASCADE", "ON DELETE NO ACTION"),
    ("messages", "AUTOINCREMENT", ""),
    ("messages", "AUTOINCREMENT", "CHECK('AUTOINCREMENT' IS NOT NULL)"),
    ("messages", "AUTOINCREMENT", 'CONSTRAINT "AUTOINCREMENT" CHECK(1)'),
    ("messages", "AUTOINCREMENT", "CONSTRAINT [AUTOINCREMENT] CHECK(1)"),
    ("messages", "AUTOINCREMENT", "CONSTRAINT `AUTOINCREMENT` CHECK(1)"),
    ("messages", ",\n                UNIQUE(box,id)", ""),
])
def test_corrupt_schema_is_rejected_without_modification(tmp_path, table, old, new):
    path = str(tmp_path / "database.sqlite3")
    Database(path)
    with sqlite3.connect(path) as db:
        ddl = db.execute("SELECT sql FROM sqlite_master WHERE name=?", (table,)).fetchone()[0]
        assert old in ddl
        db.execute(f'DROP TABLE "{table}"')  # Internal parametrized table names only.
        db.execute(ddl.replace(old, new))
        before = db.execute("SELECT type,name,sql FROM sqlite_master ORDER BY type,name").fetchall()
    with pytest.raises(ValueError, match="schema"):
        Database(path)
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT type,name,sql FROM sqlite_master ORDER BY type,name").fetchall() == before
