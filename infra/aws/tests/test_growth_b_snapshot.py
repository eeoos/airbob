"""Offline B snapshot provenance, mutation ordering and sequential restoration."""
import contextlib
import copy
import datetime as dt
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_snapshot as snapshot


class SnapshotFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.release = self.root / 'release'; self.release.mkdir()
        self.when = '2026-09-12T15:00:00+00:00'
        self.target = {'identifier': 'airbob-lab-b-snapshot', 'resourceId': 'db-' + 'A' * 24,
                       'endpoint': 'unit.ap-northeast-2.rds.amazonaws.com', 'serverUuid': '12345678-1234-1234-1234-123456789abc'}
        self.value = {'rds': self.target | {'masterSecretArn': 'synthetic-secret-reference'},
            'release': str(self.release), 'envelopeSha256': '1' * 64, 'publicationReceipt': str(self.root / 'publication.json'),
            'lease': {'table': 'airbob-performance-lab-orchestration-lease', 'lockName': 'airbob-performance-lab',
                      'owner': 'unit-owner', 'runId': 'lab-b-snapshot', 'command': 'measurement', 'fencingToken': 7}}
        self.envelope = {'datasetId': 'global-growth-b-' + '2' * 16, 'appJarSha256': '3' * 64,
            'finalScaleSelected': True, 'awsExecutionAllowed': True, 'consumerManifestSha256': '4' * 64,
            'checksumsSha256': '5' * 64, 'objects': {
                'airbob-growth.sql.gz': {'key': 'datasets/final/airbob-growth.sql.gz', 'versionId': 'one', 'sha256': '2' * 64, 'bytes': 10},
                'migration-files.json': {'key': 'datasets/final/migration-files.json', 'versionId': 'two', 'sha256': '6' * 64, 'bytes': 12}}}
        self.baseline = {'mysqlVersion': '8.4.11', 'tables': {name: {'rows': 2, 'rowsSha256': 'a' * 64,
            'domainRowsSha256': 'b' * 64, 'ddlSha256': 'c' * 64} for name in snapshot.contract.TABLES}}
        self.baseline['tables']['flyway_schema_history']['rows'] = 28
        self.baseline['tables']['outbox']['rows'] = 0
        self.write('publication.json', {})
        self.write('release/before-fingerprint.json', self.baseline)
        self.proof = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-restore-receipt',
            'state': 'DATABASE_INVENTORY_LOGIN_VERIFIED', 'targetIdentity': self.target,
            'toolIdentity': snapshot.restore.tool_identity(), 'account': snapshot.ACCOUNT, 'region': snapshot.REGION,
            'datasetId': self.envelope['datasetId'], 'envelopeSha256': self.value['envelopeSha256'],
            'mysqlVersion': '8.4.11', 'flywayVersion': 28, 'finalScaleSelected': True, 'executionScope': 'final-b-rds',
            'allRowsAndDdlEqual': True, 'awsWritesExecuted': True, 'previousBusinessSchemaAbsent': True,
            'maximumSimultaneousBusinessDatabases': 1, 'sealedFingerprint': self.baseline,
            'appJarSha256': self.envelope['appJarSha256'], 'migrationFilesSha256': '6' * 64,
            'smallRdsPrerequisite': {'sameToolApplicationAndV28Verified': True},
            'preparation': {'passed': True, 'readinessVerified': True, 'accountLogins': {'passed': True},
                'currentInventory': {'everyHorizonContiguous': True}, 'ownerSha256BeforeAndAfter': '7' * 64,
                'applicationLeftRunning': False}, 'preparationCompletedAt': '2026-09-12T14:00:00+00:00'}
        self.application = {'mainCommit': '8' * 40,
            'image': snapshot.ACCOUNT + '.dkr.ecr.' + snapshot.REGION + '.amazonaws.com/airbob-repo@sha256:' + '9' * 64}
        self.configuration = {'schemaVersion': 1, 'kind': snapshot.CONFIG_KIND, 'operation': 'create',
            'operationTimeoutSeconds': 600, 'snapshotIdentifier': 'airbob-dataset-b-unit-final-snapshot', 'application': self.application,
            'restoreConfig': self.write('restore-config.json', self.value),
            'restoreReceipt': self.write('restore.json', self.proof), 'preparedFingerprint': self.write('prepared.json', self.baseline),
            'privateHandoff': self.write('private-handoff.json', {'synthetic': 'PRIVATE_HANDOFF_CONTENT'})}
        self.observation = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-service-reset-verification',
            'state': 'SERVICE_VERIFIED_AND_RESET', 'mysql': snapshot.MYSQL, 'datasetId': self.envelope['datasetId'],
            'targetIdentity': self.target, 'application': self.application | {'appJarSha256': '3' * 64, 'migrationFilesSha256': '6' * 64},
            'restoreReceiptSha256': self.configuration['restoreReceipt']['sha256'],
            'preparedFingerprintSha256': self.configuration['preparedFingerprint']['sha256'],
            'writersStopped': True, 'cdcStopped': True, 'sourceOriginalsUnchanged': True,
            'service': dict.fromkeys(('readinessPassed', 'normalLoginsPassed', 'publicReadsPassed', 'globalSearchPassed',
                                     'imagesSampled', 'reservableDatesPassed', 'domainApiMutationCdcEsPassed'), True) | {'representativeAccounts': 3},
            'reset': {'passed': True, 'testMutationRemoved': True, 'unchangedDomainAndDdl': True,
                      'remainingOutboxRows': 0, 'ownerSha256BeforeAndAfter': '7' * 64}, 'completedAt': self.when}
        self.configuration['serviceResetReceipt'] = self.write('service-reset.json', self.observation)
        self.core = snapshot.core_contract(self.configuration, self.value, self.envelope)
        self.storage = {'identifier': self.target['identifier'], 'resourceId': self.target['resourceId'],
            'kmsKeyArn': f'arn:aws:kms:{snapshot.REGION}:{snapshot.ACCOUNT}:key/12345678-1234-1234-1234-123456789abc',
            'allocatedStorageGiB': 60, 'storageType': 'gp3', 'instanceCreateTime': '2026-09-12T12:00:00+00:00'}
        self.item = {'DBSnapshotIdentifier': self.core['snapshotIdentifier'],
            'DBSnapshotArn': f"arn:aws:rds:{snapshot.REGION}:{snapshot.ACCOUNT}:snapshot:{self.core['snapshotIdentifier']}",
            'DBInstanceIdentifier': self.target['identifier'], 'DbiResourceId': self.target['resourceId'],
            'Engine': 'mysql', 'EngineVersion': '8.4.11', 'SnapshotType': 'manual', 'Encrypted': True,
            'KmsKeyId': self.storage['kmsKeyArn'], 'StorageType': 'gp3', 'AllocatedStorage': 60,
            'InstanceCreateTime': self.storage['instanceCreateTime'], 'SnapshotCreateTime': self.when, 'Status': 'available'}
        self.snapshot_list = [self.item]; self.tag_values = snapshot.snapshot_tags(self.core); self.grants = []
        self.instance_list = []
        self.aws = MagicMock(); self.aws.call.side_effect = self.aws_call; self.calls = []
        self.metadata = snapshot.snapshot_metadata(self.aws, self.core, self.storage)
        self.provenance = {'schemaVersion': 1, 'kind': snapshot.KIND, 'state': snapshot.AVAILABLE,
            'contract': self.core, 'contractSha256': snapshot.canonical_sha(self.core), 'snapshot': self.metadata,
            'storage': self.storage, 'sourceFreeze': {'heldUntilSnapshotAvailable': True},
            'allRowsAndDdlBeforeAndAfterSnapshotEqual': True, 'sourceDeletionAllowed': False, 'actualRestoreVerified': False}
        self.db = MagicMock(); self.db.timeout = 100; self.db.guard = MagicMock()

    def write(self, name, value):
        path = self.root / name
        snapshot.write(path, value)
        return {'path': str(path), 'sha256': snapshot.sha(path)}

    def aws_call(self, *args):
        self.calls.append(args)
        if args[:2] == ('sts', 'get-caller-identity'):
            return {'Account': snapshot.ACCOUNT}
        if args[:2] == ('rds', 'describe-db-snapshots'):
            return {'DBSnapshots': copy.deepcopy(self.snapshot_list)}
        if args[:2] == ('rds', 'list-tags-for-resource'):
            return {'TagList': [{'Key': key, 'Value': value} for key, value in self.tag_values.items()]}
        if args[:2] == ('rds', 'describe-db-snapshot-attributes'):
            return {'DBSnapshotAttributesResult': {'DBSnapshotIdentifier': self.core['snapshotIdentifier'],
                    'DBSnapshotAttributes': [{'AttributeName': 'restore', 'AttributeValues': self.grants}]}}
        if args[:2] == ('rds', 'describe-db-instances'):
            return {'DBInstances': self.instance_list}
        if args[:2] == ('autoscaling', 'describe-auto-scaling-groups'):
            return {'AutoScalingGroups': []}
        raise AssertionError('Unexpected or unauthorized AWS action: ' + str(args[:2]))


