"""Actual snapshot-service shell/workflow gates with closed cloud boundaries.

Only the remote verifier run is replaced. Operation/context/completion validation,
the retained-run shell predicates and exact-version publication execute as shipped.
"""
import base64
import copy
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parent))
import test_growth_b_cdc_operator as reusable

ROOT, SCRIPTS = reusable.ROOT, reusable.SCRIPTS
VERIFIER = SCRIPTS / 'growth_b_snapshot_service_verify.py'
PUBLIC_NAMES = ('snapshot-target-service-verification', 'representative-http-reads', 'warmup-receipt',
                'media-availability', 'host-runtime-qualification')
raw, digest, write, function = reusable.raw, reusable.digest, reusable.write, reusable.function


def boundary(tool, args):
    if tool != 'python3' or not args or Path(args[0]) != VERIFIER:
        return reusable.boundary(tool, args)
    root = Path(os.environ['CDC_TEST_ROOT'])
    settings = json.loads((root / 'settings.json').read_bytes())
    with (root / 'calls.jsonl').open('a') as stream:
        stream.write(json.dumps({'tool': tool, 'args': args}) + '\n')
    import growth_b_snapshot_service_verify as verifier
    options = dict(zip(args[2::2], args[3::2]))
    operation = verifier.service.read(options['--operation'])
    context = verifier.service.read(options['--context'])
    verifier.validate_operation(operation)
    delta = int((root / 'clock-delta').read_text()) if (root / 'clock-delta').exists() else 0
    verifier.validate_context(context, operation, clock=lambda: settings['now'] + delta)
    if args[1] == 'validate-completion':
        # Real CLI/parser and offline completion validator, never a proof boolean.
        result = subprocess.run([sys.executable, *args], capture_output=True)
        sys.stdout.buffer.write(result.stdout); sys.stderr.buffer.write(result.stderr)
        return result.returncode
    assert args[1] == 'run', 'undeclared snapshot verifier action'
    directory = Path(options['--output']); directory.mkdir(parents=True, mode=0o700)
    write(root / 'verifier-context.json', context)
    write(root / 'verifier-operation.json', operation)
    class NoAws:
        def call(self, *args):
            raise AssertionError('The wrapper fixture must never dispatch the remote verifier: ' + str(args[:2]))
    # A real initial journal/checkpoint gives failed-run publication the actual
    # recovery schema. No remote run, lease read or SSM submission is performed.
    recovery = None
    if 'resume' in operation:
        body = base64.b64decode(settings['resumeRecovery'])
        ref = operation['resume']['recovery']
        assert len(body) == ref['bytes'] and digest(body) == ref['sha256']
        recovery = verifier.core.parse(body)
    runner = verifier.Runner(operation, context, directory, aws=NoAws(), recovery=recovery, clock=lambda: settings['now'] + delta)
    runner.journal.close()
    for name, content in settings['verifierFiles'].items():
        path = directory / name
        assert path.is_relative_to(directory) and '..' not in Path(name).parts
        write(path, base64.b64decode(content))
    return settings.get('verifierStatus', 37)


