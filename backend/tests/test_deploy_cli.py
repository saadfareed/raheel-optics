"""Deployment CLI must reject non-deployment options before reading secrets."""
import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('deploy_backend', ROOT / '.engineering/scripts/deploy_backend.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class DeploymentCliTest(unittest.TestCase):
    def test_help_never_reads_settings_or_connects(self):
        with patch.object(deploy, 'settings') as settings, patch.object(deploy.subprocess, 'run') as run:
            with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(SystemExit) as stopped:
                deploy.main(['--help'])
            self.assertEqual(0, stopped.exception.code)
            settings.assert_not_called()
            run.assert_not_called()

    def test_unknown_option_cannot_start_deployment(self):
        with patch.object(deploy, 'settings') as settings, patch.object(deploy.subprocess, 'run') as run:
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as stopped:
                deploy.main(['--unknown'])
            self.assertEqual(2, stopped.exception.code)
            settings.assert_not_called()
            run.assert_not_called()

    def test_check_only_runs_read_query(self):
        values = {'DATABASE_URL': 'postgresql://qa:synthetic-password@db.example.test/postgres?sslmode=require'}
        with patch.object(deploy, 'settings', return_value=values), patch.object(deploy.subprocess, 'run', return_value=SimpleNamespace(returncode=0, stdout='5', stderr='')) as run:
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, deploy.main(['--check']))
            run.assert_called_once()
            self.assertIn('SELECT count(*)', run.call_args.args[0][-1])
            self.assertIsNone(run.call_args.kwargs['input'])

    def test_connection_denial_is_distinct_from_database_grant_failure(self):
        connection = deploy.connection_failure('connection to server failed: Permission denied')
        self.assertIn('before authentication', connection)
        self.assertIn('user lacks', deploy.connection_failure('ERROR: permission denied for database private'))

    def test_diagnostics_never_echo_server_messages_or_credentials(self):
        secret = 'PRIVATE_PASSWORD_DO_NOT_PRINT'
        for error in ('password authentication failed', 'could not translate host name', 'unknown failure'):
            diagnosis = deploy.connection_failure(error + ': ' + secret)
            self.assertNotIn(secret, diagnosis)
            self.assertNotIn('unknown failure', diagnosis)


if __name__ == '__main__':
    unittest.main()
