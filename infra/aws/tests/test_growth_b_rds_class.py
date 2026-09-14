"""Offline class/lineage boundaries; all success-shaped receipts are fixtures."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_rds_class as gate
import growth_b_prepare as prepare
import growth_b_aws_restore as restore
import test_growth_b_aws as aws_fixtures


def row(context, chosen=gate.LARGE):
    tags = {'RunId': context['runId'], 'FencingToken': str(context['lease']['fencingToken']),
        'Project': 'airbob', 'Stack': 'lab', 'ExpiresAt': str(context['expiresAt'])}
    if chosen == gate.LARGE:
        tags[gate.TAG] = chosen
    return {'DBInstanceIdentifier': context['rds']['identifier'], 'DbiResourceId': context['rds']['resourceId'],
        'DBInstanceClass': chosen, 'InstanceCreateTime': '2026-01-01T00:00:00Z', 'DBInstanceStatus': 'available',
        'Engine': 'mysql', 'EngineVersion': '8.4.11', 'AllocatedStorage': 100, 'StorageType': 'gp3',
        'MultiAZ': False, 'StorageEncrypted': True, 'PubliclyAccessible': False,
        'TagList': [{'Key': k, 'Value': v} for k, v in tags.items()]}


class HashRuntimeCompatibility(unittest.TestCase):
    def test_file_sha_matches_empty_binary_and_chunk_boundary_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input.bin'
            for data in (b'', b'abc', bytes(range(256)) * 8192 + b'tail'):
                with self.subTest(bytes=len(data)):
                    path.write_bytes(data)
                    self.assertEqual(hashlib.sha256(data).hexdigest(), gate.sha(path))
                    path.write_bytes(data + b'changed')
                    self.assertNotEqual(hashlib.sha256(data).hexdigest(), gate.sha(path))

    def test_sha_uses_bounded_reads_without_file_digest(self):
        data = b'x' * (2 * 1024**2 + 17)
        case = self
        class BoundedStream(io.BytesIO):
            def read(self, size=-1):
                case.assertGreater(size, 0)
                case.assertLessEqual(size, 1024**2)
                return super().read(size)
        with patch.object(Path, 'open', return_value=BoundedStream(data)), patch.object(
                hashlib, 'file_digest', side_effect=AssertionError('Python 3.9 has no file_digest'), create=True):
            self.assertEqual(hashlib.sha256(data).hexdigest(), gate.sha('fixture-only'))

    def test_actual_cli_observe_hashes_context_and_source_with_only_fake_aws(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            context = {'runId': 'lab-class-cli-fixture', 'datasetId': 'global-growth-b-' + 'a' * 16,
                'manifestSha256': '1' * 64, 'toolSources': {'fixture.py': '2' * 64},
                'rdsInstanceClass': gate.DEFAULT, 'resourceFence': 76,
                'rdsClassGuardSha256': hashlib.sha256(Path(gate.__file__).read_bytes()).hexdigest(),
                'expiresAt': str(int(time.time()) + 600),
                'lease': {'table': 'airbob-performance-lab-orchestration-lease', 'lockName': 'airbob-performance-lab',
                    'owner': 'class-cli-fixture', 'runId': 'lab-class-cli-fixture', 'command': 'up', 'fencingToken': 87},
                'rds': {'identifier': 'airbob-lab-class-cli-fixture', 'resourceId': 'db-CLASSCLIFIXTURE'}}
            context_file = root / 'context.json'; context_file.write_bytes(gate.canonical(context))
            response = row(context, gate.DEFAULT)
            for tag in response['TagList']:
                if tag['Key'] == 'FencingToken': tag['Value'] = '76'
            response_file = root / 'response.json'
            response_file.write_text(json.dumps({'DBInstances': [response]}))
            expected = ['--region', 'ap-northeast-2', '--cli-connect-timeout', '5', '--cli-read-timeout', '15',
                'rds', 'describe-db-instances', '--db-instance-identifier', context['rds']['identifier'], '--output', 'json']
            aws = root / 'fixture-aws'
            aws.write_text('#!' + sys.executable + '\nimport pathlib,sys\nassert sys.argv[1:] == ' + repr(expected) +
                '\nsys.stdout.write(pathlib.Path(__file__).with_name("response.json").read_text())\n')
            aws.chmod(0o700)
            command = [sys.executable, '-B', gate.__file__, 'observe', '--context', str(context_file),
                '--stage', 'before', '--aws', str(aws)]
            # No credential environment, AWS binary fallback, network or real API.
            env = {'PATH': str(root), 'PYTHONDONTWRITEBYTECODE': '1', 'AWS_EC2_METADATA_DISABLED': 'true'}
            output = root / 'observation.json'
            result = subprocess.run(command + ['--output', str(output)], capture_output=True, text=True, env=env, timeout=10)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            observed = json.loads(output.read_bytes())
            self.assertEqual(context['rdsClassGuardSha256'], observed['sourceSha256'])
            self.assertEqual(hashlib.sha256(context_file.read_bytes()).hexdigest(), observed['contextSha256'])
            self.assertEqual(76, observed['resourceFence'])
            self.assertEqual('db-CLASSCLIFIXTURE', observed['rds']['resourceId'])
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
            response['DbiResourceId'] = 'db-FOREIGN'
            response_file.write_text(json.dumps({'DBInstances': [response]}))
            result = subprocess.run(command, capture_output=True, text=True, env=env, timeout=10)
            self.assertEqual(1, result.returncode)
            self.assertEqual({'state': 'REJECTED', 'code': 'ORIGINAL_RDS_CLASS_OR_ID_CHANGED'}, json.loads(result.stdout))


class ClassHistory(unittest.TestCase):
    def setUp(self):
        fixture = aws_fixtures.RehearsalReceiptTest(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        self.proof, self.envelope = fixture.proof, fixture.envelope
        self.small_path = fixture.path; restore.write(self.small_path, self.proof)
        self.raw_sha = gate.sha(self.small_path)
        self.context = {'runId': 'lab-class-small', 'datasetId': self.proof['datasetId'], 'manifestSha256': '1' * 64,
            'toolSources': prepare.source_hashes(Path(gate.__file__).parent), 'rdsInstanceClass': gate.LARGE,
            'rdsClassGuardSha256': gate.source_sha(), 'expiresAt': '1789366861',
            'lease': {'table': 'airbob-performance-lab-orchestration-lease', 'lockName': 'airbob-performance-lab',
                'owner': 'class-fixture-controller', 'runId': 'lab-class-small', 'command': 'up', 'fencingToken': 10},
            'rds': {'identifier': 'airbob-lab-class-small', 'resourceId': self.proof['rdsResourceId']}}
        self.context_sha = hashlib.sha256(gate.canonical(self.context)).hexdigest()
        self.before = gate.observe(self.context, self.context_sha, 'before', {'DBInstances': [row(self.context)]}, now=1789300000)
        self.after = gate.observe(self.context, self.context_sha, 'after', {'DBInstances': [row(self.context)]}, now=1789300500)
        self.wrapper = {'kind': prepare.KIND, 'state': self.proof['state'], 'datasetId': self.context['datasetId'],
            'runId': self.context['runId'], 'manifestSha256': self.context['manifestSha256'],
            'rdsResourceId': self.proof['rdsResourceId'], 'restoreReceiptSha256': self.raw_sha,
            'serverUuid': self.proof['beforeDatabase']['serverUuid'], 'preparation': self.proof['preparation'],
            'standaloneReceiptObject': {'key': f'data-bootstrap/{self.context["runId"]}/{self.context["datasetId"]}-standalone-rds.json',
                'versionId': 'fixture-exact-version', 'sha256': self.raw_sha, 'bytes': self.small_path.stat().st_size}}
        self.manifest = {'toolSources': self.context['toolSources'], 'files': {'smallRdsReceipt': self.wrapper['standaloneReceiptObject']}}

    def qualify(self):
        # The real unchanged prerequisite validator runs; no success boundary is mocked.
        restore.validate_rehearsal({'smallRdsReceipt': {'path': str(self.small_path), 'sha256': self.raw_sha}}, self.envelope)
        return gate.qualify(self.context, self.context_sha, self.before, self.after, self.wrapper, self.proof, self.raw_sha)

    def test_real_frozen_prerequisite_and_historical_class_proof_agree(self):
        qualified = self.qualify()
        self.assertEqual(self.context['expiresAt'], qualified['originalContext']['expiresAt'])
        self.assertEqual({k: self.wrapper[k] for k in gate.WRAPPER_PROJECTION}, qualified['originalWrapper'])
        with patch.object(gate.time, 'time', return_value=2000000000):
            # The expired rehearsal is historical. Its genuine original window
            # remains unchanged; no live context/time is fabricated to admit it.
            self.assertEqual(qualified, gate.validate_qualification(qualified, self.manifest, self.proof, self.raw_sha, gate.LARGE))
        self.assertFalse(qualified['performanceMeasured'])

    def test_small_class_cannot_be_relabelled_as_large(self):
        old = copy.deepcopy(self.context); old['rdsInstanceClass'] = gate.DEFAULT
        for stage in ('before', 'after'):
            with self.assertRaises(gate.Rejected):
                gate.observe(old, self.context_sha, stage, {'DBInstances': [row(self.context)]}, now=1789300000)
        with self.assertRaises(gate.Rejected):
            gate.validate_qualification(self.qualify(), self.manifest, self.proof, self.raw_sha, gate.DEFAULT)

    def test_added_class_reference_keeps_real_frozen_completion_validation(self):
        # Only synthetic success-shaped input bytes are used. The production
        # completion and rehearsal validators execute without monkeypatching.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            envelope = root / 'envelope.json'; restore.write(envelope, self.envelope)
            qualification = root / 'class.json'; gate.write(qualification, self.qualify())
            reference = {'key': f'data-bootstrap/{self.context["runId"]}/{self.context["datasetId"]}-rds-class.json',
                         'versionId': 'fixture-class-version', 'sha256': gate.sha(qualification), 'bytes': qualification.stat().st_size}
            wrapper = self.wrapper | {key: False for key in ('deploymentReady', 'privateAccountsUsable',
                'applicationLeftRunning', 'albReady', 'kafkaCdcReady')} | {'rdsClassQualificationObject': gate.reference(reference)}
            manifest = {'scope': 'small-rds-rehearsal', 'datasetId': self.context['datasetId'],
                        'files': {'envelope': {'sha256': gate.sha(envelope)}}}
            context = {'runId': self.context['runId'], 'manifestSha256': self.context['manifestSha256'],
                       'rdsResourceId': self.proof['rdsResourceId']}
            self.assertEqual('SMALL_RDS_INVENTORY_LOGIN_VERIFIED', prepare.validate_preparation_receipt(
                manifest, context, wrapper, self.small_path, envelope)['state'])
            changed = copy.deepcopy(self.proof); changed['toolIdentity']['growth_b_aws_restore.py'] = 'f' * 64
            wrong = root / 'wrong.json'; restore.write(wrong, changed)
            with self.assertRaises(ValueError):
                prepare.validate_preparation_receipt(manifest, context, wrapper | {'restoreReceiptSha256': gate.sha(wrong)}, wrong, envelope)

    def test_before_after_class_resource_and_pending_drift_are_rejected(self):
        for patch_value in ({'DBInstanceClass': gate.DEFAULT}, {'PendingModifiedValues': {'DBInstanceClass': gate.DEFAULT}},
                            {'DbiResourceId': 'db-FOREIGN'}, {'AllocatedStorage': 200}, {'StorageType': 'gp2'}, {'MultiAZ': True},
                            {'EngineVersion': '8.0.40'}, {'TagList': []}):
            with self.subTest(patch=patch_value), self.assertRaises(gate.Rejected):
                gate.observe(self.context, self.context_sha, 'after', {'DBInstances': [row(self.context) | patch_value]}, now=1789300500)
        changed = copy.deepcopy(self.after); changed['rds']['createdAt'] = '2026-01-02T00:00:00Z'
        with self.assertRaises(gate.Rejected):
            gate.qualify(self.context, self.context_sha, self.before, changed, self.wrapper, self.proof, self.raw_sha)

    def test_service_observation_keeps_resource_fence_separate_from_new_controller_lease(self):
        service = copy.deepcopy(self.context)
        service['resourceFence'] = self.context['lease']['fencingToken']
        service['lease']['fencingToken'] += 1
        observed = gate.observe(service, self.context_sha, 'before', {'DBInstances': [row(self.context)]}, now=1789300000)
        self.assertEqual(service['resourceFence'], observed['resourceFence'])
        self.assertNotEqual(service['lease']['fencingToken'], observed['resourceFence'])

    def test_wrong_raw_receipt_tool_state_run_and_original_deadline_reject(self):
        original = self.qualify()
        mutations = [lambda v: v.update(sourceSha256='f' * 64),
            lambda v: v['toolSources'].update({'growth_b_prepare.py': 'f' * 64}),
            lambda v: v.update(runId='lab-other'), lambda v: v.update(restoreReceiptSha256='f' * 64),
            lambda v: v['originalContext'].update(expiresAt=str(self.before['observedEpoch'])),
            lambda v: v['originalWrapper'].update(state='DATABASE_INVENTORY_LOGIN_VERIFIED'),
            lambda v: v['before']['rds'].update(instanceClass=gate.DEFAULT),
            lambda v: v['after'].update(resourceFence=11), lambda v: v.update(performanceMeasured=True),
            lambda v: v['standaloneReceipt'].update(versionId='null')]
        for mutate in mutations:
            value = copy.deepcopy(original); mutate(value)
            with self.subTest(mutate=mutate), self.assertRaises(gate.Rejected):
                gate.validate_qualification(value, self.manifest, self.proof, self.raw_sha, gate.LARGE)
        final_wrapper = copy.deepcopy(self.manifest); final_wrapper['files']['smallRdsReceipt']['sha256'] = 'f' * 64
        with self.assertRaises(gate.Rejected):
            gate.validate_qualification(original, final_wrapper, self.proof, self.raw_sha, gate.LARGE)

    def test_output_is_create_only_and_cli_redacts_bad_input(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'proof.json'; gate.write(output, self.qualify())
            with self.assertRaises(FileExistsError): gate.write(output, {'wrong': True})
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
            private = Path(directory) / 'bad.json'; private.write_text('PRIVATE_PASSWORD_SENTINEL')
            result = subprocess.run([sys.executable, '-B', gate.__file__, 'selected', '--operator', str(private)], capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertNotIn('PRIVATE_PASSWORD_SENTINEL', result.stdout + result.stderr)


class SelectionAndPlan(unittest.TestCase):
    def test_class_tag_is_only_on_db_and_keeps_configuration_create_policy_unchanged(self):
        from test_global_b_infrastructure import LAB, block, evaluate_sources, sid
        source = (LAB / 'modules/rds/main.tf').read_text()
        expressions = {}
        for name in ('aws_db_subnet_group', 'aws_db_parameter_group', 'aws_db_instance'):
            actual = block(source, 'resource', name, 'this')
            expressions[name] = re.findall(r'^  tags\s*=\s*(.+)$', actual, re.MULTILINE)
            self.assertEqual(1, len(expressions[name]))
        baseline = {'Project': 'airbob', 'Stack': 'lab', 'RunId': 'lab-class-fixture',
                    'FencingToken': '74', 'ExpiresAt': '1789366861', 'Service': 'rds'}
        cases = [baseline, baseline | {gate.TAG: gate.LARGE}]
        expr = 'jsonencode([for c in local.cases : {' + ','.join(
            name + '= (' + value[0].replace('var.tags', 'c') + ')' for name, value in expressions.items()) + '}])\n'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'main.tf').write_text('locals { cases = jsondecode(file("cases.json")) }\n')
            (root / 'cases.json').write_text(json.dumps(cases))
            (root / 'terraform.rc').write_text('disable_checkpoint = true\n')
            result = subprocess.run(['terraform', '-chdir=' + directory, 'console', '-no-color'], input=expr,
                capture_output=True, text=True, timeout=30, env={'PATH': os.environ['PATH'], 'CHECKPOINT_DISABLE': '1',
                    'TF_CLI_CONFIG_FILE': str(root / 'terraform.rc'), 'AWS_EC2_METADATA_DISABLED': 'true'})
            self.assertEqual(0, result.returncode, result.stderr)
            observed = json.loads(json.loads(result.stdout))
        allowed = sid(evaluate_sources()['provision'], 'CreateTaggedLabDbConfiguration')['Condition']['ForAllValues:StringEquals']['aws:TagKeys']
        self.assertNotIn(gate.TAG, allowed)
        for result in observed:
            for name in ('aws_db_subnet_group', 'aws_db_parameter_group'):
                self.assertEqual(baseline, result[name])
                self.assertLessEqual(set(result[name]), set(allowed))
        self.assertEqual(cases[0], observed[0]['aws_db_instance'])
        self.assertEqual(cases[1], observed[1]['aws_db_instance'])

    def test_initial_closed_selection_and_exact_retained_class(self):
        self.assertEqual(gate.DEFAULT, gate.initial_operation({}))
        self.assertEqual(gate.LARGE, gate.initial_operation({'rdsInstanceClass': gate.LARGE}))
        for operation in ({'rdsInstanceClass': 'db.m6i.xlarge'}, {'class': gate.LARGE}, {'classRehearsal': {}}):
            with self.assertRaises(gate.Rejected): gate.initial_operation(operation)
        original = {'globalBPrepareOnly': True, 'rdsEngineVersion': '8.4.11', 'rdsInstanceClass': gate.LARGE}
        self.assertEqual(gate.LARGE, gate.phase3_class(original, {'rds_instance_class': gate.LARGE, 'rds_configured_storage_gib': 100}))
        for phase3 in ({}, {'rds_instance_class': gate.DEFAULT}, {'rds_instance_class': gate.LARGE, 'rds_configured_storage_gib': 20}):
            with self.assertRaises(gate.Rejected): gate.phase3_class(original, phase3)
        with self.assertRaises(gate.Rejected): gate.original_class(original | {'globalBPrepareOnly': False})
        self.assertEqual(gate.DEFAULT, gate.original_class({}))

    def test_plan_allows_initial_selection_and_unrelated_updates_but_not_in_place_class_change(self):
        after = {'instance_class': gate.LARGE, 'engine_version': '8.4.11', 'allocated_storage': 100, 'storage_type': 'gp3',
            'multi_az': False, 'storage_encrypted': True, 'publicly_accessible': False, 'tags': {gate.TAG: gate.LARGE}}
        def plan(before, value=after, actions=None):
            return {'resource_changes': [{'type': 'aws_db_instance', 'change': {'before': before, 'after': value,
                'actions': actions or (['create'] if before is None else ['update'])}}]}
        gate.validate_plan(plan(None), gate.LARGE)
        import test_growth_b_search_snapshot as fixtures
        _, docs, _, _ = fixtures.fixture()
        provenance = fixtures.document(docs['provenance'])
        inherited = plan(None, after | {'snapshot_identifier': provenance['snapshot']['identifier'],
            'allocated_storage': None, 'storage_type': None})
        gate.validate_plan(inherited, gate.LARGE, provenance)
        with self.assertRaises(gate.Rejected): gate.validate_plan(inherited, gate.LARGE)
        for field, value in (('allocatedStorageGiB', 200), ('storageType', 'gp2'), ('identifier', 'airbob-dataset-b-foreign')):
            changed = copy.deepcopy(provenance); changed['snapshot'][field] = value
            with self.assertRaises(gate.Rejected): gate.validate_plan(inherited, gate.LARGE, changed)
        with self.assertRaises(gate.Rejected):
            gate.validate_plan(plan(None, after | {'snapshot_identifier': 'airbob-dataset-legacy',
                'allocated_storage': None, 'storage_type': None}), gate.LARGE)
        gate.validate_plan(plan(after, after | {'parameter_group_name': 'same-run-pg'}), gate.LARGE)
        for before, value, actions in ((after | {'instance_class': gate.DEFAULT}, after, None),
            (after, after | {'instance_class': gate.DEFAULT}, None), (after, None, ['delete']),
            (after, after | {'tags': {}}, None), (None, after | {'allocated_storage': 200}, None)):
            with self.assertRaises(gate.Rejected): gate.validate_plan(plan(before, value, actions), gate.LARGE)

    def test_source_and_target_native_contexts_preserve_original_selection(self):
        from test_growth_b_search_controller import operation, snapshot_operation, context, NOW, c
        for op in (operation(), snapshot_operation()):
            ctx = context(op)
            if op['kind'] == c.SNAPSHOT_KIND:
                ctx['operator'].update(globalBPrepareOnly=False, globalBSnapshotRestoreOnly=True, databaseBootstrap='snapshot')
                ctx['phase3']['database_bootstrap'] = 'snapshot'
            ctx['operator']['rdsInstanceClass'] = gate.LARGE
            ctx['phase3']['rds_instance_class'] = gate.LARGE
            if op['kind'] == c.SNAPSHOT_KIND:
                ctx['phase3']['rds_configured_storage_gib'] = None
                ctx['phase3']['rds_allocated_storage_gib'] = 100
            c.validate_context(ctx, op, now=NOW)
            ctx['phase3']['rds_instance_class'] = gate.DEFAULT
            with self.assertRaises(ValueError): c.validate_context(ctx, op, now=NOW)

    def test_native_host_checks_actual_and_pending_class_without_changing_the_engine(self):
        import growth_b_search_host as native
        expected = {'identifier': 'airbob-lab-native', 'resourceId': 'db-' + 'A' * 26,
            'endpoint': 'example.ap-northeast-2.rds.amazonaws.com', 'masterSecretArn': 'arn:fixture',
            'createdAt': '2026-01-01T00:00:00Z'}
        actual = {'DBInstanceIdentifier': expected['identifier'], 'DbiResourceId': expected['resourceId'],
            'Endpoint': {'Address': expected['endpoint'], 'Port': 3306}, 'MasterUserSecret': {'SecretArn': expected['masterSecretArn']},
            'InstanceCreateTime': expected['createdAt'], 'Engine': 'mysql', 'EngineVersion': '8.4.11',
            'DBInstanceStatus': 'available', 'PubliclyAccessible': False, 'AllocatedStorage': 100, 'MultiAZ': False,
            'DBInstanceClass': gate.LARGE, 'TagList': [{'Key': gate.TAG, 'Value': gate.LARGE}]}
        aws = Mock(); aws.call.return_value = {'DBInstances': [actual]}
        context = {'rds': expected, 'rdsInstanceClass': gate.LARGE}
        self.assertEqual(actual, native.live_rds(context, aws))
        for patch_value in ({'DBInstanceClass': gate.DEFAULT}, {'PendingModifiedValues': {'DBInstanceClass': gate.DEFAULT}},
                            {'TagList': []}):
            aws.call.return_value = {'DBInstances': [actual | patch_value]}
            with self.assertRaises(ValueError): native.live_rds(context, aws)

    def test_snapshot_controller_and_host_keep_the_initial_class_outside_frozen_rds_config(self):
        import test_growth_b_snapshot_controller as fixtures
        fixture = fixtures.ControllerFixture(); fixture.setUp(); self.addCleanup(fixture.doCleanups)
        context = fixture.context | {'rdsInstanceClass': gate.LARGE, 'resourceFence': fixture.fence}
        actual = fixture.store.reads[('rds', 'describe-db-instances')]['DBInstances'][0]
        actual.update(DBInstanceClass=gate.LARGE, TagList=actual['TagList'] + [{'Key': gate.TAG, 'Value': gate.LARGE}])
        fixtures.host.validate_context(context, fixture.manifest, fixture.manifest_sha)
        self.assertEqual({'identifier', 'resourceId', 'endpoint', 'masterSecretArn'}, set(context['rds']))
        self.assertEqual(fixture.role, fixtures.controller.validate_target(fixture.store, context, fixture.manifest, fixture.fence))
        for changes in ({'rdsInstanceClass': gate.DEFAULT}, {'resourceFence': fixture.fence + 1}):
            with self.assertRaises(ValueError):
                fixtures.controller.validate_target(fixture.store, context | changes, fixture.manifest, fixture.fence)


class OperatorClass(unittest.TestCase):
    def run_shell(self, body, *, selected=gate.LARGE, original=None, **extra):
        scripts = Path(gate.__file__).parent
        source = (scripts / 'aws-lab.sh').read_text()
        functions = 'load_retained_rds_class() {' + source.split('load_retained_rds_class() {', 1)[1].split('validate_snapshot_bootstrap_inputs()', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'operator.json').write_text(json.dumps(original or {'globalBPrepareOnly': True,
                'rdsEngineVersion': '8.4.11', 'rdsInstanceClass': gate.LARGE}))
            script = '''set -euo pipefail
fail() { printf '%s\\n' "$1" >&2; exit 1; }
aws() { printf AWS_CALL >> "$temp_dir/calls"; return 99; }
'''
            env = {'PATH': os.environ['PATH'], 'script_dir': str(scripts), 'temp_dir': directory, 'rds_instance_class': selected} | extra
            result = subprocess.run(['bash', '-c', script + functions + body], capture_output=True, text=True, env=env, timeout=10)
            return result, (root / 'calls').read_text() if (root / 'calls').exists() else ''

    def test_large_final_missing_qualification_stops_before_any_remote_io(self):
        result, calls = self.run_shell('validate_b_class_rehearsal dump; printf MUTATION')
        self.assertNotEqual(0, result.returncode); self.assertEqual('', calls)
        self.assertNotIn('MUTATION', result.stdout)
        result, calls = self.run_shell('validate_b_class_rehearsal dump; printf DEFAULT', selected=gate.DEFAULT)
        self.assertEqual(0, result.returncode); self.assertEqual('DEFAULT', result.stdout); self.assertEqual('', calls)

    def test_retained_class_is_inherited_and_cannot_be_overridden(self):
        command = 'load_retained_rds_class "$temp_dir/operator.json"; printf "%s" "$rds_instance_class"'
        result, calls = self.run_shell(command)
        self.assertEqual(0, result.returncode, result.stderr); self.assertEqual(gate.LARGE, result.stdout); self.assertEqual('', calls)
        for env in ({'B_RDS_INSTANCE_CLASS': gate.DEFAULT}, {'B_RDS_CLASS_REHEARSAL_JSON': '{}'}):
            result, calls = self.run_shell(command, **env)
            self.assertNotEqual(0, result.returncode); self.assertEqual('', calls)
        result, calls = self.run_shell(command, original={'globalBPrepareOnly': True, 'rdsEngineVersion': '8.4.11'})
        self.assertEqual(0, result.returncode); self.assertEqual(gate.DEFAULT, result.stdout)


if __name__ == '__main__':
    unittest.main()
