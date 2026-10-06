#!/usr/bin/env python3
"""Local consistent SQLite backup and isolated restore inspection; no deployment.

Backups are UNENCRYPTED local files. Protected/encrypted external storage and
an approved retention/key policy are required before actual deployment.
Never replace a production database or delete Docker volumes with this tool.
Use a trusted local parent directory, not one controlled by an adversary or
concurrent SQLite writer. These checks do not promise hostile-directory safety.
"""
import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'relay'))
NOTICE = ('UNENCRYPTED local backup: protected/encrypted external storage and an '
          'approved retention/key policy are required before actual deployment')


class BackupError(ValueError):
    pass


def readonly(path):
    path = Path(path).absolute()
    if path.is_symlink() or not path.is_file():
        raise BackupError('Source must be an existing regular SQLite file, not a symlink')
    conn = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True, timeout=5)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA query_only=ON')
    return conn


def validate_snapshot(conn):
    # The constructor creates/migrates tables. Use only its read-only validator.
    from umbra_relay.app import Database
    if conn.execute('PRAGMA user_version').fetchone()[0] != 6:
        raise BackupError('A complete current schema v6 is required; no initialization or migration')
    if [r[0] for r in conn.execute('PRAGMA integrity_check')] != ['ok']:
        raise BackupError('SQLite integrity check failed')
    Database.validate_schema(conn)
    if conn.execute('PRAGMA foreign_key_check').fetchone() is not None:
        raise BackupError('SQLite foreign-key check failed')


def destination_clear(destination, *, published=False):
    suffixes = ('-wal', '-shm', '-journal') if published else ('', '-wal', '-shm', '-journal')
    for suffix in suffixes:
        candidate = Path(str(destination) + suffix)
        if candidate.exists() or candidate.is_symlink():
            raise BackupError('Destination or foreign SQLite sidecar already exists; preserved')


def backup_database(source, destination):
    """Publish an exclusive new destination after validating a consistent snapshot."""
    source, destination = Path(source), Path(destination).absolute()
    temp_path = None
    published = False
    try:
        destination_clear(destination)
        with closing(readonly(source)) as incoming:
            fd, temp_name = tempfile.mkstemp(prefix='.s1-backup-', suffix='.sqlite3', dir=destination.parent)
            temp_path = Path(temp_name)
            os.fchmod(fd, 0o600)
            os.close(fd)
            deadline = time.monotonic() + 30
            def progress(status, remaining, total):
                if time.monotonic() > deadline:
                    raise BackupError('SQLite snapshot exceeded the local time limit')
            with closing(sqlite3.connect(temp_path)) as outgoing:
                incoming.backup(outgoing, pages=256, progress=progress, sleep=0.05)
                outgoing.execute('PRAGMA journal_mode=DELETE')
            with closing(readonly(temp_path)) as snapshot:
                snapshot.execute('BEGIN')
                validate_snapshot(snapshot)
            with temp_path.open('rb') as stream:
                os.fsync(stream.fileno())
            # link() fails atomically if any destination entry already exists.
            # Temporary file lives on the same filesystem; never replace().
            destination_clear(destination)
            os.link(temp_path, destination)
            published = True
            destination_clear(destination, published=True)
            with closing(readonly(destination)) as actual_destination:
                actual_destination.execute('BEGIN')
                validate_snapshot(actual_destination)
            destination_clear(destination, published=True)
            # Local Linux directory fsync is required for publication durability.
            # Unsupported filesystems fail closed; preserve the published file
            # for the operator to inspect rather than deleting a valid snapshot.
            directory_fd = os.open(destination.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        return {'status': 'LOCAL_BACKUP_VALIDATED', 'schema_version': 6, 'notice': NOTICE}
    except (OSError, sqlite3.Error, ValueError, ImportError):
        if published:
            raise BackupError('Copy published but final validation or durability failed; destination preserved for local inspection') from None
        raise BackupError('Local backup rejected; no existing destination was replaced') from None
    finally:
        if temp_path is not None:
            # Only exact files belonging to our exclusively-created temporary DB.
            pending_error = sys.exc_info()[0] is not None
            cleanup_failed = False
            for suffix in ('-wal', '-shm', '-journal', ''):
                try:
                    Path(str(temp_path) + suffix).unlink(missing_ok=True)
                except OSError:
                    cleanup_failed = True
            if cleanup_failed and not pending_error:
                raise BackupError('Owned temporary backup cleanup failed; inspect local storage') from None


def inspect_backup(source):
    """Restore drill to an owned temporary directory; never replace source/production."""
    try:
        with tempfile.TemporaryDirectory(prefix='s1-restore-inspection-') as directory:
            backup_database(source, Path(directory) / 'isolated.sqlite3')
        return {'status': 'ISOLATED_RESTORE_VALIDATED', 'schema_version': 6,
                'production_replaced': False, 'notice': NOTICE}
    except (OSError, ValueError):
        raise BackupError('Isolated restore inspection rejected') from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='operation', required=True)
    backup = sub.add_parser('backup')
    backup.add_argument('source', type=Path)
    backup.add_argument('destination', type=Path)
    inspect = sub.add_parser('inspect')
    inspect.add_argument('source', type=Path)
    args = parser.parse_args(argv)
    try:
        result = (backup_database(args.source, args.destination) if args.operation == 'backup'
                  else inspect_backup(args.source))
    except BackupError as exc:
        print('LOCAL_SQLITE_REJECTED: ' + str(exc) + '. ' + NOTICE, file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
