"""Model the publisher's command ordering; these are NOT live GitHub tests."""
import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import publish_github as p

class PublicationFlowTests(unittest.TestCase):
    def simulate(self, private=True, remote_sha=None, owner='devopssolutionsia'):
        calls=[]
        sha='a'*40
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            (root/'.git').mkdir()
            for f in ['AGENTS.md','PROMPT_CODEX.md','android/app/build.gradle.kts',
                      'relay/umbra_relay/app.py','.github/workflows/verify.yml']:
                q=root/f; q.parent.mkdir(parents=True,exist_ok=True); q.write_text('synthetic test')
            def fake(*args, **kwargs):
                calls.append(args)
                out=''; code=0
                if args[:3]==('gh','auth','status'): pass
                elif args[:4]==('gh','api','--hostname','github.com'):
                    endpoint=args[4]
                    if endpoint=='user':
                        value={'id':123,'login':owner}
                    elif endpoint.endswith('/git/ref/heads/main'):
                        value={'object':{'sha':sha}}
                    else:
                        value={'full_name':'devopssolutionsia/umbra','private':private,'default_branch':'main'}
                    out=json.dumps(value)
                elif args[:3]==('git','rev-parse','--show-toplevel'): out=str(root)
                elif args[:3]==('git','symbolic-ref','--short'): out='main'
                elif args[:3]==('git','status','--porcelain'): pass
                elif args[:3]==('git','rev-parse','HEAD'): out=sha
                elif args[:4]==('git','remote','get-url','origin'): code=1
                elif 'ls-remote' in args:
                    out='' if remote_sha is None else remote_sha+'\trefs/heads/main\n'
                elif args[:3]==('gh','repo','create'): pass
                elif args[:3]==('git','remote','add'): pass
                elif 'push' in args: pass
                else: raise AssertionError('Unexpected command '+repr(args))
                return subprocess.CompletedProcess(args,code,out,'')
            with patch.object(p,'ROOT',root), patch.object(p,'scan',return_value=(5,[])), \
                 patch.object(p,'scan_history',return_value=(5,[])), \
                 patch.object(p.shutil,'which',return_value='/test/tool'), \
                 patch.object(p,'run',side_effect=fake), \
                 patch.object(sys,'argv',['publish_github.py']), contextlib.redirect_stdout(io.StringIO()):
                try: result=p.main(); error=None
                except RuntimeError as exc: result=None; error=str(exc)
        return result,error,calls
    def test_private_created_and_checked_before_push(self):
        result,error,calls=self.simulate()
        self.assertEqual(result,0); self.assertIsNone(error)
        create=next(i for i,c in enumerate(calls) if c[:3]==('gh','repo','create'))
        self.assertIn('--private',calls[create])
        verify=next(i for i,c in enumerate(calls) if c[:4]==('gh','api','--hostname','github.com') and c[-1]=='repos/devopssolutionsia/umbra')
        push=next(i for i,c in enumerate(calls) if 'push' in c)
        self.assertLess(create,verify); self.assertLess(verify,push)
        self.assertEqual(calls[push][-1],'main:main')
    def test_public_response_blocks_upload(self):
        result,error,calls=self.simulate(private=False)
        self.assertIsNone(result); self.assertIsNotNone(error)
        self.assertFalse(any('push' in c for c in calls))
    def test_conflicting_history_blocks_upload(self):
        result,error,calls=self.simulate(remote_sha='b'*40)
        self.assertIsNone(result); self.assertIsNotNone(error)
        self.assertFalse(any('push' in c for c in calls))
    def test_wrong_account_blocks_creation(self):
        result,error,calls=self.simulate(owner='other-account')
        self.assertIsNone(result); self.assertIsNotNone(error)
        self.assertFalse(any(c[:3]==('gh','repo','create') for c in calls))

if __name__=='__main__': unittest.main()
