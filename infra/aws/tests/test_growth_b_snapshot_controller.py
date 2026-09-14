"""Offline controller ordering, exact evidence, IAM cleanup and workflow dispatch."""
import base64
import copy
import datetime as dt
import hashlib
import json
from pathlib import Path
import sys
import tarfile
import time
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_snapshot_controller as controller
from test_growth_b_snapshot_host import Fixture, MemoryAws

host, snapshot = controller.host, controller.snapshot


class ControllerAws(MemoryAws):
    def __init__(self):
        super().__init__()
        self.policies = {}
        self.states = ['Success']
        self.snapshots = []
        self.created = False
        self.fail_attach = False
        self.uncertain_attach = False
        self.cancel_error = False
        self.delete_error = False
        self.status_error = False

    def call(self, *args):
        def option(name):
            return args[args.index(name) + 1]
        operation = args[:2]
        if operation == ('s3api', 'head-object'):
            self.calls.append(args)
            ref, raw = self.objects[(option('--bucket'), option('--key'))]
            if '--version-id' in args:
                assert ref['versionId'] == option('--version-id')
            return {'VersionId': ref['versionId'], 'ContentLength': len(raw)}
        if operation == ('s3api', 'list-objects-v2'):
            self.calls.append(args)
            return {'Contents': [{'Key': key} for bucket, key in self.objects
                                 if bucket == option('--bucket') and key.startswith(option('--prefix'))]}
        if operation == ('iam', 'list-role-policies'):
            self.calls.append(args)
            return {'PolicyNames': list(self.policies)}
        if operation == ('iam', 'get-role-policy'):
            self.calls.append(args)
            return {'PolicyDocument': copy.deepcopy(self.policies[option('--policy-name')])}
        if operation == ('iam', 'put-role-policy'):
            self.calls.append(args)
            if self.fail_attach:
                raise ValueError('AccessDenied')
            name = option('--policy-name')
            assert name not in self.policies
            self.policies[name] = json.loads(Path(option('--policy-document').removeprefix('file://')).read_text())
            if self.uncertain_attach: raise ValueError('Uncertain attachment response')
            return {}
        if operation == ('iam', 'delete-role-policy'):
            self.calls.append(args)
            if self.delete_error: raise ValueError('Delete policy denied')
            del self.policies[option('--policy-name')]
            return {}
        if operation == ('ssm', 'send-command'):
            self.calls.append(args)
            return {'Command': {'CommandId': 'command-' + str(sum(c[:2] == operation for c in self.calls))}}
        if operation == ('ssm', 'list-command-invocations'):
            self.calls.append(args)
            if self.status_error: raise ValueError('Original host status read failed')
            selected = self.states[0]
            if len(self.states) > 1:
                self.states.pop(0)
            return {'CommandInvocations': [{'Status': selected}]}
        if operation == ('ssm', 'cancel-command'):
            self.calls.append(args)
            if self.cancel_error:
                raise ValueError('Cancel failed')
            return {}
        if operation == ('rds', 'describe-db-snapshots'):
            self.calls.append(args)
            return {'DBSnapshots': self.snapshots}
        if operation == ('rds', 'create-db-snapshot'):
            self.calls.append(args); self.created = True
            row = {'DBSnapshotIdentifier': option('--db-snapshot-identifier'),
                   'DBInstanceIdentifier': option('--db-instance-identifier'),
                   'DBSnapshotArn': 'arn:aws:rds:' + host.REGION + ':' + host.ACCOUNT + ':snapshot:' + option('--db-snapshot-identifier')}
            self.snapshots = [row]
            return {'DBSnapshot': row}
        return super().call(*args)


