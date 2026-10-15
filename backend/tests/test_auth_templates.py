import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / '.engineering/scripts'))
from prepare_auth_templates import template_payload

class AuthTemplatesTests(unittest.TestCase):
    def test_links_use_hash_fragment_and_correct_otp_type(self):
        values=template_payload('https://example.test/auth-email.html')
        self.assertEqual(len(values), 10)
        for name, otp_type in [('confirmation','signup'),('recovery','recovery'),
                               ('invite','invite'),('magic_link','magiclink'),
                               ('email_change','email_change')]:
            content=values['mailer_templates_'+name+'_content']
            self.assertIn('https://example.test/auth-email.html#token_hash={{ .TokenHash }}&amp;type='+otp_type,content)
            self.assertNotIn('ConfirmationURL',content)
    def test_rejects_redirects_credentials_non_https_and_wrong_page(self):
        for value in ['http://example.test/auth-email.html',
                      'https://user@example.test/auth-email.html',
                      'https://example.test/auth-email.html?next=bad',
                      'https://example.test/other.html']:
            with self.subTest(value=value),self.assertRaises(ValueError): template_payload(value)

if __name__=='__main__': unittest.main()
