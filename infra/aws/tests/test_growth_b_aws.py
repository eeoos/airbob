"""Hermetic B AWS boundary tests; these never contact or change AWS."""
from contextlib import ExitStack, nullcontext
import copy
import datetime as dt
import gzip
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import tempfile
import tarfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_aws_contract as contract
import growth_b_aws_restore as restore


class AwsContractTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.dataset = 'global-growth-b-' + 'a' * 16
        for name, body in [('consumer-manifest.json', b'{}'), ('SHA256SUMS.json', b'{}'), ('airbob-growth.sql.gz', b'fake gzip')]:
            (self.root / name).write_bytes(body)
        self.objects = {p.name: {'key': 'datasets/' + self.dataset + '/' + p.name, 'versionId': 'immutable-v1',
            'sha256': contract.sha(p), 'bytes': p.stat().st_size} for p in self.root.iterdir()}
        self.receipt = {'schemaVersion': 1, 'kind': 'global-growth-b-s3-publication',
            'state': 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED', 'datasetId': self.dataset,
            'bucket': contract.BUCKET, 'region': contract.REGION, 'objects': self.objects,
            'completionKey': self.objects['consumer-manifest.json']['key'], 'completionVersionId': 'immutable-v1',
            'consumerManifestSha256': self.objects['consumer-manifest.json']['sha256'],
            'totalBytes': sum(v['bytes'] for v in self.objects.values())}

    def test_exact_publication_inventory_is_accepted(self):
        self.assertEqual(contract.publication(self.root, self.receipt, self.dataset), self.objects)

    def test_null_or_latest_version_is_rejected(self):
        for version in ('null', '', 'None', None):
            with self.subTest(version=version):
                receipt = copy.deepcopy(self.receipt)
                receipt['objects']['airbob-growth.sql.gz']['versionId'] = version
                with self.assertRaisesRegex(ValueError, 'VersionId'):
                    contract.publication(self.root, receipt, self.dataset)

    def test_consumer_marker_must_bind_its_exact_version(self):
        self.receipt['completionVersionId'] = 'later-version'
        with self.assertRaisesRegex(ValueError, 'completion'):
            contract.publication(self.root, self.receipt, self.dataset)

    def test_replaced_dump_is_rejected(self):
        (self.root / 'airbob-growth.sql.gz').write_bytes(b'tampered')
        with self.assertRaisesRegex(ValueError, 'bytes'):
            contract.publication(self.root, self.receipt, self.dataset)

    def test_extra_private_file_is_rejected(self):
        (self.root / 'accounts.private.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'inventory'):
            contract.publication(self.root, self.receipt, self.dataset)

    def test_schema_fingerprint_is_semantic_across_jvm_field_order(self):
        one = {'algorithm': 'full', 'tables': {'member': {'rows': 2, 'sha': 'a'}}}
        two = {'tables': {'member': {'sha': 'a', 'rows': 2}}, 'algorithm': 'full'}
        self.assertEqual(restore.canonical_sha(one), restore.canonical_sha(two))
        two['tables']['member']['rows'] = 3
        self.assertNotEqual(restore.canonical_sha(one), restore.canonical_sha(two))


class AwsConfigurationIdentityTest(unittest.TestCase):
    def config(self, run_id='lab-34497367298-1'):
        config = {key: '/tmp/' + key for key in ('release', 'migrationDirectory', 'publicationReceipt',
                                                'envelope', 'appJar', 'privateAccounts')}
        config.update(schemaVersion=1, mode='aws-global-b', envelopeSha256='a' * 64,
            rds={'identifier': 'airbob-' + run_id, 'resourceId': 'db-ABC123',
                 'endpoint': 'airbob.example.ap-northeast-2.rds.amazonaws.com',
                 'serverUuid': '11111111-1111-1111-1111-111111111111',
                 'masterSecretArn': f'arn:aws:secretsmanager:{restore.REGION}:{restore.ACCOUNT}:secret:lab',
                 'caBundle': '/tmp/rds-ca.pem', 'caBundleSha256': 'a' * 64},
            writerAsgNames=[], redisImage='redis@sha256:' + 'b' * 64, replacementPolicy='empty-only',
            lease={'table': 'airbob-performance-lab-orchestration-lease', 'lockName': 'lab/main',
                   'owner': 'operator', 'runId': run_id, 'command': 'up', 'fencingToken': 1})
        return config

    def parse(self, config):
        with patch.object(restore, 'read', return_value=config), patch.object(restore, 'sha', return_value='a' * 64):
            return restore.configuration('/tmp/config.json')

    def test_workflow_run_id_binds_to_the_existing_terraform_rds_name(self):
        for run_id in ('lab-34497367298-1', 'lab-a', 'lab-' + 'a' * 28):
            with self.subTest(run_id=run_id):
                parsed = self.parse(self.config(run_id))
                self.assertEqual(parsed['rds']['identifier'], 'airbob-' + parsed['lease']['runId'])

    def test_old_double_prefix_and_another_run_are_rejected(self):
        for identifier in ('airbob-lab-lab-34497367298-1', 'airbob-lab-34497367298-2'):
            with self.subTest(identifier=identifier):
                config = self.config(); config['rds']['identifier'] = identifier
                with self.assertRaisesRegex(ValueError, 'orchestration lease'):
                    self.parse(config)

    def test_noncanonical_run_ids_are_rejected_before_aws_calls(self):
        for run_id in ('run-123', 'lab-', 'lab-a-', 'lab-a--b', 'lab-A', 'lab-' + 'a' * 29):
            with self.subTest(run_id=run_id), patch.object(restore.Aws, 'call') as aws:
                with self.assertRaisesRegex(ValueError, 'RDS identity'):
                    self.parse(self.config(run_id))
                aws.assert_not_called()

    def test_actual_terraform_asg_name_is_accepted_through_its_full_output_chain(self):
        lab = Path(__file__).resolve().parents[1] / 'lab'
        app = re.search(r'(?ms)^module "app_asg" \{\n(.*?)^\}', (lab / 'app.tf').read_text()).group(1)
        asg = re.search(r'(?ms)^resource "aws_autoscaling_group" "app" \{\n(.*?)^\}',
                        (lab / 'modules/app-asg/main.tf').read_text()).group(1)
        prefix = re.search(r'(?m)^\s*name_prefix\s*=\s*"([^"]+)"\s*$', app).group(1)
        name = re.search(r'(?m)^\s*name\s*=\s*"([^"]+)"\s*$', asg).group(1)
        self.assertRegex((lab / 'modules/app-asg/outputs.tf').read_text(),
                         r'output "name"\s*\{\s*value\s*=\s*aws_autoscaling_group\.app\.name\s*\}')
        self.assertRegex((lab / 'outputs.tf').read_text(),
                         r'auto_scaling_group_name\s*=\s*local\.application_infrastructure_enabled \? module\.app_asg\[0\]\.name : null')
        for run_id in ('lab-34497367298-1', 'lab-a', 'lab-' + 'a' * 28):
            with self.subTest(run_id=run_id):
                expected = name.replace('${var.name_prefix}', prefix.replace('${var.run_id}', run_id))
                self.assertNotIn('${', expected, 'Review the changed Terraform naming expression')
                config = self.config(run_id); config['writerAsgNames'] = [expected]
                self.assertEqual(self.parse(config)['writerAsgNames'], [expected])

    def test_other_run_suffix_launch_template_and_bounded_alb_names_are_rejected(self):
        run_id = 'lab-34497367298-1'
        identifier = 'airbob-' + run_id
        bounded = 'airbob-' + run_id[:12] + '-' + hashlib.sha1(run_id.encode()).hexdigest()[:6]
        for name in (identifier, identifier + '-', identifier + '-worker', identifier + '-app-extra',
                     identifier + '-child-app', 'airbob-lab-34497367298-2-app', bounded + '-app', bounded + '-alb'):
            with self.subTest(name=name), patch.object(restore.Aws, 'call') as aws:
                config = self.config(run_id); config['writerAsgNames'] = [name]
                with self.assertRaisesRegex(ValueError, 'Writer ASGs'):
                    self.parse(config)
                aws.assert_not_called()

    def test_writer_asg_inventory_must_be_an_empty_or_single_exact_name_list(self):
        expected = self.config()['rds']['identifier'] + '-app'
        self.assertEqual(self.parse(self.config())['writerAsgNames'], [])
        for names in ([expected, expected], [expected, expected + '-other'], expected, {}, [None], [[]], [{}]):
            with self.subTest(names=names):
                config = self.config(); config['writerAsgNames'] = names
                with self.assertRaisesRegex(ValueError, 'Writer ASGs'):
                    self.parse(config)

    def stopped_asg(self, name):
        return {'AutoScalingGroupName': name, 'DesiredCapacity': 0, 'MinSize': 0, 'Instances': []}

    def live_inventory(self, config, groups):
        rds = config['rds']
        item = {'DBInstanceIdentifier': rds['identifier'], 'DbiResourceId': rds['resourceId'],
                'Endpoint': {'Address': rds['endpoint'], 'Port': 3306}, 'Engine': 'mysql', 'EngineVersion': '8.4.11',
                'PubliclyAccessible': False, 'DBInstanceStatus': 'available', 'AllocatedStorage': 100,
                'BackupRetentionPeriod': 0, 'MasterUsername': 'fixture_admin',
                'MasterUserSecret': {'SecretArn': rds['masterSecretArn']}}
        aws = Mock()
        aws.call.side_effect = [{'Account': restore.ACCOUNT}, {'DBInstances': [item]}, {'AutoScalingGroups': groups}]
        return restore.live_rds(aws, self.parse(config))

    def test_live_inventory_accepts_the_exact_stopped_asg_with_an_unrelated_run_present(self):
        config = self.config(); expected = config['rds']['identifier'] + '-app'
        config['writerAsgNames'] = [expected]
        groups = [self.stopped_asg(expected), self.stopped_asg('airbob-lab-unrelated-app')]
        groups[1].update(DesiredCapacity=1, MinSize=1, Instances=[{'InstanceId': 'i-fixture-unrelated'}])
        self.assertEqual(self.live_inventory(config, groups)['writerAsgsStopped'], [expected])

    def test_live_inventory_requires_the_reviewed_writer_to_exist_and_be_declared(self):
        config = self.config(); expected = config['rds']['identifier'] + '-app'
        for names, groups in (([], [self.stopped_asg(expected)]), ([expected], [])):
            with self.subTest(names=names):
                config['writerAsgNames'] = names
                with self.assertRaisesRegex(ValueError, 'Run ASG inventory differs'):
                    self.live_inventory(config, groups)
        config['writerAsgNames'] = []
        self.assertEqual(self.live_inventory(config, [])['writerAsgsStopped'], [])

    def test_live_inventory_keeps_unknown_and_prefix_colliding_run_names_fail_closed(self):
        config = self.config(); expected = config['rds']['identifier'] + '-app'
        config['writerAsgNames'] = [expected]
        for extra in (config['rds']['identifier'] + '-worker', expected + '-extra',
                      config['rds']['identifier'] + '-child-app'):
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, 'Run ASG inventory differs'):
                self.live_inventory(config, [self.stopped_asg(expected), self.stopped_asg(extra)])

    def test_live_inventory_rejects_active_capacity_or_instances_on_the_exact_asg(self):
        config = self.config(); expected = config['rds']['identifier'] + '-app'
        config['writerAsgNames'] = [expected]
        for active in ({'DesiredCapacity': 1}, {'MinSize': 1}, {'Instances': [{'InstanceId': 'i-fixture-app'}]}):
            with self.subTest(active=active):
                with self.assertRaisesRegex(ValueError, 'ASGs must be stopped'):
                    self.live_inventory(config, [self.stopped_asg(expected) | active])


