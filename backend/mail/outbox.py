"""Private SQLite outbox. SMTP is at-least-once; receipts bound webhook retries."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import time
import uuid
from contextlib import contextmanager


class Outbox:
    def __init__(self, path, capacity=5000, max_attempts=8, ttl=86400):
        self.path = Path(path)
        self.capacity, self.max_attempts, self.ttl = capacity, max_attempts, ttl
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        # Require a private dedicated directory; do not chmod a shared parent.
        if self.path.parent.stat().st_mode & 0o077:
            raise ValueError('Mail outbox directory must have mode 0700')
        descriptor = os.open(self.path, os.O_CREAT | os.O_RDWR, 0o600)
        os.close(descriptor)
        os.chmod(self.path, 0o600)
        with self.connect() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.executescript('''
                CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, digest TEXT NOT NULL, created REAL NOT NULL);
                CREATE INDEX IF NOT EXISTS event_retention ON events(created);
                CREATE TABLE IF NOT EXISTS jobs(
                    id INTEGER PRIMARY KEY, event TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
                    recipient INTEGER NOT NULL, body TEXT, state TEXT NOT NULL DEFAULT 'queued',
                    attempts INTEGER NOT NULL DEFAULT 0, next_at REAL NOT NULL,
                    lease_until REAL NOT NULL DEFAULT 0, expires REAL NOT NULL,
                    UNIQUE(event,recipient));
                CREATE INDEX IF NOT EXISTS ready_jobs ON jobs(state,next_at,lease_until);
            ''')
            if 'lease_token' not in {row[1] for row in db.execute('PRAGMA table_info(jobs)')}:
                db.execute('ALTER TABLE jobs ADD COLUMN lease_token TEXT')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.execute('PRAGMA foreign_keys=ON')
        db.execute('PRAGMA synchronous=FULL')
        db.execute('PRAGMA secure_delete=ON')
        try:
            with db:
                yield db
        finally:
            db.close()

    def enqueue(self, event_id, raw, items, now=None):
        now = time.time() if now is None else now
        if not isinstance(event_id, str) or not 1 <= len(event_id) <= 200:
            raise ValueError('Invalid event ID')
        digest = hashlib.sha256(raw).hexdigest()
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            previous = db.execute('SELECT digest FROM events WHERE id=?', (event_id,)).fetchone()
            if previous:
                if previous[0] != digest:
                    raise ValueError('Event ID reused with a different payload')
                return False
            count = db.execute("SELECT count(*) FROM jobs WHERE state IN ('queued','sending')").fetchone()[0]
            if count + len(items) > self.capacity:
                raise RuntimeError('Mail queue full')
            db.execute('INSERT INTO events VALUES(?,?,?)', (event_id, digest, now))
            db.executemany('INSERT INTO jobs(event,recipient,body,next_at,expires) VALUES(?,?,?,?,?)',
                           [(event_id, index, json.dumps(item), now, now + self.ttl) for index, item in enumerate(items)])
        return True

    def process_one(self, sender, now=None):
        now = time.time() if now is None else now
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute("UPDATE jobs SET state='dead',body=NULL WHERE state IN ('queued','sending') AND lease_until<=? AND (expires<=? OR attempts>=?)",
                       (now, now, self.max_attempts))
            row = db.execute("SELECT id,body,attempts FROM jobs WHERE state IN ('queued','sending') AND next_at<=? AND lease_until<=? ORDER BY next_at,id LIMIT 1",
                             (now, now)).fetchone()
            if row is None:
                return False
            identity, body, attempts = row
            attempts += 1
            lease_token=uuid.uuid4().hex
            db.execute("UPDATE jobs SET state='sending',attempts=?,lease_until=?,lease_token=? WHERE id=?", (attempts, now + 120, lease_token, identity))
        try:
            sender([tuple(json.loads(body))])
        except Exception:
            with self.connect() as db:
                dead = attempts >= self.max_attempts
                db.execute("UPDATE jobs SET state=?,body=CASE WHEN ? THEN NULL ELSE body END,next_at=?,lease_until=0 WHERE id=? AND state='sending' AND lease_token=?",
                           ('dead' if dead else 'queued', dead, now + min(3600, 30 * 2 ** (attempts - 1)), identity,lease_token))
        else:
            with self.connect() as db:
                db.execute("UPDATE jobs SET state='sent',body=NULL,lease_until=0 WHERE id=? AND state='sending' AND lease_token=?", (identity,lease_token))
        return True

    def stats(self):
        with self.connect() as db:
            counts = dict(db.execute('SELECT state,count(*) FROM jobs GROUP BY state'))
            oldest = db.execute("SELECT min(events.created) FROM jobs JOIN events ON events.id=jobs.event WHERE state IN ('queued','sending')").fetchone()[0]
        return {'counts': counts, 'oldest_pending_age_seconds': max(0, time.time() - oldest) if oldest is not None else 0}

    def prune(self, now=None):
        now = time.time() if now is None else now
        with self.connect() as db:
            db.execute("DELETE FROM events WHERE id IN (SELECT id FROM events WHERE created<? AND NOT EXISTS(SELECT 1 FROM jobs WHERE event=events.id AND state IN ('queued','sending')) ORDER BY created LIMIT 1000)", (now - 7 * 86400,))
