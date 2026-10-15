"""Disposable QA-only pg_dump/restore and receipt/projection consistency drill."""
import os
from pathlib import Path
import subprocess
import tempfile
import uuid

socket = Path(os.environ.get('PGHOST', ''))
assert socket.name == 'socket' and socket.parent.name.startswith('daymark-qa-'), 'Disposable QA cluster required'
source = 'restore_source_' + uuid.uuid4().hex
restored = 'restore_target_' + uuid.uuid4().hex
root = Path(__file__).resolve().parents[2]
base = dict(os.environ)
def run(*command, database='postgres', input=None):
    return subprocess.run(command, env=base | {'PGDATABASE': database}, input=input, text=True,
                          check=True, capture_output=True).stdout

try:
    run('createdb', source)
    # auth.uid() depends on per-request settings; test roles already exist in this cluster.
    bootstrap = (root / 'backend/tests/bootstrap.sql').read_text()
    bootstrap = '\n'.join(line for line in bootstrap.splitlines() if not line.startswith('create role '))
    run('psql', '-X', '-v', 'ON_ERROR_STOP=1', database=source, input=bootstrap)
    for migration in sorted((root / 'backend/migrations').glob('*.sql')):
        run('psql', '-X', '-v', 'ON_ERROR_STOP=1', '-f', str(migration), database=source)
    fixture = """
        SET ROLE authenticated;
        SELECT set_config('request.jwt.claim.sub','11111111-1111-1111-1111-111111111111',false);
        SELECT public.apply_operations('[
          {"id":"aaaaaaaa-1111-1111-1111-111111111111","action":"create","baseRevision":0,"occurredAt":1000,"todo":{"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","title":"Restore task"}},
          {"id":"aaaaaaaa-2222-2222-2222-222222222222","action":"complete","baseRevision":1,"occurredAt":2000,"todo":{"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","title":"Restore task"}}
        ]');
    """
    run('psql', '-X', '-v', 'ON_ERROR_STOP=1', database=source, input=fixture)
    with tempfile.TemporaryDirectory(prefix='daymark-restore-') as directory:
        archive = str(Path(directory) / 'backup.dump')
        run('pg_dump', '-Fc', '-f', archive, database=source)
        run('createdb', restored)
        run('pg_restore', '--exit-on-error', '-d', restored, archive, database=restored)
    # Replay after recovery must leave the canonical counts and history unchanged.
    run('psql', '-X', '-v', 'ON_ERROR_STOP=1', database=restored, input=fixture)
    counts = run('psql', '-X', '-At', '-c', "SELECT (SELECT count(*) FROM todos),(SELECT count(*) FROM operation_receipts),(SELECT count(*) FROM todo_history),(SELECT count(*) FROM todo_history_latest),(SELECT completed FROM user_totals),(SELECT history_events FROM mutation_admission);", database=restored).strip()
    assert counts == '1|2|1|1|1|1', counts
    print('PASS: disposable backup restore, receipt replay, totals and history projections')
finally:
    run('dropdb', '--if-exists', restored)
    run('dropdb', '--if-exists', source)
