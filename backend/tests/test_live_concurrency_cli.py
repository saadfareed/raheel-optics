"""An interrupted live probe must retain failing evidence and attempt cleanup."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS=Path(__file__).resolve().parents[2]/'.engineering/scripts'
sys.path.insert(0,str(SCRIPTS))
import live_concurrency_acceptance as probe

class InterruptedProbeTest(unittest.TestCase):
    def run_interrupted(self,cleanup_status):
        removed=[]
        def call(url,key=None,token=None,body=None,method=None):
            if 'api-keys?' in url:return 200,[{'name':'service_role','api_key':'test-admin'}]
            if method=='DELETE':removed.append(url);return cleanup_status,None
            if url.endswith('/admin/users'):return 201,{'id':'disposable-test-id'}
            if 'grant_type=password' in url:return 200,{'access_token':'test-token'}
            raise TimeoutError('simulated transport interruption')
        with tempfile.TemporaryDirectory() as directory:
            previous=Path.cwd();os.chdir(directory)
            try:
                destination=Path('.engineering/tasks/bottleneck-fixes-2026-10-09');destination.mkdir(parents=True)
                with patch.object(probe,'settings',return_value={'SUPABASE_URL':'https://staging.invalid','SUPABASE_PUBLIC_KEY':'test-public','SUPABASE_ACCESS_TOKEN':'test-management'}),patch.object(probe,'project',return_value='test-project'),patch.object(probe,'call',side_effect=call),patch.object(sys,'argv',['probe','--staging']):
                    with self.assertRaises(TimeoutError if cleanup_status==204 else RuntimeError):probe.main()
                report=json.loads((destination/'live-concurrency-acceptance.json').read_text())
                self.assertFalse(report['passed'])
                self.assertEqual(len(removed),1)
                return report
            finally:os.chdir(previous)
    def test_transport_interruption_is_not_success_after_cleanup(self):
        self.assertEqual(self.run_interrupted(204)['cleanup_remaining_account_ids'],[])
    def test_cleanup_failure_reports_remaining_identity(self):
        self.assertEqual(self.run_interrupted(503)['cleanup_remaining_account_ids'],['disposable-test-id'])

if __name__=='__main__':unittest.main()