class ControllerFixture(Fixture):
    def setUp(self):
        super().setUp()
        self.store = ControllerAws()
        self.context['rds']['masterSecretArn'] = f'arn:aws:secretsmanager:{host.REGION}:{host.ACCOUNT}:secret:rds!db-unit-abcdef'
        self.fence = 12
        tags = {'RunId': self.run, 'FencingToken': str(self.fence), 'Project': 'airbob', 'Stack': 'lab', 'Service': 'debezium'}
        self.tags = [{'Key': key, 'Value': value} for key, value in tags.items()]
        self.role = 'airbob-lab-host-' + self.run + '-debezium'
        self.store.reads = {
            ('sts', 'get-caller-identity'): {'Account': host.ACCOUNT, 'Arn': 'arn:aws:sts::' + host.ACCOUNT + ':assumed-role/airbob-lab-operator/unit'},
            ('rds', 'describe-db-instances'): {'DBInstances': [{
                'DBInstanceIdentifier': self.target['identifier'], 'DbiResourceId': self.target['resourceId'],
                'Endpoint': {'Address': self.target['endpoint'], 'Port': 3306},
                'MasterUserSecret': {'SecretArn': self.context['rds']['masterSecretArn']},
                'EngineVersion': '8.4.11', 'DBInstanceClass': 'db.t3.small', 'StorageEncrypted': True,
                'DBInstanceStatus': 'available', 'PubliclyAccessible': False, 'TagList': self.tags}]},
            ('iam', 'get-role'): {'Role': {'PermissionsBoundary': {'PermissionsBoundaryArn': controller.BOUNDARY}, 'Tags': self.tags}},
            ('ec2', 'describe-instances'): {'Reservations': [{'Instances': [{'State': {'Name': 'running'}, 'Tags': self.tags,
                'IamInstanceProfile': {'Arn': 'arn:aws:iam:' + host.ACCOUNT + ':instance-profile/' + self.role}}]}]},
            ('autoscaling', 'describe-auto-scaling-groups'): {'AutoScalingGroups': []}}
        self.guard = MagicMock()
        now = dt.datetime.now(dt.timezone.utc)
        self.request = {'kind': snapshot.KIND + '-controller-create-request', 'state': 'SOURCE_READ_LOCK_HELD',
            'source': self.target, 'snapshotIdentifier': self.manifest['snapshotIdentifier'], 'requiredLease': self.context['lease'],
            'requiresLiveReadLockAcknowledgment': True, 'hostCreatesOrDeletesAwsResources': False, 'contractSha256': 'd'*64,
            'requestedAt': (now - dt.timedelta(seconds=2)).isoformat(), 'deadlineAt': (now + dt.timedelta(seconds=300)).isoformat(),
            'tags': {'Project': 'airbob', 'Environment': 'performance-lab', 'Stack': 'dataset', 'Persistence': 'persistent',
                'ManagedBy': 'global-b-snapshot', 'BProvenanceSchemaVersion': '1', 'DatasetId': self.dataset,
                'SourceRdsResourceId': self.target['resourceId'], 'SourceMysqlUuid': self.target['serverUuid'],
                'SourceRunId': self.run, 'MysqlVersion': '8.4.11', 'FlywayVersion': '28', 'SnapshotContractSha256': 'd'*64}}
        self.request['tags'].update(SourceRestoreReceiptSha256=self.manifest['evidence']['restoreReceipt']['sha256'],
            SourceServiceResetReceiptSha256=self.manifest['evidence']['serviceResetReceipt']['sha256'],
            PreparedFingerprintSha256=self.manifest['evidence']['preparedFingerprint']['sha256'],
            PrivateHandoffSha256=self.manifest['source']['privateAccountsSha256'])
        self.request_ref = self.store.add(host.EVIDENCE, self.context['evidencePrefix'] + 'snapshot-create-request.json', self.request)
        self.ack = {'kind': host.KIND + '-read-lock-ack', 'state': 'SOURCE_READ_LOCK_HELD', 'manifestSha256': self.manifest_sha,
            'requestSha256': self.request_ref['sha256'], 'requestVersionId': self.request_ref['versionId'], 'requestKey': self.request_ref['key'],
            'source': self.target, 'lease': self.context['lease'], 'hostInstanceId': self.context['hostInstanceId'],
            'snapshotIdentifier': self.manifest['snapshotIdentifier'], 'connectionId': 11,
            'observedAt': (now - dt.timedelta(seconds=1)).isoformat(), 'expiresAt': (now + dt.timedelta(seconds=40)).isoformat(),
            'hostCreatesOrDeletesAwsResources': False}
        self.ack_key = self.context['evidencePrefix'] + 'read-lock-acks/' + str(int(time.time())) + '-000001.json'
        self.store.add(host.EVIDENCE, self.ack_key, self.ack)

    def put_receipt(self, mode='prepare'):
        self.manifest = self.new_manifest(mode); self.context = self.new_context()
        self.context['rds']['masterSecretArn'] = f'arn:aws:secretsmanager:{host.REGION}:{host.ACCOUNT}:secret:rds!db-unit-abcdef'
        self.receipt = {'kind': host.KIND + '-receipt', 'state': 'HOST_OPERATION_COMPLETE',
            **{key: self.context[key] for key in ('runId', 'datasetId', 'operationId', 'operation', 'toolSources', 'hostInstanceId')},
            'manifestSha256': self.manifest_sha, 'persistentAwsResourcesMutatedByHost': False,
            'sqlImportExecuted': False, 'applicationLeftRunning': False, 'deploymentReady': False, 'targetIdentity': self.target, 'objects': {}}
        refs = self.receipt['objects']
        if mode == 'prepare':
            for name in ('snapshot-operation.json', 'prepared-fingerprint.json'):
                refs[name] = self.store.add(host.EVIDENCE, self.context['evidencePrefix'] + name, {'fixture': name})
            proof = {'kind': 'global-growth-b-aws-data-only-preparation', 'state': 'DATABASE_INVENTORY_LOGIN_VERIFIED',
                'sourceMode': 'verified-global-b-snapshot', 'datasetId': self.dataset, 'runId': self.run,
                'rdsResourceId': self.target['resourceId'], 'serverUuid': self.target['serverUuid'], 'rdsEngineVersion': '8.4.11', 'flywayVersion': 28,
                'snapshotProvenanceSha256': self.manifest['evidence']['provenance']['sha256'],
                'restoreReceiptSha256': refs['snapshot-operation.json']['sha256'],
                'preparation': {'preparedFingerprintSha256': refs['prepared-fingerprint.json']['sha256']},
                'snapshotRestoreEvidence': {'evidenceSource': 'controller-pinned-cloudtrail-event'},
                'applicationLeftRunning': False, 'deploymentReady': False}
            refs['data-only-preparation.json'] = self.store.add(host.EVIDENCE, self.context['evidencePrefix'] + 'data-only-preparation.json', proof)
        else:
            now = dt.datetime.now(dt.timezone.utc)
            self.core = {'source': self.target, 'snapshotIdentifier': self.manifest['snapshotIdentifier'], 'datasetId': self.dataset}
            self.provenance = {'contractSha256': 'd'*64, 'contract': self.core}
            self.admission = {'kind': snapshot.KIND + '-source-deletion-admission', 'source': self.target,
                'contractSha256': 'd'*64, 'sourceDeletionAllowed': True, 'snapshotAvailableVerified': True,
                'privateHandoffPreserved': True, 'deletionExecuted': False, 'issuedAt': now.isoformat(),
                'expiresAt': (now + dt.timedelta(seconds=60)).isoformat()}
            for name, obj in [('snapshot-provenance.json', self.provenance), ('source-deletion-admission.json', self.admission)]:
                refs[name] = self.store.add(host.EVIDENCE, self.context['evidencePrefix'] + name, obj)
        self.store.add(host.EVIDENCE, self.context['evidencePrefix'] + 'host-receipt.json', self.receipt)

    def run_controller(self, mode='prepare'):
        self.put_receipt(mode)
        refs = [self.context['manifest'], self.manifest['awsPreparation']]
        with patch.object(controller.restore, 'Aws', return_value=self.store), \
             patch.object(controller.restore, 'Lease', return_value=self.guard), \
             patch.object(controller, 'collect_read_references', return_value=refs), \
             patch.object(controller.time, 'sleep'):
            return controller.run(self.manifest, self.context, self.root / 'controller', self.fence, self.manifest['snapshotIdentifier'])