class AwsLiveGatesTest(unittest.TestCase):
    def test_lease_rejects_old_fence_even_with_future_expiry(self):
        config = {'table': 'table', 'lockName': 'lock', 'owner': 'owner', 'runId': 'run', 'command': 'up', 'fencingToken': 2}
        item = {key: {'S': config[field]} for key, field in [('Owner', 'owner'), ('RunId', 'runId'), ('Command', 'command')]}
        item.update(FencingToken={'N': '1'}, ExpiresAt={'N': '9999999999'}, CommandDeadline={'N': '9999999999'})
        aws = Mock(); aws.call.return_value = {'Item': item}
        with self.assertRaisesRegex(ValueError, 'fencing'):
            restore.Lease(aws, config)(force=True)

    def test_lease_rejects_expired_owner(self):
        config = {'table': 'table', 'lockName': 'lock', 'owner': 'owner', 'runId': 'run', 'command': 'up', 'fencingToken': 2}
        item = {key: {'S': config[field]} for key, field in [('Owner', 'owner'), ('RunId', 'runId'), ('Command', 'command')]}
        item.update(FencingToken={'N': '2'}, ExpiresAt={'N': '1'}, CommandDeadline={'N': '9999999999'})
        aws = Mock(); aws.call.return_value = {'Item': item}
        with self.assertRaisesRegex(ValueError, 'expired'):
            restore.Lease(aws, config)(force=True)

    def test_stale_pre_removal_free_space_is_not_credited(self):
        now = dt.datetime.now(dt.timezone.utc)
        aws = Mock(); aws.call.return_value = {'Datapoints': [{'Timestamp': (now - dt.timedelta(seconds=30)).isoformat(),
                                                             'Minimum': 10**12, 'Unit': 'Bytes'}]}
        with self.assertRaisesRegex(ValueError, 'predates'):
            restore.free_storage(aws, 'rds', not_before=now)

    def test_metric_freshness_is_required(self):
        aws = Mock(); aws.call.return_value = {'Datapoints': [{'Timestamp': '2000-01-01T00:00:00Z', 'Minimum': 10**12, 'Unit': 'Bytes'}]}
        with self.assertRaisesRegex(ValueError, 'stale'):
            restore.free_storage(aws, 'rds')

    def test_enabled_binlogs_need_a_separate_capacity_basis(self):
        envelope = {'storage': {'requiredRdsFreeAfterRemovalBytes': 100}}
        with self.assertRaisesRegex(ValueError, 'Binary logging'):
            restore.required_rds_bytes({}, envelope, {'binaryLogging': True})
        self.assertEqual(restore.required_rds_bytes({}, envelope, {'binaryLogging': False}), 100)
        self.assertEqual(restore.required_rds_bytes({'binlogBudget': {'additionalReserveBytes': 42}}, envelope,
                                                   {'binaryLogging': True}), 142)

    def test_mysql_secret_options_are_quoted(self):
        self.assertEqual(restore.cnf_quote('p"a\\ss#word'), '"p\\"a\\\\ss#word"')
        for unsafe in ['password\nssl-mode=DISABLED', 'password\r', 'nul\0']:
            with self.assertRaises(ValueError):
                restore.cnf_quote(unsafe)

    def test_secret_file_is_private_from_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'secret'
            restore.private_text(path, 'synthetic-test-value')
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                restore.private_text(path, 'replacement')

    def test_remote_version_check_never_requests_latest(self):
        item = {'key': 'datasets/b/dump.gz', 'versionId': 'pinned-01', 'bytes': 7 * 1024**3, 'sha256': 'a' * 64}
        aws = Mock(); aws.call.return_value = {'VersionId': 'pinned-01', 'ContentLength': item['bytes']}
        restore.verify_remote_versions(aws, {'bucket': 'bucket', 'objects': {'dump': item}})
        args = aws.call.call_args.args
        self.assertIn('--version-id', args)
        self.assertEqual(args[args.index('--version-id') + 1], 'pinned-01')
        aws.call.return_value['VersionId'] = 'later'
        with self.assertRaises(ValueError):
            restore.verify_remote_versions(aws, {'bucket': 'bucket', 'objects': {'dump': item}})


