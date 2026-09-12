"""Offline source identity, private v2, and frozen-runtime adapter boundaries.

No Docker, database, application, cloud, or Java process is started by these tests.
"""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

from test_growth_b_contract import account_fixture, private_account_fixture

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('account_adapter_restore', ROOT / 'scripts/restore-growth-b-local.py')
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)


class SourceRepresentativeScopeTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.path = self.root / 'source-restore.json'
        self.source = {'datasetId': 'global-growth-b-' + '1' * 16,
            'dumpSha256': '2' * 64, 'mysqlServerUuid': '33333333-3333-3333-3333-333333333333',
            'restoreReceipt': str(self.path)}
        self.login = account_fixture()[3]
        # Source IDs deliberately differ from the incoming release's fixture IDs.
        for row in self.login['representatives']['accounts']:
            row['memberId'] += 5000
        self.receipt = {key: self.source[key] for key in ('datasetId', 'dumpSha256', 'mysqlServerUuid')}
        self.receipt.update(allRowsAndDdlEqual=True, preparation={'accountLogins': self.login})
        self.db = MagicMock()
        self.db.scalar.return_value = 3
        self.db.rows.return_value = [{key: row[key] for key in ('memberId', 'email', 'role', 'status')}
            for row in self.login['representatives']['accounts']]
        self.seal()

    def seal(self):
        self.path.write_text(json.dumps(self.receipt))
        self.source['restoreReceiptSha256'] = restore.sha(self.path)

    def observe(self):
        return restore.source_account_scope(self.source, self.db)

    def test_legacy_v4_example_accounts_need_no_canonical_exception(self):
        self.db.scalar.return_value = 0
        legacy = {'datasetId': 'korea-growth-v4-' + '4' * 16}
        self.assertEqual({'exampleTestAccountsOnly': True, 'verifiedRepresentativeAccounts': []},
                         restore.source_account_scope(legacy, self.db))
        self.db.rows.assert_not_called()
        self.assertIn("email IS NULL OR email NOT LIKE '%@example.test'", self.db.scalar.call_args.args[0])

    def test_source_own_mapping_passes_without_incoming_release_ids(self):
        result = self.observe()
        self.assertFalse(result['exampleTestAccountsOnly'])
        self.assertEqual([5002, 5003, 5004], [row['memberId'] for row in result['verifiedRepresentativeAccounts']])
        self.assertEqual(self.source['restoreReceiptSha256'], result['sourceRestoreReceiptSha256'])
        self.db.execute.assert_not_called()

    def test_incoming_ids_cannot_authorize_another_source_member(self):
        incoming = account_fixture()[0]['representativeAccounts']
        self.db.rows.return_value = [{key: row[key] for key in ('memberId', 'email', 'role', 'status')} for row in incoming]
        with self.assertRaisesRegex(ValueError, 'Actual source canonical members differ'):
            self.observe()

    def test_each_receipt_anchor_is_required(self):
        for key, value in [('datasetId', 'global-growth-b-' + '9' * 16), ('dumpSha256', '9' * 64),
                           ('mysqlServerUuid', '99999999-9999-9999-9999-999999999999'), ('allRowsAndDdlEqual', False)]:
            with self.subTest(anchor=key):
                original = copy.deepcopy(self.receipt)
                self.receipt[key] = value; self.seal()
                with self.assertRaisesRegex(ValueError, 'not bound'):
                    self.observe()
                self.receipt = original
        self.seal()
        self.path.write_text(self.path.read_text() + ' ')
        with self.assertRaisesRegex(ValueError, 'receipt bytes differ'):
            self.observe()

    def test_missing_or_failed_normal_login_proof_cannot_whitelist_aliases(self):
        original = copy.deepcopy(self.receipt)
        mutations = [lambda value: value['preparation'].pop('accountLogins'),
            lambda value: value['preparation']['accountLogins'].pop('representatives'),
            lambda value: value['preparation']['accountLogins'].update(passed=False),
            lambda value: value['preparation']['accountLogins'].update(identityAndLogoutVerified=False),
            lambda value: value['preparation']['accountLogins'].update(crossCredentialRejected=False),
            lambda value: value['preparation']['accountLogins']['representatives'].update(identityRoleAndOwnershipVerified=False),
            lambda value: value['preparation']['accountLogins']['representatives'].update(emailMutationAfterRestore=True)]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                self.receipt = copy.deepcopy(original); mutate(self.receipt); self.seal()
                with self.assertRaisesRegex(ValueError, 'lack successful normal-login'):
                    self.observe()

    def test_wrong_actual_email_id_role_or_status_is_rejected(self):
        rows = copy.deepcopy(self.db.rows.return_value)
        for key, value in [('memberId', 7000), ('email', 'fourth@airbob.test'), ('email', None),
                           ('email', 'demo@unrelated.test'), ('role', 'ADMIN'), ('status', 'DELETED')]:
            with self.subTest(field=key, value=value):
                self.db.rows.return_value = copy.deepcopy(rows)
                self.db.rows.return_value[0][key] = value
                with self.assertRaisesRegex(ValueError, 'Actual source canonical members differ'):
                    self.observe()

    def test_fourth_unknown_account_and_partial_alias_sets_are_rejected(self):
        for count in (1, 2, 4, 100):
            with self.subTest(count=count):
                self.db.scalar.return_value = count
                with self.assertRaises(ValueError):
                    self.observe()
        self.db.rows.assert_not_called()

    def test_resealed_receipt_cannot_invent_alias_role_or_duplicate_identity(self):
        original = copy.deepcopy(self.receipt)
        for key, value in [('key', 'another'), ('key', 'host'), ('memberId', 5003), ('memberId', True),
                           ('email', 'fourth@airbob.test'), ('role', 'ADMIN'), ('status', 'DELETED')]:
            with self.subTest(field=key, value=value):
                self.receipt = copy.deepcopy(original)
                self.receipt['preparation']['accountLogins']['representatives']['accounts'][0][key] = value
                self.seal()
                with self.assertRaisesRegex(ValueError, 'unexpected alias, role, or member ID'):
                    self.observe()

    def test_source_observation_emits_only_identity_fields(self):
        self.login['representatives']['accounts'][0]['password'] = 'never-copy-receipt-private-value'
        self.seal()
        self.assertNotIn('never-copy-receipt-private-value', json.dumps(self.observe()))


