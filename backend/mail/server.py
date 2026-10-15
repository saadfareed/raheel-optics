"""Owned auth-email webhook. Run behind an HTTPS reverse proxy; no third-party mail fallback."""
import base64
import hashlib
import hmac
import json
import os
import smtplib
import ssl
import time
import threading
import socket
import urllib.request
from email.message import EmailMessage
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlencode, urlsplit
from outbox import Outbox


def report_error(where):
    """Optional backend error signal to Sentry; no-op unless SENTRY_DSN_BACKEND is set.

    Sends only a fixed, non-identifying location label. Never includes exceptions,
    payloads, tokens, email addresses, or provider error bodies, so the redaction
    guarantees elsewhere in this module are preserved."""
    dsn = os.environ.get('SENTRY_DSN_BACKEND')
    if not dsn:
        return
    try:
        parsed = urlsplit(dsn)
        if parsed.scheme != 'https' or not parsed.hostname or not parsed.username:
            return
        project = parsed.path.rsplit('/', 1)[-1]
        endpoint = f'https://{parsed.hostname}/api/{project}/store/'
        event = json.dumps({'message': 'daymark-mail-webhook error at ' + where, 'level': 'error',
                            'platform': 'python', 'logger': 'daymark.mail'}).encode()
        request = urllib.request.Request(endpoint, data=event, headers={
            'Content-Type': 'application/json',
            'X-Sentry-Auth': f'Sentry sentry_version=7, sentry_client=daymark-mail/1, sentry_key={parsed.username}'})
        context = ssl.create_default_context()
        with urllib.request.urlopen(request, timeout=3, context=context):
            pass
    except Exception:
        # Error reporting must never alter the webhook's own error handling.
        pass


def verify(body, headers, secret, now=None):
    timestamp = headers.get('webhook-timestamp', '')
    try:
        if abs((time.time() if now is None else now) - int(timestamp)) > 300:
            raise ValueError('Expired signature')
        key = base64.b64decode(secret.removeprefix('v1,').removeprefix('whsec_'), validate=True)
        if not 24 <= len(key) <= 64:
            raise ValueError('Signing secret must contain 24–64 random bytes')
        signed = headers['webhook-id'].encode() + b'.' + timestamp.encode() + b'.' + body
        signature = base64.b64encode(hmac.new(key, signed, hashlib.sha256).digest()).decode()
        if not any(hmac.compare_digest(item, 'v1,' + signature) for item in headers.get('webhook-signature', '').split()):
            raise ValueError('Invalid signature')
    except (KeyError, TypeError) as error:
        raise ValueError('Invalid signature') from error
    return json.loads(body)


