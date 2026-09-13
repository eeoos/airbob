"""Execute the actual CDC shell wrapper with offline cloud/process boundaries.

The source/context validators, jq predicates and immutable publication helpers
are real. Git, Terraform, AWS and the remote supervisor run are closed fakes.
"""
import base64
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[3]
SCRIPTS = ROOT / 'infra/aws/scripts'
SOURCE = SCRIPTS / 'aws-lab.sh'
sys.path.insert(0, str(SCRIPTS))
sys.path.insert(0, str(Path(__file__).parent))


def raw(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def digest(value):
    return hashlib.sha256(value).hexdigest()


def write(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(value if isinstance(value, bytes) else raw(value)); path.chmod(0o600)


def function(source, name):
    start = source.index(name + '() {\n')
    end = source.index('\n}\n', start) + 3
    return source[start:end]


def boundary(tool, args):
    """One local executable shim; every unsupported external action is fatal."""
    root = Path(os.environ['CDC_TEST_ROOT']); settings = json.loads((root / 'settings.json').read_bytes())
    with (root / 'calls.jsonl').open('a') as stream:
        stream.write(json.dumps({'tool': tool, 'args': args}) + '\n')
    if tool == 'git':
        if args[-3:] == ['status', '--porcelain', '--untracked-files=all']:
            print(settings.get('dirty', ''), end=''); return 0
        if args[-2:] == ['rev-parse', 'HEAD']:
            print(settings['head']); return 0
        raise AssertionError('undeclared Git command')
    if tool == 'date':
        assert args == ['+%s']
        delta = int((root / 'clock-delta').read_text()) if (root / 'clock-delta').exists() else 0
        print(settings['now'] + delta); return 0
    if tool == 'terraform':
        assert len(args) == 4 and args[0].startswith('-chdir=') and args[1:3] == ['output', '-json']
        assert args[3] in ('phase2_contract', 'phase3_contract', 'phase4_contract', 'global_b_service')
        print(json.dumps(settings['terraform'][args[3]])); return settings.get('terraformStatus', 0)
    if tool == 'python3':
        if args[0] == '-':
            result = subprocess.run([sys.executable, *args], input=sys.stdin.buffer.read(), capture_output=True)
            sys.stdout.buffer.write(result.stdout); sys.stderr.buffer.write(result.stderr); return result.returncode
        assert Path(args[0]) == SCRIPTS / 'growth_b_cdc_supervisor.py'
        import growth_b_cdc_supervisor as supervisor
        options = dict(zip(args[2::2], args[3::2]))
        operation = supervisor.service.read(options['--operation'])
        context = supervisor.service.read(options['--context'])
        supervisor.validate_operation(operation)
        delta = int((root / 'clock-delta').read_text()) if (root / 'clock-delta').exists() else 0
        supervisor.validate_context(context, operation, now=lambda: settings['now'] + delta)
        if args[1] == 'validate-operation':
            write(options['--output'], {'state': 'OFFLINE_OPERATION_AND_CONTEXT_VALIDATED', 'context': context})
            return 0
        assert args[1] == 'run'
        directory = Path(options['--directory']); public = directory / 'public'; public.mkdir(parents=True, mode=0o700)
        write(root / 'supervisor-context.json', context)
        write(root / 'supervisor-operation.json', operation)
        write(directory / 'selected-service.json', settings['selectedService'])
        write(directory / 'selected-readiness.json', settings['selectedReadiness'])
        # These are the immutable original files, including the original lease.
        # The real supervisor applies a newly acquired lease only to its in-memory
        # controller configuration; its public raw hashes still name these bytes.
        for name, configuration in settings['configurations'].items():
            write(directory / (name + '-configuration.json'), supervisor.encoded(configuration))
        proof = settings['outputs'].get('supervisor.json')
        if proof:
            if proof['contextSha256'] is None: proof['contextSha256'] = supervisor.sha(supervisor.encoded(context))
            if proof['operationSha256'] is None: proof['operationSha256'] = supervisor.operation_binding(operation)
            if proof['exports'] is None:
                proof['exports'] = {name: {'sha256': digest(raw(settings['outputs'][name])), 'bytes': len(raw(settings['outputs'][name]))}
                                    for name in ('service-verified-and-reset.json', 'restore-receipt.json', 'prepared-fingerprint.json')
                                    if name in settings['outputs']}
        for name, value in settings['outputs'].items():
            write(public / name, value)
        return settings.get('supervisorStatus', 0)
    assert tool == 'aws' and args[0] == 's3api', 'undeclared external tool'
    verb, options, positional = args[1], {}, []
    index = 2
    while index < len(args):
        if args[index] == '--no-cli-pager': index += 1; continue
        if args[index].startswith('--'):
            options[args[index]] = args[index + 1]; index += 2
        else:
            positional.append(args[index]); index += 1
    assert options.get('--region') == 'ap-northeast-2'
    if verb == 'list-objects-v2':
        print('data-bootstrap/retired' if settings.get('retired') else 'None'); return 0
    state = json.loads((root / 'store.json').read_bytes()); key = options['--key']
    if verb == 'put-object':
        assert options['--if-none-match'] == '*' and options['--server-side-encryption'] == 'AES256'
        assert options['--content-type'] == 'application/json'
        if settings.get('putFail'): return 9
        if key in state: return 12
        body = Path(options['--body']).read_bytes()
        state[key] = {'version': 'created-v1', 'body': base64.b64encode(body).decode()}
        write(root / 'store.json', state); print(json.dumps({'VersionId': 'created-v1'})); return 0
    assert key in state, 'unregistered S3 object'
    item = state[key]
    if verb == 'head-object':
        assert options.get('--query') == 'VersionId' and options.get('--output') == 'text'
        print(settings.get('headVersion', item['version'])); return 0
    assert verb == 'get-object' and len(positional) == 1
    requested = options.get('--version-id')
    body = base64.b64decode(item['body'])
    if requested:
        if settings.get('versionReadFail'): return 13
        if settings.get('wrongVersionBytes'): body += b'CORRUPTED_EXACT_VERSION'
        if settings.get('truncateVersionBytes'): body = body[:-1]
        if settings.get('sourceMutationOnVersionRead'):
            evidence = root / 'evidence'
            for source in evidence.glob('*/supervisor/public/recovery.json'):
                source.write_bytes(source.read_bytes() + b'CHANGED_AFTER_HASH')
    write(positional[0], body)
    print(json.dumps({'VersionId': settings.get('responseVersion', requested or item['version']), 'ContentLength': len(body)}))
    return 0


class CdcOperatorFixture(unittest.TestCase):
    def setUp(self):
        from test_growth_b_cdc_aws import configuration
        import growth_b_cdc_supervisor as supervisor
        self.supervisor = supervisor
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve(); self.now = int(time.time())
        self.config, self.manifest, self.readiness = configuration(self.root)
        self.run, self.dataset = self.config['runId'], self.config['datasetId']
        self.expiry, self.approved = self.now + 80000, self.now + 86400
        self.target = {key: self.config['rds'][key] for key in ('identifier', 'resourceId', 'endpoint', 'serverUuid')}
        self.restore = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-restore-receipt',
                        'state': 'DATABASE_INVENTORY_LOGIN_VERIFIED', 'datasetId': self.dataset,
                        'configSha256': self.manifest['preparation']['restoreConfigSha256'],
                        'targetIdentity': self.target, 'preparation': {'ownerSha256BeforeAndAfter': '6' * 64}}
        self.fingerprint = {'tables': {'accommodation': {'rows': 657358, 'contentSha256': '7' * 64}}, 'ddlSha256': '8' * 64}
        self.manifest['preparation']['restoreReceiptSha256'] = digest(raw(self.restore))
        write(self.config['serviceManifest']['path'], self.manifest)
        self.config['serviceManifest'].update(sha256=digest(raw(self.manifest)), bytes=len(raw(self.manifest)))
        self.readiness['manifestSha256'] = self.config['serviceManifest']['sha256']
        write(self.config['serviceReadiness']['path'], self.readiness)
        self.config['serviceReadiness'].update(sha256=digest(raw(self.readiness)), bytes=len(raw(self.readiness)))
        self.operation = {'schemaVersion': 1, 'kind': supervisor.KIND, 'stage': 'all', 'operationId': self.config['operationId'],
            'runId': self.run, 'datasetId': self.dataset, 'serviceRelease': self.manifest['serviceRelease'],
            'executionCommit': self.config['executionCommit'], 'sourceArchiveSha256': supervisor.source_archive()[1]['sha256'],
            'manifest': {k: v for k, v in self.config['serviceManifest'].items() if k != 'path'},
            'readiness': {k: v for k, v in self.config['serviceReadiness'].items() if k != 'path'}}
        self.original = {'schemaVersion': 2, 'runId': self.run, 'globalBPrepareOnly': True, 'databaseBootstrap': 'dump',
            'mode': 'performance', 'dnsMode': 'direct-only', 'rdsEngineVersion': '8.4.11', 'loadGeneratorEnabled': False,
            'cacheEnabled': False, 'datasetRelease': self.dataset, 'fencingToken': 7, 'expiresAt': self.expiry,
            'approvedExecutionDeadlineEpoch': self.approved, 'bundleCommit': self.manifest['application']['mainCommit'],
            'appImageReference': self.manifest['application']['image'], 'bundleSha256': '9' * 64,
            'infraImageReferences': {'KAFKA_IMAGE': self.config['hosts']['kafka']['image']}}
        self.phase2 = {'run_id': self.run, 'fencing_token': 7, 'vpc_id': 'vpc-' + 'a' * 17,
                      'services': {name: self.config['hosts'][role]['instanceId'] for name, role in
                                   (('debezium', 'connect'), ('kafka', 'kafka'), ('elasticsearch', 'elasticsearch'))}}
        self.phase3 = {'dataset_release': self.dataset, 'rds_instance_id': self.target['identifier'],
                      'rds_engine_version': '8.4.11', 'rds_resource_id': self.target['resourceId'], 'rds_endpoint': self.target['endpoint']}
        self.phase4 = {'app_enabled': True, 'capacity': {'min': 1, 'desired': 1, 'max': 1},
                      'accommodation_detail_cache_enabled': False, 'load_generator_enabled': False,
                      'measurement_policy': 'isolated-read',
                      'auto_scaling_group_name': self.config['asg']['name'], 'runtime_revision': 'f' * 64}
        self.service_state = {'selected': True, 'manifest_key': self.operation['manifest']['key'],
            'manifest_version_id': self.operation['manifest']['versionId'], 'manifest_sha256': self.operation['manifest']['sha256'],
            'readiness_receipt': {('version_id' if k == 'versionId' else k): v for k, v in self.operation['readiness'].items()}}
        legacy = supervisor.tf_digest({'run_id': self.run, 'app_image_reference': self.manifest['application']['image'],
            'bundle_sha256': self.original['bundleSha256'], 'dataset_manifest_sha256': self.operation['manifest']['sha256'],
            'rds_resource_id': self.manifest['rds']['resourceId'], 'measurement_policy': self.phase4['measurement_policy'],
            'accommodation_detail_cache_enabled': False})
        revision = supervisor.tf_digest({'legacyRevision': legacy, 'globalBReadiness': self.service_state['readiness_receipt'],
            'appRuntimeBinding': self.manifest['appRuntimeBinding'], 'appRuntime': self.readiness['appRuntime']})
        self.phase4['runtime_revision'] = self.service_state['runtime_revision'] = revision
        self.lease = dict(self.config['lease'])
        self.config.update(expiresAt=self.expiry, approvedExecutionDeadlineEpoch=self.approved,
                           businessStartedEpoch=self.now, businessDeadlineEpoch=self.now + 900)
        self.config['asg']['runtimeRevision'] = revision
        self.config['preconditions']['serviceReceipt']['sha256'] = self.config['serviceReadiness']['sha256']
        self.live_configuration = copy.deepcopy(self.config)
        self.cdc_configuration = copy.deepcopy(self.live_configuration)
        self.cdc_configuration.update(businessStartedEpoch=self.now + 120, businessDeadlineEpoch=self.now + 1020)
        for configuration in (self.live_configuration, self.cdc_configuration):
            supervisor.cdc.validate_config(configuration, check_files=False)
        self.producer = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-service-reset-verification',
            'state': 'SERVICE_VERIFIED_AND_RESET', 'datasetId': self.dataset,
            'mysql': {'version': '8.4.11', 'flywayVersion': 28, 'schema': 'airbobdb'}, 'targetIdentity': self.target,
            'application': self.manifest['application'], 'restoreReceiptSha256': digest(raw(self.restore)),
            'preparedFingerprintSha256': digest(raw(self.fingerprint)), 'writersStopped': True, 'cdcStopped': True,
            'sourceOriginalsUnchanged': True, 'snapshotCreated': False, 'privateValuesIncluded': False,
            'databaseBusinessWritesPerformed': False,
            'service': {key: True for key in ('readinessPassed', 'normalLoginsPassed', 'publicReadsPassed', 'globalSearchPassed',
                'imagesSampled', 'reservableDatesPassed', 'domainApiMutationCdcEsPassed', 'detailCacheDisabledVerified')},
            'reset': {'passed': True, 'testMutationRemoved': True, 'unchangedDomainAndDdl': True,
                      'remainingOutboxRows': 0, 'ownerSha256BeforeAndAfter': '6' * 64},
            'binding': {'operationId': self.operation['operationId'], 'runId': self.run, 'datasetId': self.dataset,
                'targetIdentity': self.target, 'lease': self.lease, 'resourceFencingToken': 7, 'expiresAt': self.expiry,
                'approvedExecutionDeadlineEpoch': self.approved, 'verificationDeadlineEpoch': self.now + 17800,
                'serviceManifest': self.operation['manifest'], 'serviceReadiness': self.operation['readiness'],
                'restoreConfigSha256': self.manifest['preparation']['restoreConfigSha256'],
                'configurationSha256': supervisor.cdc.journal_binding(self.cdc_configuration),
                'sharedBindingSha256': supervisor.shared_binding(self.cdc_configuration),
                'restoreReceiptSha256': digest(raw(self.restore))}}
        self.producer['service']['representativeAccounts'] = 3
        self.proof = {'schemaVersion': 1, 'kind': 'global-b-aws-source-r4-supervisor-receipt', 'state': 'SOURCE_R4_VERIFIED_AND_RESET',
                      'phasePassed': True, 'fullServiceVerificationAndReset': True, 'snapshotCreated': False,
                      'runId': self.run, 'operationId': self.operation['operationId'], 'datasetId': self.dataset,
                      'executionCommit': self.operation['executionCommit'], 'sourceArchiveSha256': self.operation['sourceArchiveSha256'],
                      'operationSha256': None, 'contextSha256': None, 'exports': None, 'targetIdentity': self.target,
                      'selected': {'manifest': self.operation['manifest'], 'readiness': self.operation['readiness']},
                      'liveConfigurationSha256': supervisor.sha(supervisor.encoded(self.live_configuration)),
                      'cdcConfigurationSha256': supervisor.sha(supervisor.encoded(self.cdc_configuration)),
                      'liveJournalBindingSha256': supervisor.cdc.journal_binding(self.live_configuration),
                      'cdcJournalBindingSha256': supervisor.cdc.journal_binding(self.cdc_configuration),
                      'sharedBindingSha256': supervisor.shared_binding(self.cdc_configuration),
                      'privateValuesIncluded': False, 'failureCode': None}
        self.recovery = {'schemaVersion': 1, 'kind': 'global-b-aws-source-r4-recovery', 'runId': self.run,
                         'operationId': self.operation['operationId'], 'datasetId': self.dataset, 'outstandingCommands': []}
        self.settings = {'now': self.now, 'head': self.operation['executionCommit'],
            'selectedService': self.manifest, 'selectedReadiness': self.readiness,
            'configurations': {'live': self.live_configuration, 'cdc': self.cdc_configuration},
            'terraform': {'phase2_contract': self.phase2, 'phase3_contract': self.phase3,
                          'phase4_contract': self.phase4, 'global_b_service': self.service_state},
            'outputs': {'supervisor.json': self.proof, 'recovery.json': self.recovery,
                        'service-verified-and-reset.json': self.producer, 'restore-receipt.json': self.restore,
                        'prepared-fingerprint.json': self.fingerprint}}
        self.temp = self.root / 'temporary'; self.temp.mkdir()
        self.fake_bin = self.root / 'bin'; self.fake_bin.mkdir()
        for tool in ('aws', 'git', 'date', 'terraform', 'python3'):
            file = self.fake_bin / tool
            file.write_text('#!/bin/sh\nexec ' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(Path(__file__).resolve())) +
                            ' --boundary ' + shlex.quote(tool) + ' "$@"\n')
            file.chmod(0o700)
        self.source = SOURCE.read_text(); self.source_sha = digest(self.source.encode())
        names = ('valid_run_id', 'require_global_b_execution_deadline', 'validate_retained_global_b_execution_deadline',
                 'validate_operator_scope_for_action', 'read_run_manifest', 'publish_immutable_json', 'sha256_file',
                 'sha256_text', 'write_current_lease_file', 'assert_b_source_not_retired', 'publish_global_b_cdc_json', 'continue_global_b_cdc')
        self.functions = '\n\n'.join(function(self.source, name) for name in names)
        self.evidence = self.root / 'evidence'
        self.public = self.evidence / (self.run + '-' + self.operation['operationId'] + '-' + str(self.lease['fencingToken'])) / 'supervisor/public'

    def resume_with_new_lease(self):
        original = copy.deepcopy(self.operation)
        original_lease = copy.deepcopy(self.lease)
        self.lease = self.lease | {'owner': 'reviewed-resume-controller', 'fencingToken': self.lease['fencingToken'] + 1}
        effective = copy.deepcopy(self.cdc_configuration)
        effective['lease'] = copy.deepcopy(self.lease)
        self.supervisor.cdc.validate_config(effective, check_files=False)
        self.producer['binding']['lease'] = copy.deepcopy(self.lease)
        self.producer['binding']['configurationSha256'] = self.supervisor.cdc.journal_binding(effective)
        checksum = digest(raw(self.recovery))
        self.operation.update(stage='resume', resume={
            'originalOperationSha256': self.supervisor.sha(self.supervisor.encoded(original)),
            'supervisorJournalHeadSha256': '1' * 64, 'controllerJournalHeadSha256': '2' * 64,
            'liveConfigurationSha256': self.proof['liveConfigurationSha256'],
            'cdcConfigurationSha256': self.proof['cdcConfigurationSha256'],
            'recovery': {'key': f'data-bootstrap/{self.run}/{self.dataset}-r4/{self.operation["operationId"]}/recovery-{checksum}.json',
                         'versionId': 'prior-recovery-v1', 'sha256': checksum, 'bytes': len(raw(self.recovery))}})
        self.public = self.evidence / (self.run + '-' + self.operation['operationId'] + '-' + str(self.lease['fencingToken'])) / 'supervisor/public'
        return original, original_lease, effective

    def execute(self, env=None):
        store = {}
        for key, version, value in [(f'runs/{self.run}/operator.json', 'operator-v1', self.original),
                                   (self.operation['manifest']['key'], self.operation['manifest']['versionId'], self.manifest),
                                   (self.operation['readiness']['key'], self.operation['readiness']['versionId'], self.readiness)]:
            store[key] = {'version': version, 'body': base64.b64encode(raw(value)).decode()}
        write(self.root / 'store.json', store); write(self.root / 'settings.json', self.settings)
        values = {'script_dir': str(SCRIPTS), 'repo_root': str(ROOT), 'lab_root': str(ROOT / 'infra/aws/lab'),
            'temp_dir': str(self.temp), 'evidence_bucket': 'airbob-performance-lab-data-bootstrap-942632789808',
            'RUN_ID': self.run, 'DATASET_RELEASE': self.dataset, 'B_CDC_OPERATION_JSON': json.dumps(self.operation),
            'CDC_EVIDENCE_DIR': str(self.evidence), 'approved_execution_deadline_epoch': str(self.approved),
            'AIRBOB_WORKFLOW_DEADLINE_EPOCH': str(self.now + 21000), 'AWS_REGION': 'ap-northeast-2',
            'COMMAND_DEADLINE_SECONDS': '18000', 'LEASE_DEADLINE_SECONDS': '20700', 'action': 'up', 'operator_scope': 'direct',
            'lease_table': self.lease['table'], 'lease_lock_id': self.lease['lockName'], 'lease_owner': self.lease['owner'],
            'lease_command': 'up', 'fencing_token': str(self.lease['fencingToken']), 'GLOBAL_LEASE_FAIL': 'false',
            'BACKEND_DELAY_SECONDS': '0',
            'cache_enabled': 'true', 'bundle_commit': 'untrusted-environment-input'}
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
'''
        footer = '\ncontinue_global_b_cdc\nprintf "%s\\n" "$run_id" "$bundle_commit" "$cache_enabled" "$expires_at" "$resource_fencing_token" > "$CDC_TEST_ROOT/final-variables"\n'
        environment = {'PATH': str(self.fake_bin) + ':' + os.environ['PATH'], 'CDC_TEST_ROOT': str(self.root),
                       'PYTHONPATH': str(SCRIPTS), 'AWS_EC2_METADATA_DISABLED': 'true'}
        result = subprocess.run(['bash', '-c', setup + '\n' + stubs + self.functions + footer], cwd=ROOT,
                                env=environment, capture_output=True, text=True, timeout=20)
        self.calls = [json.loads(line) for line in (self.root / 'calls.jsonl').read_text().splitlines()] if (self.root / 'calls.jsonl').exists() else []
        self.result = result
        return result

    def assert_failed(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('b_source_r4_complete=true', result.stdout)


class OperatorAdmissionTest(CdcOperatorFixture):
    def test_exact_eleven_field_context_is_constructed_from_retained_tf_and_current_lease(self):
        result = self.execute(); self.assertEqual(0, result.returncode, result.stderr)
        context = json.loads((self.root / 'supervisor-context.json').read_bytes())
        self.assertEqual(set(context), set('schemaVersion kind executionCommit approvedExecutionDeadlineEpoch controllerDeadlineEpoch operator lease phase2 phase3 phase4 serviceState'.split()))
        self.assertEqual(context['operator'], self.original); self.assertEqual(context['lease'], self.lease)
        self.assertEqual(context['phase2'], self.phase2); self.assertEqual(context['phase3'], self.phase3)
        self.assertEqual(context['phase4'], self.phase4); self.assertEqual(context['serviceState'], self.service_state)
        self.assertEqual(context['controllerDeadlineEpoch'], self.now + 17940)
        self.assertEqual(context['approvedExecutionDeadlineEpoch'], self.approved)
        self.assertEqual((self.root / 'final-variables').read_text().splitlines(),
                         [self.run, self.original['bundleCommit'], 'false', str(self.expiry), '7'])
        self.assertEqual([call['args'][-1] for call in self.calls if call['tool'] == 'terraform'],
                         ['phase2_contract', 'phase3_contract', 'phase4_contract', 'global_b_service'])
        self.assertFalse(any(any(word in call['args'] for word in ('apply', 'destroy', 'plan', 'init')) for call in self.calls if call['tool'] == 'terraform'))

    def test_first_run_binds_original_config_bytes_and_separate_lease_free_journals(self):
        result = self.execute(); self.assertEqual(0, result.returncode, result.stderr)
        proof = json.loads((self.public / 'supervisor.json').read_bytes())
        for name, configuration in self.settings['configurations'].items():
            body = (self.public.parent / (name + '-configuration.json')).read_bytes()
            self.assertEqual(body, self.supervisor.encoded(configuration))
            self.assertEqual(json.loads(body)['lease'], self.lease)
            self.assertEqual(proof[name + 'ConfigurationSha256'], digest(body))
            self.assertEqual(proof[name + 'JournalBindingSha256'], self.supervisor.cdc.journal_binding(configuration))
            self.assertNotEqual(proof[name + 'ConfigurationSha256'], proof[name + 'JournalBindingSha256'])
        self.assertNotEqual(proof['liveJournalBindingSha256'], proof['cdcJournalBindingSha256'],
                            'The distinct live-read and CDC business windows remain part of their journal identities.')
        self.assertEqual(self.supervisor.shared_binding(self.live_configuration), self.supervisor.shared_binding(self.cdc_configuration))
        self.assertEqual(self.producer['binding']['configurationSha256'], proof['cdcJournalBindingSha256'])

    def test_resume_new_lease_preserves_original_raw_config_and_same_business_journal(self):
        before = {name: self.supervisor.encoded(value) for name, value in self.settings['configurations'].items()}
        original, original_lease, effective = self.resume_with_new_lease()
        result = self.execute(); self.assertEqual(0, result.returncode, result.stderr)
        context = json.loads((self.root / 'supervisor-context.json').read_bytes())
        forwarded = json.loads((self.root / 'supervisor-operation.json').read_bytes())
        proof = json.loads((self.public / 'supervisor.json').read_bytes())
        producer = json.loads((self.public / 'service-verified-and-reset.json').read_bytes())
        self.assertNotEqual(original_lease, context['lease'])
        self.assertEqual(context['lease'], self.lease)
        self.assertEqual(producer['binding']['lease'], self.lease)
        self.assertEqual(self.supervisor.operation_binding(original), self.supervisor.operation_binding(forwarded))
        for name, body in before.items():
            self.assertEqual((self.public.parent / (name + '-configuration.json')).read_bytes(), body)
            self.assertEqual(json.loads(body)['lease'], original_lease)
            self.assertEqual(proof[name + 'ConfigurationSha256'], forwarded['resume'][name + 'ConfigurationSha256'])
        self.assertNotEqual(digest(self.supervisor.encoded(effective)), proof['cdcConfigurationSha256'])
        self.assertEqual(self.supervisor.cdc.journal_binding(effective), proof['cdcJournalBindingSha256'])
        self.assertEqual(self.supervisor.shared_binding(effective), proof['sharedBindingSha256'])
        self.assertEqual(effective['businessStartedEpoch'], self.cdc_configuration['businessStartedEpoch'])
        self.assertEqual(effective['businessDeadlineEpoch'], self.cdc_configuration['businessDeadlineEpoch'])
        self.assertEqual(context['operator']['expiresAt'], self.expiry)

    def test_wrong_original_cache_and_changed_common_deadline_are_rejected(self):
        self.original['cacheEnabled'] = True
        self.assert_failed(self.execute())
        self.assertFalse(any(call['tool'] == 'terraform' for call in self.calls))

    def test_approved_deadline_cannot_be_extended_by_continuation(self):
        self.assert_failed(self.execute({'approved_execution_deadline_epoch': str(self.approved + 1)}))

    def test_resource_expiry_cannot_be_refreshed_to_cover_a_new_lease(self):
        self.original['expiresAt'] = self.now + 20000
        self.assert_failed(self.execute())
        self.assertFalse(any(call['tool'] == 'terraform' for call in self.calls))

    def test_requested_run_must_match_retained_operator(self):
        self.original['runId'] = 'lab-other'
        self.assert_failed(self.execute())

    def test_requested_dataset_must_match_original(self):
        self.assert_failed(self.execute({'DATASET_RELEASE': 'global-growth-b-' + 'f' * 16}))

    def test_dirty_or_changed_execution_commit_never_reaches_supervisor(self):
        self.settings['dirty'] = ' M unreviewed-source\n'
        self.assert_failed(self.execute())
        self.assertFalse((self.root / 'supervisor-context.json').exists())

    def test_exact_reviewed_head_is_required(self):
        self.settings['head'] = 'd' * 40
        self.assert_failed(self.execute())

    def test_unknown_operation_fields_are_rejected_by_real_offline_validator(self):
        self.operation['allowUnreviewedRetry'] = True
        self.assert_failed(self.execute())
        self.assertFalse(any(call['tool'] == 'aws' for call in self.calls))

    def test_source_archive_tamper_is_rejected_before_lease(self):
        self.operation['sourceArchiveSha256'] = '0' * 64
        self.assert_failed(self.execute())
        self.assertFalse((self.root / 'shell-calls').exists())

    def test_current_tf_topology_and_manifest_refs_are_validated_offline(self):
        self.phase4['capacity']['desired'] = 2
        self.assert_failed(self.execute())
        self.assertFalse((self.root / 'supervisor-context.json').exists())

    def test_stale_lease_run_is_rejected_by_context_validator(self):
        self.assert_failed(self.execute({'lease_command': 'down'}))
        self.assertFalse((self.root / 'supervisor-context.json').exists())

    def test_retired_source_has_no_supervisor_or_tf_mutation(self):
        self.settings['retired'] = True
        self.assert_failed(self.execute())
        self.assertFalse(any(call['tool'] == 'terraform' for call in self.calls))

    def test_resume_forwards_exact_recovery_ref_with_same_run_and_expiry(self):
        original = copy.deepcopy(self.operation)
        checksum = digest(raw(self.recovery))
        self.operation.update(stage='resume', resume={
            'originalOperationSha256': self.supervisor.sha(self.supervisor.encoded(original)),
            'supervisorJournalHeadSha256': '1' * 64, 'controllerJournalHeadSha256': None,
            'liveConfigurationSha256': None, 'cdcConfigurationSha256': None,
            'recovery': {'key': f'data-bootstrap/{self.run}/{self.dataset}-r4/{self.operation["operationId"]}/recovery-{checksum}.json',
                         'versionId': 'prior-recovery-v1', 'sha256': checksum, 'bytes': len(raw(self.recovery))}})
        result = self.execute(); self.assertEqual(0, result.returncode, result.stderr)
        forwarded = json.loads((self.root / 'supervisor-operation.json').read_bytes())
        context = json.loads((self.root / 'supervisor-context.json').read_bytes())
        self.assertEqual(forwarded, self.operation)
        self.assertEqual(context['operator']['expiresAt'], self.expiry)
        self.assertEqual(context['lease']['runId'], original['runId'])
        self.assertEqual(self.supervisor.operation_binding(forwarded), self.supervisor.operation_binding(original))

    def test_actual_workflow_cdc_admission_feeds_the_same_closed_operator_context(self):
        from test_growth_b_workflow import WorkflowAdmission, COMMIT
        WorkflowAdmission.setUpClass()
        self.operation['executionCommit'] = self.settings['head'] = self.proof['executionCommit'] = COMMIT
        for name, configuration in self.settings['configurations'].items():
            configuration['executionCommit'] = COMMIT
            self.proof[name + 'ConfigurationSha256'] = self.supervisor.sha(self.supervisor.encoded(configuration))
            self.proof[name + 'JournalBindingSha256'] = self.supervisor.cdc.journal_binding(configuration)
        self.producer['binding']['configurationSha256'] = self.proof['cdcJournalBindingSha256']
        self.producer['binding']['sharedBindingSha256'] = self.proof['sharedBindingSha256'] = self.supervisor.shared_binding(self.cdc_configuration)
        status, exported = WorkflowAdmission().run_gate('cdc', self.operation, deadline=str(self.approved))
        self.assertEqual(0, status)
        parsed = json.loads(exported.removeprefix('B_CDC_OPERATION_JSON=').strip())
        self.assertEqual(parsed, self.operation)
        result = self.execute({'B_CDC_OPERATION_JSON': json.dumps(parsed)})
        self.assertEqual(0, result.returncode, result.stderr)
        context = json.loads((self.root / 'supervisor-context.json').read_bytes())
        self.assertEqual(context['executionCommit'], COMMIT)
        self.assertEqual(context['operator']['runId'], self.run)

    def test_backend_delay_does_not_restart_the_controller_command_deadline(self):
        result = self.execute({'BACKEND_DELAY_SECONDS': '300'})
        self.assertEqual(0, result.returncode, result.stderr)
        context = json.loads((self.root / 'supervisor-context.json').read_bytes())
        self.assertEqual(context['controllerDeadlineEpoch'], self.now + 17940,
                         'Backend work consumed 300 seconds inside the already running watchdog; its deadline must stay fixed.')


class CompletionAndPublicationTest(CdcOperatorFixture):
    def assert_failed(self, result):
        super().assert_failed(result)
        self.assertTrue((self.root / 'supervisor-context.json').exists(), 'Failure happened before the intended completion/publication boundary: ' + result.stderr)

    def test_complete_success_publishes_three_actual_byte_and_version_references(self):
        result = self.execute(); self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('b_source_r4_complete=true', result.stdout); self.assertIn('r7_overall_complete=false', result.stdout)
        store = json.loads((self.root / 'store.json').read_bytes())
        for name in ('restore-receipt', 'prepared-fingerprint', 'service-verified-and-reset', 'recovery'):
            body = (self.public / (name + '.json')).read_bytes()
            reference = json.loads((self.public / (name + '-reference.json')).read_bytes())
            self.assertEqual(reference['sha256'], digest(body)); self.assertEqual(reference['bytes'], len(body))
            self.assertEqual(reference['versionId'], 'created-v1')
            self.assertEqual(base64.b64decode(store[reference['key']]['body']), body)
            self.assertIn('-' + digest(body) + '.json', reference['key'])
            self.assertTrue(any(call['tool'] == 'aws' and '--version-id' in call['args'] and reference['key'] in call['args'] for call in self.calls))

    def test_unknown_supervisor_status_remains_failure_after_successful_recovery_publish(self):
        self.settings['supervisorStatus'] = 124
        result = self.execute(); self.assert_failed(result)
        self.assertEqual(result.returncode, 124)
        self.assertTrue((self.public / 'recovery-reference.json').exists())
        self.assertFalse((self.public / 'service-verified-and-reset-reference.json').exists())

    def test_failed_supervisor_cannot_be_overridden_by_passing_files(self):
        self.settings['supervisorStatus'] = 1
        self.assert_failed(self.execute())

    def test_zero_exit_without_actual_producer_is_failure(self):
        del self.settings['outputs']['service-verified-and-reset.json']
        self.assert_failed(self.execute())

    def test_supervisor_full_gate_cannot_be_partial(self):
        self.proof['fullServiceVerificationAndReset'] = False
        self.assert_failed(self.execute())

    def test_actual_domain_cdc_es_gate_cannot_be_false(self):
        self.producer['service']['domainApiMutationCdcEsPassed'] = False
        self.assert_failed(self.execute())

    def test_reset_cannot_leave_outbox_work(self):
        self.producer['reset']['remainingOutboxRows'] = 1
        self.assert_failed(self.execute())

    def test_foreign_dataset_and_current_rds_target_are_rejected(self):
        self.producer['datasetId'] = 'global-growth-b-' + 'f' * 16
        self.assert_failed(self.execute())

    def test_foreign_rds_resource_id_is_rejected(self):
        self.producer['targetIdentity'] = self.target | {'resourceId': 'db-' + 'B' * 24}
        self.assert_failed(self.execute())

    def test_wrong_runtime_application_or_manifest_lineage_is_rejected(self):
        self.producer['application'] = self.manifest['application'] | {'mainCommit': 'e' * 40}
        self.assert_failed(self.execute())

    def test_changed_prepared_fingerprint_bytes_are_rejected(self):
        self.fingerprint['ddlSha256'] = 'c' * 64
        self.assert_failed(self.execute())

    def test_inventory_owner_digest_must_match_original_restore(self):
        self.producer['reset']['ownerSha256BeforeAndAfter'] = 'b' * 64
        self.assert_failed(self.execute())

    def test_lost_lease_prevents_success_publication(self):
        self.assert_failed(self.execute({'GLOBAL_LEASE_FAIL': 'true'}))
        self.assertFalse((self.public / 'service-verified-and-reset-reference.json').exists())

    def test_wrong_exact_version_body_is_never_referenced(self):
        self.settings['wrongVersionBytes'] = True
        self.assert_failed(self.execute())
        self.assertFalse((self.public / 'recovery-reference.json').exists())

    def test_wrong_version_response_is_never_referenced(self):
        self.settings['responseVersion'] = 'foreign-v2'
        self.assert_failed(self.execute())
        self.assertFalse((self.public / 'recovery-reference.json').exists())

    def test_missing_version_or_lost_readback_is_failure(self):
        self.settings['headVersion'] = 'None'
        self.assert_failed(self.execute())
        self.assertFalse((self.public / 'recovery-reference.json').exists())

    def test_source_mutation_during_publication_prevents_reference(self):
        self.settings['sourceMutationOnVersionRead'] = True
        self.assert_failed(self.execute())
        self.assertFalse((self.public / 'recovery-reference.json').exists())

    def test_fully_rehashed_modified_restore_cannot_relabel_the_selected_source(self):
        self.restore['unboundChangedContent'] = 'changed-after-original-publication'
        self.producer['restoreReceiptSha256'] = self.producer['binding']['restoreReceiptSha256'] = digest(raw(self.restore))
        self.assert_failed(self.execute())

    def test_same_foreign_mysql_uuid_in_restore_producer_and_supervisor_is_rejected(self):
        self.target['serverUuid'] = '87654321-4321-4321-4321-cba987654321'
        self.producer['restoreReceiptSha256'] = self.producer['binding']['restoreReceiptSha256'] = digest(raw(self.restore))
        self.assert_failed(self.execute())

    def test_supervisor_cannot_bind_another_controller_context(self):
        self.proof['contextSha256'] = 'e' * 64
        self.assert_failed(self.execute())

    def test_supervisor_export_hash_must_match_actual_producer_bytes(self):
        self.proof['exports'] = {name: {'sha256': digest(raw(self.settings['outputs'][name])),
            'bytes': len(raw(self.settings['outputs'][name]))} for name in
            ('service-verified-and-reset.json', 'restore-receipt.json', 'prepared-fingerprint.json')}
        self.proof['exports']['service-verified-and-reset.json']['sha256'] = '0' * 64
        self.assert_failed(self.execute())

    def test_raw_cdc_hash_cannot_be_replaced_with_the_lease_free_journal_binding(self):
        self.proof['cdcConfigurationSha256'] = self.proof['cdcJournalBindingSha256']
        self.assert_failed(self.execute())

    def test_raw_live_hash_cannot_be_replaced_with_the_lease_free_journal_binding(self):
        self.proof['liveConfigurationSha256'] = self.proof['liveJournalBindingSha256']
        self.assert_failed(self.execute())

    def test_matching_producer_and_supervisor_raw_hashes_cannot_replace_the_cdc_journal(self):
        self.proof['cdcJournalBindingSha256'] = self.producer['binding']['configurationSha256'] = self.proof['cdcConfigurationSha256']
        self.assert_failed(self.execute())

    def test_missing_original_configuration_cannot_be_inferred_from_public_proof(self):
        del self.settings['configurations']['cdc']
        self.assert_failed(self.execute())
        self.assertIn('Original R4 configuration is missing or linked', self.result.stderr)

    def test_original_config_lease_change_is_detected_even_when_journal_binding_is_equal(self):
        before = self.supervisor.cdc.journal_binding(self.cdc_configuration)
        self.cdc_configuration['lease']['fencingToken'] += 1
        self.assertEqual(self.supervisor.cdc.journal_binding(self.cdc_configuration), before)
        self.assert_failed(self.execute())

    def test_resume_effective_config_raw_hash_cannot_relabel_the_original_bytes(self):
        _, _, effective = self.resume_with_new_lease()
        self.proof['cdcConfigurationSha256'] = self.supervisor.sha(self.supervisor.encoded(effective))
        self.assert_failed(self.execute())

    def test_resume_cannot_keep_the_old_producer_lease_even_when_journal_is_equal(self):
        _, old_lease, effective = self.resume_with_new_lease()
        self.assertEqual(self.supervisor.cdc.journal_binding(effective), self.proof['cdcJournalBindingSha256'])
        self.producer['binding']['lease'] = old_lease
        self.assert_failed(self.execute())

    def test_resume_cannot_refresh_the_original_business_window(self):
        _, _, effective = self.resume_with_new_lease()
        effective['businessStartedEpoch'] += 60
        effective['businessDeadlineEpoch'] += 60
        self.supervisor.cdc.validate_config(effective, check_files=False)
        changed = self.supervisor.cdc.journal_binding(effective)
        self.assertNotEqual(changed, self.proof['cdcJournalBindingSha256'])
        self.proof['cdcJournalBindingSha256'] = self.producer['binding']['configurationSha256'] = changed
        self.assert_failed(self.execute())


if __name__ == '__main__':
    if len(sys.argv) >= 3 and sys.argv[1] == '--boundary':
        raise SystemExit(boundary(sys.argv[2], sys.argv[3:]))
    unittest.main()
