"""Synthetic safety tests. No GitHub API calls and no production credentials."""
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(SCRIPTS))
import repository_guard as guard
import publish_github as pub

class GuardTests(unittest.TestCase):
    def test_only_exact_reviewed_native_dependency_can_exceed_source_limit(self):
        name='android/vendor/webrtc-150.7871.01-umbra.5.aar'
        data=(SCRIPTS.parent/name).read_bytes()
        self.assertEqual([],guard.check_bytes(name,data))
        self.assertTrue(guard.check_bytes(name,data[:-1]+bytes([data[-1]^1])))
        self.assertTrue(guard.check_bytes('other.aar',data))
        self.assertTrue(guard.check_bytes(name,data[:-1]))
    def test_source_is_allowed(self):
        self.assertEqual(guard.check_bytes('Main.java', b'class Main {}'),[])
    def test_env_example_allowed(self):
        self.assertFalse(guard.prohibited_name('.env.example'))
    def test_nested_env_example_allowed(self):
        self.assertFalse(guard.prohibited_name('.env.staging.example'))
    def test_real_env_rejected(self):
        self.assertTrue(guard.prohibited_name('.env'))
    def test_staging_env_rejected(self):
        self.assertTrue(guard.prohibited_name('.env.staging'))
    def test_key_file_rejected(self):
        self.assertTrue(guard.prohibited_name('prod.p12'))
    def test_database_rejected(self):
        self.assertTrue(guard.prohibited_name('identity.sqlite3-wal'))
    def test_signing_store_rejected(self):
        self.assertTrue(guard.prohibited_name('upload.jks'))
    def test_local_sdk_config_rejected(self):
        self.assertTrue(guard.prohibited_name('local.properties'))
    def test_private_key_pattern(self):
        sample = b'-----BEGIN ' + b'PRIVATE KEY-----'
        self.assertTrue(guard.check_bytes('sample.txt',sample))
    def test_rsa_key_pattern(self):
        sample = b'-----BEGIN ' + b'RSA PRIVATE KEY-----'
        self.assertTrue(guard.check_bytes('sample.txt',sample))
    def test_github_pattern(self):
        sample = b'gh' + b'p_' + b'A'*36
        issues=guard.check_bytes('sample.txt',sample)
        self.assertTrue(issues)
        self.assertNotIn(sample.decode(),str(issues))
    def test_fine_grained_pattern(self):
        sample=b'github_' + b'pat_' + b'B'*40
        self.assertTrue(guard.check_bytes('sample.txt',sample))
    def test_aws_pattern(self):
        sample=b'AK' + b'IA' + b'A'*16
        self.assertTrue(guard.check_bytes('sample.txt',sample))
    def test_openai_pattern(self):
        sample=b'sk-' + b'proj-' + b'A'*30
        self.assertTrue(guard.check_bytes('sample.txt',sample))
    def test_credential_url_pattern(self):
        sample=b'https://' + b'user:' + b'password@example.test'
        self.assertTrue(guard.check_bytes('sample.txt',sample))
    def test_public_url_allowed(self):
        self.assertEqual(guard.check_bytes('file.md',b'https://example.test/docs'),[])
    def test_worktree_scan(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); (root/'README.md').write_text('safe'); (root/'.env').write_text('dummy')
            count,issues=guard.scan(root)
            self.assertEqual(count,2); self.assertEqual(len(issues),1)
    def test_venv_not_published(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); (root/'.venv').mkdir(); (root/'.venv'/'local.key').write_text('dummy')
            self.assertEqual(guard.scan(root),(0,[]))
    def test_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); (root/'safe.txt').write_text('safe')
            try: (root/'alias.txt').symlink_to(root/'safe.txt')
            except OSError: self.skipTest('Host does not permit symbolic links')
            self.assertTrue(guard.scan(root)[1])

class PublisherTests(unittest.TestCase):
    def test_expected_target_valid(self):
        self.assertTrue(pub.valid_target('devopssolutionsia','umbra'))
    def test_path_traversal_rejected(self):
        self.assertFalse(pub.valid_target('owner','../umbra'))
    def test_option_injection_rejected(self):
        self.assertFalse(pub.valid_target('--public','umbra'))
    def test_url_rejected(self):
        self.assertFalse(pub.valid_target('owner','https://example.test'))
    def test_git_suffix_rejected(self):
        self.assertFalse(pub.valid_target('owner','umbra.git'))
    def test_empty_owner_rejected(self):
        self.assertFalse(pub.valid_target('','umbra'))
    def test_public_repo_rejected(self):
        with self.assertRaises(RuntimeError):
            pub.assert_private({'private':False,'full_name':'owner/umbra'},'owner/umbra')
    def test_private_exact_repo_accepted(self):
        pub.assert_private({'private':True,'full_name':'owner/umbra'},'owner/umbra')
    def test_wrong_repo_rejected(self):
        with self.assertRaises(RuntimeError):
            pub.assert_private({'private':True,'full_name':'other/umbra'},'owner/umbra')
    def test_non_boolean_visibility_rejected(self):
        with self.assertRaises(RuntimeError):
            pub.assert_private({'private':'true','full_name':'owner/umbra'},'owner/umbra')
    def test_empty_remote_not_claimed_complete(self):
        self.assertFalse(pub.only_matching_main('', 'a'*40))
    def test_matching_main(self):
        s='a'*40
        self.assertTrue(pub.only_matching_main(f'{s}\tHEAD\n{s}\trefs/heads/main\n',s))
    def test_different_main_rejected(self):
        self.assertFalse(pub.only_matching_main('b'*40+'\trefs/heads/main\n','a'*40))
    def test_other_branch_rejected(self):
        self.assertFalse(pub.only_matching_main('a'*40+'\trefs/heads/other\n','a'*40))
    def test_real_check_only_does_not_invoke_network(self):
        with patch.object(sys,'argv',['publish_github.py','--check-only']), patch.object(pub,'run') as run:
            self.assertEqual(pub.main(),0)
            run.assert_not_called()

if __name__=='__main__':
    unittest.main()