class SnapshotContractTest(SnapshotFixture):
    def test_offline_plan_and_unreviewed_apply_cannot_call_aws(self):
        source = self.write('snapshot-config.json', self.configuration)
        for mode in ('plan', 'create'):
            arguments = ['growth_b_snapshot.py', mode, '--config', source['path'], '--output', str(self.root / ('output-' + mode))]
            if mode == 'create':
                arguments += ['--preflight', str(self.root / 'missing.json'), '--preflight-sha256', 'invalid']
            with self.subTest(mode=mode), patch.object(sys, 'argv', arguments), \
                    patch.object(snapshot, 'restore_inputs', return_value=(self.value, self.envelope)), \
                    patch.object(snapshot.restore, 'Aws') as aws:
                if mode == 'plan': snapshot.main()
                else:
                    with self.assertRaisesRegex(ValueError, 'exact reviewed preflight SHA'): snapshot.main()
                aws.assert_not_called()

    def test_b_name_stays_within_existing_dataset_iam_namespace(self):
        source = self.write('valid-config.json', self.configuration)
        self.assertEqual(snapshot.config(source['path']), self.configuration)
        for name in ('airbob-b-old-namespace', 'airbob-dataset-v27-legacy', 'airbob-dataset-b-invalid--name'):
            source = self.write('bad-name.json', self.configuration | {'snapshotIdentifier': name})
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'B-only snapshot name'):
                snapshot.config(source['path'])

    def test_explicit_b_contract_binds_all_inputs_and_records_no_private_values(self):
        self.assertEqual(self.target, self.core['source'])
        self.assertEqual(snapshot.MYSQL, self.core['mysql'])
        self.assertEqual(self.envelope['objects'], self.core['publication']['objects'])
        self.assertNotIn('PRIVATE_HANDOFF_CONTENT', json.dumps(self.core))
        self.assertNotIn(str(self.root), json.dumps(self.core))
        self.assertEqual(snapshot.validate_provenance(self.provenance), self.core)

    def test_legacy_engine_version_scope_or_same_tool_mismatch_cannot_be_relabelled(self):
        for key, value in [('mysqlVersion', '8.0.45'), ('flywayVersion', 27), ('finalScaleSelected', False),
                           ('kind', 'legacy-rds-promotion'), ('state', 'OFFLINE_PLAN'), ('toolIdentity', {}),
                           ('smallRdsPrerequisite', {}), ('targetIdentity', {})]:
            proof = copy.deepcopy(self.proof); proof[key] = value
            self.configuration['restoreReceipt'] = self.write('altered.json', proof)
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'same-tool final B'):
                snapshot.core_contract(self.configuration, self.value, self.envelope)

    def test_service_cdc_or_reset_failure_blocks_creation(self):
        for path in [('service', 'normalLoginsPassed'), ('service', 'domainApiMutationCdcEsPassed'),
                     ('reset', 'testMutationRemoved'), ('reset', 'unchangedDomainAndDdl')]:
            observed = copy.deepcopy(self.observation); observed[path[0]][path[1]] = False
            self.configuration['serviceResetReceipt'] = self.write('changed-observation.json', observed)
            with self.subTest(path=path), self.assertRaises(ValueError):
                snapshot.core_contract(self.configuration, self.value, self.envelope)

    def test_evidence_bytes_private_permissions_and_unreviewed_fields_fail(self):
        Path(self.configuration['restoreReceipt']['path']).write_text('{}')
        with self.assertRaisesRegex(ValueError, 'bytes changed'):
            snapshot.core_contract(self.configuration, self.value, self.envelope)
        self.configuration['restoreReceipt'] = self.write('restore.json', self.proof)
        Path(self.configuration['privateHandoff']['path']).chmod(0o644)
        with self.assertRaisesRegex(ValueError, 'private directory and file'):
            snapshot.core_contract(self.configuration, self.value, self.envelope)
        Path(self.configuration['privateHandoff']['path']).chmod(0o600)
        self.configuration['deleteSourceAutomatically'] = True
        ref = self.write('config.json', self.configuration)
        with self.assertRaisesRegex(ValueError, 'fields differ'):
            snapshot.config(ref['path'])

    def test_provenance_core_and_metadata_tampering_are_rejected(self):
        value = copy.deepcopy(self.provenance); value['contract']['source']['resourceId'] = 'db-' + 'B' * 24
        with self.assertRaises(ValueError):
            snapshot.validate_provenance(value)
        value = copy.deepcopy(self.provenance); value['state'] = 'COMPLETE'
        with self.assertRaises(ValueError):
            snapshot.validate_provenance(value)
        value = copy.deepcopy(self.provenance); value['actualRestoreVerified'] = True
        with self.assertRaises(ValueError):
            snapshot.validate_provenance(value)