def messages(payload, public_url):
    parsed = urlsplit(public_url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
        raise ValueError('AUTH_EMAIL_PAGE_URL must be your HTTPS page')
    user, data = payload['user'], payload['email_data']
    action = data['email_action_type']
    titles = {'signup': 'Confirm your Daymark account', 'recovery': 'Reset your Daymark password',
              'magiclink': 'Sign in to Daymark', 'email': 'Your Daymark sign-in link', 'invite': 'Your Daymark invitation',
              'email_change': 'Confirm your Daymark email change', 'reauthentication': 'Your Daymark security code'}
    notifications = {'password_changed': 'Your Daymark password was changed', 'email_changed': 'Your Daymark email was changed', 'phone_changed': 'Your Daymark phone number was changed', 'identity_linked': 'A sign-in identity was added to Daymark', 'identity_unlinked': 'A sign-in identity was removed from Daymark', 'mfa_factor_enrolled': 'A security method was added to Daymark', 'mfa_factor_unenrolled': 'A security method was removed from Daymark'}
    titles.update({name + '_notification': subject for name, subject in notifications.items()})
    if action not in titles:
        raise ValueError('Unsupported email action')
    recipients = [(user['email'], data.get('token_hash'), data.get('token'))]
    if action == 'email_change':
        recipients = [(user['new_email'], data['token_hash'], data.get('token_new') or data.get('token'))]
        if data.get('token_hash_new'):
            recipients.append((user['email'], data['token_hash_new'], data.get('token')))
    if action == 'email_changed_notification' and data.get('old_email') and data['old_email'] != user['email']:
        recipients.append((data['old_email'], None, None))
    result = []
    for recipient, token_hash, token in recipients:
        if not recipient or '\n' in recipient or '\r' in recipient or '@' not in recipient:
            raise ValueError('Invalid recipient')
        if action.endswith('_notification'):
            content = titles[action] + '. If you did not make this change, contact Daymark support immediately.'
        elif action == 'reauthentication':
            if not token:
                raise ValueError('Missing security code')
            content = 'Your Daymark security code is: ' + token
        else:
            if not token_hash:
                raise ValueError('Missing token hash')
            # Fragment keeps credentials out of web-server logs and referrers.
            link = public_url + '#' + urlencode({'token_hash': token_hash, 'type': action})
            content = titles[action] + '\n\nOpen this link to continue:\n' + link
        result.append((recipient, titles[action], content + '\n\nIf you did not request this, ignore this email.\nDaymark support: ' + os.environ.get('MAIL_REPLY_TO', 'pakistanbakeries@gmail.com')))
    return result


def deliver(items):
    port = int(os.environ.get('SMTP_PORT', '587'))
    context = ssl.create_default_context()
    smtp_type = smtplib.SMTP_SSL if port == 465 else smtplib.SMTP
    kwargs = {'context': context} if port == 465 else {}
    with smtp_type(os.environ['SMTP_HOST'], port, timeout=3, **kwargs) as smtp:
        def abort_delivery():
            connection=getattr(smtp,'sock',None)
            if connection:
                try:connection.shutdown(socket.SHUT_RDWR)
                except OSError:pass
                connection.close()
        deadline=threading.Timer(60,abort_delivery)
        deadline.daemon=True
        deadline.start()
        try:
            _deliver_connected(smtp,items,port,context)
        finally:deadline.cancel()


def _deliver_connected(smtp,items,port,context):
        if port != 465:
            smtp.starttls(context=context)
        smtp.login(os.environ['SMTP_USER'], os.environ['SMTP_PASSWORD'])
        for recipient, subject, content in items:
            message = EmailMessage()
            message['From'] = f"Daymark <{os.environ['MAIL_FROM_ADDRESS']}>"
            message['To'] = recipient
            message['Reply-To'] = os.environ.get('MAIL_REPLY_TO', os.environ['MAIL_FROM_ADDRESS'])
            message['Subject'] = subject
            message.set_content(content)
            smtp.send_message(message)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != '/health':
            self.send_error(404)
            return
        body = json.dumps(self.server.outbox.stats()).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != '/auth-email':
            self.send_error(404)
            return
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 65536:
                self.send_error(413)
                return
            body = self.rfile.read(size)
            if len(body) != size:
                raise ValueError('Incomplete request')
            payload = verify(body, self.headers, os.environ['SEND_EMAIL_HOOK_SECRET'])
        except (ValueError, KeyError):
            self.send_error(401)
            return
        try:
            self.server.outbox.enqueue(self.headers.get('webhook-id'), body,
                                       messages(payload, os.environ['AUTH_EMAIL_PAGE_URL']))
        except Exception:
            # Never log credentials, tokens, email addresses, or provider error details.
            report_error('enqueue')
            self.send_error(503, 'Email delivery unavailable')
            return
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(b'{}')

    def log_message(self, *_):
        pass


class BoundedServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, handler, outbox, concurrency=32):
        self.slots = threading.BoundedSemaphore(concurrency)
        self.outbox = outbox
        super().__init__(address, handler)

    def process_request(self, request, address):
        request.settimeout(10)
        if not self.slots.acquire(blocking=False):
            try:
                request.sendall(b'HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        def expire():
            try:request.shutdown(socket.SHUT_RDWR)
            except OSError:pass
        deadline=threading.Timer(15,expire)
        deadline.daemon=True
        deadline.start()
        try:
            super().process_request_thread(request, address)
        finally:
            deadline.cancel()
            self.slots.release()

    def handle_error(self, request, address):
        # No raw handler tracebacks containing mail payloads or addresses.
        pass


def run_worker(outbox, stop):
    last_prune = 0
    while not stop.is_set():
        try:
            if time.monotonic() - last_prune >= 60:
                outbox.prune()
                last_prune = time.monotonic()
            if not outbox.process_one(deliver):
                stop.wait(1)
        except Exception:
            # Persistence outage: keep the lease/payload and retry after recovery.
            report_error('worker')
            stop.wait(5)


if __name__ == '__main__':
    required = ['SEND_EMAIL_HOOK_SECRET', 'SMTP_HOST', 'SMTP_USER', 'SMTP_PASSWORD', 'MAIL_FROM_ADDRESS', 'AUTH_EMAIL_PAGE_URL', 'MAIL_OUTBOX_PATH']
    if any(not os.environ.get(key) for key in required):
        raise SystemExit('Missing required mail environment settings; see docs/backend/OWNED-CHANNELS.md')
    outbox = Outbox(os.environ['MAIL_OUTBOX_PATH'])
    stop = threading.Event()
    workers = [threading.Thread(target=run_worker, args=(outbox, stop), daemon=True) for _ in range(2)]
    for worker in workers:
        worker.start()
    server = BoundedServer(('127.0.0.1', int(os.environ.get('MAIL_WEBHOOK_PORT', '8787'))), Handler, outbox)
    try:
        server.serve_forever()
    finally:
        stop.set()
        server.server_close()
        for worker in workers:
            worker.join(timeout=15)