class IdentityAndPolicyTests(ControllerFixture):
    def test_offline_package_contains_six_sealed_helpers_and_three_outer_modules_and_is_reproducible(self):
        first = controller.package_tools(self.root / 'package-a', self.dataset, self.run, self.operation)
        second = controller.package_tools(self.root / 'package-b', self.dataset, self.run, self.operation)
        self.assertEqual(first['archive']['sha256'], second['archive']['sha256'])
        self.assertFalse(first['cloudMutationsExecuted'])
        self.assertEqual(first['toolSources'], host.sources())
        with tarfile.open(first['archive']['path'], 'r:gz') as archive:
            self.assertEqual(9, len(archive.getmembers()))
            for member in archive.getmembers():
                self.assertTrue(member.isfile())
                self.assertEqual(first['toolSources'][member.name], hashlib.sha256(archive.extractfile(member).read()).hexdigest())
        self.assertFalse(self.store.calls)

    def test_exact_retained_target_role_instance_and_stopped_asg_are_required(self):
        self.assertEqual(controller.validate_target(self.store, self.context, self.manifest, self.fence), self.role)
        mutations = [
            (('sts', 'get-caller-identity'), lambda v: v.update(Arn='arn:aws:sts::' + host.ACCOUNT + ':assumed-role/other/unit')),
            (('rds', 'describe-db-instances'), lambda v: v['DBInstances'].append(copy.deepcopy(v['DBInstances'][0]))),
            (('rds', 'describe-db-instances'), lambda v: v['DBInstances'][0].update(EngineVersion='8.0.46')),
            (('iam', 'get-role'), lambda v: v['Role']['PermissionsBoundary'].update(PermissionsBoundaryArn='wrong')),
            (('ec2', 'describe-instances'), lambda v: v['Reservations'][0]['Instances'][0]['IamInstanceProfile'].update(Arn='other')),
            (('autoscaling', 'describe-auto-scaling-groups'), lambda v: v['AutoScalingGroups'].append(
                {'AutoScalingGroupName': 'airbob-' + self.run + '-app', 'MinSize': 0, 'DesiredCapacity': 1, 'Instances': [{}]}))]
        for operation, mutate in mutations:
            with self.subTest(operation=operation):
                original = copy.deepcopy(self.store.reads[operation])
                mutate(self.store.reads[operation])
                with self.assertRaises(ValueError):
                    controller.validate_target(self.store, self.context, self.manifest, self.fence)
                self.store.reads[operation] = original

    def test_finite_version_pool_has_deadline_and_selected_prefixes_only(self):
        refs = [self.ref(f'datasets/{self.dataset}/chunk-{i}.json') | {'versionId': 'version-' + str(i)} for i in range(57)]
        refs += [self.context['manifest'], self.manifest['awsPreparation'], self.request_ref]
        policy = controller.read_policy(refs, self.context['deadlineEpoch'])
        self.assertLess(len(controller.canonical(policy)), 4000)
        for statement in policy['Statement']:
            self.assertEqual('s3:GetObjectVersion', statement['Action'])
            self.assertIn('aws:CurrentTime', statement['Condition']['DateLessThan'])
            self.assertTrue(statement['Condition']['StringEquals']['s3:VersionId'])
            self.assertNotIn('arn:aws:s3:::*', statement['Resource'])
        self.assertIn('arn:aws:s3:::' + host.BUCKET + '/datasets/' + self.dataset + '/*', policy['Statement'][0]['Resource'])
        self.assertEqual(['arn:aws:s3:::' + host.EVIDENCE + '/' + self.request_ref['key']], policy['Statement'][1]['Resource'])

    def test_host_manifest_and_evidence_must_be_exact_own_run(self):
        controller.validate_manifest(self.manifest, self.manifest_sha, self.dataset, self.run, self.operation)
        changed = copy.deepcopy(self.manifest)
        changed['evidence']['restoreReceipt']['key'] = 'data-bootstrap/lab-other/restore.json'
        with self.assertRaises(ValueError):
            controller.validate_manifest(changed, self.manifest_sha, self.dataset, self.run, self.operation)

    def test_helper_drift_is_rejected_before_aws(self):
        value = copy.deepcopy(self.manifest); value['toolSources']['growth_b_snapshot.py'] = 'f'*64
        with self.assertRaises(ValueError):
            controller.validate_manifest(value, self.manifest_sha, self.dataset, self.run, self.operation)
        self.assertFalse(self.store.calls)


