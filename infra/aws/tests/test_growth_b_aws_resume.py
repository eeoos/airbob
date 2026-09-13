"""Preparation-only RDS resumes; no AWS, DB, Redis or Docker connections."""
from contextlib import nullcontext
import copy
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_aws_restore as restore
import growth_b_contract as contract


class ResumeFixture(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(); self.addCleanup(directory.cleanup)
        self.root = Path(directory.name); self.release = self.root / 'release'; self.release.mkdir()
        self.output = self.root / 'output'; self.output.mkdir()
        self.config = {'operation': 'resume-preparation', 'envelopeSha256': 'a' * 64,
            'release': str(self.release), 'replacementPolicy': 'empty-only', 'lease': {},
            'redisImage': 'unit@sha256:' + 'b' * 64, 'operationTimeoutSeconds': 300,
            'rds': {'identifier': 'airbob-lab-unit', 'resourceId': 'db-UNIT',
                'endpoint': 'unit.ap-northeast-2.rds.amazonaws.com', 'serverUuid': '11111111-1111-1111-1111-111111111111'}}
        self.envelope = {'datasetId': 'global-growth-b-' + 'a' * 16, 'appJarSha256': 'c' * 64,
            'finalScaleSelected': True, 'awsExecutionAllowed': True,
            'objects': {'migration-files.json': {'sha256': 'd' * 64}},
            'storage': {'requiredAdditionalDataHostFreeBytes': 1, 'requiredRdsFreeAfterRemovalBytes': 100}}
        self.baseline = {'mysqlVersion': '8.4.11', 'tables': {name: {'rows': 2, 'rowsSha256': 'a' * 64,
            'domainRowsSha256': 'b' * 64, 'ddlSha256': 'c' * 64} for name in contract.TABLES}}
        self.baseline['tables']['flyway_schema_history']['rows'] = 28
        self.baseline['tables']['outbox']['rows'] = 0
        restore.write(self.release / 'before-fingerprint.json', self.baseline)
        self.current = copy.deepcopy(self.baseline)
        self.current['tables']['member']['rowsSha256'] = 'd' * 64
        self.current['tables']['accommodation_inventory_day']['rows'] = 200
        self.db = MagicMock(timeout=300)
        self.binding = {'targetIdentity': restore.target_identity(self.config), 'credentialBindingSha256': 'e' * 64,
                        'searchCoordinatesSha256': restore.canonical_sha(None)}
        self.mock('execution_bindings', side_effect=lambda config: self.binding)
        self.owner = self.mock('owner_fingerprint', return_value='f' * 64)
        self.mock('tool_identity', return_value={'growth_b_aws_restore.py': '1' * 64, 'growth_b_inventory.py': '2' * 64})
        self.mock_import = patch.object(restore.importlib, 'import_module', return_value=SimpleNamespace(verify_closed_ownership=MagicMock()))
        self.mock_import.start(); self.addCleanup(self.mock_import.stop)
        self.receipt = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-restore-receipt', 'state': 'FAILED',
            'account': restore.ACCOUNT, 'region': restore.REGION, 'toolIdentity': restore.tool_identity(),
            'datasetId': self.envelope['datasetId'], 'envelopeSha256': self.config['envelopeSha256'],
            'appJarSha256': self.envelope['appJarSha256'], 'migrationFilesSha256': 'd' * 64,
            'mysqlVersion': '8.4.11', 'flywayVersion': 28, 'finalScaleSelected': True, 'executionScope': 'final-b-rds',
            'allRowsAndDdlEqual': True, 'restoredFingerprintSha256': '3' * 64, 'previousBusinessSchemaAbsent': True,
            'maximumSimultaneousBusinessDatabases': 1, 'awsWritesExecuted': True,
            'sealedFingerprint': self.baseline, 'baselineOwnerSha256': 'f' * 64, **copy.deepcopy(self.binding)}
        self.seal()

    def mock(self, name, **kwargs):
        item = patch.object(restore, name, **kwargs); self.addCleanup(item.stop)
        return item.start()

    def seal(self):
        path = self.root / 'prior-restore.json'; restore.write(path, self.receipt)
        self.config['resumeReceipt'] = {'path': str(path), 'sha256': restore.sha(path)}

    def validate(self):
        return restore.validate_preparation_resume(self.config, self.envelope, self.current, self.db, self.root)