class SnapshotMetadataTest(SnapshotFixture):
    def test_current_aws_metadata_and_tags_verify(self):
        self.assertEqual(snapshot.verify(self.aws, self.provenance), self.core)
        self.assertEqual(self.metadata['identifier'], self.core['snapshotIdentifier'])
        self.assertEqual(self.metadata['sourceRdsResourceId'], self.target['resourceId'])

    def test_wrong_source_engine_kms_encryption_copy_or_size_fail(self):
        for key, value in [('DbiResourceId', 'db-WRONG'), ('DBInstanceIdentifier', 'other'), ('EngineVersion', '8.0.45'),
                           ('Encrypted', False), ('KmsKeyId', 'other'), ('AllocatedStorage', 100),
                           ('SourceRegion', 'us-east-1'), ('Status', 'failed')]:
            original = self.item.get(key); self.item[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                snapshot.snapshot_metadata(self.aws, self.core, self.storage)
            if original is None: self.item.pop(key)
            else: self.item[key] = original

    def test_no_tag_adoption_or_public_cross_account_permissions(self):
        self.tag_values['SnapshotContractSha256'] = 'f' * 64
        with self.assertRaisesRegex(ValueError, 'tags differ'):
            snapshot.verify(self.aws, self.provenance)
        self.tag_values = snapshot.snapshot_tags(self.core)
        for grant in ('all', '111111111111'):
            self.grants = [grant]
            with self.subTest(grant=grant), self.assertRaisesRegex(ValueError, 'public or shared'):
                snapshot.verify(self.aws, self.provenance)

    def test_snapshot_absence_is_not_confused_with_aws_error(self):
        self.snapshot_list = []
        with self.assertRaisesRegex(ValueError, 'missing'):
            snapshot.verify(self.aws, self.provenance)
        self.aws.call.side_effect = TimeoutError('synthetic API unavailable')
        with self.assertRaises(TimeoutError):
            snapshot.snapshots(self.aws, self.core['snapshotIdentifier'])


class SnapshotExecutionTest(SnapshotFixture):
    def setUp(self):
        super().setUp()
        self.snapshot_list = []
        self.trace, self.receipt = [], {'schemaVersion': 1, 'kind': snapshot.KIND + '-operation', 'operation': 'create',
                                      'toolIdentity': snapshot.tool_identity()}
        self.reviewed = {'state': 'SOURCE_READ_ONLY_PREFLIGHT', 'contractSha256': snapshot.canonical_sha(self.core),
                         'toolIdentity': snapshot.tool_identity(), 'storage': self.storage}

    def event(self, state, **values):
        self.trace.append(state); self.receipt.update(state=state, **values)
        snapshot.write(self.root / 'snapshot-operation.json', self.receipt)

    @contextlib.contextmanager
    def freeze(self, db):
        evidence = {'heldUntilSnapshotAvailable': False}; self.trace.append('lock')
        try: yield evidence
        finally: self.trace.append('unlock'); evidence['released'] = True

    @contextlib.contextmanager
    def dependencies(self):
        with patch.object(snapshot.restore, 'exclusive_database', side_effect=lambda _: contextlib.nullcontext()), \
                patch.object(snapshot, 'frozen_tables', side_effect=self.freeze), \
                patch.object(snapshot, 'source_instance', return_value=({}, self.storage)), \
                patch.object(snapshot, 'current_baseline', return_value=self.baseline) as baseline, \
                patch.object(snapshot, 'current_inventory', return_value={'localDateVector': {'Asia/Seoul': '2026-09-13'}}), \
                patch.object(snapshot.time, 'sleep', side_effect=lambda _: setattr(self, 'snapshot_list', [self.item])), \
                patch.object(snapshot, 'now', return_value=self.when):
            yield baseline

    def create(self):
        return snapshot.create_snapshot(self.configuration, self.value, self.envelope, self.reviewed,
            self.root, self.aws, self.root, self.db, {}, self.event)

    def test_create_is_frozen_full_verified_and_never_deletes_source(self):
        with self.dependencies() as baseline:
            proof = self.create()
        self.assertEqual(baseline.call_count, 2)
        self.assertLess(self.trace.index('lock'), self.trace.index('CREATE_REQUESTED'))
        self.assertLess(self.trace.index('unlock'), self.trace.index(snapshot.AVAILABLE))
        self.assertTrue(proof['sourceFreeze']['heldUntilSnapshotAvailable'])
        self.assertFalse(proof['sourceDeletionAllowed']); self.assertFalse(proof['actualRestoreVerified'])
        self.assertEqual([args[:2] for args in self.calls if 'create' in args[1]], [])
        request = snapshot.read(self.root / 'snapshot-create-request.json')
        self.assertEqual(request['tags'], snapshot.snapshot_tags(self.core))
        self.assertEqual(request['source'], self.target)
        self.assertFalse(request['hostCreatesOrDeletesAwsResources'])
        self.assertFalse(any('delete' in args[1] or 'restore-db' in args[1] for args in self.calls))
        self.assertEqual(snapshot.read(self.root / 'snapshot-provenance.json')['state'], snapshot.AVAILABLE)

    def test_baseline_change_lease_loss_or_unreviewed_existing_snapshot_prevents_create(self):
        with self.dependencies() as baseline:
            baseline.side_effect = ValueError('baseline changed')
            with self.assertRaises(ValueError): self.create()
        with self.dependencies():
            self.db.guard.side_effect = RuntimeError('lease lost')
            with self.assertRaises(RuntimeError): self.create()
        self.db.guard.side_effect = None
        self.snapshot_list = [self.item]
        with self.dependencies(), self.assertRaisesRegex(ValueError, 'cannot be adopted'):
            self.create()
        self.assertFalse(any(args[1] == 'create-db-snapshot' for args in self.calls))

    def test_interrupted_freeze_never_adopts_its_pending_snapshot_on_retry(self):
        with self.dependencies():
            with patch.object(snapshot.time, 'sleep', side_effect=TimeoutError('controller wait ended')):
                with self.assertRaises(TimeoutError): self.create()
            self.event('FAILED_RESOURCES_RETAINED')
        self.snapshot_list = [self.item]
        self.configuration['resumeReceipt'] = self.write('retry-source.json', self.receipt)
        with self.dependencies(), self.assertRaisesRegex(ValueError, 'interrupted freeze'):
            self.create()
        self.assertFalse((self.root / 'snapshot-provenance.json').exists())
        self.assertFalse(any(args[1] == 'create-db-snapshot' for args in self.calls))

    def test_retry_accepts_only_already_completed_source_freeze_evidence(self):
        with self.dependencies():
            self.create()
        self.event('FAILED_RESOURCES_RETAINED')
        self.configuration['resumeReceipt'] = self.write('completed-source-retry.json', self.receipt)
        with self.dependencies(): proof = self.create()
        self.assertEqual(proof['state'], snapshot.AVAILABLE)
        self.assertFalse(any(args[1] == 'create-db-snapshot' for args in self.calls))
        changed = copy.deepcopy(self.receipt); changed['contractSha256'] = 'f' * 64
        self.configuration['resumeReceipt'] = self.write('invalid-retry.json', changed)
        with self.assertRaisesRegex(ValueError, 'exact own'):
            snapshot.own_create_resume(self.configuration, self.core)

    def test_snapshot_failure_has_no_provenance_and_no_cleanup_deletion(self):
        self.item['Status'] = 'failed'
        with self.dependencies(), self.assertRaisesRegex(ValueError, 'creation failed'):
            self.create()
        self.assertFalse((self.root / 'snapshot-provenance.json').exists())
        self.assertIn('unlock', self.trace)
        self.assertFalse(any('delete' in args[1] for args in self.calls))


class SnapshotRestoreBoundaryTest(SnapshotFixture):
    def admission_config(self):
        return {'operation': 'restore-admission', 'provenance': self.write('provenance.json', self.provenance),
                'targetIdentifier': self.target['identifier'], 'lease': self.value['lease']}

    def test_source_absence_and_target_absence_are_required_before_controller_restore(self):
        config = self.admission_config()
        with patch.object(snapshot.restore, 'Lease', return_value=self.db.guard):
            result = snapshot.restore_admission(config, self.aws)
            self.assertEqual(result['state'], 'SOURCE_ABSENT_TARGET_ABSENT')
            self.assertFalse(result['restoreExecuted'])
            for identifier in (self.target['resourceId'], 'db-OTHER'):
                self.instance_list = [{'DbiResourceId': identifier}]
                with self.subTest(identifier=identifier), self.assertRaisesRegex(ValueError, 'region empty'):
                    snapshot.restore_admission(config, self.aws)

    def test_restore_admission_stops_on_expired_lease_or_live_asg(self):
        config = self.admission_config()
        self.db.guard.side_effect = RuntimeError('expired lease')
        with patch.object(snapshot.restore, 'Lease', return_value=self.db.guard), self.assertRaises(RuntimeError):
            snapshot.restore_admission(config, self.aws)
        self.db.guard.side_effect = None
        original = self.aws.call.side_effect
        def live(*args):
            if args[:2] == ('autoscaling', 'describe-auto-scaling-groups'):
                return {'AutoScalingGroups': [{'AutoScalingGroupName': self.target['identifier'] + '-app',
                    'DesiredCapacity': 1, 'MinSize': 0, 'Instances': [{'InstanceId': 'i-live'}]}]}
            return original(*args)
        self.aws.call.side_effect = live
        with patch.object(snapshot.restore, 'Lease', return_value=self.db.guard), self.assertRaisesRegex(ValueError, 'ASGs must remain stopped'):
            snapshot.restore_admission(config, self.aws)

    def test_database_identity_requires_all_v28_tables_and_no_external_clients(self):
        self.db.snapshot_read_lock_connection_id = 123
        def scalar(sql, database=True):
            if sql == 'SELECT @@version': return '8.4.11'
            if sql == 'SELECT @@server_uuid': return self.target['serverUuid']
            return '0'
        def rows(sql, database=True):
            if 'flyway_schema_history' in sql: return [{'version': str(i), 'success': '1'} for i in range(1, 29)]
            if 'schemata' in sql: return [{'SCHEMA_NAME': name} for name in snapshot.restore.SYSTEM_SCHEMAS | {'airbobdb'}]
            return [{'TABLE_NAME': name, 'TABLE_TYPE': 'BASE TABLE'} for name in snapshot.contract.TABLES]
        self.db.rows.side_effect = rows; self.db.scalar.side_effect = scalar
        snapshot.database_identity(self.db, self.value)
        self.db.scalar.side_effect = lambda sql, database=True: '1' if 'processlist' in sql else scalar(sql, database)
        with self.assertRaisesRegex(ValueError, 'undeclared MySQL client'):
            snapshot.database_identity(self.db, self.value)
        self.db.snapshot_read_lock_connection_id = None
        with self.assertRaisesRegex(ValueError, 'authenticated snapshot READ lock'):
            snapshot.database_identity(self.db, self.value)

    def test_deletion_gate_requires_valid_snapshot_private_handoff_source_and_full_live_match(self):
        self.instance_list = [{'DbiResourceId': self.target['resourceId'], 'DBInstanceIdentifier': self.target['identifier']}]
        with patch.object(snapshot, 'source_instance'), \
                patch.object(snapshot.restore, 'exclusive_database', return_value=contextlib.nullcontext()), \
                patch.object(snapshot, 'frozen_tables', return_value=contextlib.nullcontext()), \
                patch.object(snapshot, 'current_baseline', return_value=self.baseline) as baseline:
            admitted = snapshot.deletion_admission(self.aws, self.provenance, self.configuration, self.value,
                self.envelope, self.root, self.db, {}, self.root)
            self.assertTrue(admitted['sourceDeletionAllowed']); self.assertFalse(admitted['deletionExecuted'])
            baseline.side_effect = ValueError('source changed')
            with self.assertRaises(ValueError):
                snapshot.deletion_admission(self.aws, self.provenance, self.configuration, self.value,
                    self.envelope, self.root, self.db, {}, self.root)
        self.grants = ['all']
        with self.assertRaises(ValueError):
            snapshot.deletion_admission(self.aws, self.provenance, self.configuration, self.value,
                self.envelope, self.root, self.db, {}, self.root)

    def test_actual_restore_event_new_resource_and_same_snapshot_are_all_required(self):
        value = copy.deepcopy(self.value); value['rds']['resourceId'] = 'db-' + 'B' * 24
        self.instance_list = [{'DbiResourceId': value['rds']['resourceId'], 'StorageEncrypted': True,
            'KmsKeyId': self.storage['kmsKeyArn'], 'AllocatedStorage': 60, 'StorageType': 'gp3',
            'InstanceCreateTime': '2026-09-12T16:00:10+00:00'}]
        self.cloudtrail = {'eventName': 'RestoreDBInstanceFromDBSnapshot', 'eventSource': 'rds.amazonaws.com',
            'awsRegion': snapshot.REGION, 'recipientAccountId': snapshot.ACCOUNT,
            'requestParameters': {'dBInstanceIdentifier': value['rds']['identifier'], 'dBSnapshotIdentifier': self.metadata['arn']},
            'eventID': 'unit-event', 'eventTime': '2026-09-12T16:00:00+00:00'}
        admission = {'kind': snapshot.KIND + '-restore-admission', 'state': 'SOURCE_ABSENT_TARGET_ABSENT',
            'source': self.target, 'targetIdentifier': value['rds']['identifier'], 'snapshotArn': self.metadata['arn'],
            'recordedAt': '2026-09-12T15:50:00+00:00'}
        with patch.object(snapshot.restore, 'live_rds', return_value={}):
            _, actual = snapshot.restored_target(self.aws, self.provenance, admission, value, self.cloudtrail)
            self.assertEqual(actual['eventId'], 'unit-event')
            self.cloudtrail['requestParameters']['dBSnapshotIdentifier'] = 'legacy-v27-snapshot'
            with self.assertRaisesRegex(ValueError, 'exact successful AWS'):
                snapshot.restored_target(self.aws, self.provenance, admission, value, self.cloudtrail)
            self.instance_list[0]['DbiResourceId'] = self.target['resourceId']
            with self.assertRaisesRegex(ValueError, 'Old source or another DB'):
                snapshot.restored_target(self.aws, self.provenance, admission, value, self.cloudtrail)


class SnapshotPreparationTest(SnapshotFixture):
    def setUp(self):
        super().setUp()
        self.configuration = {'schemaVersion': 1, 'kind': snapshot.CONFIG_KIND, 'operation': 'prepare',
            'operationTimeoutSeconds': 600, 'restoreConfig': self.write('restore-target.json', self.value),
            'provenance': self.write('provenance.json', self.provenance),
            'restoreEvent': self.write('controller-restore-event.json', {}),
            'admission': self.write('admission.json', {'provenanceSha256': 'pending'})}
        admission = {'provenanceSha256': self.configuration['provenance']['sha256']}
        self.configuration['admission'] = self.write('admission.json', admission)
        self.receipt = {'schemaVersion': 1, 'kind': snapshot.KIND + '-operation', 'operation': 'prepare',
                        'toolIdentity': snapshot.tool_identity()}
        self.current = copy.deepcopy(self.baseline)
        self.trace = []
        self.prepared = {'passed': True, 'preparedFingerprintSha256': 'a' * 64,
                         'currentInventory': {'everyHorizonContiguous': True, 'checkedAt': self.when}}

    def event(self, state, **values):
        self.trace.append(state); self.receipt.update(state=state, **values)
        snapshot.write(self.root / 'snapshot-operation.json', self.receipt)

    def observe_baseline(self, db, value, envelope, runtime, environment, output, core, **kwargs):
        if not kwargs.get('allow_prepared'):
            snapshot.require(self.current == core['preparedFingerprint'], 'Whole snapshot baseline mismatch')
        else:
            snapshot.restore.validate_prepared_changes(core['sealedFingerprint'], self.current)
        snapshot.write(output, self.current)
        return self.current

    def prepare(self, *args, **kwargs):
        self.assertEqual(kwargs['expected_owner_sha'], self.core['ownerSha256'])
        snapshot.write(self.root / 'prepared-fingerprint.json', self.current)
        return copy.deepcopy(self.prepared)

    @contextlib.contextmanager
    def dependencies(self):
        with patch.object(snapshot.restore, 'exclusive_database', side_effect=lambda _: contextlib.nullcontext()), \
                patch.object(snapshot, 'frozen_tables', side_effect=lambda _: contextlib.nullcontext()), \
                patch.object(snapshot, 'restored_target', return_value=({}, {'eventId': 'actual-source-event'})), \
                patch.object(snapshot, 'current_baseline', side_effect=self.observe_baseline), \
                patch.object(snapshot.restore, 'credential_binding', return_value='d' * 64), \
                patch.object(snapshot.restore, 'owner_fingerprint', return_value=self.core['ownerSha256']), \
                patch.object(snapshot.restore, 'prepare_service', side_effect=self.prepare) as prepare, \
                patch.object(snapshot.restore, 'stream_restore', side_effect=AssertionError('Snapshot path must never import SQL')) as stream:
            yield prepare, stream

    def preflight(self):
        return snapshot.prepare_preflight(self.configuration, self.value, self.envelope, self.root,
                                          self.aws, self.root, self.db, {})

    def execute(self, plan):
        return snapshot.prepare_restored(self.configuration, self.value, self.envelope, plan, self.root,
                                         self.aws, self.root, self.db, {}, self.event)

    def test_actual_snapshot_baseline_prepares_without_sql_import_and_emits_common_projection(self):
        with self.dependencies() as (prepare, stream):
            projected = self.execute(self.preflight())
        self.assertEqual(self.receipt['state'], snapshot.PREPARED)
        self.assertTrue(self.receipt['snapshotWholeBaselineVerified']); self.assertTrue(self.receipt['sqlImportSkipped'])
        self.assertFalse(self.receipt['databaseRecreatedInThisRun'])
        self.assertEqual(projected['kind'], 'global-growth-b-aws-data-only-preparation')
        self.assertEqual(projected['state'], 'DATABASE_INVENTORY_LOGIN_VERIFIED')
        self.assertEqual(projected['sourceMode'], 'verified-global-b-snapshot')
        self.assertEqual(projected['restoreReceiptSha256'], snapshot.sha(self.root / 'snapshot-operation.json'))
        self.assertFalse(projected['deploymentReady']); self.assertFalse(projected['applicationLeftRunning'])
        self.assertEqual(projected['preparation']['preparedFingerprintSha256'], snapshot.sha(self.root / 'prepared-fingerprint.json'))
        prepare.assert_called_once(); stream.assert_not_called()
        self.db.execute.assert_not_called()

    def test_password_change_before_first_snapshot_baseline_is_rejected(self):
        self.current['tables']['member']['rowsSha256'] = 'f' * 64
        with self.dependencies() as (prepare, stream), self.assertRaisesRegex(ValueError, 'Whole snapshot baseline'):
            self.preflight()
        prepare.assert_not_called(); stream.assert_not_called()

    def test_change_after_preflight_cannot_start_preparation(self):
        with self.dependencies() as (prepare, stream):
            plan = self.preflight(); self.current['tables']['member']['rowsSha256'] = 'f' * 64
            with self.assertRaises(ValueError): self.execute(plan)
        prepare.assert_not_called(); stream.assert_not_called()

    def test_own_verified_preparation_failure_resumes_only_allowed_live_changes(self):
        with self.dependencies() as (prepare, stream):
            plan = self.preflight(); prepare.side_effect = RuntimeError('synthetic preparation failure')
            with self.assertRaises(RuntimeError): self.execute(plan)
            self.event('FAILED_RESOURCES_RETAINED')
            self.configuration['resumeReceipt'] = self.write('failed-preparation.json', self.receipt)
            self.current['tables']['member']['rowsSha256'] = 'f' * 64
            self.current['tables']['accommodation_inventory_day']['rows'] += 4
            prepare.side_effect = self.prepare
            projected = self.execute(self.preflight())
            stream.assert_not_called()
        self.assertEqual(projected['state'], 'DATABASE_INVENTORY_LOGIN_VERIFIED')
        self.assertEqual(prepare.call_count, 2)

    def test_resume_from_other_target_tool_credentials_or_unverified_baseline_fails(self):
        with self.dependencies():
            bindings = snapshot.preparation_bindings(self.configuration, self.value, self.envelope)
            proof = self.receipt | bindings | {'state': 'FAILED_RESOURCES_RETAINED',
                'snapshotWholeBaselineVerified': True, 'snapshotBaselineCanonicalSha256': snapshot.canonical_sha(self.baseline)}
            for key, value in [('targetIdentity', {}), ('toolIdentity', {}), ('credentialBindingSha256', 'f' * 64),
                               ('snapshotWholeBaselineVerified', False), ('provenanceSha256', 'f' * 64)]:
                changed = proof | {key: value}
                self.configuration['resumeReceipt'] = self.write('wrong-retry.json', changed)
                with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'exact own verified'):
                    self.preflight()


