import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'.engineering/scripts'))
from inspect_device_export import inspect

class DeviceExportInspectorTests(unittest.TestCase):
    def test_extracts_exact_tasks_without_replaying_operations(self):
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/'export.jsonl';target=Path(folder)/'tasks.jsonl'
            source.write_text('\n'.join(json.dumps(x) for x in [
                {'format':'daymark-export','version':1},
                {'type':'task','data':{'id':'one','title':'private note','status':'active'}},
                {'type':'queuedChange','data':{'sdkData':'private operation'}}])+'\n')
            report=inspect(source,target)
            self.assertEqual(report['counts']['task'],1)
            self.assertEqual(report['counts']['queuedChange'],1)
            self.assertNotIn('private operation',target.read_text())
            self.assertEqual(target.stat().st_mode & 0o777,0o600)
    def test_invalid_record_removes_partial_extraction(self):
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/'export.jsonl';target=Path(folder)/'tasks.jsonl'
            source.write_text('{"format":"daymark-export","version":1}\n{"type":"task","data":{"id":"one","title":"x","status":"active"}}\nnot json\n')
            with self.assertRaises(ValueError):inspect(source,target)
            self.assertFalse(target.exists())
    def test_existing_output_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/'export.jsonl';target=Path(folder)/'tasks.jsonl'
            source.write_text('{"format":"daymark-export","version":1}\n');target.write_text('keep')
            with self.assertRaises(FileExistsError):inspect(source,target)
            self.assertEqual(target.read_text(),'keep')
if __name__=='__main__':unittest.main()