class SnapshotOperatorFixture(reusable.CdcOperatorFixture):
    def setUp(self):
        super().setUp()
        import growth_b_snapshot_service_verify as verifier
        self.verifier = verifier
        self.original.update(globalBSnapshotRestoreOnly=True, globalBPrepareOnly=False, databaseBootstrap='snapshot')
        self.phase3['rds_configured_storage_gib'] = None
        selected_sha = 'd' * 64
        preparation_id = 'target-prepare-01'
        self.operation = {key: copy.deepcopy(value) for key, value in self.operation.items()}
        self.operation.update(kind=verifier.KIND, stage='snapshot-verify', operationId='target-service-01',
            sourceArchiveSha256=verifier.source_archive()[1]['sha256'],
            targetPreparation={
                'manifest': {'key': f'datasets/{self.dataset}-aws-snapshots/operations/{self.run}/{preparation_id}/manifest-{selected_sha}.json',
                             'versionId': 'target-manifest-v1', 'sha256': selected_sha, 'bytes': 1000},
                'hostReceipt': {'key': f'data-bootstrap/{self.run}/{self.dataset}-snapshot/{preparation_id}/host-receipt.json',
                                'versionId': 'target-host-v1', 'sha256': 'e' * 64, 'bytes': 1000}})
        verifier.validate_operation(self.operation)
        self.settings['verifierFiles'] = {'.private/never-public.txt': base64.b64encode(b'OWNED_PRIVATE_TEST_SENTINEL').decode()}
        self.settings['verifierStatus'] = 37
        for tool in ('aws', 'git', 'date', 'terraform', 'python3'):
            file = self.fake_bin / tool
            file.write_text('#!/bin/sh\nexec ' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(Path(__file__).resolve())) +
                            ' --boundary ' + shlex.quote(tool) + ' "$@"\n')
            file.chmod(0o700)
        names = ('valid_run_id', 'require_global_b_execution_deadline', 'validate_retained_global_b_execution_deadline',
                 'load_retained_rds_class', 'verify_retained_rds_class',
                 'validate_operator_scope_for_action', 'read_run_manifest', 'publish_immutable_json', 'sha256_file',
                 'sha256_text', 'write_current_lease_file', 'assert_b_source_not_retired', 'publish_global_b_cdc_json',
                 'continue_global_b_snapshot_service', 'continue_global_b_services')
        self.functions = '\n\n'.join(function(self.source, name) for name in names)
        self.public = self.evidence / (self.run + '-' + self.operation['operationId'] + '-' + str(self.lease['fencingToken'])) / 'verifier/public'

    def execute(self, env=None):
        store = {}
        for key, version, value in [(f'runs/{self.run}/operator.json', 'operator-v1', self.original),
                                   (self.operation['manifest']['key'], self.operation['manifest']['versionId'], self.manifest),
                                   (self.operation['readiness']['key'], self.operation['readiness']['versionId'], self.readiness)]:
            store[key] = {'version': version, 'body': base64.b64encode(raw(value)).decode()}
        write(self.root / 'store.json', store); write(self.root / 'settings.json', self.settings)
        values = {'script_dir': str(SCRIPTS), 'repo_root': str(ROOT), 'lab_root': str(ROOT / 'infra/aws/lab'),
            'temp_dir': str(self.temp), 'evidence_bucket': 'airbob-performance-lab-data-bootstrap-942632789808',
            'RUN_ID': self.run, 'DATASET_RELEASE': self.dataset, 'B_SNAPSHOT_SERVICE_OPERATION_JSON': json.dumps(self.operation),
            'B_SERVICE_STAGE': 'snapshot-verify', 'SNAPSHOT_SERVICE_EVIDENCE_DIR': str(self.evidence),
            'approved_execution_deadline_epoch': str(self.approved), 'AIRBOB_WORKFLOW_DEADLINE_EPOCH': str(self.now + 21000),
            'AWS_REGION': 'ap-northeast-2', 'COMMAND_DEADLINE_SECONDS': '18000', 'LEASE_DEADLINE_SECONDS': '20700',
            'action': 'up', 'operator_scope': 'direct', 'lease_table': self.lease['table'],
            'lease_lock_id': self.lease['lockName'], 'lease_owner': self.lease['owner'], 'lease_command': 'up',
            'fencing_token': str(self.lease['fencingToken']), 'GLOBAL_LEASE_FAIL': 'false', 'BACKEND_DELAY_SECONDS': '0',
            'global_b_service_release': 'untrusted-input', 'cache_enabled': 'true', 'bundle_commit': 'untrusted-input'}
        values.update(global_b_prepare_only='false', global_b_snapshot_restore_only='false', global_b_services='true',
                      global_b_snapshot_operation='', rds_instance_class='db.t3.small', rds_class_operator_file='')
        values.update(env or {})
        setup = 'set -euo pipefail\numask 077\n' + '\n'.join(key + '=' + shlex.quote(value) for key, value in values.items())
        stubs = r'''
fail() { printf '%s\n' "$1" >&2; exit 2; }
canonical_operator_tree_sha256() { printf '%064d\n' 1; }
record() { printf '%s\n' "$*" >> "$CDC_TEST_ROOT/shell-calls"; }
validate_workflow_deadline_budget() { record workflow-budget; }
validate_up_credential_budget() { record credential-budget; }
start_mutation_guard() { record lease-start; }
prepare_lab_backend() { record backend-read; printf '%s' "$BACKEND_DELAY_SECONDS" > "$CDC_TEST_ROOT/clock-delta"; }
recover_prior_terraform_lock() { record native-lock-read; }
assert_state_run_identity() { record state-identity; [[ "$1" == required ]]; }
run_terraform_command() { shift; terraform "$@"; }
run_supervised_mutation() { record supervised-run; shift; "$@"; }
assert_lease() { record lease-assert; [[ "$GLOBAL_LEASE_FAIL" == false ]]; }
write_tfvars() { fail FORBIDDEN_NEW_TFVARS; }
apply_lab() { fail FORBIDDEN_TERRAFORM_APPLY; }
destroy_lab() { fail FORBIDDEN_TERRAFORM_DESTROY; }
continue_global_b_cdc() { fail FORBIDDEN_SOURCE_CDC; }
'''
        footer = '\ncontinue_global_b_services\nprintf "%s\\n" "$run_id" "$bundle_commit" "$cache_enabled" "$expires_at" "$resource_fencing_token" > "$CDC_TEST_ROOT/final-variables"\n'
        environment = {'PATH': str(self.fake_bin) + ':' + os.environ['PATH'], 'CDC_TEST_ROOT': str(self.root),
                       'PYTHONPATH': str(SCRIPTS), 'AWS_EC2_METADATA_DISABLED': 'true'}
        result = subprocess.run(['bash', '-c', setup + '\n' + stubs + self.functions + footer], cwd=ROOT,
                                env=environment, capture_output=True, text=True, timeout=25)
        self.calls = [json.loads(line) for line in (self.root / 'calls.jsonl').read_text().splitlines()] if (self.root / 'calls.jsonl').exists() else []
        self.result = result
        return result

    def assert_failed(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('snapshot_target_service_verified=true', result.stdout)

    def assert_before_remote_run(self, result):
        self.assert_failed(result)
        self.assertFalse((self.root / 'verifier-context.json').exists())


class SnapshotAdmissionTest(SnapshotOperatorFixture):
    def test_large_target_uses_actual_allocated_storage_and_current_class_guard(self):
        self.select_large_class()
        result = self.execute(); self.assertEqual(37, result.returncode, result.stderr)
        context = json.loads((self.root / 'verifier-context.json').read_bytes())
        self.assertEqual('db.m6i.large', context['operator']['rdsInstanceClass'])
        self.assertIsNone(context['phase3']['rds_configured_storage_gib'])
        self.assertEqual(100, context['phase3']['rds_allocated_storage_gib'])
        self.assertTrue(any(call['tool'] == 'python3' and Path(call['args'][0]) == SCRIPTS / 'growth_b_rds_class.py'
                            and call['args'][1] == 'live' for call in self.calls))

    def test_snapshot_target_pending_class_drift_closes_before_read_worker(self):
        self.select_large_class()
        self.settings['rds']['PendingModifiedValues'] = {'DBInstanceClass': 'db.t3.small'}
        result = self.execute(); self.assert_before_remote_run(result)
        self.assertIn('Live RDS class, immutable selection', result.stderr)
        self.assertFalse(any(call['tool'] == 'aws' and call['args'][:2] == ['s3api', 'put-object'] for call in self.calls))

    def test_retained_snapshot_context_is_exact_and_remote_unknown_remains_failure(self):
        result = self.execute(); self.assertEqual(37, result.returncode, result.stderr)
        context = json.loads((self.root / 'verifier-context.json').read_bytes())
        self.assertEqual(set(context), set('schemaVersion kind executionCommit approvedExecutionDeadlineEpoch controllerDeadlineEpoch operator lease phase2 phase3 phase4 serviceState'.split()))
        self.assertEqual(context['kind'], self.verifier.CONTEXT_KIND)
        for key, expected in (('operator', self.original), ('lease', self.lease), ('phase2', self.phase2),
                              ('phase3', self.phase3), ('phase4', self.phase4), ('serviceState', self.service_state)):
            self.assertEqual(context[key], expected)
        self.assertEqual(context['controllerDeadlineEpoch'], self.now + 17940)
        self.assertEqual([call['args'][-1] for call in self.calls if call['tool'] == 'terraform'],
                         ['phase2_contract', 'phase3_contract', 'phase4_contract', 'global_b_service'])
        self.assertEqual(1, sum(call['tool'] == 'aws' and call['args'][:2] == ['rds', 'describe-db-instances'] for call in self.calls))
        self.assertFalse(any(call['tool'] == 'aws' and call['args'][0] not in ('s3api', 'rds') for call in self.calls))
        self.assertFalse(any(call['tool'] == 'python3' and 'growth_b_cdc_supervisor.py' in call['args'][0] for call in self.calls))

    def test_dump_source_cannot_use_snapshot_verifier(self):
        self.original.update(globalBSnapshotRestoreOnly=False, globalBPrepareOnly=True, databaseBootstrap='dump')
        self.assert_before_remote_run(self.execute())
        self.assertFalse(any(call['tool'] == 'terraform' for call in self.calls))

    def test_mixed_dump_and_snapshot_claim_is_rejected_by_actual_context_validator(self):
        self.original['globalBPrepareOnly'] = True
        self.assert_before_remote_run(self.execute())

    def test_cache_enabled_original_is_rejected(self):
        self.original['cacheEnabled'] = True
        self.assert_before_remote_run(self.execute())

    def test_requested_dataset_and_retained_run_cannot_change(self):
        self.assert_before_remote_run(self.execute({'DATASET_RELEASE': 'global-growth-b-' + 'f' * 16}))

    def test_original_run_identity_cannot_be_relabelled(self):
        self.original['runId'] = 'lab-foreign'
        self.assert_before_remote_run(self.execute())

    def test_original_approved_deadline_cannot_be_extended(self):
        self.assert_before_remote_run(self.execute({'approved_execution_deadline_epoch': str(self.approved + 1)}))

    def test_short_original_resource_expiry_cannot_be_refreshed(self):
        self.original['expiresAt'] = self.now + 20000
        self.assert_before_remote_run(self.execute())

    def test_unknown_input_and_changed_sources_are_rejected_before_any_aws(self):
        self.operation['unreviewedOverride'] = True
        self.assert_before_remote_run(self.execute())
        self.assertFalse(any(call['tool'] == 'aws' for call in self.calls))

    def test_reviewed_source_archive_is_required_before_lease(self):
        self.operation['sourceArchiveSha256'] = '0' * 64
        self.assert_before_remote_run(self.execute())
        self.assertFalse((self.root / 'shell-calls').exists())

    def test_dirty_or_different_execution_commit_is_rejected(self):
        self.settings['head'] = 'd' * 40
        self.assert_before_remote_run(self.execute())

    def test_current_tf_capacity_and_selected_readiness_cannot_drift(self):
        self.phase4['capacity']['desired'] = 2
        self.assert_before_remote_run(self.execute())

    def test_selected_exact_readiness_version_must_match_current_state(self):
        self.service_state['readiness_receipt']['version_id'] = 'foreign-v2'
        self.assert_before_remote_run(self.execute())

    def test_current_lease_command_cannot_be_down(self):
        self.assert_before_remote_run(self.execute({'lease_command': 'down'}))

    def test_backend_delay_consumes_the_already_started_watchdog_budget(self):
        result = self.execute({'BACKEND_DELAY_SECONDS': '300'})
        self.assertEqual(37, result.returncode, result.stderr)
        context = json.loads((self.root / 'verifier-context.json').read_bytes())
        self.assertEqual(context['controllerDeadlineEpoch'], self.now + 17940)
        calls = (self.root / 'shell-calls').read_text().splitlines()
        self.assertLess(calls.index('lease-start'), calls.index('backend-read'))


class SnapshotPublicationTest(SnapshotOperatorFixture):
    def test_zero_exit_and_success_looking_files_require_the_real_completion_validator(self):
        self.settings['verifierStatus'] = 0
        for name in PUBLIC_NAMES:
            self.settings['verifierFiles']['public/' + name + '.json'] = base64.b64encode(raw({
                'schemaVersion': 1, 'kind': self.verifier.RECEIPT_KIND, 'state': self.verifier.COMPLETE, 'passed': True})).decode()
        result = self.execute(); self.assert_failed(result)
        self.assertTrue(any(call['tool'] == 'python3' and call['args'][1] == 'validate-completion' for call in self.calls))
        self.assertIn('Snapshot target service result or current input bindings differ', result.stderr)
        self.assertFalse((self.public / 'snapshot-target-service-verification-reference.json').exists())
        self.assertTrue((self.public / 'recovery-reference.json').exists())

    def test_published_failure_can_resume_under_new_lease_without_changing_original_run_or_deadline(self):
        first = self.execute(); self.assertEqual(37, first.returncode, first.stderr)
        original_context = json.loads((self.root / 'verifier-context.json').read_bytes())
        body = (self.public / 'recovery.json').read_bytes()
        ref = json.loads((self.public / 'recovery-reference.json').read_bytes())
        self.operation['resume'] = {'recovery': ref}
        self.settings['resumeRecovery'] = base64.b64encode(body).decode()
        self.lease = self.lease | {'owner': 'snapshot-resume-controller', 'fencingToken': self.lease['fencingToken'] + 1}
        self.public = self.evidence / (self.run + '-' + self.operation['operationId'] + '-' + str(self.lease['fencingToken'])) / 'verifier/public'
        second = self.execute(); self.assertEqual(37, second.returncode, second.stderr)
        resumed = json.loads((self.root / 'verifier-context.json').read_bytes())
        forwarded = json.loads((self.root / 'verifier-operation.json').read_bytes())
        saved = json.loads((self.public / 'recovery.json').read_bytes())
        self.assertEqual(forwarded['resume']['recovery'], ref)
        self.assertEqual(resumed['lease'], self.lease)
        self.assertNotEqual(resumed['lease'], original_context['lease'])
        self.assertEqual(saved['originalContext'], original_context)
        self.assertEqual(resumed['operator'], original_context['operator'])
        self.assertEqual(resumed['controllerDeadlineEpoch'], original_context['controllerDeadlineEpoch'])
        self.assertEqual(saved['job'], None)
        self.assertFalse(any('validate-completion' in call['args'] for call in self.calls))

    def test_failed_remote_run_cannot_be_overridden_by_success_looking_files(self):
        self.settings['verifierStatus'] = 1
        for name in PUBLIC_NAMES:
            self.settings['verifierFiles']['public/' + name + '.json'] = base64.b64encode(raw({'passed': True})).decode()
        result = self.execute(); self.assertEqual(1, result.returncode, result.stderr)
        self.assert_failed(result)
        self.assertFalse(any('validate-completion' in call['args'] for call in self.calls))
        self.assertFalse((self.public / 'snapshot-target-service-verification-reference.json').exists())

    def test_unknown_run_publishes_only_exact_version_public_recovery(self):
        self.settings['verifierStatus'] = 124
        result = self.execute(); self.assertEqual(124, result.returncode, result.stderr)
        self.assert_failed(result)
        body = (self.public / 'recovery.json').read_bytes()
        ref = json.loads((self.public / 'recovery-reference.json').read_bytes())
        self.assertEqual(ref, {'key': self.verifier.prefix(self.operation) + 'recovery-' + digest(body) + '.json',
                              'versionId': 'created-v1', 'sha256': digest(body), 'bytes': len(body)})
        self.verifier.validate_recovery(json.loads(body), self.operation | {'resume': {'recovery': ref}})
        uploads = [call for call in self.calls if call['tool'] == 'aws' and call['args'][1] == 'put-object']
        self.assertEqual(1, len(uploads))
        self.assertTrue(all('/public/' in call['args'][call['args'].index('--body') + 1] for call in uploads))
        self.assertFalse(any('validate-completion' in call['args'] for call in self.calls))
        self.assertNotIn('OWNED_PRIVATE_TEST_SENTINEL', result.stdout + result.stderr)

    def test_wrong_exact_version_recovery_bytes_have_no_published_reference(self):
        self.settings['wrongVersionBytes'] = True
        self.assert_failed(self.execute())
        self.assertFalse((self.public / 'recovery-reference.json').exists())

    def test_wrong_exact_version_response_has_no_published_reference(self):
        self.settings['responseVersion'] = 'foreign-v2'
        self.assert_failed(self.execute())
        self.assertFalse((self.public / 'recovery-reference.json').exists())

    def test_missing_version_or_failed_exact_get_is_not_success(self):
        self.settings['headVersion'] = 'None'
        self.assert_failed(self.execute())
        self.assertFalse((self.public / 'recovery-reference.json').exists())


class SnapshotWorkflowTest(SnapshotOperatorFixture):
    def workflow(self, operation=None, **overrides):
        from test_growth_b_workflow import WorkflowAdmission, COMMIT
        WorkflowAdmission.setUpClass()
        selected = copy.deepcopy(self.operation if operation is None else operation)
        selected['executionCommit'] = COMMIT
        return WorkflowAdmission().run_gate('services', selected, deadline=str(self.approved), **overrides)

    def test_actual_pre_oidc_branch_exports_only_the_explicit_snapshot_inputs(self):
        status, env = self.workflow(bootstrap='snapshot')
        self.assertEqual(0, status)
        exported = dict(line.split('=', 1) for line in env.splitlines())
        self.assertEqual(set(exported), {'B_SERVICE_RELEASE', 'B_SERVICE_STAGE', 'B_SNAPSHOT_SERVICE_OPERATION_JSON'})
        self.assertEqual(exported['B_SERVICE_STAGE'], 'snapshot-verify')
        selected = json.loads(exported['B_SNAPSHOT_SERVICE_OPERATION_JSON'])
        self.assertEqual(selected['runId'], self.run); self.assertEqual(selected['datasetId'], self.dataset)
        self.assertEqual(selected['targetPreparation'], self.operation['targetPreparation'])

    def test_pre_oidc_export_enters_the_actual_snapshot_dispatch_branch(self):
        from test_growth_b_workflow import COMMIT
        status, env = self.workflow(bootstrap='snapshot')
        self.assertEqual(0, status)
        exported = dict(line.split('=', 1) for line in env.splitlines())
        self.settings['head'] = COMMIT
        result = self.execute(exported)
        self.assertEqual(37, result.returncode, result.stderr)
        context = json.loads((self.root / 'verifier-context.json').read_bytes())
        selected = json.loads((self.root / 'verifier-operation.json').read_bytes())
        self.assertEqual(context['executionCommit'], COMMIT)
        self.assertEqual(selected, json.loads(exported['B_SNAPSHOT_SERVICE_OPERATION_JSON']))
        self.assertEqual(context['operator'], self.original)

    def test_workflow_retains_existing_run_and_uploads_only_public_result_paths(self):
        from test_growth_b_workflow import WORKFLOW
        source = WORKFLOW.read_text()
        self.assertLess(source.index('Verify reviewed main commit'), source.index('Configure short-lived AWS credentials'))
        self.assertNotIn("inputs.action == 'services'", next(line for line in source.splitlines() if 'RUN_ID: ${{' in line))
        artifact = source.split('      - name: Preserve closed snapshot target service evidence\n', 1)[1].split('      - name:', 1)[0]
        self.assertIn("if: always()", artifact)
        self.assertIn("env.B_SERVICE_STAGE == 'snapshot-verify'", artifact)
        self.assertIn('path: ${{ runner.temp }}/airbob-b-snapshot-service/**/public/**', artifact)
        self.assertNotIn('.private', artifact)

    def test_dump_bootstrap_is_rejected_before_oidc(self):
        status, env = self.workflow(bootstrap='dump')
        self.assertNotEqual(0, status); self.assertEqual('', env)

    def test_unversioned_target_preparation_is_rejected_before_oidc(self):
        selected = copy.deepcopy(self.operation)
        selected['targetPreparation']['hostReceipt']['versionId'] = 'null'
        status, env = self.workflow(selected, bootstrap='snapshot')
        self.assertNotEqual(0, status); self.assertEqual('', env)

    def test_unknown_workflow_operation_fields_cannot_reach_oidc(self):
        status, env = self.workflow(self.operation | {'unreviewed': True}, bootstrap='snapshot')
        self.assertNotEqual(0, status); self.assertEqual('', env)


class SnapshotCompletionTest(SnapshotOperatorFixture):
    def setUp(self):
        super().setUp()
        from test_growth_b_snapshot_service_verify import completion_fixture
        self.completed = completion_fixture(self.root / 'actual-producer', now=self.now)
        self.operation = self.completed['operation']
        self.expected_context = self.completed['context']
        context = self.expected_context
        self.original, self.lease = context['operator'], context['lease']
        self.phase2, self.phase3, self.phase4 = (context[k] for k in ('phase2', 'phase3', 'phase4'))
        self.service_state = context['serviceState']
        self.run, self.dataset = self.operation['runId'], self.operation['datasetId']
        self.expiry, self.approved = int(self.original['expiresAt']), context['approvedExecutionDeadlineEpoch']
        self.manifest = self.verifier.unpack_document(self.completed['documents']['serviceManifest'])[0]
        self.readiness = self.verifier.unpack_document(self.completed['documents']['readiness'])[0]
        self.settings.update(head=self.operation['executionCommit'], selectedService=self.manifest, selectedReadiness=self.readiness,
            rds=reusable.rds_fixture(self.original, self.phase3, self.now),
            terraform={'phase2_contract': self.phase2, 'phase3_contract': self.phase3,
                       'phase4_contract': self.phase4, 'global_b_service': self.service_state}, verifierStatus=0)
        for path in (self.completed['output'] / 'public').iterdir():
            self.assertTrue(path.is_file() and not path.is_symlink())
            self.settings['verifierFiles']['public/' + path.name] = base64.b64encode(path.read_bytes()).decode()
        self.public = self.evidence / (self.run + '-' + self.operation['operationId'] + '-' + str(self.lease['fencingToken'])) / 'verifier/public'

    def change_public(self, name, update):
        key = 'public/' + name + '.json'
        value = json.loads(base64.b64decode(self.settings['verifierFiles'][key]))
        update(value)
        self.settings['verifierFiles'][key] = base64.b64encode(self.verifier.encoded(value)).decode()

    def assert_completion_rejected(self, result):
        self.assert_failed(result)
        self.assertTrue(any(call['tool'] == 'python3' and call['args'][1] == 'validate-completion' for call in self.calls),
                        'The expected final validation boundary was not reached: ' + result.stderr)
        self.assertFalse((self.public / 'snapshot-target-service-verification-reference.json').exists())

    def test_actual_successful_producer_requires_completion_and_publishes_five_exact_public_versions(self):
        result = self.execute(); self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertIn('snapshot_target_service_verified=true', result.stdout)
        self.assertIn('source_r4_or_cdc_mutation_executed=false', result.stdout)
        self.assertEqual(json.loads((self.root / 'verifier-context.json').read_bytes()), self.expected_context)
        self.assertEqual((self.root / 'final-variables').read_text().splitlines(),
            [self.run, self.original['bundleCommit'], 'false', str(self.expiry), str(self.original['fencingToken'])])
        completed = [call for call in self.calls if call['tool'] == 'python3' and call['args'][1] == 'validate-completion']
        self.assertEqual(len(completed), 1)
        store = json.loads((self.root / 'store.json').read_bytes())
        for name in (*PUBLIC_NAMES, 'recovery'):
            body = (self.public / (name + '.json')).read_bytes()
            self.assertEqual(body, (self.completed['output'] / 'public' / (name + '.json')).read_bytes())
            ref = json.loads((self.public / (name + '-reference.json')).read_bytes())
            self.assertEqual(ref['sha256'], digest(body)); self.assertEqual(ref['bytes'], len(body))
            self.assertEqual(ref['versionId'], 'created-v1')
            self.assertEqual(base64.b64decode(store[ref['key']]['body']), body)
            self.assertEqual(ref['key'], self.verifier.prefix(self.operation) + name + '-' + digest(body) + '.json')
            self.assertTrue(any(call['tool'] == 'aws' and '--version-id' in call['args'] and ref['key'] in call['args'] for call in self.calls))
        uploads = [call['args'][call['args'].index('--body') + 1] for call in self.calls
                   if call['tool'] == 'aws' and call['args'][1] == 'put-object']
        self.assertEqual({Path(path).name for path in uploads}, {name + '.json' for name in (*PUBLIC_NAMES, 'recovery')})
        self.assertTrue(all('/public/' in path for path in uploads))
        self.assertNotIn('OWNED_PRIVATE_TEST_SENTINEL', result.stdout + result.stderr)

    def test_success_state_without_actual_normal_login_gate_is_rejected(self):
        self.change_public('snapshot-target-service-verification', lambda value: value['service'].update(normalLoginAndOwnershipPassed=False))
        self.assert_completion_rejected(self.execute())

    def test_changed_public_warmup_bytes_are_rejected(self):
        self.change_public('warmup-receipt', lambda value: value.update(unboundChangedContent=True))
        self.assert_completion_rejected(self.execute())

    def test_missing_media_result_is_rejected(self):
        del self.settings['verifierFiles']['public/media-availability.json']
        self.assert_completion_rejected(self.execute())

    def test_current_tf_rds_resource_must_match_actual_snapshot_preparation(self):
        self.phase3['rds_resource_id'] = 'db-' + 'X' * 24
        result = self.execute(); self.assert_before_remote_run(result)
        self.assertIn('Live RDS class, immutable selection', result.stderr)

    def test_foreign_completed_target_uuid_is_rejected(self):
        self.change_public('snapshot-target-service-verification', lambda value: value['targetIdentity'].update(
            serverUuid='11111111-2222-3333-4444-555555555555'))
        self.assert_completion_rejected(self.execute())

    def test_completion_cannot_claim_another_lease(self):
        self.change_public('snapshot-target-service-verification', lambda value: value['completionLease'].update(fencingToken=999))
        self.assert_completion_rejected(self.execute())

    def test_changed_transitive_snapshot_baseline_bytes_are_rejected(self):
        def changed(value):
            blob = value['documents']['snapshotOperation']
            proof = json.loads(base64.b64decode(blob['base64']))
            proof['snapshotWholeBaselineVerified'] = False
            blob['base64'] = base64.b64encode(self.verifier.encoded(proof)).decode()
        self.change_public('recovery', changed)
        self.assert_completion_rejected(self.execute())

    def test_lost_live_lease_blocks_public_success_after_valid_completion(self):
        self.assert_completion_rejected(self.execute({'GLOBAL_LEASE_FAIL': 'true'}))
        self.assertTrue((self.public / 'recovery-reference.json').exists())

    def test_foreign_original_app_commit_cannot_use_otherwise_valid_target_service_evidence(self):
        self.original['bundleCommit'] = 'f' * 40
        with self.assertRaises(self.verifier.Failed):
            self.verifier.validate_proofs(self.operation, self.expected_context, self.completed['documents'])
        self.assert_completion_rejected(self.execute())

    def test_foreign_original_app_image_cannot_use_otherwise_valid_target_service_evidence(self):
        self.original['appImageReference'] = '942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-repo@sha256:' + 'f' * 64
        with self.assertRaises(self.verifier.Failed):
            self.verifier.validate_proofs(self.operation, self.expected_context, self.completed['documents'])
        self.assert_completion_rejected(self.execute())


if __name__ == '__main__':
    if len(sys.argv) >= 3 and sys.argv[1] == '--boundary':
        raise SystemExit(boundary(sys.argv[2], sys.argv[3:]))
    unittest.main()
