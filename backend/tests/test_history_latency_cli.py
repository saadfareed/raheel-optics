"""A diagnostic interruption must never claim a successful staging acceptance."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'.engineering/scripts'))
import history_latency_acceptance as probe

class DiagnosticFailureTest(unittest.TestCase):
    def interrupted(self,cleanup_status=204):
        operations=[];removed=[]
        def call(url,key=None,token=None,body=None,method=None):
            if 'api-keys?' in url:return 200,[{'name':'service_role','api_key':'test-admin'}]
            if method=='DELETE':removed.append(url);return cleanup_status,None
            if url.endswith('/admin/users'):return 201,{'id':'11111111-1111-1111-1111-111111111111'}
            if 'grant_type=password' in url:return 200,{'access_token':'test-token'}
            if url.endswith('/apply_operations'):
                if body['payloads'][0]['action']=='create':operations.extend(body['payloads'])
                return 200,[{'id':row['id'],'result':{'accepted':True}} for row in body['payloads']]
            if url.endswith('/history_latest_page'):
                entries=sorted([{'occurredAt':row['occurredAt'],'todo':row['todo']} for row in operations],key=lambda row:(row['occurredAt'],row['todo']['id']),reverse=True)
                return 200,entries[25:] if 'before_id' in body else entries[:25]
            raise AssertionError('Unexpected fixture request')
        with tempfile.TemporaryDirectory() as directory:
            previous=Path.cwd();os.chdir(directory)
            try:
                evidence=Path('.engineering/tasks/bottleneck-fixes-2026-10-09');evidence.mkdir(parents=True)
                with patch.object(probe,'settings',return_value={'SUPABASE_URL':'https://staging.invalid','SUPABASE_PUBLIC_KEY':'test-public','SUPABASE_ACCESS_TOKEN':'test-management'}),patch.object(probe,'project',return_value='test-project'),patch.object(probe,'call',side_effect=call),patch.object(probe,'database_plans',side_effect=TimeoutError('simulated SQL transport timeout')),patch.object(sys,'argv',['probe','--staging']):
                    with self.assertRaises(TimeoutError if cleanup_status==204 else RuntimeError):probe.main()
                report=json.loads((evidence/'history-latency-acceptance.json').read_text())
                self.assertFalse(report['passed']);self.assertEqual(len(removed),1)
                self.assertTrue(any(row['check']=='second history page completes fixture without duplicates' and row['passed'] for row in report['checks']))
                return report
            finally:os.chdir(previous)
    def test_read_only_diagnosis_timeout_preserves_failed_evidence_and_cleans_up(self):
        self.assertEqual(self.interrupted()['cleanup_remaining_account_ids'],[])
    def test_failed_cleanup_records_identity_for_recovery(self):
        self.assertEqual(self.interrupted(503)['cleanup_remaining_account_ids'],['11111111-1111-1111-1111-111111111111'])

if __name__=='__main__':unittest.main()