class StorageBudgetTest(unittest.TestCase):
    def test_current_horizon_is_not_counted_twice_and_margin_is_applied_once(self):
        with tempfile.TemporaryDirectory() as directory:
            release = Path(directory)
            base = {'dataAndIndexBytes': 1000, 'allocatedTablespaceBytes': 1100,
                'tables': [{'TABLE_NAME': 'accommodation_inventory_day', 'rows': 100, 'DATA_LENGTH': 200, 'INDEX_LENGTH': 100}]}
            current = {'dataAndIndexBytes': 2960, 'allocatedTablespaceBytes': 3256,
                'tables': [{'TABLE_NAME': 'accommodation_inventory_day', 'rows': 1080, 'DATA_LENGTH': 1180, 'INDEX_LENGTH': 1080}]}
            restore.write(release / 'measurements.json', {'databases': {'restoredBase': base, 'generatedBase': base}})
            restore.write(release / 'runtime-database-measurement.json', current)
            restore.write(release / 'scenario-qualification.json', {'repeats': [{'bootstrap': {'readiness': {
                'publishedListings': 10, 'currentHorizonRows': 980}}}]})
            item = release / 'tiny'; item.write_bytes(b'one')
            with tarfile.open(release / 'preparation-tools.tar.gz', 'w:gz') as archive:
                archive.add(item, arcname='runtime/tiny')
            budget = contract.storage_budget(release)
            self.assertEqual(budget['measuredFreeInventoryBytesPerRow'], 2)
            self.assertEqual(budget['extraCurrentFreeRowAllowance'], 10)
            self.assertEqual(budget['retainedFreeDataAndIndexBytes'], 600)
            self.assertEqual(budget['databaseWithCurrentAndRetainedFreeBytes'], math.ceil((2960 + 20 + 600) * 1.1 * 1.15))
            self.assertEqual(budget['requiredRdsFreeAfterRemovalBytes'], budget['databaseWithCurrentAndRetainedFreeBytes'] + 8 * 1024**3)


class ExecutionOrderTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(); self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.release = self.root / 'release'; self.release.mkdir()
        self.output = self.root / 'output'; self.output.mkdir()
        self.baseline = {'tables': {'member': {'rows': 42}}, 'mysqlVersion': '8.4.11'}
        restore.write(self.release / 'before-fingerprint.json', self.baseline)
        (self.release / 'airbob-growth.sql.gz').write_bytes(b'sealed compressed source fixture')
        self.before = {'tableObjects': 32, 'allocatedTablespaceBytes': 100, 'binaryLogging': False}
        self.config = {'lease': {}, 'rds': {'identifier': 'instance'}, 'operationTimeoutSeconds': 100,
            'release': str(self.release), 'replacementPolicy': 'discard-reviewed-existing', 'envelopeSha256': 'e' * 64, 'redisImage': 'digest'}
        self.envelope = {'datasetId': 'b', 'awsExecutionAllowed': True, 'finalScaleSelected': True,
            'smallRehearsalEligible': False,
            'objects': {'airbob-growth.sql.gz': {'sha256': contract.sha(self.release / 'airbob-growth.sql.gz')}},
            'storage': {'requiredAdditionalDataHostFreeBytes': 1, 'requiredRdsFreeAfterRemovalBytes': 100}}
        self.reviewed = {'state': 'READ_ONLY_PREFLIGHT', 'datasetId': 'b', 'envelopeSha256': 'e' * 64, 'rds': {},
            'beforeDatabase': self.before | {'fullFingerprintSha256': restore.canonical_sha(self.baseline)}}
        self.db = Mock(); self.db.scalar.return_value = 0; self.db.guard = Mock(); self.db.timeout = 100
        self.events = []
        self.stack = ExitStack(); self.addCleanup(self.stack.close)
        for name, result in [('verify_remote_versions', None), ('database_state', self.before), ('command', b''),
                             ('free_storage', {'bytes': 200, 'recordedAt': 'now'}), ('stream_restore', None),
                             ('prepare_service', {'passed': True, 'preparedFingerprintSha256': 'f' * 64}),
                             ('execution_bindings', {'targetIdentity': {}, 'credentialBindingSha256': 'd' * 64}),
                             ('exclusive_database', nullcontext()),
                             ('validate_rehearsal', {'state': 'SMALL_RDS_INVENTORY_LOGIN_VERIFIED'})]:
            self.stack.enter_context(patch.object(restore, name, return_value=result))
        self.stack.enter_context(patch.object(restore.shutil, 'disk_usage', return_value=Mock(free=10**12)))
        def fingerprint(runtime, release, environment, output, timeout, guard=None):
            restore.write(output, self.baseline); return self.baseline
        self.fingerprint = self.stack.enter_context(patch.object(restore, 'fingerprint', side_effect=fingerprint))

    def run_restore(self, allow_small_rehearsal=False):
        restore.execute(self.config, self.envelope, self.reviewed, self.output, Mock(), self.root, self.db, {}, {},
                        lambda state, **values: self.events.append(state), allow_small_rehearsal=allow_small_rehearsal)

    def test_complete_order_hashes_before_password_or_inventory_changes(self):
        self.run_restore()
        self.assertEqual(self.events, ['EXISTING_DATABASE_VERIFIED', 'PREVIOUS_DATABASE_REMOVED',
            'POST_REMOVAL_CAPACITY_VERIFIED', 'SQL_IMPORT_STARTED', 'SQL_IMPORT_COMPLETED', 'FULL_VALIDATION_STARTED',
            'SEALED_DATABASE_VERIFIED', 'PREPARATION_STARTED', 'DATABASE_INVENTORY_LOGIN_VERIFIED'])
        self.assertEqual(self.db.execute.call_args_list[0].args[0], 'DROP DATABASE IF EXISTS airbobdb;')
        restore.prepare_service.assert_called_once()

    def test_changed_existing_full_rows_prevent_any_drop(self):
        self.reviewed['beforeDatabase']['fullFingerprintSha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'changed since review'):
            self.run_restore()
        self.db.execute.assert_not_called(); restore.stream_restore.assert_not_called()

    def test_changed_restore_rows_prevent_credential_and_free_preparation(self):
        calls = 0
        def changed(runtime, release, environment, output, timeout, guard=None):
            nonlocal calls
            calls += 1
            result = self.baseline if calls == 1 else {'wrong': 'rows'}
            restore.write(output, result); return result
        self.fingerprint.side_effect = changed
        with self.assertRaisesRegex(ValueError, 'Full restored'):
            self.run_restore()
        restore.prepare_service.assert_not_called()
        self.assertNotIn('SEALED_DATABASE_VERIFIED', self.events)

    def test_small_is_refused_before_any_aws_or_sql_operation(self):
        self.envelope['awsExecutionAllowed'] = self.envelope['finalScaleSelected'] = False
        with self.assertRaisesRegex(ValueError, 'final B'):
            self.run_restore()
        self.db.execute.assert_not_called(); restore.verify_remote_versions.assert_not_called()

    def test_explicit_qualified_small_rehearsal_has_a_separate_terminal_state(self):
        self.envelope.update(finalScaleSelected=False, awsExecutionAllowed=False, smallRehearsalEligible=True)
        self.config['allowSmallRehearsal'] = True
        self.run_restore(allow_small_rehearsal=True)
        self.assertEqual(self.events[-1], 'SMALL_RDS_INVENTORY_LOGIN_VERIFIED')
        self.assertFalse(self.envelope['finalScaleSelected'])
        restore.validate_rehearsal.assert_not_called()
        restore.prepare_service.assert_called_once()

    def test_small_cli_flag_does_not_replace_reviewed_config_opt_in(self):
        self.envelope.update(finalScaleSelected=False, awsExecutionAllowed=False, smallRehearsalEligible=True)
        with self.assertRaisesRegex(ValueError, 'opt-ins'):
            self.run_restore(allow_small_rehearsal=True)
        self.db.execute.assert_not_called()

    def test_small_reviewed_config_does_not_replace_explicit_cli_opt_in(self):
        self.envelope.update(finalScaleSelected=False, awsExecutionAllowed=False, smallRehearsalEligible=True)
        self.config['allowSmallRehearsal'] = True
        with self.assertRaisesRegex(ValueError, 'opt-ins'):
            self.run_restore()
        self.db.execute.assert_not_called()

    def test_final_run_needs_the_actual_small_receipt_gate_before_any_operation(self):
        restore.validate_rehearsal.side_effect = ValueError('successful small receipt required')
        with self.assertRaisesRegex(ValueError, 'small receipt'):
            self.run_restore()
        self.db.execute.assert_not_called(); restore.verify_remote_versions.assert_not_called()

    def test_low_actual_space_after_removal_prevents_import(self):
        restore.free_storage.return_value = {'bytes': 99}
        with self.assertRaisesRegex(ValueError, 'space is insufficient'):
            self.run_restore()
        restore.stream_restore.assert_not_called(); restore.prepare_service.assert_not_called()
        self.assertEqual(self.events[-1], 'PREVIOUS_DATABASE_REMOVED')

    def test_source_changed_during_review_prevents_old_database_removal(self):
        (self.release / 'airbob-growth.sql.gz').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'Sealed dump changed'):
            self.run_restore()
        self.db.execute.assert_not_called()


class RehearsalReceiptTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(); self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'small-rds.json'
        self.envelope = {'appJarSha256': 'a' * 64, 'objects': {'migration-files.json': {'sha256': 'b' * 64}}}
        resource, server = 'db-SMALLFIXTURE', '11111111-1111-1111-1111-111111111111'
        environment = 'aws:' + resource + ':' + server
        representatives = {'passed': True, 'accountCount': 3, 'identityRoleAndOwnershipVerified': True,
            'emailMutationAfterRestore': False, 'accounts': [
                {'key': key, 'memberId': member, 'email': key + '@airbob.test',
                 'role': 'ADMIN' if key == 'admin' else 'MEMBER', 'status': 'ACTIVE'}
                for member, key in ((401, 'demo'), (402, 'host'), (403, 'admin'))]}
        self.proof = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-restore-receipt',
            'state': 'SMALL_RDS_INVENTORY_LOGIN_VERIFIED', 'executionScope': 'small-rds-rehearsal',
            'datasetId': 'global-growth-b-' + 'c' * 16, 'finalScaleSelected': False,
            'account': contract.ACCOUNT, 'region': contract.REGION, 'toolIdentity': restore.tool_identity(),
            'appJarSha256': 'a' * 64, 'migrationFilesSha256': 'b' * 64, 'mysqlVersion': '8.4.11', 'flywayVersion': 28,
            'awsWritesExecuted': True, 'allRowsAndDdlEqual': True, 'previousBusinessSchemaAbsent': True,
            'maximumSimultaneousBusinessDatabases': 1, 'restoredFingerprintSha256': 'd' * 64,
            'rdsResourceId': resource,
            'beforeDatabase': {'tlsCipher': 'TLS_AES_256_GCM_SHA384', 'version': '8.4.11', 'serverUuid': server}, 'deploymentReady': False,
            'preparation': {'passed': True, 'fullBaselineVerifiedBeforeCredentials': True, 'readinessVerified': True,
                'applicationLeftRunning': False, 'currentInventory': {'everyHorizonContiguous': True},
                'serviceCurrentlyAvailable': False, 'temporarySessionsRemoved': True,
                'privateCredentials': {'schemaVersion': 2, 'environment': environment, 'inputValidated': True,
                    'individualPasswords': True, 'fileMode': '0600', 'directoryMode': '0700',
                    'state': 'NOT_AVAILABLE', 'usable': False, 'credentialValuesRecorded': False},
                'credentialPreparation': {'preparedMembers': 111, 'onlyColumnChanged': 'member.password',
                    'identityRoleAndActiveVerifiedBeforeMutation': True, 'singleTransactionCommittedAfterExactCount': True,
                    'individualPasswords': True, 'environment': environment},
                'ownerSha256BeforeAndAfter': 'e' * 64, 'accountLogins': {'passed': True, 'accounts': 111,
                    'baseWorkloads': 15, 'identityAndLogoutVerified': True, 'representatives': representatives,
                    'representativeAccounts': 3, 'crossCredentialRejected': True, 'environment': environment}}}

    def config(self):
        restore.write(self.path, self.proof)
        return {'smallRdsReceipt': {'path': str(self.path), 'sha256': contract.sha(self.path)}}

    def test_same_tool_actual_success_shape_is_accepted(self):
        self.assertTrue(restore.validate_rehearsal(self.config(), self.envelope)['sameToolApplicationAndV28Verified'])

    def test_missing_receipt_rejects_final_execution(self):
        with self.assertRaisesRegex(ValueError, 'successful same-tool small'):
            restore.validate_rehearsal({}, self.envelope)

    def test_changed_receipt_hash_is_rejected(self):
        config = self.config(); self.path.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'successful same-tool small'):
            restore.validate_rehearsal(config, self.envelope)

    def test_offline_and_final_states_cannot_stand_in_for_small_rehearsal(self):
        for state in ('OFFLINE_CONTRACT_PREPARED', 'READ_ONLY_PREFLIGHT', 'DATABASE_INVENTORY_LOGIN_VERIFIED'):
            with self.subTest(state=state):
                self.proof['state'] = state
                with self.assertRaisesRegex(ValueError, 'completed small'):
                    restore.validate_rehearsal(self.config(), self.envelope)

    def test_tools_application_or_schema_changed_requires_another_rehearsal(self):
        for key in ('toolIdentity', 'appJarSha256', 'migrationFilesSha256'):
            with self.subTest(key=key):
                original = self.proof[key]; self.proof[key] = 'changed'
                with self.assertRaisesRegex(ValueError, 'different tools'):
                    restore.validate_rehearsal(self.config(), self.envelope)
                self.proof[key] = original

    def test_session_insertion_or_missing_free_inventory_is_insufficient(self):
        for key in ('accountLogins', 'currentInventory'):
            with self.subTest(key=key):
                original = self.proof['preparation'][key]; self.proof['preparation'][key] = {}
                with self.assertRaisesRegex(ValueError, 'normal-login/current-inventory'):
                    restore.validate_rehearsal(self.config(), self.envelope)
                self.proof['preparation'][key] = original

    def test_new_tool_identity_without_representative_or_cross_password_proof_is_rejected(self):
        login = self.proof['preparation']['accountLogins']
        for key in ('representatives', 'crossCredentialRejected', 'environment'):
            with self.subTest(missing=key):
                original = login.pop(key)
                with self.assertRaisesRegex(ValueError, 'representative/private credential'):
                    restore.validate_rehearsal(self.config(), self.envelope)
                login[key] = original
        for key in ('passed', 'identityRoleAndOwnershipVerified', 'emailMutationAfterRestore'):
            with self.subTest(failed=key):
                mapping = login['representatives']; original = mapping[key]; mapping[key] = not original
                with self.assertRaisesRegex(ValueError, 'representative/private credential'):
                    restore.validate_rehearsal(self.config(), self.envelope)
                mapping[key] = original

    def test_representatives_require_three_canonical_distinct_active_identities(self):
        mapping = self.proof['preparation']['accountLogins']['representatives']
        original = copy.deepcopy(mapping['accounts'])
        for change in ({'memberId': 402}, {'email': 'wrong@example.test'}, {'role': 'ADMIN'}, {'status': 'DELETED'}):
            with self.subTest(field=next(iter(change))):
                mapping['accounts'] = copy.deepcopy(original); mapping['accounts'][0].update(change)
                with self.assertRaises(ValueError):
                    restore.validate_rehearsal(self.config(), self.envelope)
        mapping['accounts'] = original[:2]
        with self.assertRaises(ValueError):
            restore.validate_rehearsal(self.config(), self.envelope)

    def test_small_representative_ids_are_not_required_to_equal_final_b_ids(self):
        for index, row in enumerate(self.proof['preparation']['accountLogins']['representatives']['accounts']):
            row['memberId'] = 9001 + index
        self.assertTrue(restore.validate_rehearsal(self.config(), self.envelope)['sameToolApplicationAndV28Verified'])

    def test_private_environment_and_stopped_application_state_are_required(self):
        prepared = self.proof['preparation']
        for container, change in ((prepared, {'serviceCurrentlyAvailable': True}),
                (prepared, {'temporarySessionsRemoved': False}),
                (prepared['privateCredentials'], {'inputValidated': False}),
                (prepared['privateCredentials'], {'environment': 'local:old-pilot'}),
                (prepared['privateCredentials'], {'usable': True}),
                (prepared['credentialPreparation'], {'singleTransactionCommittedAfterExactCount': False}),
                (prepared['credentialPreparation'], {'onlyColumnChanged': 'member.role'})):
            with self.subTest(field=next(iter(change))):
                original = dict(container); container.update(change)
                with self.assertRaisesRegex(ValueError, 'representative/private credential'):
                    restore.validate_rehearsal(self.config(), self.envelope)
                container.clear(); container.update(original)

    def test_full_success_claim_cannot_be_smuggled_through_small_override(self):
        with self.assertRaisesRegex(ValueError, 'cannot use'):
            restore.execution_scope({'allowSmallRehearsal': True}, {'finalScaleSelected': True, 'awsExecutionAllowed': True},
                                    allow_small_rehearsal=True)

    def test_opt_ins_never_accept_unqualified_small(self):
        with self.assertRaisesRegex(ValueError, 'qualified-small'):
            restore.execution_scope({'allowSmallRehearsal': True}, {'finalScaleSelected': False, 'smallRehearsalEligible': False},
                                    allow_small_rehearsal=True)


