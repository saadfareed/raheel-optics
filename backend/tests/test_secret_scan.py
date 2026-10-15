import io
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'.engineering/scripts'))
from check_secret_leaks import scan_stream

class SecretScanTest(unittest.TestCase):
    def test_detects_a_private_value_across_read_boundary(self):
        value=b'fixture-private-token'
        data=b'x'*(1024*1024-5)+value+b'trailing'
        self.assertEqual(scan_stream(io.BytesIO(data),{'TEST_ACCESS_TOKEN':value}),['TEST_ACCESS_TOKEN'])
    def test_reports_only_setting_names_not_values(self):
        result=scan_stream(io.BytesIO(b'fixture-private-token'),{'TEST_ACCESS_TOKEN':b'fixture-private-token'})
        self.assertEqual(result,['TEST_ACCESS_TOKEN'])
        self.assertNotIn('fixture-private-token',str(result))
    def test_unrelated_public_configuration_is_allowed(self):
        self.assertEqual(scan_stream(io.BytesIO(b'public-client-key'),{'TEST_ACCESS_TOKEN':b'fixture-private-token'}),[])

if __name__=='__main__':unittest.main()
