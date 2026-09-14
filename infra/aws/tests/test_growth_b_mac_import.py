"""Local tests only: no AWS, tunnel, MySQL server or real SQL dump."""
import argparse
from contextlib import contextmanager
import copy
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import stat
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('mac_import_under_test', SCRIPTS / 'growth_b_mac_import.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
UUID = '12345678-1234-4123-8123-123456789abc'
PASSWORD = 'TEST_ONLY_PASSWORD_DO_NOT_REPORT'


class MacImportTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.dump = self.root / 'airbob-growth.sql.gz'
        self.dump.write_bytes(gzip.compress(b'-- synthetic fixture only\nSELECT 1;\n'))
        self.ca = self.root / 'aws-ca.pem'
        self.ca.write_text('TEST_CA_BYTES')
        client = self.root / 'mysql'
        client.write_text('#!/bin/sh\nexit 99\n')
        client.chmod(0o700)
        self.args = argparse.Namespace(action='import', identifier='airbob-lab-mac-test',
            endpoint='airbob-lab-mac-test.example.ap-northeast-2.rds.amazonaws.com',
            resource_id='db-ABCDEFGHIJKLMNOPQRSTUVWXYZ', tunnel_port=13306,
            aws_profile='test-profile', dump=self.dump, dump_sha256=self.sha(self.dump),
            ca_bundle=self.ca, ca_sha256=self.sha(self.ca), mysql_client=client,
            output=self.root / 'evidence', expected_server_uuid=None)
        self.rds = {'DBInstanceIdentifier': self.args.identifier, 'DbiResourceId': self.args.resource_id,
            'DBInstanceArn': f'arn:aws:rds:{m.REGION}:{m.ACCOUNT}:db:{self.args.identifier}',
            'Endpoint': {'Address': self.args.endpoint, 'Port': 3306}, 'PubliclyAccessible': False,
            'Engine': 'mysql', 'EngineVersion': '8.4.11', 'DBInstanceStatus': 'available',
            'DBInstanceClass': 'db.m6i.large', 'AllocatedStorage': 100, 'StorageType': 'gp3',
            'MultiAZ': False, 'StorageEncrypted': True, 'PendingModifiedValues': {},
            'MasterUsername': 'admin', 'MasterUserSecret': {'SecretArn':
                f'arn:aws:secretsmanager:{m.REGION}:{m.ACCOUNT}:secret:rds!db-test', 'SecretStatus': 'active'}}
        self.state = SimpleNamespace(imported=False, table_objects=0, routines=0, clients=0,
                                     schema_exists=True, other_schema=False, uuid=UUID,
                                     postcheck_uuid=None, stream_failure=False, streams=0,
                                     flyway=28, failed_migrations=0, owner=1, listing=1)
        self.aws_calls, self.executed_sql = [], []
        self.last_db = None
        owner = self

        class FakeAws:
            def __init__(self, profile):
                owner.assertEqual(profile, 'test-profile')

            def call(self, *args):
                owner.aws_calls.append(args)
                if args[:2] == ('sts', 'get-caller-identity'):
                    return {'Account': m.ACCOUNT}
                if args[:2] == ('rds', 'describe-db-instances'):
                    owner.assertEqual(args[2:], ('--db-instance-identifier', owner.args.identifier))
                    return {'DBInstances': [copy.deepcopy(owner.rds)]}
                if args[:2] == ('secretsmanager', 'get-secret-value'):
                    return {'ARN': owner.rds['MasterUserSecret']['SecretArn'],
                            'SecretString': json.dumps({'username': 'admin', 'password': PASSWORD})}
                raise AssertionError('Non-read AWS call')

        class FakeDatabase:
            def __init__(self, defaults, args, guard):
                owner.assertEqual(stat.S_IMODE(defaults.stat().st_mode), 0o600)
                owner.assertIn(PASSWORD, defaults.read_text())
                self.guard, self.defaults, self.timeout = guard, defaults, 60
                owner.last_db = self

            def rows(self, sql, database=False):
                self.guard()
                if sql.startswith('SELECT @@version'):
                    current = owner.state.postcheck_uuid if owner.state.imported and owner.state.postcheck_uuid else owner.state.uuid
                    return [{'version': '8.4.11', 'serverUuid': current, 'readOnly': 0, 'superReadOnly': 0}]
                if sql.startswith('SHOW SESSION'):
                    return [{'Variable_name': 'Ssl_cipher', 'Value': 'TLS_AES_256_GCM_SHA384'}]
                if sql.startswith('SELECT SCHEMA_NAME'):
                    schemas = set(m.SYSTEM_SCHEMAS)
                    if owner.state.schema_exists:
                        schemas.add('airbobdb')
                    if owner.state.other_schema:
                        schemas.add('foreign')
                    return [{'SCHEMA_NAME': x} for x in schemas]
                raise AssertionError('Unexpected read query')

            def scalar(self, sql, database=False):
                self.guard()
                if 'information_schema.tables' in sql:
                    return 4 if owner.state.imported else owner.state.table_objects
                if 'information_schema.routines' in sql:
                    return owner.state.routines
                if 'information_schema.processlist' in sql:
                    return owner.state.clients
                if 'flyway_schema_history WHERE success=1' in sql:
                    return owner.state.flyway
                if 'flyway_schema_history WHERE success=0' in sql:
                    return owner.state.failed_migrations
                if 'FROM airbobdb.member WHERE id=6675' in sql:
                    return owner.state.owner
                if 'FROM airbobdb.accommodation WHERE id=16102 AND member_id=6675' in sql:
                    return owner.state.listing
                raise AssertionError('Unexpected scalar query')

            def execute(self, sql, database=False):
                owner.executed_sql.append(sql)
                owner.assertEqual(sql, 'CREATE DATABASE airbobdb CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
                owner.state.schema_exists = True

        @contextmanager
        def fake_fence(db):
            db.guard(force=True)
            yield

        def fake_stream(db, dump, *, progress):
            owner.state.streams += 1
            owner.assertTrue((owner.args.output / 'import-intent.json').exists())
            owner.assertEqual(dump, owner.dump)
            value = {'state': 'SQL_IMPORT_RUNNING', 'compressedBytesRead': 0,
                     'compressedBytesTotal': dump.stat().st_size, 'elapsedSeconds': 20000.0,
                     'mysqlExitCode': None, 'gzipExitCode': None}
            progress(value)
            if owner.state.stream_failure:
                raise ValueError('test-private-subprocess-error')
            db.guard(force=True)
            owner.state.imported = True
            value.update(state='SQL_IMPORT_COMPLETED', compressedBytesRead=dump.stat().st_size,
                         mysqlExitCode=0, gzipExitCode=0)
            progress(value)
            return value

        for item in (patch.object(m, 'Aws', FakeAws), patch.object(m, 'Database', FakeDatabase),
                     patch.object(m.restore, 'exclusive_database', fake_fence),
                     patch.object(m.restore, 'stream_restore', fake_stream),
                     patch.object(m.sys, 'platform', 'darwin'), patch.object(Path, 'home', return_value=self.root),
                     patch.object(m.socket, 'getaddrinfo', return_value=[(socket.AF_INET, socket.SOCK_STREAM, 6, '', ('127.0.0.1', 13306))])):
            item.start()
            self.addCleanup(item.stop)
        self.stdout = io.StringIO()
        context = patch('sys.stdout', self.stdout)
        context.start()
        self.addCleanup(context.stop)

    @staticmethod
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def assert_secret_removed(self):
        self.assertEqual(list(self.args.output.glob('.mysql-*')), [])
        self.assertNotIn(PASSWORD, self.stdout.getvalue())
        for path in self.args.output.glob('*.json'):
            self.assertNotIn(PASSWORD, path.read_text())

    def test_success_uses_single_stream_and_unlimited_sql_time(self):
        result = m.run(self.args)
        self.assertEqual(result['state'], 'SQL_IMPORT_POSTCHECK_PASSED')
        completed = m.read_record(self.args.output / 'sql-import-completed.json')
        self.assertEqual(completed['stream']['elapsedSeconds'], 20000)
        self.assertIsNone(completed['binding']['sqlElapsedTimeoutSeconds'])
        self.assertEqual(completed['database']['serverUuid'], UUID)
        self.assertFalse(completed['fullDatasetValidated'])
        self.assertEqual(self.state.streams, 1)
        self.assertEqual(sum(call[:2] == ('sts', 'get-caller-identity') for call in self.aws_calls), 1)
        self.assertEqual(result['flywayVersion'], 28)
        self.assert_secret_removed()

    def test_expiry_is_reported_without_a_second_import_cutoff(self):
        self.rds['TagList'] = [{'Key': 'ExpiresAt', 'Value': '1'}]
        self.assertEqual(m.run(self.args)['state'], 'SQL_IMPORT_POSTCHECK_PASSED')
        value = m.read_record(self.args.output / 'progress.json')
        self.assertTrue(value['resourceExpiryReached'])
        self.assertFalse(value['resourceExpiryAutomaticallyExtended'])

    def test_small_postcheck_failure_does_not_invalidate_import_checkpoint(self):
        self.state.flyway = 27
        with self.assertRaisesRegex(m.MacImportError, 'IMPORTED_REPRESENTATIVE_OR_FLYWAY_MISMATCH'):
            m.run(self.args)
        self.assertEqual(m.read_record(self.args.output / 'sql-import-completed.json')['state'], 'SQL_IMPORT_COMPLETED')
        self.assertEqual(self.state.streams, 1)

    def test_postcheck_failure_preserves_completion_and_never_reimports(self):
        self.state.postcheck_uuid = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
        with self.assertRaisesRegex(m.MacImportError, 'DATABASE_UUID_CHANGED'):
            m.run(self.args)
        completed = self.args.output / 'sql-import-completed.json'
        original = completed.read_bytes()
        self.assertTrue(list(self.args.output.glob('postcheck-failed-*.json')))
        with self.assertRaisesRegex(m.MacImportError, 'SQL_ALREADY_IMPORTED_USE_POSTCHECK'):
            m.run(self.args)
        self.state.postcheck_uuid = None
        self.args.action = 'postcheck'
        self.assertEqual(m.run(self.args)['state'], 'SQL_IMPORT_POSTCHECK_PASSED')
        self.assertEqual(completed.read_bytes(), original)
        self.assertEqual(self.state.streams, 1)
        self.assert_secret_removed()

    def test_ambiguous_import_retains_intent_and_rejects_replay(self):
        self.state.stream_failure = True
        with self.assertRaises(ValueError):
            m.run(self.args)
        self.assertFalse((self.args.output / 'sql-import-completed.json').exists())
        self.assertTrue((self.args.output / 'import-unconfirmed.json').exists())
        with self.assertRaisesRegex(m.MacImportError, 'IMPORT_INTENT_EXISTS_NO_REPLAY'):
            m.run(self.args)
        self.assertEqual(self.state.streams, 1)
        self.assert_secret_removed()

    def test_failed_completion_checkpoint_does_not_replay_successful_sql(self):
        original = m.record
        def fail_checkpoint(path, value):
            if path.name == 'sql-import-completed.json':
                raise OSError('test-only fsync failure')
            return original(path, value)
        with patch.object(m, 'record', side_effect=fail_checkpoint):
            with self.assertRaises(OSError):
                m.run(self.args)
        with self.assertRaisesRegex(m.MacImportError, 'IMPORT_INTENT_EXISTS_NO_REPLAY'):
            m.run(self.args)
        self.assertEqual(self.state.streams, 1)
        self.assert_secret_removed()

    def test_postcheck_requires_exact_original_binding_and_no_new_stream(self):
        m.run(self.args)
        self.args.action = 'postcheck'
        self.args.tunnel_port += 1
        with self.assertRaisesRegex(m.MacImportError, 'COMPLETED_IMPORT_BINDING_REQUIRED'):
            m.run(self.args)
        self.assertEqual(self.state.streams, 1)

    def test_wrong_dump_sha_fails_before_aws_or_intent(self):
        self.args.dump_sha256 = '0' * 64
        with self.assertRaisesRegex(m.MacImportError, 'INPUT_SHA_OR_IDENTITY_CHANGED'):
            m.run(self.args)
        self.assertEqual(self.aws_calls, [])
        self.assertFalse((self.args.output / 'import-intent.json').exists())

    def test_optional_expected_uuid_is_enforced_before_import(self):
        self.args.expected_server_uuid = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
        with self.assertRaisesRegex(m.MacImportError, 'DATABASE_UUID_CHANGED'):
            m.run(self.args)
        self.assertEqual(self.state.streams, 0)
        self.assert_secret_removed()

    def test_no_empty_schema_destroy_or_extra_rehearsal(self):
        self.state.schema_exists = False
        m.run(self.args)
        self.assertEqual(len(self.executed_sql), 1)
        self.assertTrue(self.executed_sql[0].startswith('CREATE DATABASE airbobdb '))
        self.assertFalse(any('DROP' in s for s in self.executed_sql))

    def test_nonempty_or_foreign_or_active_target_is_rejected(self):
        for field, value, code in [('table_objects', 1, 'DATABASE_NOT_EMPTY'),
                                  ('routines', 1, 'DATABASE_ROUTINE_OR_EVENT_PRESENT'),
                                  ('clients', 1, 'OTHER_DATABASE_CLIENTS_PRESENT'),
                                  ('other_schema', True, 'FOREIGN_BUSINESS_SCHEMA_PRESENT')]:
            with self.subTest(field=field):
                setattr(self.state, field, value)
                with self.assertRaisesRegex(m.MacImportError, code):
                    m.run(self.args)
                setattr(self.state, field, False if field == 'other_schema' else 0)
                self.assertFalse((self.args.output / 'import-intent.json').exists())
        self.assertEqual(self.state.streams, 0)

    def test_rds_identity_private_engine_class_and_pending_drift(self):
        original = copy.deepcopy(self.rds)
        for field, value in [('DbiResourceId', 'db-FOREIGN'), ('PubliclyAccessible', True),
                             ('EngineVersion', '8.0.43'), ('DBInstanceClass', 'db.t3.small'),
                             ('AllocatedStorage', 200), ('MultiAZ', True),
                             ('PendingModifiedValues', {'DBInstanceClass': 'db.t3.small'})]:
            with self.subTest(field=field):
                self.rds[field] = value
                with self.assertRaisesRegex(m.MacImportError, 'RDS_IDENTITY_OR_SHAPE_MISMATCH'):
                    m.run(self.args)
                self.rds = copy.deepcopy(original)
        self.assertEqual(self.state.streams, 0)

    def test_public_or_mixed_dns_does_not_open_mysql(self):
        with patch.object(m.socket, 'getaddrinfo', return_value=[
                (socket.AF_INET, socket.SOCK_STREAM, 6, '', ('127.0.0.1', 13306)),
                (socket.AF_INET, socket.SOCK_STREAM, 6, '', ('10.1.2.3', 13306))]):
            with self.assertRaisesRegex(m.MacImportError, 'REVIEWED_ENDPOINT_LOOPBACK_DNS_OVERRIDE_REQUIRED'):
                m.run(self.args)
        self.assertEqual(self.aws_calls, [])
        self.assertIsNone(self.last_db)

    def test_target_lock_rejects_same_resource_with_different_output(self):
        with m.target_lock(self.args.resource_id):
            self.args.output = self.root / 'different-output'
            with self.assertRaisesRegex(m.MacImportError, 'TARGET_IMPORT_ALREADY_RUNNING'):
                m.run(self.args)
        self.assertEqual(self.aws_calls, [])

    def test_resume_with_changed_stored_uuid_fails_read_only(self):
        m.run(self.args)
        self.args.action = 'postcheck'
        self.state.uuid = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
        with self.assertRaisesRegex(m.MacImportError, 'DATABASE_UUID_CHANGED'):
            m.run(self.args)
        self.assertEqual(self.state.streams, 1)


class ClientAndCliTests(unittest.TestCase):
    def test_command_keeps_original_hostname_and_verified_tls(self):
        args = SimpleNamespace(mysql_client=Path('/reviewed/mysql'), endpoint='db.example.ap-northeast-2.rds.amazonaws.com',
                               tunnel_port=13306, ca_bundle=Path('/reviewed/ca.pem'))
        db = m.Database(Path('/private/mysql.cnf'), args, None)
        command = db.command()
        self.assertIn('--host=' + args.endpoint, command)
        self.assertIn('--port=13306', command)
        self.assertIn('--ssl-mode=VERIFY_IDENTITY', command)
        self.assertIn('--defaults-file=/private/mysql.cnf', command)
        self.assertIn('--no-login-paths', command)
        self.assertIn('--skip-reconnect', command)
        self.assertEqual(command[:3], ['/usr/bin/env', '-i', 'PATH=/usr/bin:/bin'])
        self.assertNotIn(PASSWORD, ' '.join(command))
        self.assertNotIn('--ssl-mode=DISABLED', command)

    def test_cli_requires_explicit_target_hash_tunnel_and_profile(self):
        with patch('sys.stderr', io.StringIO()):
            for args in ([], ['import'], ['import', '--sql-timeout', '14400']):
                with self.assertRaises(SystemExit):
                    m.parser().parse_args(args)
        options = {s for a in m.parser()._actions for s in a.option_strings}
        self.assertNotIn('--sql-timeout', options)
        self.assertIn('--expected-server-uuid', options)

    def test_aws_credential_error_is_closed_and_never_copies_stderr(self):
        result = SimpleNamespace(returncode=1, stdout=b'', stderr=('ExpiredToken ' + PASSWORD).encode())
        with patch.object(m.subprocess, 'run', return_value=result) as process:
            with self.assertRaises(m.MacImportError) as caught:
                m.Aws('review-profile').call('sts', 'get-caller-identity')
        self.assertEqual(caught.exception.code, 'AWS_AUTHENTICATION_REQUIRED')
        self.assertNotIn(PASSWORD, str(caught.exception))
        self.assertIn('--profile', process.call_args.args[0])
        with self.assertRaisesRegex(m.MacImportError, 'NON_READ_AWS_OPERATION_REJECTED'):
            m.Aws('review-profile').call('rds', 'modify-db-instance')

    def test_shared_stream_ignores_query_timeout_and_uses_direct_pipe(self):
        with tempfile.TemporaryDirectory() as temporary:
            dump = Path(temporary) / 'synthetic.sql.gz'
            dump.write_bytes(gzip.compress(b'-- synthetic test bytes\n'))
            db = SimpleNamespace(timeout=0.000001, guard=None,
                command=lambda: [sys.executable, '-c', 'import sys,time;sys.stdin.buffer.read();time.sleep(0.02)'])
            observations = []
            summary = m.restore.stream_restore(db, dump, progress=observations.append)
            self.assertEqual(summary['mysqlExitCode'], 0)
            self.assertEqual(summary['gzipExitCode'], 0)
            self.assertEqual(summary['compressedBytesRead'], dump.stat().st_size)
            self.assertGreater(summary['elapsedSeconds'], db.timeout)
            self.assertEqual(observations[-1]['state'], 'SQL_IMPORT_COMPLETED')


if __name__ == '__main__':
    unittest.main(verbosity=2)
