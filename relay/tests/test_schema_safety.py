"""Startup must not rewrite future, foreign or damaged persistent databases."""
import sqlite3

import pytest

from umbra_relay.app import Database


def snapshot(path):
    with sqlite3.connect(path) as db:
        return (db.execute("PRAGMA user_version").fetchone()[0],
                db.execute("SELECT type,name,sql FROM sqlite_master ORDER BY type,name").fetchall(),
                db.execute("PRAGMA journal_mode").fetchone()[0])


@pytest.mark.parametrize("version", [-1, 4, 2147483647])
def test_unknown_schema_version_is_preserved(tmp_path, version):
    path = tmp_path / "unknown.sqlite3"
    with sqlite3.connect(path) as db:
        db.execute(f"PRAGMA user_version={version}")
        db.execute("CREATE TABLE future_payload(value TEXT)")
        db.execute("INSERT INTO future_payload VALUES ('synthetic-retained-record')")
    before = snapshot(path)
    with pytest.raises(ValueError, match="schema"):
        Database(str(path))
    assert snapshot(path) == before
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT value FROM future_payload").fetchone()[0] == "synthetic-retained-record"


def test_unversioned_foreign_database_is_not_adopted(tmp_path):
    path = tmp_path / "foreign.sqlite3"
    with sqlite3.connect(path) as db:
        db.execute("CREATE TABLE unrelated(value TEXT)")
    before = snapshot(path)
    with pytest.raises(ValueError, match="schema"):
        Database(str(path))
    assert snapshot(path) == before


@pytest.mark.parametrize("table", ["boxes", "messages", "invites", "acknowledged"])
def test_missing_v2_table_is_not_silently_recreated(tmp_path, table):
    path = tmp_path / "v2.sqlite3"
    Database(str(path))
    with sqlite3.connect(path) as db:
        db.execute(f"DROP TABLE {table}")  # Internal test constants, never external input.
    before = snapshot(path)
    with pytest.raises(ValueError, match="schema"):
        Database(str(path))
    assert snapshot(path) == before


def test_v2_is_idempotent_and_remains_readable(tmp_path):
    path = tmp_path / "v2.sqlite3"
    database = Database(str(path))
    invitation = database.issue_invite()
    before = snapshot(path)
    Database(str(path))
    assert snapshot(path) == before
    with database.connect() as db:
        assert db.execute("SELECT COUNT(*) FROM invites").fetchone()[0] == 1
    assert invitation
