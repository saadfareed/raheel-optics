"""Exercise actual deployment SQL in a database inside the disposable QA cluster."""
from pathlib import Path
import hashlib
import importlib.util
import os
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
socket = Path(os.environ.get('PGHOST', ''))
assert socket.name == 'socket' and socket.parent.name.startswith('daymark-qa-'), 'Disposable QA cluster required'
spec = importlib.util.spec_from_file_location('deploy_backend', ROOT / '.engineering/scripts/deploy_backend.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)
database = 'deployment_qa_' + uuid.uuid4().hex
admin_env = dict(os.environ, PGDATABASE='postgres')
env = dict(os.environ, PGDATABASE=database)

def sql(text, connection=env, expect_success=True):
    result = subprocess.run(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'], input=text,
        env=connection, capture_output=True, text=True)
    if expect_success and result.returncode:
        raise AssertionError(result.stderr)
    return result

def interrupt(script, name):
    connection = dict(env, PGAPPNAME=name)
    process = subprocess.Popen(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'], stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=connection)
    process.stdin.write(script)
    process.stdin.close()
    process.stdin = None
    try:
        deadline = time.monotonic()+10
        while time.monotonic()<deadline:
            pid = sql(f"SELECT pid FROM pg_stat_activity WHERE application_name='{name}' AND wait_event='PgSleep';", admin_env).stdout.strip()
            if pid:
                sql(f'SELECT pg_terminate_backend({int(pid)});', admin_env)
                process.communicate(timeout=5)
                assert process.returncode != 0
                return
            if process.poll() is not None:
                raise AssertionError('Probe ended before the interrupt point')
            time.sleep(.05)
        raise AssertionError('Interrupt point not reached')
    finally:
        if process.poll() is None:
            process.kill()
            process.communicate()

sql(f'CREATE DATABASE {database};', admin_env)
try:
    bootstrap = (ROOT / 'backend/tests/bootstrap.sql').read_text()
    bootstrap = '\n'.join(line for line in bootstrap.splitlines() if not line.startswith('create role '))
    sql(bootstrap)
    paths = sorted((ROOT / 'backend/migrations').glob('*.sql'))
    first = deploy.deployment_sql(paths[:1])
    before_record = first.replace('INSERT INTO public.daymark_schema_migrations', 'SELECT pg_sleep(30);\nINSERT INTO public.daymark_schema_migrations', 1)
    interrupt(before_record, 'qa_before_record')
    assert sql("SELECT to_regclass('public.todos') IS NULL;").stdout.strip() == 't'
    assert sql('SELECT count(*) FROM public.daymark_schema_migrations;').stdout.strip() == '0'
    sql(first)
    assert sql('SELECT count(*) FROM public.daymark_schema_migrations;').stdout.strip() == '1'

    # Interruption after commit must leave BOTH schema and hash present for retry.
    with tempfile.TemporaryDirectory(prefix='daymark-migration-fixtures-') as directory:
        fixture = Path(directory) / '010_runner_probe.sql'
        fixture.write_text('begin;\ncreate table public.runner_probe(id integer primary key);\ncommit;\n')
        script = deploy.deployment_sql([fixture])
        interrupt(script.replace('COMMIT;', 'COMMIT;\nSELECT pg_sleep(30);', 1), 'qa_after_commit')
        assert sql("SELECT to_regclass('public.runner_probe') IS NOT NULL;").stdout.strip() == 't'
        assert sql("SELECT count(*) FROM public.daymark_schema_migrations WHERE name='010_runner_probe.sql';").stdout.strip() == '1'
        assert 'Already applied' in sql(script).stdout
        fixture.write_text(fixture.read_text()+'-- changed checksum\n')
        assert sql(deploy.deployment_sql([fixture]), expect_success=False).returncode != 0
        sql("DROP TABLE public.runner_probe; DELETE FROM public.daymark_schema_migrations WHERE name='010_runner_probe.sql';")
        fixture.write_text('begin;\ncreate table public.runner_probe(id integer primary key);\ncommit;\n')
        script = deploy.deployment_sql([fixture])
        runner = subprocess.Popen(['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
        runner.stdin.write(script.replace('INSERT INTO public.daymark_schema_migrations', 'SELECT pg_sleep(.4);\nINSERT INTO public.daymark_schema_migrations', 1))
        runner.stdin.close(); runner.stdin = None
        other = sql(script)
        output, errors = runner.communicate(timeout=10)
        assert runner.returncode == 0, errors
        assert sql("SELECT count(*) FROM public.daymark_schema_migrations WHERE name='010_runner_probe.sql';").stdout.strip() == '1'
        assert 'Already applied' in output+other.stdout

    sql(deploy.deployment_sql(paths))
    assert sql('SELECT count(*) FROM public.daymark_schema_migrations WHERE name<>\'010_runner_probe.sql\';').stdout.strip() == str(len(paths))
    assert sql("SELECT to_regclass('public.todo_history_latest') IS NOT NULL;").stdout.strip() == 't'
    assert sql(deploy.deployment_sql(paths)).stdout.count('Already applied') == len(paths)
    print('PASS: deployment interruption before/after commit, retries, checksum rejection and concurrent runners', flush=True)
finally:
    sql(f'DROP DATABASE {database} WITH (FORCE);', admin_env)