class StreamingProcessTest(unittest.TestCase):
    def test_database_fence_loss_is_rejected_before_next_write(self):
        program = 'import sys\nfor i,line in enumerate(sys.stdin):\n print(1 if i==0 else 0,flush=True)\n'
        previous = Mock()
        db = Mock(guard=previous)
        db.command.return_value = [sys.executable, '-u', '-c', program]
        with restore.exclusive_database(db):
            with self.assertRaisesRegex(ValueError, 'fence was lost'):
                db.guard(force=True)
        self.assertIs(db.guard, previous)

    def test_owner_hash_streams_without_a_row_spool(self):
        rows = b'accommodation_id\tstay_date\n1\t2026-09-11\n'
        db = Mock(timeout=5, guard=None)
        db.command.return_value = [sys.executable, '-c', 'import sys;sys.stdout.buffer.write(' + repr(rows) + ')']
        with patch.object(restore.tempfile, 'TemporaryFile', side_effect=AssertionError('No row spool allowed')):
            self.assertEqual(restore.owner_fingerprint(db), hashlib.sha256(rows).hexdigest())

    def test_failed_gzip_never_reports_a_successful_import(self):
        with tempfile.TemporaryDirectory() as directory:
            dump = Path(directory) / 'bad.gz'; dump.write_bytes(b'bad gzip')
            db = Mock(timeout=5, guard=None)
            db.command.return_value = [sys.executable, '-c', 'import sys;sys.stdin.buffer.read()']
            with self.assertRaisesRegex(ValueError, 'gzip/MySQL'):
                restore.stream_restore(db, dump)

    def test_valid_gzip_is_piped_directly_to_consumer(self):
        with tempfile.TemporaryDirectory() as directory:
            dump = Path(directory) / 'good.gz'
            with gzip.open(dump, 'wb') as stream:
                stream.write(b'SELECT 1;\n')
            db = Mock(timeout=5, guard=None)
            db.command.return_value = [sys.executable, '-c', 'import sys;assert sys.stdin.buffer.read()==b"SELECT 1;\\n"']
            restore.stream_restore(db, dump)
            self.assertEqual(list(Path(directory).iterdir()), [dump])

    def test_import_survives_the_old_four_hour_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            dump = Path(directory) / 'good.gz'
            with gzip.open(dump, 'wb') as stream:
                stream.write(b'SELECT 1;\n')
            db = Mock(timeout=14400, guard=None)
            db.command.return_value = [sys.executable, '-c',
                'import sys,time;sys.stdin.buffer.read();time.sleep(0.05)']
            # Each clock observation crosses the former total import limit.
            ticks = iter(range(0, 1_000_000, 14401))
            observed = []
            with patch.object(restore.time, 'monotonic', side_effect=lambda: next(ticks)):
                result = restore.stream_restore(db, dump, progress=observed.append)
            self.assertEqual(result['state'], 'SQL_IMPORT_COMPLETED')
            self.assertEqual(result['compressedBytesRead'], dump.stat().st_size)
            self.assertGreater(result['elapsedSeconds'], 14400)
            self.assertEqual(observed[-1]['mysqlExitCode'], 0)

    def test_progress_write_failure_does_not_abort_valid_import(self):
        with tempfile.TemporaryDirectory() as directory:
            dump = Path(directory) / 'good.gz'
            with gzip.open(dump, 'wb') as stream:
                stream.write(b'SELECT 1;\n')
            db = Mock(timeout=1, guard=None)
            db.command.return_value = [sys.executable, '-c', 'import sys;sys.stdin.buffer.read()']
            result = restore.stream_restore(db, dump, progress=Mock(side_effect=OSError('disk full')))
            self.assertEqual(result['state'], 'SQL_IMPORT_COMPLETED')

    def test_mysql_failure_retains_nonzero_exit_without_sql_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            dump = Path(directory) / 'good.gz'
            with gzip.open(dump, 'wb') as stream:
                stream.write(b'SELECT 1;\n')
            db = Mock(timeout=1, guard=None)
            db.command.return_value = [sys.executable, '-c',
                'import sys;sys.stderr.write("ERROR 2006 (HY000) at line 77: private SQL data\\n");sys.exit(3)']
            observed = []
            with self.assertRaisesRegex(ValueError, r'MySQL exit 3') as failure:
                restore.stream_restore(db, dump, progress=observed.append)
            self.assertNotIn('private', str(failure.exception))
            self.assertEqual(observed[-1]['state'], 'SQL_IMPORT_FAILED')
            self.assertEqual(observed[-1]['mysqlExitCode'], 3)
            self.assertEqual(observed[-1]['mysqlErrors'], [{'code': 2006, 'sqlState': 'HY000', 'lineNumber': 77}])
            self.assertNotIn('private', json.dumps(observed))

    def test_guard_can_still_cancel_an_active_import(self):
        with tempfile.TemporaryDirectory() as directory:
            dump = Path(directory) / 'good.gz'
            with gzip.open(dump, 'wb') as stream:
                stream.write(b'SELECT 1;\n')
            db = Mock(timeout=14400, guard=Mock(side_effect=[None, ValueError('fence lost')]))
            db.command.return_value = [sys.executable, '-c', 'import time;time.sleep(60)']
            with self.assertRaisesRegex(ValueError, 'fence lost'):
                restore.stream_restore(db, dump)
            self.assertEqual(db.guard.call_count, 2)

    def test_live_guard_cancels_subprocess_without_raw_secret_error(self):
        guard = Mock(side_effect=ValueError('fence lost'))
        with self.assertRaisesRegex(ValueError, 'fence lost'):
            restore.command([sys.executable, '-c', 'print("should-not-start")'], guard=guard)


if __name__ == '__main__':
    unittest.main()
