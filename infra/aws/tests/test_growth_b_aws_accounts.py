"""AWS credential orchestration only: in-memory HTTP and a Python SQL protocol double.

These tests never start or connect to AWS, MySQL, Redis, Docker, or an application.
"""
import base64
import copy
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
sys.path.insert(0, str(ROOT / 'infra/aws/tests/fixtures/etl-account-runtime'))
import growth_accounts as accounts
import growth_b_aws_restore as restore
from growth_runtime import App


def bundle_fixture():
    ownership = {metric: {'count': 1, 'sampleIds': [1]} for metric in (
        'listings', 'publishedListings', 'reservations', 'reviews', 'wishlists',
        'receivedReservations', 'receivedReviews', 'settlements')}
    representatives = [dict(key=key, memberId=member, email=key + '@airbob.test', nickname=key,
        role='ADMIN' if key == 'admin' else 'MEMBER', status='ACTIVE', purpose=key,
        ownership=copy.deepcopy(ownership)) for member, key in ((101, 'demo'), (209, 'host'), (3131, 'admin'))]
    qualification = {'memberId': 51, 'email': 'growth-51@example.test', 'nickname': 'qualification',
                     'role': 'MEMBER', 'status': 'ACTIVE', 'labels': ['qualification-only']}
    return {'schemaVersion': 2, 'datasetProfile': 'global-growth-b', 'accountCount': 2,
        'accounts': [qualification, representatives[2]], 'representativeAccounts': representatives,
        'representativeAccountCount': 3, 'baseScenarioTargets': [],
        'loadPoolPolicy': {'state': 'NOT_CONFIGURED', 'targetConcurrentMembers': None,
                          'qualificationAccountsAreLoadPool': False, 'oneMemberPerConcurrentWriter': True}}


SQL_PROTOCOL = r'''import json, sys
from pathlib import Path
path = Path(sys.argv[1])
state = json.loads(path.read_text())
state.update(statements=[], committed=False, pendingWrites=0, persistedWrites=0, rolledBack=False)
def save():
    temporary = path.with_suffix('.next')
    temporary.write_text(json.dumps(state))
    temporary.replace(path)
try:
    for line in sys.stdin:
        sql = line.strip()
        state['statements'].append(sql)
        if sql.startswith('SELECT JSON_OBJECT'):
            for row in state['locked']:
                print(json.dumps(row), flush=True)
        elif sql.startswith('UPDATE member SET password='):
            state['pendingWrites'] = state['changed']
        elif sql == 'SELECT ROW_COUNT();':
            save()
            print(state['changed'], flush=True)
        elif sql == 'COMMIT;':
            state['committed'] = True
            state['persistedWrites'] = state['pendingWrites']
            state['pendingWrites'] = 0
        elif sql.startswith("SELECT 'airbob_credentials_"):
            print(sql.split("'")[1], flush=True)
        save()
finally:
    state['rolledBack'] = bool(state['pendingWrites']) and not state['committed']
    state['pendingWrites'] = 0
    save()
'''