class ReadLockTests(ControllerFixture):
    def test_snapshot_create_uses_fresh_ack_then_immutable_intent_then_one_exact_create(self):
        self.store.states = ['InProgress']
        result = controller.create_exact_snapshot(self.store, self.manifest, self.context, self.root, self.guard,
            'command-1', self.manifest['snapshotIdentifier'])
        self.assertEqual(result['request'], self.request_ref)
        creates = [call for call in self.store.calls if call[:2] == ('rds', 'create-db-snapshot')]
        self.assertEqual(len(creates), 1)
        self.assertEqual(creates[0][3], self.target['identifier'])
        intent = [i for i, call in enumerate(self.store.calls) if call[:2] == ('s3api', 'put-object')]
        self.assertLess(intent[-1], self.store.calls.index(creates[0]))
        self.assertGreaterEqual(self.guard.call_count, 2)

    def test_stale_wrong_version_wrong_lease_and_unverified_connection_never_create(self):
        for mutate in (lambda v: v.update(expiresAt='2020-01-01T00:00:00Z'),
                       lambda v: v.update(requestVersionId='other'),
                       lambda v: v['lease'].update(fencingToken=99),
                       lambda v: v.update(connectionId=0),
                       lambda v: v.update(hostCreatesOrDeletesAwsResources=True)):
            with self.subTest(mutate=mutate):
                changed = copy.deepcopy(self.ack); mutate(changed)
                with self.assertRaises(ValueError):
                    controller.validate_ack(changed, self.request, self.request_ref, self.context)
        self.assertFalse(self.store.created)

    def test_no_snapshot_create_after_ssm_finishes_or_if_name_already_exists(self):
        for state, present in [('Success', []), ('InProgress', [{'DBSnapshotIdentifier': self.manifest['snapshotIdentifier']}])]:
            self.store.states = [state]; self.store.snapshots = present
            with self.assertRaises(ValueError):
                controller.create_exact_snapshot(self.store, self.manifest, self.context, self.root, self.guard,
                    'command-1', self.manifest['snapshotIdentifier'])
        self.assertFalse(self.store.created)

    def test_lease_loss_before_create_keeps_all_resources(self):
        self.store.states = ['InProgress']; self.guard.side_effect = ValueError('lost lease')
        with self.assertRaises(ValueError):
            controller.create_exact_snapshot(self.store, self.manifest, self.context, self.root, self.guard,
                'command-1', self.manifest['snapshotIdentifier'])
        self.assertFalse(self.store.created)