class AccountInputFixture(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.release = self.root / 'release'; self.release.mkdir()
        self.bundle, self.representatives, _, self.login = account_fixture()
        self.baseline = {'tables': {name: {'rows': 1, 'ddlSha256': 'a' * 64,
            'rowsSha256': 'b' * 64, 'domainRowsSha256': 'c' * 64}
            for name in ('member', 'accommodation_inventory_day', 'reservation')}}
        for name, value in [('accounts.json', self.bundle), ('representative-accounts.json', self.representatives),
                            ('before-fingerprint.json', self.baseline), ('profile.json', {}),
                            ('consumer-manifest.json', {}), ('SHA256SUMS.json', {})]:
            (self.release / name).write_text(json.dumps(value))
        self.jar = self.root / 'app.jar'; self.jar.write_bytes(b'synthetic-app-never-executed')
        self.path = self.root / 'private/accounts.private.json'; self.path.parent.mkdir(mode=0o700)
        self.config = {'mode': 'local', 'operation': restore.FRESH_OPERATION,
            'datasetId': 'global-growth-b-' + '1' * 16, 'release': str(self.release), 'backendRoot': str(ROOT),
            'appJar': str(self.jar), 'privateAccounts': str(self.path), 'allowSmall': True,
            'consumerManifestSha256': restore.sha(self.release / 'consumer-manifest.json'),
            'checksumsSha256': restore.sha(self.release / 'SHA256SUMS.json'),
            'target': {'container': 'account-adapter-unit', 'redisImage': 'sha256:' + 'd' * 64}}
        self.environment = restore.account_environment(self.config)
        self.private = private_account_fixture(self.bundle, self.environment)
        self.write_private()

    def write_private(self):
        self.path.write_text(json.dumps(self.private)); self.path.chmod(0o600)


class AccountInputTest(AccountInputFixture):
    def validate(self):
        # Release authenticity is covered separately; exercise the real private validator here.
        with patch.object(restore, 'validate', return_value=({}, {}, self.baseline)) as public:
            result = restore.validate_inputs(self.config)
            public.assert_called_once_with(self.release, self.config['datasetId'], ROOT / 'src/main/resources/db/migration',
                allow_small=True, expected_app_sha=restore.sha(self.jar))
            return result

    def test_private_v2_exact_public_union_is_bound_to_actual_target(self):
        self.assertEqual(self.environment, 'local:account-adapter-unit:global-growth-b-' + '1' * 16)
        self.assertEqual(({}, {}, self.baseline), self.validate())
        self.config['target']['container'] = 'another-target'
        with self.assertRaisesRegex(ValueError, 'environment'):
            self.validate()

    def test_oci_target_does_not_accept_local_environment_credentials(self):
        self.config['mode'] = 'oci'
        with self.assertRaisesRegex(ValueError, 'environment'):
            self.validate()
        self.private['environment'] = restore.account_environment(self.config)
        for row in self.private['credentials']:
            row['environment'] = self.private['environment']
        self.write_private(); self.validate()

    def test_legacy_common_password_input_is_rejected(self):
        self.private = {'schemaVersion': 1, 'datasetProfile': 'global-growth-b',
                        'accounts': self.bundle['accounts'], 'password': 'old-common-fixture-value'}
        self.write_private()
        with self.assertRaisesRegex(ValueError, 'Private v2'):
            self.validate()

    def test_wrong_release_bytes_stop_before_any_private_use(self):
        (self.release / 'consumer-manifest.json').write_text('{"changed": true}')
        with patch.object(restore.contract, 'validate_private_accounts') as private:
            with self.assertRaisesRegex(ValueError, 'trust anchors'):
                restore.validate_inputs(self.config)
            private.assert_not_called()


class PreparationAdapterTest(AccountInputFixture):
    def setUp(self):
        super().setUp()
        self.output = self.root / 'output'; self.output.mkdir(); (self.output / '.private').mkdir()
        self.runtime = self.root / 'frozen-runtime'
        self.events = []
        self.accounts = SimpleNamespace(
            update_login_state=MagicMock(),
            verify_representative_accounts=MagicMock(side_effect=lambda *args: self.record('representatives', self.login['representatives'])),
            apply_saved_credentials=MagicMock(side_effect=lambda *args, **kwargs: self.record('credentials', {})),
            qualify_account_logins=MagicMock(side_effect=lambda *args, **kwargs: self.record('normal-logins', self.login)))
        self.inventory = SimpleNamespace(verify_closed_ownership=MagicMock(),
            owned_fingerprint=MagicMock(return_value={'count': 3, 'sha256': 'd' * 64}),
            verify_current_inventory=MagicMock(return_value={'passed': True}))
        self.app = MagicMock()
        self.app.env = {}; self.app.startup_seconds = 1.5
        self.app.__enter__.return_value = self.app
        self.app.request.return_value = {'response': {'status': 'UP'}}
        self.app_class = MagicMock(return_value=self.app)
        modules = {'growth_accounts': self.accounts, 'growth_inventory': self.inventory,
                   'growth_runtime': SimpleNamespace(App=self.app_class),
                   'growth_settings': SimpleNamespace(utc_jdbc_url=lambda value: value)}
        self.patch(restore.importlib, 'import_module', side_effect=lambda name: modules[name])
        self.patch(restore, 'connection_environment', return_value={
            'AIRBOB_ETL_DB_URL': 'jdbc:mysql://127.0.0.1:13306/airbobdb?connectionTimeZone=UTC'})
        self.docker = self.patch(restore, 'docker', side_effect=lambda *args: b'127.0.0.1:16379' if args[0] == 'port' else b'')
        self.after = copy.deepcopy(self.baseline)
        self.after['tables']['member']['rowsSha256'] = 'e' * 64  # Individual BCrypt hashes may differ.
        self.after['tables']['accommodation_inventory_day']['rows'] = 20
        self.fingerprint = self.patch(restore, 'fingerprint', return_value=self.after)
        self.db = MagicMock()
        self.info = {'Id': 'f' * 64}

    def patch(self, obj, name, **kwargs):
        item = patch.object(obj, name, **kwargs); self.addCleanup(item.stop)
        return item.start()

    def record(self, name, value):
        self.events.append(name)
        return value

    def prepare(self):
        return restore.prepare_service(self.config, self.output, self.runtime, self.info, self.db)

    def test_file_credentials_and_source_mapping_flow_through_frozen_normal_login_adapter(self):
        result = self.prepare()
        self.assertEqual(['representatives', 'credentials', 'normal-logins'], self.events)
        self.assertEqual(self.login['representatives'], result['accountLogins']['representatives'])
        self.assertEqual(self.login['representatives'], result['representativeMapping'])
        self.assertEqual(self.environment, result['accountEnvironment'])
        args, kwargs = self.accounts.apply_saved_credentials.call_args
        self.assertEqual(self.release / 'before-fingerprint.json', args[2])
        self.assertEqual(str(self.path), args[3]); self.assertEqual('airbobdb', kwargs['schema'])
        self.assertEqual(str(self.path.resolve()), args[5]['AIRBOB_GROWTH_CREDENTIALS_FILE'])
        self.assertNotIn('AIRBOB_GROWTH_LOGIN_PASSWORD', args[5])
        self.accounts.qualify_account_logins.assert_called_once_with(self.app, self.db, self.bundle, self.output,
            private_input=str(self.path), environment=self.environment)
        self.assertEqual('administrator.private.json', result['privateAccountHandoffs']['administrator'])
        self.assertTrue(self.accounts.update_login_state.call_args_list[0].kwargs['usable'] is False)
        self.assertTrue(self.docker.call_args.args[:3] == ('rm', '-f', '-v'))
        self.assertTrue(all(row['password'] not in json.dumps(result) for row in self.private['credentials']))

    def test_restored_representative_drift_blocks_password_application_and_app_start(self):
        self.accounts.verify_representative_accounts.side_effect = ValueError('Restored member identity differs')
        with self.assertRaisesRegex(ValueError, 'Restored member identity'):
            self.prepare()
        self.accounts.apply_saved_credentials.assert_not_called()
        self.app_class.assert_not_called(); self.docker.assert_not_called()
        self.assertFalse(self.accounts.update_login_state.call_args.kwargs['usable'])

    def test_baseline_mismatch_blocks_app_start(self):
        self.accounts.apply_saved_credentials.side_effect = ValueError('Rows or DDL differ from sealed baseline')
        with self.assertRaisesRegex(ValueError, 'sealed baseline'):
            self.prepare()
        self.app_class.assert_not_called(); self.docker.assert_not_called()
        self.assertFalse(self.accounts.update_login_state.call_args.kwargs['usable'])

    def test_login_failure_marks_private_accounts_unavailable_and_cleans_sessions(self):
        self.accounts.qualify_account_logins.side_effect = ValueError('Normal login failed')
        with self.assertRaisesRegex(ValueError, 'Normal login failed'):
            self.prepare()
        self.assertFalse(self.accounts.update_login_state.call_args.kwargs['usable'])
        self.assertTrue(self.docker.call_args.args[:3] == ('rm', '-f', '-v'))
        self.fingerprint.assert_not_called()

    def test_email_role_and_other_member_domain_mutations_are_not_fingerprint_exceptions(self):
        self.after['tables']['member']['domainRowsSha256'] = 'f' * 64
        with self.assertRaisesRegex(ValueError, 'member domain values'):
            self.prepare()
        self.assertFalse(self.accounts.update_login_state.call_args.kwargs['usable'])

    def test_non_live_domain_tables_and_ddl_must_remain_exact(self):
        for table, field in [('reservation', 'rowsSha256'), ('member', 'ddlSha256'),
                             ('accommodation_inventory_day', 'ddlSha256')]:
            with self.subTest(table=table, field=field):
                original = self.after['tables'][table][field]
                self.after['tables'][table][field] = '0' * 64
                with self.assertRaisesRegex(ValueError, 'Preparation changed'):
                    self.prepare()
                self.assertFalse(self.accounts.update_login_state.call_args.kwargs['usable'])
                self.after['tables'][table][field] = original


if __name__ == '__main__':
    unittest.main()
