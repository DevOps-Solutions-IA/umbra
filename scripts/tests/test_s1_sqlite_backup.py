from contextlib import closing
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch
import os

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'relay'))
from s1_sqlite_backup import BackupError, backup_database, inspect_backup
from umbra_relay.app import Database


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source.sqlite3'
        self.dest = self.root / 'backup.sqlite3'
        Database(str(self.source))  # Only synthetic fixture initialization.

    def test_snapshot_includes_committed_wal_and_preserves_state(self):
        with closing(sqlite3.connect(self.source)) as conn:
            conn.execute('PRAGMA wal_autocheckpoint=0')
            conn.execute("INSERT INTO boxes VALUES('synthetic-box','read-hash','write-hash',10)")
            conn.execute("INSERT INTO messages(box,id,digest,envelope,size,expires,created) VALUES('synthetic-box','id','digest','opaque-ciphertext',17,9999999999,10)")
            conn.execute("INSERT INTO admission_revocations VALUES('synthetic-credential','synthetic-public-revocation',3)")
            conn.execute("INSERT INTO acknowledged VALUES('synthetic-box','deduplicated-id','digest',9999999999)")
            conn.execute("INSERT INTO pairing_rendezvous VALUES('synthetic-rendezvous','synthetic-box','owner-hash','request-hash',NULL,NULL,NULL,9999999999,NULL,NULL,NULL,0)")
            conn.commit()
            self.assertTrue(Path(str(self.source) + '-wal').exists())
            before = self.source.read_bytes()
            backup_database(self.source, self.dest)
            self.assertEqual(before, self.source.read_bytes())
            with closing(sqlite3.connect(self.dest)) as restored:
                self.assertEqual(('opaque-ciphertext',), restored.execute('SELECT envelope FROM messages').fetchone())
                self.assertEqual(('synthetic-public-revocation', 3), restored.execute('SELECT wire, sequence FROM admission_revocations').fetchone())
                self.assertEqual(('deduplicated-id',), restored.execute('SELECT id FROM acknowledged').fetchone())
                self.assertEqual(('synthetic-rendezvous',), restored.execute('SELECT id_hash FROM pairing_rendezvous').fetchone())
                self.assertEqual((1,), restored.execute("SELECT seq FROM sqlite_sequence WHERE name='messages'").fetchone())
        self.assertEqual('ISOLATED_RESTORE_VALIDATED', inspect_backup(self.dest)['status'])
        self.assertEqual({'source.sqlite3', 'backup.sqlite3'}, {p.name for p in self.root.iterdir()})

    def test_existing_destination_preserved(self):
        self.dest.write_bytes(b'preserve')
        with self.assertRaises(BackupError): backup_database(self.source, self.dest)
        self.assertEqual(b'preserve', self.dest.read_bytes())

    def test_bad_schema_empty_and_corrupt_rejected_without_output(self):
        for content in ('empty', 'wrong-schema', 'corrupt'):
            source = self.root / content
            if content == 'corrupt': source.write_bytes(b'not sqlite')
            else:
                with closing(sqlite3.connect(source)) as conn:
                    if content == 'wrong-schema': conn.execute('CREATE TABLE secrets(value TEXT)')
                    conn.commit()
            before = source.read_bytes()
            with self.assertRaises(BackupError): backup_database(source, self.dest)
            self.assertFalse(self.dest.exists())
            self.assertEqual(before, source.read_bytes())
        self.assertFalse(any(p.name.startswith('.s1-') for p in self.root.iterdir()))

    def test_missing_source_and_symlinks_rejected(self):
        missing = self.root / 'missing'
        with self.assertRaises(BackupError): backup_database(missing, self.dest)
        self.assertFalse(missing.exists())
        alias = self.root / 'alias'
        alias.symlink_to(self.source)
        with self.assertRaises(BackupError): backup_database(alias, self.dest)
        self.dest.symlink_to(self.source)
        with self.assertRaises(BackupError): backup_database(self.source, self.dest)

    def test_inspection_rejects_corruption_preserves_backup(self):
        self.dest.write_bytes(b'corrupt')
        with self.assertRaises(BackupError): inspect_backup(self.dest)
        self.assertEqual(b'corrupt', self.dest.read_bytes())

    def test_destination_created_during_snapshot_is_preserved(self):
        real_link = os.link
        def competing_link(source, destination):
            Path(destination).write_bytes(b'concurrent-owner')
            return real_link(source, destination)
        with patch('s1_sqlite_backup.os.link', side_effect=competing_link):
            with self.assertRaises(BackupError): backup_database(self.source, self.dest)
        self.assertEqual(b'concurrent-owner', self.dest.read_bytes())
        self.assertFalse(any(p.name.startswith('.s1-') for p in self.root.iterdir()))

    def test_incomplete_schema_rejected_without_recreating_tables(self):
        with closing(sqlite3.connect(self.source)) as conn:
            conn.execute('DROP TABLE admission_credentials')
            conn.commit()
        with self.assertRaises(BackupError): backup_database(self.source, self.dest)
        with closing(sqlite3.connect(self.source)) as conn:
            self.assertIsNone(conn.execute("SELECT name FROM sqlite_master WHERE name='admission_credentials'").fetchone())
        self.assertFalse(self.dest.exists())

    def test_file_and_parent_directory_are_synced_before_success(self):
        import stat
        calls = []
        real_sync = os.fsync
        def recording_sync(fd):
            calls.append('directory' if stat.S_ISDIR(os.fstat(fd).st_mode) else 'file')
            real_sync(fd)
        with patch('s1_sqlite_backup.os.fsync', side_effect=recording_sync):
            backup_database(self.source, self.dest)
        self.assertEqual(['file', 'directory'], calls)

    def test_directory_sync_failure_preserves_published_copy_for_inspection(self):
        import stat
        real_sync = os.fsync
        def fail_directory_sync(fd):
            if stat.S_ISDIR(os.fstat(fd).st_mode):
                raise OSError('synthetic directory fsync failure')
            real_sync(fd)
        with patch('s1_sqlite_backup.os.fsync', side_effect=fail_directory_sync):
            with self.assertRaisesRegex(BackupError, 'published'):
                backup_database(self.source, self.dest)
        self.assertTrue(self.dest.is_file())
        self.assertEqual('ISOLATED_RESTORE_VALIDATED', inspect_backup(self.dest)['status'])
        self.assertFalse(any(p.name.startswith('.s1-') for p in self.root.iterdir()))

    def test_orphaned_real_wal_destination_is_preserved_and_rejected(self):
        Database(str(self.dest))
        with closing(sqlite3.connect(self.dest)) as old:
            old.execute('PRAGMA wal_autocheckpoint=0')
            old.execute("INSERT INTO boxes VALUES('stale-box','old-read','old-write',1)")
            old.commit()
            self.dest.unlink()
            foreign = {p: p.read_bytes() for p in self.root.iterdir()
                       if p.name in {'backup.sqlite3-wal', 'backup.sqlite3-shm'}}
            self.assertEqual(2, len(foreign))
            with self.assertRaises(BackupError): backup_database(self.source, self.dest)
            self.assertFalse(self.dest.exists())
            for path, original in foreign.items(): self.assertEqual(original, path.read_bytes())

    def test_foreign_sidecar_appearing_after_link_never_claims_success(self):
        real_link = os.link
        sidecar = Path(str(self.dest) + '-journal')
        def racing_link(source, destination):
            result = real_link(source, destination)
            sidecar.write_bytes(b'foreign-journal')
            return result
        with patch('s1_sqlite_backup.os.link', side_effect=racing_link):
            with self.assertRaises(BackupError): backup_database(self.source, self.dest)
        self.assertTrue(self.dest.exists())
        self.assertEqual(b'foreign-journal', sidecar.read_bytes())
