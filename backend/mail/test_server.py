import base64
import hashlib
import hmac
import json
import unittest
from unittest.mock import patch
from server import verify, messages, deliver


class MailTests(unittest.TestCase):
    def test_empty_or_short_key_cannot_authenticate_even_matching_signature(self):
        body = b'{"test":true}'
        for key in [b'', b'weak', b'a' * 23, b'a' * 65]:
            secret = base64.b64encode(key).decode()
            signature = base64.b64encode(hmac.new(key, b'event.1000.' + body, hashlib.sha256).digest()).decode()
            headers = {'webhook-id': 'event', 'webhook-timestamp': '1000', 'webhook-signature': 'v1,' + signature}
            with self.assertRaises(ValueError):
                verify(body, headers, 'v1,whsec_' + secret, now=1000)

    def test_standard_minimum_length_key_authenticates(self):
        key = b'a' * 24
        body = b'{"test":true}'
        signature = base64.b64encode(hmac.new(key, b'event.1000.' + body, hashlib.sha256).digest()).decode()
        headers = {'webhook-id': 'event', 'webhook-timestamp': '1000', 'webhook-signature': 'v1,' + signature}
        self.assertEqual(verify(body, headers, 'whsec_' + base64.b64encode(key).decode(), now=1000), {'test': True})

    def test_signature_tampering_and_expiry(self):
        secret = base64.b64encode(b'a' * 32).decode()
        body = b'{"test":true}'
        signature = base64.b64encode(hmac.new(b'a' * 32, b'event.1000.' + body, hashlib.sha256).digest()).decode()
        headers = {'webhook-id': 'event', 'webhook-timestamp': '1000', 'webhook-signature': 'v1,' + signature}
        self.assertEqual(verify(body, headers, 'v1,whsec_' + secret, now=1000), {'test': True})
        for changed_body, now in [(b'{}', 1000), (body, 1301)]:
            with self.assertRaises(ValueError):
                verify(changed_body, headers, secret, now=now)

    def test_recovery_uses_owned_page_not_payload_redirect(self):
        payload = {'user': {'email': 'user@example.com'}, 'email_data': {'email_action_type': 'recovery', 'token_hash': 'hash', 'redirect_to': 'https://evil.example'}}
        result = messages(payload, 'https://daymark.example/auth-email.html')
        self.assertIn('https://daymark.example/auth-email.html#token_hash=hash&type=recovery', result[0][2])
        self.assertNotIn('evil.example', result[0][2])

    def test_secure_email_change_maps_both_hashes(self):
        payload = {'user': {'email': 'old@example.com', 'new_email': 'new@example.com'}, 'email_data': {'email_action_type': 'email_change', 'token_hash': 'new-hash', 'token_hash_new': 'old-hash'}}
        result = messages(payload, 'https://daymark.example/auth-email.html')
        self.assertEqual([item[0] for item in result], ['new@example.com', 'old@example.com'])
        self.assertIn('token_hash=new-hash', result[0][2])
        self.assertIn('token_hash=old-hash', result[1][2])

    def test_smtp_requires_tls_and_owned_sender(self):
        env = {'SMTP_HOST': 'smtp.gmail.com', 'SMTP_PORT': '587', 'SMTP_USER': 'pakistanbakeries@gmail.com', 'SMTP_PASSWORD': 'test', 'MAIL_FROM_ADDRESS': 'pakistanbakeries@gmail.com'}
        with patch.dict('os.environ', env), patch('server.smtplib.SMTP') as smtp:
            deliver([('user@example.com', 'Daymark', 'test')])
            connection = smtp.return_value.__enter__.return_value
            connection.starttls.assert_called_once()
            connection.login.assert_called_once_with(env['SMTP_USER'], 'test')
            message = connection.send_message.call_args.args[0]
            self.assertEqual(message['From'], 'Daymark <pakistanbakeries@gmail.com>')

    def test_security_notifications_do_not_require_tokens(self):
        for action in ['password_changed', 'email_changed', 'phone_changed', 'identity_linked', 'identity_unlinked', 'mfa_factor_enrolled', 'mfa_factor_unenrolled']:
            payload = {'user': {'email': 'user@example.com'}, 'email_data': {'email_action_type': action + '_notification'}}
            result = messages(payload, 'https://daymark.example/auth-email.html')
            self.assertIn('Daymark', result[0][1])
            self.assertNotIn('token_hash', result[0][2])

    def test_reauthentication_and_invalid_actions(self):
        payload = {'user': {'email': 'user@example.com'}, 'email_data': {'email_action_type': 'reauthentication', 'token': '123456'}}
        self.assertIn('123456', messages(payload, 'https://daymark.example/auth-email.html')[0][2])
        payload['email_data']['email_action_type'] = 'unknown'
        with self.assertRaises(ValueError):
            messages(payload, 'https://daymark.example/auth-email.html')


if __name__ == '__main__':
    unittest.main()


class ContinuousRetentionTest(unittest.TestCase):
    def test_busy_worker_still_prunes(self):
        import threading
        from server import run_worker
        stop=threading.Event()
        class BusyOutbox:
            def __init__(self):self.processed=0;self.pruned=0
            def prune(self):self.pruned+=1
            def process_one(self,sender):
                self.processed+=1
                if self.processed==3:stop.set()
                return True
        box=BusyOutbox()
        with patch('server.time.monotonic',return_value=120):run_worker(box,stop)
        self.assertEqual(3,box.processed)
        self.assertEqual(1,box.pruned)

class TotalDeadlineTest(unittest.TestCase):
    def test_smtp_deadline_interrupts_a_stalled_send(self):
        import socket
        import smtplib
        import threading
        import time
        timer_type=threading.Timer
        connection,peer=socket.socketpair()
        connection.settimeout(2)
        class StalledSMTP:
            sock=connection
            def __enter__(self):return self
            def __exit__(self,*args):self.sock.close()
            def starttls(self,**kwargs):pass
            def login(self,*args):pass
            def send_message(self,message):
                try:self.sock.recv(1)
                except OSError:pass
                raise smtplib.SMTPServerDisconnected('deadline interrupted stalled send')
        env={'SMTP_HOST':'smtp.example.invalid','SMTP_PORT':'587','SMTP_USER':'sender@example.invalid','SMTP_PASSWORD':'fixture','MAIL_FROM_ADDRESS':'sender@example.invalid'}
        try:
            started=time.monotonic()
            with patch.dict('os.environ',env),patch('server.smtplib.SMTP',return_value=StalledSMTP()),patch('server.threading.Timer',side_effect=lambda seconds,callback:timer_type(.05,callback)):
                with self.assertRaises(smtplib.SMTPServerDisconnected):deliver([('recipient@example.invalid','Fixture','No email is sent')])
            self.assertLess(time.monotonic()-started,1)
        finally:connection.close();peer.close()

    def test_request_deadline_releases_slot_after_stalled_reader(self):
        import socket
        import threading
        import time
        from server import BoundedServer
        timer_type=threading.Timer
        connection,peer=socket.socketpair()
        connection.settimeout(2)
        server=object.__new__(BoundedServer)
        server.slots=threading.BoundedSemaphore(1)
        server.slots.acquire()
        server.finish_request=lambda request,address:request.recv(1)
        server.handle_error=lambda *args:None
        try:
            started=time.monotonic()
            with patch('server.threading.Timer',side_effect=lambda seconds,callback:timer_type(.05,callback)):
                server.process_request_thread(connection,('fixture',0))
            self.assertLess(time.monotonic()-started,1)
            self.assertTrue(server.slots.acquire(blocking=False))
        finally:connection.close();peer.close()
