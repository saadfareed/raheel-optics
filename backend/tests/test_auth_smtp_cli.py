import importlib.util
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / '.engineering/scripts'))
from configure_auth_smtp import smtp_payload

class SMTPConfigurationTests(unittest.TestCase):
    def test_payload_uses_management_api_string_port_and_only_smtp_fields(self):
        payload = smtp_payload(dict(SMTP_HOST='smtp.example.test', SMTP_USER='user',
            SMTP_PASSWORD='private', MAIL_FROM_ADDRESS='mail@example.test', SMTP_PORT='587'))
        self.assertEqual(payload['smtp_port'], '587')
        self.assertEqual(set(payload), {'smtp_host','smtp_port','smtp_user','smtp_pass',
                                      'smtp_admin_email','smtp_sender_name'})
    def test_missing_or_invalid_settings_rejected_before_network(self):
        with self.assertRaises(ValueError): smtp_payload({})
        with self.assertRaises(ValueError): smtp_payload(dict(SMTP_HOST='host',SMTP_USER='user',
            SMTP_PASSWORD='private',MAIL_FROM_ADDRESS='mail@example.test',SMTP_PORT='0'))

if __name__ == '__main__': unittest.main()