class OperationTests(ControllerFixture):
    def test_preparation_completes_with_versioned_host_receipt_and_cleans_own_policy(self):
        result = self.run_controller()
        self.assertEqual(result['state'], 'SNAPSHOT_CONTROLLER_OPERATION_COMPLETE')
        self.assertIsNone(result['createdSnapshot']); self.assertFalse(result['sourceDeletionExecuted'])
        self.assertFalse(self.store.policies)
        self.assertFalse(any(call[:2] in {('rds', 'create-db-snapshot'), ('rds', 'delete-db-instance'), ('rds', 'restore-db-instance-from-db-snapshot')} for call in self.store.calls))
        sequence = [call[:2] for call in self.store.calls]
        self.assertLess(sequence.index(('s3api', 'put-object')), sequence.index(('iam', 'put-role-policy')))
        self.assertLess(sequence.index(('iam', 'put-role-policy')), sequence.index(('ssm', 'send-command')))
        self.assertEqual(1, sequence.count(('iam', 'delete-role-policy')))
        self.assertTrue(result['accessCleanup']['hostTerminalObserved'])
        self.assertTrue(result['accessCleanup']['policyRemoved'])

    def test_terminal_ssm_failure_preserves_original_error_without_cancel_permission(self):
        self.store.states = ['Failed']; self.store.cancel_error = True
        with self.assertRaisesRegex(ValueError, 'Snapshot host operation failed'):
            self.run_controller()
        self.assertFalse(self.store.policies)
        self.assertFalse(any(call[:2] == ('ssm', 'cancel-command') for call in self.store.calls))
        proof = json.loads((self.root/'controller/controller-cleanup.json').read_text())
        self.assertEqual('SNAPSHOT_OPERATION_FAILED', proof['originalFailureCode'])
        self.assertFalse(proof['cancellationConfirmed']); self.assertTrue(proof['hostTerminalObserved'])

    def test_policy_cleanup_failure_does_not_mask_original_host_failure(self):
        self.store.states = ['Failed']; self.store.delete_error = True
        with self.assertRaisesRegex(ValueError, 'Snapshot host operation failed'): self.run_controller()
        self.assertTrue(self.store.policies)
        proof = json.loads((self.root/'controller/controller-cleanup.json').read_text())
        self.assertEqual('EXACT_POLICY_CLEANUP_FAILED', proof['cleanupFailureCode'])
        self.assertTrue(proof['policyRetained']); self.assertTrue(proof['dataResourcesRetained'])

    def test_unknown_host_state_retains_deadline_bound_access_and_original_read_failure(self):
        self.store.status_error = True
        with self.assertRaisesRegex(ValueError, 'Original host status read failed'): self.run_controller()
        self.assertTrue(self.store.policies)
        proof = json.loads((self.root/'controller/controller-cleanup.json').read_text())
        self.assertEqual('SSM_STATUS_READ_FAILED', proof['observationFailureCode'])
        self.assertFalse(proof['hostTerminalObserved']); self.assertTrue(proof['policyRetained'])

    def test_failed_lease_with_running_host_retains_access_until_terminal_or_deadline(self):
        self.store.states = ['InProgress']; self.guard.side_effect = [None, None, RuntimeError('Original lease failure')]
        with self.assertRaisesRegex(RuntimeError, 'Original lease failure'): self.run_controller()
        self.assertTrue(self.store.policies)
        proof = json.loads((self.root/'controller/controller-cleanup.json').read_text())
        self.assertEqual('InProgress', proof['hostState']); self.assertFalse(proof['cancellationAttempted'])
        self.assertIn('new valid lease', proof['resumeAction'])

    def test_uncertain_attachment_is_observed_and_cleaned_without_dispatch_or_error_masking(self):
        self.store.uncertain_attach = True
        with self.assertRaisesRegex(ValueError, 'Uncertain attachment response'): self.run_controller()
        self.assertFalse(self.store.policies)
        self.assertFalse(any(call[:2] == ('ssm', 'send-command') for call in self.store.calls))
        proof = json.loads((self.root/'controller/controller-cleanup.json').read_text())
        self.assertTrue(proof['policyRemoved']); self.assertEqual('NOT_DISPATCHED', proof['hostState'])

    def test_cleanup_evidence_publication_failure_does_not_replace_original_error(self):
        import io
        original = controller.publish
        def publish(aws, key, *args):
            if key.endswith('/controller-cleanup.json'): raise RuntimeError('Unpublished private transport diagnostic')
            return original(aws, key, *args)
        self.store.states = ['Failed']
        with patch.object(controller, 'publish', side_effect=publish), patch('sys.stdout', new=io.StringIO()) as output:
            with self.assertRaisesRegex(ValueError, 'Snapshot host operation failed'): self.run_controller()
        proof = json.loads((self.root/'controller/controller-cleanup-local.json').read_text())
        self.assertEqual('CLEANUP_RECEIPT_PUBLICATION_FAILED', proof['evidenceFailureCode'])
        self.assertNotIn('Unpublished private transport diagnostic', output.getvalue())

    def test_inline_policy_quota_fails_before_permission_or_ssm_mutation(self):
        self.store.policies['existing'] = {'Statement': [{'Resource': 'x' * 10240}]}
        with self.assertRaisesRegex(ValueError, 'quota'):
            self.run_controller()
        self.assertFalse(any(call[:2] in {('iam', 'put-role-policy'), ('ssm', 'send-command')} for call in self.store.calls))

    def test_controller_dispatch_pins_entry_script_and_context_and_stops_exact_run_only(self):
        controller.dispatch(self.store, self.context, self.root)
        params = json.loads((self.root / 'host-parameters.json').read_text())
        commands = params['commands']
        self.assertTrue(any(host.sha(Path(host.__file__).with_name('bootstrap-growth-b-snapshot.sh')) in command for command in commands))
        encoded = next(command for command in commands if '/context.json' in command).split("'")[3]
        self.assertEqual(json.loads(base64.b64decode(encoded)), self.context)
        self.assertLessEqual(int(params['executionTimeout'][0]), 18000)
        controller.dispatch(self.store, self.context, self.root, stop=True)
        stop = json.loads((self.root / 'stop-parameters.json').read_text())
        self.assertIn('/opt/airbob/global-b/' + self.run + '/STOP', stop['commands'][-1])
        self.assertEqual(stop['executionTimeout'], ['60'])

    def test_source_create_waits_for_host_ack_and_snapshot_proof_before_retirement(self):
        self.put_receipt('create')
        # First main poll plus live-ACK confirmation, then host completion and STOP.
        self.store.states = ['InProgress', 'InProgress', 'Success']
        with patch.object(controller.restore, 'Aws', return_value=self.store), \
             patch.object(controller.restore, 'Lease', return_value=self.guard), \
             patch.object(controller, 'collect_read_references', return_value=[self.context['manifest']]), \
             patch.object(snapshot, 'verify', return_value=self.core), patch.object(controller.time, 'sleep'):
            result = controller.run(self.manifest, self.context, self.root / 'create', self.fence, self.manifest['snapshotIdentifier'])
        self.assertTrue(self.store.created)
        self.assertEqual(result['sourceRetirement']['key'], f'data-bootstrap/{self.run}/b-source-retirement.json')
        self.assertFalse(result['sourceDeletionExecuted'])
        self.assertFalse(self.store.policies)
        self.assertFalse(any(call[:2] == ('rds', 'delete-db-instance') for call in self.store.calls))

    def test_stop_delay_cannot_turn_expired_admission_into_permanent_retirement(self):
        self.put_receipt('retire')
        current = [dt.datetime.now(dt.timezone.utc)]
        real_datetime = dt.datetime
        class AdvancingClock(real_datetime):
            @classmethod
            def now(cls, zone=None):
                return current[0]
        def stop_status(*args):
            current[0] += dt.timedelta(seconds=61)
            return 'Success'
        with patch.object(snapshot, 'verify', return_value=self.core), \
             patch.object(controller, 'status', side_effect=stop_status), \
             patch.object(controller.dt, 'datetime', AdvancingClock):
            with self.assertRaisesRegex(ValueError, 'Fresh exact source deletion admission'):
                controller.retire_source(self.store, self.manifest, self.context, self.receipt, self.root, self.guard)
        self.assertTrue(any(call[:2] == ('ssm', 'send-command') for call in self.store.calls))
        self.assertNotIn((host.EVIDENCE, f'data-bootstrap/{self.run}/b-source-retirement.json'), self.store.objects)

    def test_prepared_target_receipt_cannot_claim_another_snapshot_or_sql_import(self):
        self.put_receipt()
        changed = copy.deepcopy(self.receipt); changed['sqlImportExecuted'] = True
        with self.assertRaises(ValueError):
            controller.validate_host_receipt(self.store, changed, self.manifest, self.context, self.root)
        reference = self.receipt['objects']['data-only-preparation.json']
        raw = json.loads(self.store.objects[(host.EVIDENCE, reference['key'])][1])
        raw['snapshotProvenanceSha256'] = 'f'*64
        self.receipt['objects']['data-only-preparation.json'] = self.store.add(host.EVIDENCE, reference['key'], raw)
        with self.assertRaisesRegex(ValueError, 'Actual snapshot-only'):
            controller.validate_host_receipt(self.store, self.receipt, self.manifest, self.context, self.root)