class ResumeSourceTest(ResumeFixture):
    def test_password_and_free_changes_reuse_verified_baseline_and_owned_nights(self):
        proof = self.validate()
        self.assertEqual(proof['baselineOwnerSha256'], 'f' * 64)
        self.assertEqual(proof['receiptSha256'], self.config['resumeReceipt']['sha256'])
        self.db.execute.assert_not_called()

    def test_legacy_version_wrong_target_or_other_tools_cannot_be_relabeled(self):
        for key, value in [('kind', 'pipeline-rehearsal'), ('mysqlVersion', '8.0.44'), ('flywayVersion', 27),
                           ('targetIdentity', {}), ('toolIdentity', {}), ('credentialBindingSha256', '4' * 64),
                           ('datasetId', 'global-growth-b-' + 'b' * 16), ('envelopeSha256', '5' * 64)]:
            with self.subTest(key=key):
                old = self.receipt[key]; self.receipt[key] = value; self.seal()
                with self.assertRaisesRegex(ValueError, 'exact target'):
                    self.validate()
                self.receipt[key] = old
        self.db.execute.assert_not_called()

    def test_receipt_byte_tampering_fails_before_database_access(self):
        Path(self.config['resumeReceipt']['path']).write_text('{}')
        with self.assertRaisesRegex(ValueError, 'receipt changed'):
            self.validate()
        self.owner.assert_not_called()

    def test_unverified_import_cannot_take_preparation_only_path(self):
        self.receipt['allRowsAndDdlEqual'] = False; self.seal()
        with self.assertRaisesRegex(ValueError, 'successful same-tool baseline'):
            self.validate()
        self.owner.assert_not_called()

    def test_complete_original_fingerprint_is_required(self):
        self.receipt['sealedFingerprint'] = {'tables': {}}; self.seal()
        with self.assertRaisesRegex(ValueError, 'complete original'):
            self.validate()

    def test_non_live_member_domain_and_ddl_changes_are_rejected(self):
        cases = [('reservation', 'rows', 3), ('member', 'domainRowsSha256', '5' * 64),
                 ('accommodation_inventory_day', 'ddlSha256', '5' * 64)]
        for table, key, value in cases:
            with self.subTest(table=table, key=key):
                old = self.current['tables'][table][key]; self.current['tables'][table][key] = value
                with self.assertRaises(ValueError):
                    self.validate()
                self.current['tables'][table][key] = old

    def test_owner_change_is_never_an_allowed_free_day_change(self):
        self.owner.return_value = '5' * 64
        with self.assertRaisesRegex(ValueError, 'HOLD/OCCUPIED'):
            self.validate()

    def test_interrupted_owner_checkpoint_requires_unchanged_baseline(self):
        self.receipt.pop('baselineOwnerSha256'); self.seal()
        with self.assertRaisesRegex(ValueError, 'unchanged complete baseline'):
            self.validate()
        self.current = copy.deepcopy(self.baseline)
        self.assertEqual(self.validate()['baselineOwnerSha256'], 'f' * 64)

    def test_required_search_qualification_cannot_be_skipped(self):
        self.config['search'] = {'unit': 'required'}
        with self.assertRaisesRegex(ValueError, 'search qualification'):
            self.validate()


class ResumeExecutionTest(ResumeFixture):
    def setUp(self):
        super().setUp()
        self.before = {'tableObjects': 32, 'allocatedTablespaceBytes': 100, 'binaryLogging': False}
        self.reviewed = {'state': 'READ_ONLY_PREFLIGHT', 'datasetId': self.envelope['datasetId'],
            'envelopeSha256': self.config['envelopeSha256'], 'rds': {}, 'operation': 'resume-preparation',
            'beforeDatabase': self.before | {'fullFingerprintSha256': restore.canonical_sha(self.current)},
            'resumeEvidence': self.validate()}
        self.mock('verify_remote_versions')
        self.mock('database_state', return_value=self.before)
        self.mock('command', return_value=b'')
        self.mock('free_storage', return_value={'bytes': 200, 'recordedAt': 'now'})
        self.stream = self.mock('stream_restore')
        self.preparation = self.mock('prepare_service', return_value={'passed': True, 'preparedFingerprintSha256': '6' * 64})
        self.fence = self.mock('exclusive_database', side_effect=lambda db: nullcontext())
        self.mock('validate_rehearsal', return_value={'sameToolApplicationAndV28Verified': True})
        self.mock('fingerprint', side_effect=lambda *args, **kwargs: self.current)
        self.events = []

    def execute(self):
        restore.execute(self.config, self.envelope, self.reviewed, self.output, MagicMock(), self.root, self.db, {}, {},
                        lambda state, **values: self.events.append({'state': state, **values}))

    def test_resume_holds_lease_and_database_fence_but_never_drops_or_imports(self):
        self.execute()
        self.db.execute.assert_not_called(); self.stream.assert_not_called()
        self.fence.assert_called_once_with(self.db)
        self.assertTrue(self.db.guard.called)
        self.assertEqual(self.preparation.call_args.kwargs['expected_owner_sha'], 'f' * 64)
        self.assertEqual([event['state'] for event in self.events], [
            'VERIFIED_BASELINE_REUSED_FOR_PREPARATION', 'PREPARATION_STARTED', 'DATABASE_INVENTORY_LOGIN_VERIFIED'])
        self.assertTrue(self.events[0]['sqlImportSkipped'])
        self.assertFalse(self.events[0]['databaseRecreatedInThisRun'])
        self.assertEqual(self.events[-1]['preparedFingerprintSha256'], '6' * 64)

    def test_source_changed_since_preflight_prevents_all_preparation(self):
        self.current['tables']['member']['rowsSha256'] = '7' * 64
        with self.assertRaisesRegex(ValueError, 'changed since review'):
            self.execute()
        self.preparation.assert_not_called(); self.stream.assert_not_called(); self.db.execute.assert_not_called()

    def test_lease_failure_prevents_any_fingerprint_or_preparation(self):
        self.db.guard.side_effect = ValueError('fixture lease expired')
        with self.assertRaisesRegex(ValueError, 'lease expired'):
            self.execute()
        restore.fingerprint.assert_not_called(); self.preparation.assert_not_called(); self.db.execute.assert_not_called()

    def test_preparation_failure_retains_the_original_database(self):
        self.preparation.side_effect = RuntimeError('fixture midnight bound exhausted')
        with self.assertRaisesRegex(RuntimeError, 'midnight bound'):
            self.execute()
        self.db.execute.assert_not_called(); self.stream.assert_not_called()
        self.assertNotIn('DATABASE_INVENTORY_LOGIN_VERIFIED', [event['state'] for event in self.events])


if __name__ == '__main__':
    unittest.main()
