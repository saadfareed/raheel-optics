import tempfile
from pathlib import Path
import unittest
import threading
from outbox import Outbox


class OutboxTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / 'private' / 'queue.sqlite'
        self.outbox = Outbox(self.path)
        self.message = ('user@example.test', 'Subject', 'Secret link')

    def tearDown(self):
        self.directory.cleanup()

    def test_restart_dedup_and_private_permissions(self):
        self.assertTrue(self.outbox.enqueue('event', b'payload', [self.message], now=1000))
        reopened = Outbox(self.path)
        self.assertFalse(reopened.enqueue('event', b'payload', [self.message], now=1001))
        with self.assertRaises(ValueError):
            reopened.enqueue('event', b'changed', [self.message], now=1002)
        delivered = []
        self.assertTrue(reopened.process_one(delivered.extend, now=1001))
        self.assertEqual(delivered, [self.message])
        self.assertFalse(reopened.process_one(delivered.extend, now=1002))
        with reopened.connect() as db:
            self.assertIsNone(db.execute('SELECT body FROM jobs').fetchone()[0])
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)

    def test_retry_backoff_and_terminal_body_cleanup(self):
        box = Outbox(self.path, max_attempts=2)
        box.enqueue('event', b'payload', [self.message], now=1000)
        def fail(_):
            raise RuntimeError('Provider unavailable')
        self.assertTrue(box.process_one(fail, now=1000))
        self.assertFalse(box.process_one(fail, now=1029))
        self.assertTrue(box.process_one(fail, now=1030))
        self.assertEqual(box.stats()['counts'], {'dead': 1})
        with box.connect() as db:
            self.assertIsNone(db.execute('SELECT body FROM jobs').fetchone()[0])

    def test_lease_recovery_and_expiry(self):
        self.outbox.enqueue('event', b'payload', [self.message], now=1000)
        with self.outbox.connect() as db:
            db.execute("UPDATE jobs SET state='sending',lease_until=1120,attempts=1")
        sent = []
        self.assertFalse(self.outbox.process_one(sent.extend, now=1119))
        self.assertTrue(self.outbox.process_one(sent.extend, now=1120))
        self.outbox.enqueue('expired', b'expired', [self.message], now=0)
        self.assertFalse(self.outbox.process_one(sent.extend, now=90000))
        self.assertEqual(len(sent), 1)

    def test_queue_capacity_and_recipient_level_delivery(self):
        box = Outbox(self.path, capacity=2)
        box.enqueue('event', b'payload', [self.message, ('second@example.test', 'Second', 'Secret')], now=0)
        with self.assertRaises(RuntimeError):
            box.enqueue('overflow', b'payload', [self.message], now=0)
        sent = []
        box.process_one(sent.extend, now=0)
        box.process_one(sent.extend, now=0)
        self.assertEqual(len(sent), 2)
        self.assertFalse(box.enqueue('event', b'payload', [self.message], now=1))

    def test_two_workers_do_not_claim_the_same_active_lease(self):
        self.outbox.enqueue('event', b'payload', [self.message], now=1000)
        started = threading.Event()
        release = threading.Event()
        sent = []
        def sender(items):
            started.set()
            if not release.wait(5):
                raise RuntimeError('Test worker timed out')
            sent.extend(items)
        thread = threading.Thread(target=lambda: self.outbox.process_one(sender, now=1000))
        thread.start()
        try:
            self.assertTrue(started.wait(5))
            self.assertFalse(self.outbox.process_one(sent.extend, now=1001))
        finally:
            release.set()
            thread.join(timeout=5)
        self.assertEqual(sent, [self.message])

    def test_one_recipient_failure_does_not_resend_recorded_recipient(self):
        self.outbox.enqueue('event', b'payload', [self.message, ('second@example.test', 'Second', 'Link')], now=0)
        sent = []
        self.outbox.process_one(sent.extend, now=0)
        def fail(_):
            raise RuntimeError('Provider failure')
        self.outbox.process_one(fail, now=0)
        self.outbox.process_one(sent.extend, now=30)
        self.assertEqual([item[0] for item in sent], ['user@example.test', 'second@example.test'])



class LeaseAndRetentionTest(unittest.TestCase):
    def test_old_worker_cannot_overwrite_a_reclaimed_lease(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory).chmod(0o700)
            box=Outbox(Path(directory)/'outbox.db')
            box.enqueue('event',b'body',[('recipient@example.invalid','Subject','Body')],now=1000)
            def old_sender(items):
                def new_sender(items):raise RuntimeError('New worker retry')
                self.assertTrue(box.process_one(new_sender,now=1120))
            self.assertTrue(box.process_one(old_sender,now=1000))
            with box.connect() as db:
                row=db.execute('SELECT state,attempts,body FROM jobs').fetchone()
            self.assertEqual(('queued',2),row[:2])
            self.assertIsNotNone(row[2])

    def test_retention_deletes_only_a_bounded_page(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory).chmod(0o700)
            box=Outbox(Path(directory)/'outbox.db')
            with box.connect() as db:
                db.executemany('INSERT INTO events VALUES(?,?,?)',[(str(i),'digest',0) for i in range(1001)])
            box.prune(now=8*86400)
            with box.connect() as db:self.assertEqual(1,db.execute('SELECT count(*) FROM events').fetchone()[0])
            box.prune(now=8*86400)
            with box.connect() as db:self.assertEqual(0,db.execute('SELECT count(*) FROM events').fetchone()[0])

if __name__ == '__main__':
    unittest.main()