class CleanupAndRestoreEventTests(ControllerFixture):
    def prepare_policy_intent(self, *, changed=False):
        name = 'airbob-b-snapshot-42'
        policy = controller.read_policy([self.context['manifest']], self.context['deadlineEpoch'])
        self.store.policies[name] = policy
        self.store.add(host.EVIDENCE, f'data-bootstrap/{self.run}/b-snapshot-policy-intent-42.json',
            {'kind': controller.KIND + '-access-intent', 'runId': self.run, 'datasetId': self.dataset,
             'roleName': self.role, 'policyName': name, 'resourceFencingToken': self.fence,
             'policyCanonicalSha256': 'f'*64 if changed else snapshot.canonical_sha(policy),
             'deadlineEpoch': self.context['deadlineEpoch'], 'hostInstanceId': self.context['hostInstanceId'],
             'dispatchEvidenceKey': f'data-bootstrap/{self.run}/b-snapshot-policy-dispatch-42.json'})
        self.store.add(host.EVIDENCE, f'data-bootstrap/{self.run}/b-snapshot-policy-dispatch-42.json',
            {'kind': controller.KIND + '-dispatch', 'runId': self.run, 'policyName': name,
             'policyCanonicalSha256': snapshot.canonical_sha(policy), 'commandId': 'command-1',
             'hostInstanceId': self.context['hostInstanceId'], 'deadlineEpoch': self.context['deadlineEpoch']})
        return name

    def test_cleanup_removes_only_unchanged_own_policy_intent(self):
        own = self.prepare_policy_intent()
        self.store.policies['another-policy'] = {'Statement': []}
        self.assertEqual(controller.cleanup_access(self.store, self.run, self.dataset, self.fence, self.root, self.guard), [own])
        self.assertIn('another-policy', self.store.policies)

    def test_cleanup_cannot_delete_policy_with_changed_hash_or_other_resource_fence(self):
        self.prepare_policy_intent(changed=True)
        with self.assertRaises(ValueError):
            controller.cleanup_access(self.store, self.run, self.dataset, self.fence, self.root, self.guard)
        self.assertIn('airbob-b-snapshot-42', self.store.policies)

    def test_cleanup_requires_exact_host_terminal_before_original_deadline(self):
        own = self.prepare_policy_intent(); self.store.states = ['InProgress']
        with self.assertRaisesRegex(ValueError, 'not terminal'):
            controller.cleanup_access(self.store, self.run, self.dataset, self.fence, self.root, self.guard)
        self.assertIn(own, self.store.policies)

    def test_expired_original_policy_can_be_removed_without_claiming_host_stopped(self):
        own = self.prepare_policy_intent(); self.store.status_error = True
        with patch.object(controller.time, 'time', return_value=self.context['deadlineEpoch']):
            self.assertEqual([own], controller.cleanup_access(self.store, self.run, self.dataset, self.fence, self.root, self.guard))
        self.assertFalse(any(call[:2] == ('ssm', 'cancel-command') for call in self.store.calls))

    def test_cloudtrail_capture_is_exact_and_omits_identity_and_private_payload(self):
        now = dt.datetime.now(dt.timezone.utc)
        provenance = {'snapshot': {'identifier': self.manifest['snapshotIdentifier'], 'arn': 'snapshot-arn'},
                      'contract': {'source': self.target | {'resourceId': 'db-' + 'Z'*24}}}
        admission = {'kind': snapshot.KIND + '-restore-admission', 'state': 'SOURCE_ABSENT_TARGET_ABSENT', 'snapshotArn': 'snapshot-arn',
            'source': provenance['contract']['source'],
            'targetIdentifier': self.target['identifier'], 'recordedAt': (now - dt.timedelta(minutes=5)).isoformat(),
            'lease': self.context['lease']}
        self.store.reads[('rds', 'describe-db-instances')]['DBInstances'][0]['InstanceCreateTime'] = now.isoformat()
        event = {'eventName': 'RestoreDBInstanceFromDBSnapshot', 'eventSource': 'rds.amazonaws.com', 'awsRegion': host.REGION,
            'recipientAccountId': host.ACCOUNT, 'eventID': '12345678-1234-1234-1234-123456789abc', 'eventTime': (now - dt.timedelta(minutes=1)).isoformat(),
            'requestParameters': {'dBInstanceIdentifier': self.target['identifier'], 'dBSnapshotIdentifier': 'snapshot-arn',
                                  'unrelated': 'PRIVATE_SENTINEL'}, 'userIdentity': {'private': 'PRIVATE_SENTINEL'}}
        self.store.reads[('cloudtrail', 'lookup-events')] = {'Events': [{'CloudTrailEvent': json.dumps(event)}]}
        with patch.object(snapshot, 'verify'):
            ref = controller.capture_restore_event(self.store, provenance, admission, self.root, self.guard)
        raw = self.store.objects[(host.EVIDENCE, ref['key'])][1]
        self.assertNotIn(b'PRIVATE_SENTINEL', raw)
        self.assertEqual(json.loads(raw)['requestParameters'],
            {'dBInstanceIdentifier': self.target['identifier'], 'dBSnapshotIdentifier': 'snapshot-arn'})

    def test_retirement_rejects_other_source_and_accepts_only_own_absent_recovery(self):
        core = {'source': self.target, 'datasetId': self.dataset}
        provenance = {'contract': core}
        ref = self.store.add(host.EVIDENCE, self.context['evidencePrefix'] + 'snapshot-provenance.json', provenance)
        proof = {'kind': controller.KIND + '-source-retirement', 'state': 'SOURCE_FENCED_FOR_TEARDOWN',
            'runId': self.run, 'datasetId': self.dataset, 'sourceReactivationForbidden': True,
            'sourceDeletionExecuted': False, 'provenance': ref, 'source': self.target}
        self.store.add(host.EVIDENCE, f'data-bootstrap/{self.run}/b-source-retirement.json', proof)
        with patch.object(snapshot, 'verify', return_value=core):
            controller.verify_retirement(self.store, self.run, self.dataset, self.root)
            self.store.reads[('rds', 'describe-db-instances')]['DBInstances'][0]['DbiResourceId'] = 'db-other'
            with self.assertRaises(ValueError):
                controller.verify_retirement(self.store, self.run, self.dataset, self.root)
            self.store.reads[('rds', 'describe-db-instances')]['DBInstances'] = []
            controller.verify_retirement(self.store, self.run, self.dataset, self.root)


if __name__ == '__main__':
    unittest.main()