class SnapshotReadFenceTest(unittest.TestCase):
    def setUp(self):
        self.db = MagicMock(); self.original_guard = MagicMock(); self.db.guard = self.original_guard
        self.db.snapshot_read_lock_connection_id = None; self.db.command.return_value = ['mysql', '--defaults-file=/private/client.cnf']
        self.process = MagicMock(); self.process.poll.return_value = None
        self.process.stdout.readline.side_effect = ['123\n', '1\n', '1\n']

    @contextlib.contextmanager
    def process_context(self):
        selector = MagicMock(); selector.__enter__.return_value.select.return_value = [('ready', 1)]
        with patch.object(snapshot.subprocess, 'Popen', return_value=self.process) as popen, \
                patch.object(snapshot.selectors, 'DefaultSelector', return_value=selector), \
                patch.object(snapshot.restore, 'terminate') as terminate:
            yield popen, terminate

    def test_all_tables_read_locked_reconnect_disabled_guarded_and_cleaned(self):
        with self.process_context() as (popen, terminate):
            with snapshot.frozen_tables(self.db) as evidence:
                self.assertEqual(self.db.snapshot_read_lock_connection_id, 123)
                self.db.guard(force=True)
            self.assertTrue(evidence['released'])
        sql = ''.join(call.args[0] for call in self.process.stdin.write.call_args_list)
        self.assertIn('LOCK TABLES', sql)
        self.assertTrue(all('`' + table + '` READ' in sql for table in snapshot.contract.TABLES))
        self.assertNotIn('DROP', sql); self.assertNotIn('UPDATE', sql)
        self.assertIn('--skip-reconnect', popen.call_args.args[0])
        self.assertIs(self.db.guard, self.original_guard)
        self.assertIsNone(self.db.snapshot_read_lock_connection_id)
        terminate.assert_called_once_with(self.process)

    def test_lost_read_lock_or_lease_never_produces_a_snapshot_frozen_claim(self):
        with self.process_context():
            with self.assertRaisesRegex(ValueError, 'session was lost'):
                with snapshot.frozen_tables(self.db) as evidence:
                    self.process.poll.return_value = 1
            self.assertFalse(evidence['heldUntilSnapshotAvailable'])
            self.assertTrue(evidence['released'])
        self.process.poll.return_value = None
        self.original_guard.side_effect = RuntimeError('lease lost')
        with self.process_context(), self.assertRaisesRegex(RuntimeError, 'lease lost'):
            with snapshot.frozen_tables(self.db): self.fail('Must not enter')

    def test_cleanup_error_preserves_original_lock_failure(self):
        self.process.stdout.readline.side_effect = ['0\n']
        with self.process_context():
            with patch.object(snapshot.restore, 'terminate', side_effect=OSError('cleanup failure')):
                with self.assertRaisesRegex(ValueError, 'READ-lock session failed') as failure:
                    with snapshot.frozen_tables(self.db): self.fail('Must not enter')
            self.assertIn('cleanup did not complete', failure.exception.__notes__[0])


if __name__ == '__main__':
    unittest.main()