class PrivateAwsFixture(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(); self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.release = self.root / 'release'; self.release.mkdir()
        self.output = self.root / 'output'; self.output.mkdir()
        self.secret_dir = self.root / 'ephemeral'; self.secret_dir.mkdir(mode=0o700)
        self.config = {'release': str(self.release), 'privateAccounts': str(self.root / 'private/accounts.private.json'),
            'rds': {'resourceId': 'db-FIXTURE', 'serverUuid': '11111111-1111-1111-1111-111111111111'},
            'appJar': '/fixture/app.jar', 'redisImage': 'redis@sha256:' + 'a' * 64,
            'appStartupTimeoutSeconds': 60}
        self.environment = restore.credential_environment(self.config)
        self.bundle = bundle_fixture()
        restore.write(self.release / 'accounts.json', self.bundle)
        self.private = accounts.create_private_credentials(self.bundle, self.root / 'private', self.environment)
        self.expected = [{key: row[key] for key in ('memberId', 'email', 'role', 'status')}
                         for row in restore.public_account_union(self.bundle)]
        self.db = Mock(timeout=5, guard=None)
        self.db.rows.return_value = copy.deepcopy(self.expected)
        self.db.literal = restore.Database.literal
        self.protocol = self.root / 'protocol.json'
        restore.write(self.protocol, {'locked': self.expected, 'changed': len(self.expected)})
        self.db.command.return_value = [sys.executable, '-u', '-c', SQL_PROTOCOL, str(self.protocol)]
        self.hashes = {row['memberId']: '$2a$10$' + chr(65 + index) * 53 for index, row in enumerate(self.expected)}

    def hash_output(self, *_, **__):
        return ''.join(str(member) + '\t' + value + '\n' for member, value in self.hashes.items()).encode()

    def apply(self):
        return restore.apply_credentials(self.db, self.root, self.private,
            {'AIRBOB_ETL_BENCHMARK_PASSWORD': 'obsolete-common-secret', 'AIRBOB_ETL_DB_PASSWORD': 'rds-test-only-secret'},
            self.secret_dir, self.bundle)


class AwsPrivateContractTest(PrivateAwsFixture):
    def test_union_deduplicates_admin_and_includes_the_other_representatives(self):
        private = restore.private_accounts(self.config['privateAccounts'], self.release,
                                           expected_environment=self.environment)
        self.assertEqual([51, 101, 209, 3131], [row['memberId'] for row in private['credentials']])
        self.assertEqual({'qualification', 'representative', 'administrator'}, {row['group'] for row in private['credentials']})

    def test_offline_validation_binds_private_environment_without_aws_or_db_calls(self):
        with patch.object(restore, 'validate_envelope', return_value={'fixture': True}), patch.object(restore.Aws, 'call') as aws:
            config = self.config | dict.fromkeys(('envelope', 'envelopeSha256', 'migrationDirectory',
                                                 'publicationReceipt'), '/fixture')
            self.assertEqual({'fixture': True}, restore.validate_inputs(config))
            config['rds'] = dict(config['rds'], serverUuid='22222222-2222-2222-2222-222222222222')
            with self.assertRaisesRegex(ValueError, 'environment'):
                restore.validate_inputs(config)
            aws.assert_not_called(); self.db.rows.assert_not_called()

    def test_old_shared_password_and_incomplete_union_fail_before_mutation(self):
        cases = [dict(self.private, schemaVersion=1, password='old-shared-test-only'),
                 dict(self.private, credentials=self.private['credentials'][:-1])]
        for value in cases:
            with self.subTest(schema=value['schemaVersion']):
                accounts.write_private(self.config['privateAccounts'], value)
                with self.assertRaises(ValueError):
                    restore.private_accounts(self.config['privateAccounts'], self.release, expected_environment=self.environment)
        self.db.rows.assert_not_called()

    def test_duplicate_password_wrong_group_and_individual_environment_are_rejected(self):
        for change in ({'password': self.private['credentials'][1]['password']},
                       {'group': 'load'}, {'environment': 'local:old-pilot'}):
            with self.subTest(field=next(iter(change))):
                value = copy.deepcopy(self.private); value['credentials'][0].update(change)
                accounts.write_private(self.config['privateAccounts'], value)
                with self.assertRaises(ValueError):
                    restore.private_accounts(self.config['privateAccounts'], self.release, expected_environment=self.environment)

    def test_shared_file_permissions_fail(self):
        Path(self.config['privateAccounts']).chmod(0o644)
        with self.assertRaisesRegex(ValueError, '0600'):
            restore.private_accounts(self.config['privateAccounts'], self.release, expected_environment=self.environment)


class AwsCredentialTransactionTest(PrivateAwsFixture):
    def test_each_password_is_framed_on_stdin_and_committed_only_after_exact_locked_identity_and_count(self):
        with patch.object(restore, 'command', side_effect=self.hash_output) as command:
            report = self.apply()
        state = restore.read(self.protocol)
        self.assertTrue(state['committed']); self.assertEqual(4, state['persistedWrites'])
        self.assertFalse(state['rolledBack'])
        sql = state['statements']
        self.assertLess(next(i for i, line in enumerate(sql) if 'FOR UPDATE' in line),
                        next(i for i, line in enumerate(sql) if line.startswith('UPDATE')))
        self.assertLess(sql.index('SELECT ROW_COUNT();'), sql.index('COMMIT;'))
        updates = [line for line in sql if line.startswith('UPDATE')]
        self.assertEqual(1, len(updates))
        self.assertTrue(updates[0].startswith('UPDATE member SET password=CASE id '))
        self.assertTrue(updates[0].endswith('WHERE id IN (51,101,209,3131);'))
        for member, value in self.hashes.items():
            self.assertIn('WHEN ' + str(member) + ' THEN ' + self.db.literal(value), updates[0])
        args, kwargs = command.call_args
        sent = {int(line.split('\t')[0]): base64.b64decode(line.split('\t')[1]).decode()
                for line in kwargs['input'].splitlines()}
        self.assertEqual({row['memberId']: row['password'] for row in self.private['credentials']}, sent)
        self.assertNotIn('AIRBOB_ETL_BENCHMARK_PASSWORD', kwargs['env'])
        self.assertNotIn('AIRBOB_ETL_DB_PASSWORD', kwargs['env'])
        public = json.dumps(report)
        for row in self.private['credentials']:
            self.assertNotIn(row['password'], repr(args)); self.assertNotIn(row['password'], repr(kwargs['env']))
            self.assertNotIn(row['password'], public)
        self.assertTrue(all(value not in public for value in self.hashes.values()))
        self.assertEqual(4, report['preparedMembers']); self.assertTrue(report['individualPasswords'])

    def test_missing_member_email_role_or_inactive_status_stops_before_hashing_or_transaction(self):
        for change in ({'memberId': 9999}, {'email': 'changed@example.test'}, {'role': 'ADMIN'}, {'status': 'DELETED'}):
            with self.subTest(field=next(iter(change))), patch.object(restore, 'command') as command:
                observed = copy.deepcopy(self.expected); observed[0].update(change)
                self.db.rows.return_value = observed
                with self.assertRaisesRegex(ValueError, 'identity, role, or ACTIVE'):
                    self.apply()
                command.assert_not_called(); self.db.command.assert_not_called()

    def test_locked_identity_drift_never_sends_update_or_commit(self):
        locked = copy.deepcopy(self.expected); locked[1]['role'] = 'ADMIN'
        restore.write(self.protocol, {'locked': locked, 'changed': 4})
        with patch.object(restore, 'command', side_effect=self.hash_output):
            with self.assertRaisesRegex(ValueError, 'Locked account'):
                self.apply()
        state = restore.read(self.protocol)
        self.assertFalse(state['committed']); self.assertEqual(0, state['persistedWrites'])
        self.assertFalse(any(line.startswith(('UPDATE', 'COMMIT')) for line in state['statements']))

    def test_partial_update_count_disconnects_and_rolls_back_without_commit(self):
        restore.write(self.protocol, {'locked': self.expected, 'changed': 3})
        with patch.object(restore, 'command', side_effect=self.hash_output):
            with self.assertRaisesRegex(ValueError, 'transaction was not committed'):
                self.apply()
        state = restore.read(self.protocol)
        self.assertTrue(any(line.startswith('UPDATE') for line in state['statements']))
        self.assertNotIn('COMMIT;', state['statements'])
        self.assertTrue(state['rolledBack']); self.assertEqual(0, state['persistedWrites'])

    def test_lease_loss_after_update_prevents_commit_and_rolls_back(self):
        def guard(**_):
            state = restore.read(self.protocol)
            if 'SELECT ROW_COUNT();' in state.get('statements', []):
                raise ValueError('fixture lease expired')
        self.db.guard = guard
        with patch.object(restore, 'command', side_effect=self.hash_output):
            with self.assertRaisesRegex(ValueError, 'lease expired'):
                self.apply()
        state = restore.read(self.protocol)
        self.assertTrue(state['rolledBack']); self.assertNotIn('COMMIT;', state['statements'])

    def test_hashes_must_cover_every_member_with_distinct_valid_bcrypt(self):
        good = self.hash_output()
        cases = [b'invalid-output', b'\n'.join(good.splitlines()[:-1]), good + good.splitlines()[0] + b'\n',
                 b'\n'.join(str(member).encode() + b'\t' + next(iter(self.hashes.values())).encode() for member in self.hashes)]
        for index, raw in enumerate(cases):
            with self.subTest(index=index), patch.object(restore, 'command', return_value=raw):
                (self.secret_dir / 'CredentialHashes.java').unlink(missing_ok=True)
                with self.assertRaisesRegex(ValueError, 'BCrypt'):
                    self.apply()
                self.db.command.assert_not_called()


class AwsBcryptHelperTest(unittest.TestCase):
    def test_java_hash_helper_uses_each_utf8_password_and_cross_passwords_do_not_match(self):
        jar = ROOT / 'build/growth-b-contract-test-runtime/jbcrypt-0.4.jar'
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
        self.assertTrue(jar.is_file(), 'Run ./gradlew prepareGrowthBContractTestRuntime before the Global B contracts')
        self.assertTrue(java and Path(java).is_file(), 'A JDK 21 java executable is required for the BCrypt contract')
        passwords = ['first-test-password-only', 'second-test-password-only', '테스트-암호-password-only',
                     'a' * 72, 'password-with-tab\tand-newline\nonly']
        payload = ''.join(str(i) + '\t' + base64.b64encode(password.encode()).decode() + '\n'
                          for i, password in enumerate(passwords, 1))
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'CredentialHashes.java'
            restore.private_text(source, restore.CREDENTIAL_HASH_SOURCE)
            result = restore.command([java, '--class-path', str(jar), str(source)], input=payload)
            rows = [line.split('\t') for line in result.decode().splitlines()]
            self.assertEqual([str(i) for i in range(1, 6)], [row[0] for row in rows])
            self.assertEqual(5, len({row[1] for row in rows}))
            check = Path(directory) / 'CheckHashes.java'
            restore.private_text(check, '''import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
class CheckHashes {
  public static void main(String[] args) throws Exception {
    var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String first = null;
    for (String line; (line = reader.readLine()) != null;) {
      String[] fields = line.split("\\t", -1);
      String password = new String(Base64.getDecoder().decode(fields[0]), StandardCharsets.UTF_8);
      if (!org.mindrot.jbcrypt.BCrypt.checkpw(password, fields[1])) throw new AssertionError();
      if (first != null && org.mindrot.jbcrypt.BCrypt.checkpw(first, fields[1])) throw new AssertionError();
      if (first == null) first = password;
    }
    System.out.print("ALL_INDIVIDUAL_AND_CROSS_CHECKS_PASSED");
  }
}
''')
            check_input = ''.join(base64.b64encode(password.encode()).decode() + '\t' + row[1] + '\n'
                                  for password, row in zip(passwords, rows))
            self.assertEqual(b'ALL_INDIVIDUAL_AND_CROSS_CHECKS_PASSED',
                             restore.command([java, '--class-path', str(jar), str(check)], input=check_input))


class FakeServiceApp:
    def __init__(self, bundle, private_input, *, wrong_identity=False):
        self.allowed = {row['email']: row for row in accounts.read_private(private_input)['credentials']}
        self.identities = {row['email']: row for row in accounts.public_account_union(bundle)}
        self.env = {}; self.sessions = {}; self.normal_logins = 0; self.cross_rejections = 0
        self.startup_seconds = 0.1; self.process = None; self.stopped = False; self.wrong_identity = wrong_identity
    def __enter__(self): return self
    def __exit__(self, *_): self.stopped = True
    def client(self): return object()
    def login(self, client, email):
        App.login(self, client, email)
        self.normal_logins += 1
    def request(self, client, path, **kwargs):
        if path == '/actuator/health/readiness':
            return {'response': {'status': 'UP'}}
        if path.endswith('/login'):
            assert kwargs['capture'] is False
            body = kwargs['body']
            valid = body['password'] == self.allowed[body['email']]['password']
            if valid:
                assert kwargs.get('expected', (200,)) == (200,)
                self.sessions[client] = self.identities[body['email']]
            else:
                assert kwargs.get('expected') == (400, 401)
                self.cross_rejections += 1
        if path.endswith('/logout'):
            self.sessions.pop(client)
        if path.endswith('/me'):
            if kwargs.get('expected') == (401,):
                assert client not in self.sessions
            else:
                row = self.sessions[client]
                return {'response': {'data': {'id': -1 if self.wrong_identity else row['memberId'],
                                             'email': row['email'], 'nickname': row['nickname']}}}
        return {'response': None}


class AwsLoginLifecycleTest(PrivateAwsFixture):
    def prepare(self, *, wrong_identity=False, representative_drift=False):
        self.app = FakeServiceApp(self.bundle, self.config['privateAccounts'], wrong_identity=wrong_identity)
        runtime_module = Mock()
        def app_factory(*args, **kwargs):
            self.app.env = args[3]
            return self.app
        runtime_module.App.side_effect = app_factory
        inventory = Mock()
        inventory.verify_current_inventory.return_value = {'everyHorizonContiguous': True}
        modules = {'growth_accounts': accounts, 'growth_inventory': inventory, 'growth_runtime': runtime_module}
        def scalar(sql):
            if 'JSON_OBJECT' in sql:
                member = int(re.search(r'WHERE id=(\d+)', sql).group(1))
                row = next(row for row in self.expected if row['memberId'] == member).copy()
                if representative_drift:
                    row['status'] = 'DELETED'
                return json.dumps(row)
            return 1
        self.db.scalar.side_effect = scalar
        before = {'tables': {'member': {'ddlSha256': 'a' * 64, 'domainRowsSha256': 'b' * 64, 'rows': 4},
                             'accommodation_inventory_day': {'ddlSha256': 'c' * 64}}}
        restore.write(self.release / 'before-fingerprint.json', before)
        def command(argv, **_):
            self.commands.append(argv)
            assert argv[:2] in (['docker', 'run'], ['docker', 'port'], ['docker', 'rm'])
            return b'127.0.0.1:12345' if argv[1] == 'port' else b''
        self.commands = []
        with patch.object(restore.importlib, 'import_module', side_effect=lambda name: modules[name]), \
                patch.object(restore, 'owner_fingerprint', return_value='d' * 64), \
                patch.object(restore, 'fingerprint', return_value=before), \
                patch.object(restore, 'apply_credentials', return_value={'individualPasswords': True}) as apply, \
                patch.object(restore, 'command', side_effect=command):
            self.apply_mock = apply
            return restore.prepare_service(self.config, {}, self.root, {'AIRBOB_ETL_DB_URL': 'jdbc:mysql://fixture/airbobdb',
                'AIRBOB_ETL_DB_USER': 'fixture', 'AIRBOB_ETL_BENCHMARK_PASSWORD': 'old-shared-test-only'},
                self.db, self.output, self.secret_dir)

    def test_real_qualifier_uses_every_individual_login_and_cross_rejects_then_marks_stopped_service_unavailable(self):
        report = self.prepare()
        self.assertEqual(4, self.app.normal_logins); self.assertEqual(4, self.app.cross_rejections)
        self.assertEqual(str(Path(self.config['privateAccounts']).resolve()), self.app.env['AIRBOB_GROWTH_CREDENTIALS_FILE'])
        self.assertNotIn('AIRBOB_ETL_BENCHMARK_PASSWORD', self.app.env)
        self.assertTrue(self.app.stopped); self.assertFalse(report['serviceCurrentlyAvailable'])
        self.assertFalse(report['applicationLeftRunning']); self.assertFalse(report['privateCredentials']['usable'])
        self.assertEqual(self.environment, report['accountLogins']['environment'])
        mapping = report['accountLogins']['representatives']
        self.assertTrue(mapping['identityRoleAndOwnershipVerified'])
        self.assertEqual({101, 209, 3131}, {row['memberId'] for row in mapping['accounts']})
        self.assertTrue(report['accountLogins']['crossCredentialRejected'])
        for filename in ('accounts.private.json', 'representative-accounts.private.json',
                         'demo-accounts.private.json', 'administrator.private.json'):
            value = accounts.read_private(self.root / 'private' / filename)
            self.assertTrue(all(row['loginState'] == 'NOT_AVAILABLE' and not row['usable'] for row in value['credentials']))
        public = json.dumps(report) + (self.output / 'account-login-qualification.json').read_text()
        self.assertTrue(all(row['password'] not in public for row in self.private['credentials']))
        self.assertEqual(['docker', 'rm', '-f'], self.commands[-1][:3])

    def test_failed_login_leaves_handoffs_unavailable_and_cleans_only_owned_redis(self):
        with self.assertRaisesRegex(AssertionError, 'different member identity'):
            self.prepare(wrong_identity=True)
        self.assertTrue(self.app.stopped)
        self.assertTrue(all(not row['usable'] for row in accounts.read_private(self.config['privateAccounts'])['credentials']))
        created = next(command for command in self.commands if command[1] == 'run')
        self.assertEqual(created[created.index('--name') + 1], self.commands[-1][-1])
        self.assertEqual(['docker', 'rm', '-f'], self.commands[-1][:3])

    def test_representative_ownership_identity_drift_stops_before_credentials_or_app(self):
        with self.assertRaisesRegex(AssertionError, 'sealed baseline'):
            self.prepare(representative_drift=True)
        self.apply_mock.assert_not_called(); self.assertEqual([], self.commands)
        self.assertTrue(all(not row['usable'] for row in accounts.read_private(self.config['privateAccounts'])['credentials']))


if __name__ == '__main__':
    unittest.main()
