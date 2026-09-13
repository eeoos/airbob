"""Offline R4 source transport and orchestration; no AWS/OCI API requests."""
import base64
import contextlib
import copy
import fcntl
import gzip
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
import uuid
from datetime import datetime, timezone
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_cdc_supervisor as sup
from test_growth_b_cdc_aws import configuration, NOW

core = sup.core


def operation(config, archive_sha='a' * 64):
    return {'schemaVersion': 1, 'kind': sup.KIND, 'stage': 'all', 'operationId': config['operationId'], 'runId': config['runId'],
        'datasetId': config['datasetId'], 'serviceRelease': 'aws-service-01', 'executionCommit': config['executionCommit'], 'sourceArchiveSha256': archive_sha,
        'manifest': {k: v for k, v in config['serviceManifest'].items() if k != 'path'}, 'readiness': {k: v for k, v in config['serviceReadiness'].items() if k != 'path'}}


class SourceStageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name).resolve()
        self.repo = self.root / 'repo'; self.repo.mkdir()
        self.files = {}
        for name, raw in [('infra/aws/scripts/one.py', b'# public source\n' * 1000), ('infra/aws/vendor/pin/scripts/two.py', b'pass\n')]:
            path = self.repo / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(raw); self.files[name] = sup.sha(raw)
        self.archive, self.meta = sup.source_archive(self.files, self.repo)
        self.host = self.root / 'host'; self.host.mkdir(mode=0o700)
        self.config, _, _ = configuration(self.root)
        self.stage = {'runId': self.config['runId'], 'operationId': self.config['operationId'], 'lease': self.config['lease'],
            'deadlineEpoch': NOW + 300, 'expiresAt': NOW + 1000, 'pin': self.config['hosts']['connect']}
    def execute(self, *, index=None, data=None, meta=None):
        text = sup.stage_program(self.stage, self.meta if meta is None else meta, index=index, data=data)
        source = text.split('\n', 1)[1].rsplit('\nAIRBOB_R4_FIXED', 1)[0]
        head, body = source.split('try:\n root=guard()', 1)
        namespace = {}; exec(compile(head, '<stage-library>', 'exec'), namespace)
        namespace['guard'] = lambda: self.host
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            try: exec(compile('try:\n root=guard()' + body, '<stage-only-filesystem>', 'exec'), namespace)
            except SystemExit: pass
            finally:
                for key in ('lock', 'fd'):
                    if isinstance(namespace.get(key), int): os.close(namespace[key])
        return json.loads(output.getvalue())
    def chunks(self):
        for index, offset in enumerate(range(0, len(self.archive), sup.CHUNK_BYTES)):
            self.assertTrue(self.execute(index=index, data=self.archive[offset:offset + sup.CHUNK_BYTES])['passed'])
    def test_deterministic_archive_and_regular_0600_file_install(self):
        for name in self.files: os.utime(self.repo / name, (120, 120))
        self.assertEqual(self.archive, sup.source_archive(self.files, self.repo)[0])
        self.chunks(); self.assertTrue(self.execute()['passed'])
        for name in self.files:
            target = self.host / 'cdc-tools' / name
            self.assertEqual((self.repo / name).read_bytes(), target.read_bytes()); self.assertEqual(0o600, target.stat().st_mode & 0o777)
        self.assertTrue(self.execute()['passed'])
    def test_changed_source_chunk_or_installed_file_is_preserved_and_rejected(self):
        self.chunks(); self.assertTrue(self.execute()['passed'])
        target = self.host / 'cdc-tools' / next(iter(self.files)); target.write_bytes(b'UNOWNED_CHANGE')
        self.assertFalse(self.execute()['passed']); self.assertEqual(b'UNOWNED_CHANGE', target.read_bytes())
        chunk = self.host / 'r4-staging' / self.config['operationId'] / '000.chunk'; old = chunk.read_bytes()
        changed = bytes([self.archive[0] ^ 1]) + self.archive[1:]
        self.assertFalse(self.execute(index=0, data=changed)['passed']); self.assertEqual(old, chunk.read_bytes())
    def test_tar_links_and_missing_chunk_cannot_create_completion_marker(self):
        self.assertFalse(self.execute()['passed'])
        self.host = self.root / 'linked-archive-host'; self.host.mkdir(mode=0o700)
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode='w') as archive:
            for name in self.files:
                member = tarfile.TarInfo(name); member.type = tarfile.SYMTYPE; member.linkname = '/tmp/not-owned'
                archive.addfile(member)
        self.archive = gzip.compress(data.getvalue(), mtime=0)
        self.meta.update(sha256=sup.sha(self.archive), bytes=len(self.archive))
        self.chunks(); self.assertFalse(self.execute()['passed'])
        self.assertFalse((self.host / 'cdc-tools/source-package.json').exists())
    def test_active_worker_lock_blocks_source_write(self):
        area = self.host / 'r4' / self.config['operationId']; area.mkdir(parents=True, mode=0o700)
        path = area / 'worker.lock'; path.touch(mode=0o600)
        with path.open('rb') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertFalse(self.execute(index=0, data=self.archive)['passed'])
        self.assertFalse((self.host / 'r4-staging' / self.config['operationId'] / '000.chunk').exists())
    def test_source_link_and_traversal_are_rejected_before_packaging(self):
        name = next(iter(self.files)); (self.repo / name).unlink(); (self.repo / name).symlink_to(self.repo / list(self.files)[1])
        with self.assertRaisesRegex(core.Failed, 'SOURCE_FILE_MISSING_OR_LINKED'): sup.source_archive(self.files, self.repo)
        with self.assertRaisesRegex(core.Failed, 'SOURCE_PATH_NOT_ALLOWED'): sup.source_archive({'infra/aws/../private': 'a' * 64}, self.repo)


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.journal = sup.RecoveryJournal(Path(self.temp.name).resolve() / 'journal', 'a' * 64, 'b' * 64); self.addCleanup(self.journal.close)
        self.clock = NOW; self.calls = []; self.fail_send = False; self.command = str(uuid.uuid4())
        self.ssm = sup.Ssm(self, self.journal, lambda: None, clock=lambda: self.clock, sleep=lambda seconds: None)
    def call(self, *args):
        self.calls.append(args)
        if args[:2] == ('ssm', 'send-command'):
            if self.fail_send: raise RuntimeError('PRIVATE_PROVIDER_ERROR')
            return {'Command': {'CommandId': self.command}}
        if args[:2] == ('ssm', 'list-command-invocations'): return {'CommandInvocations': [{'Status': 'Success'}]}
        if args[:2] == ('ssm', 'get-command-invocation'):
            return {'InstanceId': 'i-' + '1' * 17, 'CommandId': self.command, 'Status': 'Success', 'ResponseCode': 0,
                'StandardOutputContent': '{"passed":true}', 'StandardErrorContent': 'PRIVATE_PROVIDER_ERROR'}
        raise AssertionError(args[:2])
    def run_stage(self, text='fixed source command'):
        return self.ssm.run('source-chunk-000', 'i-' + '1' * 17, 'a' * 64, text, self.clock + 100)
    def test_full_request_limit_rejects_before_send_and_never_copies_stderr(self):
        with self.assertRaisesRegex(core.Failed, 'R4_SSM_COMMAND_JSON_BUDGET'): self.run_stage('a' * sup.MAX_COMMAND_BYTES)
        self.assertEqual([], self.calls)
        self.run_stage(); self.assertNotIn('PRIVATE', json.dumps(self.journal.entries))
    def test_unknown_send_is_not_resubmitted_even_for_exact_repeat_chunk(self):
        self.fail_send = True
        with self.assertRaises(RuntimeError): self.run_stage()
        with self.assertRaisesRegex(core.Failed, 'UNCERTAIN_SSM_SUBMISSION_NO_REPLAY'): self.run_stage()
        self.assertEqual(1, len(self.calls))
    def test_unknown_cid_is_only_permitted_for_explicit_initial_discovery(self):
        with self.assertRaisesRegex(core.Failed, 'R4_EXACT_SSM_TARGET_REQUIRED'):
            self.ssm.run('source-install', 'i-' + '1' * 17, None, 'fixed', self.clock + 100)
        self.ssm.run('discover-connect', 'i-' + '1' * 17, None, 'fixed discovery', self.clock + 100)
        self.assertIsNone(self.journal.last('SSM_INTENT')['containerId'])


class BusinessWindowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name).resolve()
        self.config, _, _ = configuration(self.root); self.clock = NOW + 10.25
        self.journal = sup.RecoveryJournal(self.root / 'journal', 'a' * 64, 'b' * 64); self.addCleanup(self.journal.close)
        self.path = self.root / 'cdc-configuration.json'; self.guards = 0
    def sleep(self, seconds): self.clock += seconds
    def guard(self): self.guards += 1
    def fix(self, *, intent=False):
        return sup.fix_cdc_configuration(self.config, self.path, self.journal,
            live_completed=datetime.fromtimestamp(NOW + 10.2, timezone.utc).isoformat(), host_api_intent=intent, deadline=NOW + 18000,
            lease=self.config['lease'], clock=lambda: self.clock, sleep=self.sleep, guard=self.guard)
    def test_same_second_live_completion_waits_before_once_fixed_first_window(self):
        config = self.fix(); self.assertEqual(NOW + 11, self.clock); self.assertEqual(NOW + 11, config['businessStartedEpoch'])
        self.assertEqual(900, config['businessDeadlineEpoch'] - config['businessStartedEpoch']); self.assertGreaterEqual(self.guards, 3)
        raw = self.path.read_bytes(); self.clock += 2000
        self.assertEqual(config, self.fix(intent=True)); self.assertEqual(raw, self.path.read_bytes())
    def test_missing_window_with_prior_host_or_runner_intent_is_never_replaced(self):
        with self.assertRaisesRegex(core.Failed, 'MISSING_PRIOR_CDC_WINDOW_NO_REPLACEMENT'): self.fix(intent=True)
        self.assertFalse(self.path.exists()); self.journal.add('CDC_CONTROLLER_INTENT', {'action': 'verify'})
        with self.assertRaisesRegex(core.Failed, 'MISSING_PRIOR_CDC_WINDOW_NO_REPLACEMENT'): self.fix()
        self.assertFalse(self.path.exists())
    def test_changed_runtime_or_modified_existing_window_rejects_resume(self):
        self.fix(); value = json.loads(self.path.read_bytes()); value['businessDeadlineEpoch'] += 1; self.path.write_bytes(core.encoded(value))
        with self.assertRaisesRegex(core.Failed, 'SAVED_CDC_CONFIGURATION_CHANGED'): self.fix(intent=True)
        value['hosts']['app']['containerId'] = 'c' * 64; self.path.write_bytes(core.encoded(value))
        with self.assertRaisesRegex(core.Failed, 'SAVED_CDC_TARGET_OR_SOURCE_CHANGED'): self.fix(intent=True)


def context(config, manifest, ready):
    op = operation(config); op['serviceRelease'] = manifest['serviceRelease']
    op['sourceArchiveSha256'] = sup.source_archive()[1]['sha256']
    mref, rref = op['manifest'], op['readiness']
    value = {'schemaVersion': 1, 'kind': sup.CONTEXT_KIND, 'executionCommit': config['executionCommit'],
        'approvedExecutionDeadlineEpoch': config['approvedExecutionDeadlineEpoch'], 'controllerDeadlineEpoch': NOW + 18000,
        'operator': {'schemaVersion': 2, 'runId': config['runId'], 'datasetRelease': config['datasetId'], 'rdsEngineVersion': '8.4.11',
            'cacheEnabled': False, 'loadGeneratorEnabled': False, 'fencingToken': config['resourceFencingToken'], 'expiresAt': str(config['expiresAt']),
            'bundleSha256': '7' * 64, 'infraImageReferences': {'KAFKA_IMAGE': config['hosts']['kafka']['image']}},
        'lease': copy.deepcopy(config['lease']),
        'phase2': {'run_id': config['runId'], 'fencing_token': config['resourceFencingToken'], 'vpc_id': 'vpc-' + 'a' * 17,
            'services': {r: config['hosts'][k]['instanceId'] for r, k in [('debezium', 'connect'), ('kafka', 'kafka'), ('elasticsearch', 'elasticsearch')]}},
        'phase3': {'dataset_release': config['datasetId'], 'rds_instance_id': config['rds']['identifier'], 'rds_engine_version': '8.4.11',
            'rds_resource_id': config['rds']['resourceId'], 'rds_endpoint': config['rds']['endpoint']},
        'phase4': {'app_enabled': True, 'capacity': config['asg']['originalCapacity'], 'load_generator_enabled': False,
            'auto_scaling_group_name': config['asg']['name'], 'runtime_revision': config['asg']['runtimeRevision'],
            'accommodation_detail_cache_enabled': False, 'measurement_policy': {'mode': 'isolated-read'}},
        'serviceState': {'selected': True, 'manifest_key': mref['key'], 'manifest_version_id': mref['versionId'], 'manifest_sha256': mref['sha256'],
            'readiness_receipt': {'key': rref['key'], 'version_id': rref['versionId'], 'sha256': rref['sha256'], 'bytes': rref['bytes']},
            'runtime_revision': config['asg']['runtimeRevision']}}
    legacy = sup.tf_digest({'run_id': op['runId'], 'app_image_reference': manifest['application']['image'],
        'bundle_sha256': value['operator']['bundleSha256'], 'dataset_manifest_sha256': op['manifest']['sha256'],
        'rds_resource_id': manifest['rds']['resourceId'], 'measurement_policy': value['phase4']['measurement_policy'], 'accommodation_detail_cache_enabled': False})
    runtime = sup.tf_digest({'legacyRevision': legacy, 'globalBReadiness': value['serviceState']['readiness_receipt'],
        'appRuntimeBinding': manifest['appRuntimeBinding'], 'appRuntime': ready['appRuntime']})
    value['phase4']['runtime_revision'] = value['serviceState']['runtime_revision'] = runtime
    config['asg']['runtimeRevision'] = runtime
    return op, value


class AdmissionRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name).resolve()
        self.config, self.manifest, self.ready = configuration(self.root)
        self.operation, self.context = context(self.config, self.manifest, self.ready)
        self.instances = []; self.clock = NOW
    def tearDown(self):
        for instance in reversed(self.instances): instance.close()
    def new(self, name='supervisor', operation=None, current=None, recovery=None):
        instance = sup.Supervisor(operation or self.operation, current or self.context, self.root / name,
            aws=self, recovery=recovery, clock=lambda: self.clock)
        self.instances.append(instance); return instance
    def call(self, *args): raise AssertionError('AWS must not be called by admission: ' + str(args[:2]))
    def recovery(self, instance):
        instance.checkpoint(); value = json.loads((instance.public / 'recovery.json').read_bytes())
        raw = sup.encoded(value); selected = copy.deepcopy(self.operation); selected['stage'] = 'resume'
        selected['resume'] = {key: value[key] for key in ('originalOperationSha256', 'supervisorJournalHeadSha256',
            'controllerJournalHeadSha256', 'liveConfigurationSha256', 'cdcConfigurationSha256')}
        selected['resume']['recovery'] = {'key': f"data-bootstrap/{selected['runId']}/{selected['datasetId']}-r4/{selected['operationId']}/recovery-{sup.sha(raw)}.json",
            'versionId': 'exact-recovery-version', 'sha256': sup.sha(raw), 'bytes': len(raw)}
        return selected, value
    def test_closed_operation_context_and_runtime_require_exact_selections_cache_false(self):
        self.assertEqual(self.operation, sup.validate_operation(self.operation))
        sup.validate_context(self.context, self.operation, now=lambda: NOW)
        sup.validate_runtime_revision(self.context, self.operation, self.manifest, self.ready)
        changes = [('operator', 'cacheEnabled', True), ('phase3', 'rds_engine_version', '8.0.44'), ('lease', 'command', 'down'),
            ('serviceState', 'manifest_version_id', 'latest'), ('phase4', 'accommodation_detail_cache_enabled', True)]
        for area, key, value in changes:
            changed = copy.deepcopy(self.context); changed[area][key] = value
            with self.subTest(key=key), self.assertRaises(core.Failed): sup.validate_context(changed, self.operation, now=lambda: NOW)
        changed = copy.deepcopy(self.operation); changed['runtimeSourceCommit'] = 'd' * 40
        with self.assertRaisesRegex(core.Failed, 'R4_OPERATION_FIELDS_DIFFER'): sup.validate_operation(changed)
        changed = copy.deepcopy(self.context); changed['operator']['bundleSha256'] = 'e' * 64
        with self.assertRaisesRegex(core.Failed, 'RUNTIME_REVISION_CHANGED'): sup.validate_runtime_revision(changed, self.operation, self.manifest, self.ready)
    def test_recovery_hash_chain_missing_record_and_secret_field_are_rejected(self):
        instance = self.new(); instance.journal.add('TEST_METADATA', {'passed': True})
        selected, value = self.recovery(instance)
        sup.validate_recovery(value, selected)
        bad = copy.deepcopy(value); bad['supervisorJournal'][1]['data']['passed'] = False
        with self.assertRaisesRegex(core.Failed, 'CHAIN_CHANGED|BINDING_CHANGED'): sup.validate_recovery(bad, selected)
        bad = copy.deepcopy(value); bad['supervisorJournal'].pop(0)
        with self.assertRaisesRegex(core.Failed, 'CHAIN_CHANGED'): sup.validate_recovery(bad, selected)
        bad = copy.deepcopy(value); bad['supervisorJournal'][1]['data']['password'] = 'do-not-export'
        with self.assertRaisesRegex(core.Failed, 'PRIVATE_FIELD'): sup.validate_recovery(bad, selected)
    def test_new_runner_recovers_immutable_config_and_clips_current_deadline(self):
        instance = self.new(); sup.exact_write(instance.directory / 'live-configuration.json', sup.encoded(self.config))
        cdc_config = copy.deepcopy(self.config); cdc_config.update(businessStartedEpoch=NOW + 100, businessDeadlineEpoch=NOW + 1000)
        sup.exact_write(instance.directory / 'cdc-configuration.json', sup.encoded(cdc_config))
        selected, value = self.recovery(instance)
        current = copy.deepcopy(self.context); current['lease']['fencingToken'] += 1; current['lease']['owner'] = 'new-reviewed-controller'
        self.clock += 100; current['controllerDeadlineEpoch'] += 100
        resumed = self.new('resumed', selected, current, value)
        self.assertEqual(NOW + 18000, resumed.deadline)
        self.assertEqual(sup.encoded(cdc_config), (resumed.directory / 'cdc-configuration.json').read_bytes())
        resumed.builder.manifest, resumed.builder.readiness = self.manifest, self.ready
        controller = resumed.open_controller()
        self.assertEqual(current['lease'], controller.config['lease'])
        self.assertEqual(NOW + 100, controller.config['businessStartedEpoch'])
        self.assertEqual(NOW + 1000, controller.config['businessDeadlineEpoch'])
        self.assertEqual(value['cdcConfigurationSha256'], json.loads((resumed.public / 'recovery.json').read_bytes())['cdcConfigurationSha256'])
    def test_selected_recovery_reference_must_include_exact_version_and_bytes(self):
        instance = self.new(); selected, value = self.recovery(instance)
        path = self.root / 'recovery.json'; path.write_bytes(sup.encoded(value)); path.chmod(0o600)
        sup.recovery_input(selected, path, self, self.root)
        changed = copy.deepcopy(selected); changed['resume']['recovery']['sha256'] = 'd' * 64
        with self.assertRaisesRegex(core.Failed, 'EXACT_RECOVERY_BYTES_CHANGED'): sup.recovery_input(changed, path, self, self.root)
        changed = copy.deepcopy(selected); changed['resume']['recovery']['versionId'] = 'null'
        with self.assertRaises(core.Failed): sup.validate_operation(changed)
    def test_source_package_is_importable_without_root_scripts(self):
        import subprocess
        path = self.root / 'archive'; package = sup.package_sources(path)
        extracted = self.root / 'extracted'; extracted.mkdir()
        with tarfile.open(path / 'source.tar.gz', 'r:gz') as archive: archive.extractall(extracted, filter='data')
        self.assertFalse((extracted / 'scripts').exists())
        result = subprocess.run([sys.executable, str(extracted / 'infra/aws/scripts/growth_b_cdc_supervisor.py'), '--help'], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(0, result.returncode, result.stderr.decode()); self.assertEqual(22, len(package['archive']['files']))
        op = self.root / 'operation.json'; op.write_bytes(sup.encoded(self.operation))
        result = subprocess.run([sys.executable, str(extracted / 'infra/aws/scripts/growth_b_cdc_supervisor.py'), 'validate-operation',
            '--operation', str(op), '--output', str(self.root / 'offline-validation.json')], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(0, result.returncode, result.stdout.decode() + result.stderr.decode())
        self.assertFalse(json.loads((self.root / 'offline-validation.json').read_bytes())['awsCallsExecuted'])
        restored = sup.source_archive(root=extracted)[1]
        self.assertEqual(package['archive']['sha256'], restored['sha256'])
    def test_stop_journal_binds_exact_stopped_or_restarted_connect_lifetime(self):
        instance = self.new(); instance.live = self.config; instance.original_configuration = self.config
        instance.builder.manifest, instance.builder.readiness = self.manifest, self.ready; instance.open_controller()
        journal = instance.controller_journal
        journal.add('FROZEN_OBSERVATION', {'connectFinishedAt': '2027-01-01T01:00:00Z'})
        result = instance.host_payload(); self.assertFalse(result['connectRunning']); self.assertEqual('2027-01-01T01:00:00Z', result['connectFinishedAt'])
        journal.add('CONNECT_START_INTENT', {'cycleSequence': 3})
        with self.assertRaisesRegex(core.Failed, 'NO_HOST_ADOPTION'): instance.host_payload()
        journal.add('CONNECT_RESTART_CONFIRMED', {'observation': {'startedAt': '2027-01-01T02:00:00Z'}})
        result = instance.host_payload(); self.assertEqual('2027-01-01T02:00:00Z', result['pin']['startedAt']); self.assertNotIn('connectRunning', result)
    def test_completion_cannot_be_created_from_empty_or_source_closed_flags(self):
        instance = self.new()
        with self.assertRaisesRegex(core.Failed, 'ACTUAL_FULL_SOURCE_EVIDENCE_REQUIRED'): instance.public_report(True)
        instance.journal.add('SOURCE_CLOSED', {'writersStopped': True, 'cdcStopped': True})
        with self.assertRaisesRegex(core.Failed, 'ACTUAL_FULL_SOURCE_EVIDENCE_REQUIRED'): instance.public_report(True)
        report = instance.public_report(False, 'R4_EXECUTION_UNCONFIRMED')
        self.assertFalse(report['fullServiceVerificationAndReset']); self.assertEqual('R4_UNCONFIRMED', report['state'])
    def test_public_raw_configuration_sha_and_lease_independent_journal_binding_are_distinct(self):
        instance = self.new(); instance.live = copy.deepcopy(self.config); instance.original_configuration = copy.deepcopy(self.config)
        instance.config = copy.deepcopy(self.config); instance.config['lease']['fencingToken'] += 1
        report = instance.public_report(False, 'TEST_ONLY')
        self.assertEqual(sup.sha(sup.encoded(self.config)), report['cdcConfigurationSha256'])
        self.assertEqual(sup.cdc.journal_binding(instance.config), report['cdcJournalBindingSha256'])
        self.assertEqual(sup.cdc.journal_binding(self.config), report['cdcJournalBindingSha256'])
        self.assertNotEqual(report['cdcConfigurationSha256'], report['cdcJournalBindingSha256'])
        self.assertNotEqual(sup.sha(sup.encoded(instance.config)), report['cdcConfigurationSha256'])
        self.assertEqual(sup.cdc.journal_binding(self.config), report['liveJournalBindingSha256'])
    def test_once_unknown_submission_is_not_replayed_after_new_runner_recovery(self):
        instance = self.new(); instance.guard = lambda: None; calls = []
        def fail(*args): calls.append(args); raise RuntimeError('PRIVATE_PROVIDER_ERROR')
        instance.transport.guard = lambda: None; instance.transport.aws.call = fail
        with self.assertRaises(RuntimeError): instance.once('one-worker', 'service-worker-live', self.config['hosts']['connect'], 'fixed program')
        selected, value = self.recovery(instance)
        resumed = self.new('resumed', selected, recovery=value); resumed.guard = lambda: None
        with self.assertRaisesRegex(core.Failed, 'UNCERTAIN_SSM_SUBMISSION_NO_REPLAY'):
            resumed.once('one-worker', 'service-worker-live', self.config['hosts']['connect'], 'fixed program')
        self.assertEqual(1, len(calls)); self.assertNotIn('PRIVATE_PROVIDER_ERROR', json.dumps(value))
    def test_known_terminal_receipt_is_recovered_by_exact_command_id_without_resubmit(self):
        instance = self.new(); instance.guard = lambda: None; pin = self.config['hosts']['connect']; command_id = str(uuid.uuid4())
        item = {'token': 2, 'name': 'service-worker-live', 'instanceId': pin['instanceId'], 'containerId': pin['containerId'],
                'commandSha256': sup.sha(b'original fixed program'), 'startedEpoch': NOW}
        instance.journal.add('COMMAND_OPERATION_INTENT', {'key': 'worker', **{k: item[k] for k in ('name', 'instanceId', 'containerId', 'commandSha256')}})
        instance.journal.add('SSM_INTENT', item)
        instance.journal.add('SSM_SUBMITTED', item | {'commandId': command_id})
        instance.journal.add('SSM_TERMINAL', item | {'commandId': command_id, 'status': 'Success', 'completedEpoch': NOW + 1})
        calls = []
        def aws(*args):
            calls.append(args); self.assertEqual(('ssm', 'get-command-invocation'), args[:2])
            return {'CommandId': command_id, 'InstanceId': pin['instanceId'], 'Status': 'Success', 'ResponseCode': 0, 'StandardOutputContent': '{"passed":true}'}
        instance.aws = type('Aws', (), {'call': staticmethod(aws)})()
        value, command = instance.once('worker', 'service-worker-live', pin, 'current lease program')
        self.assertTrue(value['passed']); self.assertEqual(command_id, command['commandId']); self.assertEqual(1, len(calls))
    def test_new_public_attempt_preserves_previous_exact_bytes_in_hash_named_history(self):
        instance = self.new(); old = b'{\n "observed": 1\n}\n'; new = b'{"observed":2}\n'
        instance.exported('prepared-fingerprint.json', old); instance.exported('prepared-fingerprint.json', new)
        self.assertEqual(new, (instance.public / 'prepared-fingerprint.json').read_bytes())
        self.assertEqual(old, (instance.public / 'history' / (sup.sha(old) + '-prepared-fingerprint.json')).read_bytes())


class HostWorkerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name).resolve()
        self.area = self.root / 'r4/op-01'; self.area.mkdir(mode=0o700, parents=True)
        self.area.parent.chmod(0o700)
        self.tools = self.root / 'cdc-tools'; self.tools.mkdir(mode=0o700)
        self.inputs = self.area / 'inputs'; self.inputs.mkdir(mode=0o700)
        self.config = self.inputs / 'job.json'; self.config.write_bytes(sup.encoded({'operation': 'live', 'deadlineEpoch': NOW + 900})); self.config.chmod(0o600)
        self.payload = {'operationId': 'op-01', 'phase': 'live', 'attempt': 'live', 'lease': {'fencingToken': 9}, 'config': {'path': str(self.config), 'sha256': sup.sha(self.config.read_bytes())},
            'sources': {}, 'deadlineEpoch': NOW + 900}
    def test_worker_intent_without_pid_is_never_launched_again(self):
        intent = self.area / 'live-worker-intent.json'
        intent.write_bytes(sup.encoded({'phase': 'live', 'deadlineEpoch': NOW + 900, 'configurationSha256': self.payload['config']['sha256']})); intent.chmod(0o600)
        namespace = {}; exec(sup.HOST_COMMON.replace('__PAYLOAD__', base64.b64encode(sup.encoded(self.payload)).decode()), namespace)
        namespace['guard'] = lambda: self.root
        with patch('subprocess.Popen') as launch, contextlib.redirect_stdout(io.StringIO()) as output:
            with self.assertRaises(SystemExit): exec(sup.WORKER_BODY, namespace)
        self.assertFalse(json.loads(output.getvalue())['passed']); launch.assert_not_called()
        os.close(namespace['lock']); self.assertFalse((self.area / 'live-worker.json').exists())
    def test_private_cookie_path_cannot_be_read_by_public_export_program(self):
        private = self.area / 'live/.private'; private.mkdir(mode=0o700, parents=True)
        secret = private / 'cookie.json'; secret.write_bytes(b'{"cookie":"do-not-export"}'); secret.chmod(0o600)
        payload = self.payload | {'reference': {'path': str(secret), 'sha256': sup.sha(secret.read_bytes()), 'bytes': secret.stat().st_size}, 'index': None}
        namespace = {}; exec(sup.HOST_COMMON.replace('__PAYLOAD__', base64.b64encode(sup.encoded(payload)).decode()), namespace)
        namespace['guard'] = lambda: self.root
        with contextlib.redirect_stdout(io.StringIO()) as output:
            with self.assertRaises(SystemExit): exec(sup.READ_PUBLIC_BODY, namespace)
        self.assertNotIn('do-not-export', output.getvalue()); self.assertFalse(json.loads(output.getvalue())['passed'])
    def test_full_fingerprint_and_original_restore_are_exported_with_exact_bytes(self):
        for relative in ('r4/op-01/finalize-9/prepared-fingerprint.json', 'execute/restore-receipt.json'):
            path = self.root / relative; path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            raw = b'{\n  "publicFixture": true\n}\n'; path.write_bytes(raw); path.chmod(0o600)
            payload = self.payload | {'reference': {'path': str(path), 'sha256': sup.sha(raw)}, 'index': None}
            namespace = {}; exec(sup.HOST_COMMON.replace('__PAYLOAD__', base64.b64encode(sup.encoded(payload)).decode()), namespace)
            namespace['guard'] = lambda: self.root
            with contextlib.redirect_stdout(io.StringIO()) as output: exec(sup.READ_PUBLIC_BODY, namespace)
            ref = json.loads(output.getvalue())['reference']; self.assertEqual(len(raw), ref['bytes'])
            namespace['p'].update(reference=ref, index=0)
            with contextlib.redirect_stdout(io.StringIO()) as output: exec(sup.READ_PUBLIC_BODY, namespace)
            self.assertEqual(raw, base64.b64decode(json.loads(output.getvalue())['data']))
    def finalize_attempt(self, *, prior_running):
        from types import SimpleNamespace
        old_intent = self.area / 'finalize-9-worker-intent.json'; old_state = self.area / 'finalize-9-worker.json'
        old_intent.write_bytes(b'{}\n'); old_intent.chmod(0o600)
        old_raw = sup.encoded({'pid': 12345, 'startTicks': '777'}); old_state.write_bytes(old_raw); old_state.chmod(0o600)
        self.config.write_bytes(sup.encoded({'operation': 'finalize', 'deadlineEpoch': NOW + 900}))
        payload = self.payload | {'phase': 'finalize', 'attempt': 'finalize-10', 'lease': {'fencingToken': 10},
            'config': {'path': str(self.config), 'sha256': sup.sha(self.config.read_bytes())}}
        namespace = {}; exec(sup.HOST_COMMON.replace('__PAYLOAD__', base64.b64encode(sup.encoded(payload)).decode()), namespace)
        namespace['guard'] = lambda: self.root
        def stat(path, *args, **kwargs):
            if str(path) == '/proc/12345/stat':
                if not prior_running: raise FileNotFoundError()
                ticks = '777'
            else: self.assertEqual('/proc/54321/stat', str(path)); ticks = '888'
            return '1 (owned worker) ' + ' '.join(['S'] + ['0'] * 18 + [ticks])
        def launched(*args, **kwargs):
            self.assertEqual((namespace['lock'],), kwargs['pass_fds'])
            output = self.area / 'finalize-10'; output.mkdir(mode=0o700)
            receipt = output / 'service-reset-receipt.json'; receipt.write_bytes(sup.encoded({'state': 'SERVICE_VERIFIED_AND_RESET'})); receipt.chmod(0o600)
            return SimpleNamespace(pid=54321, wait=lambda **kw: 0)
        with patch.object(Path, 'read_text', stat), patch('subprocess.Popen', side_effect=launched) as launch, patch('time.time', return_value=NOW), contextlib.redirect_stdout(io.StringIO()) as output:
            try: exec(sup.WORKER_BODY, namespace)
            except SystemExit: pass
        os.close(namespace['lock']); self.assertEqual(old_raw, old_state.read_bytes())
        return json.loads(output.getvalue()), launch.call_count
    def test_new_finalize_lease_refuses_still_running_exact_prior_worker_pid(self):
        report, launches = self.finalize_attempt(prior_running=True)
        self.assertFalse(report['passed']); self.assertEqual(0, launches)
    def test_known_dead_prior_worker_allows_separate_read_only_finalize_attempt(self):
        report, launches = self.finalize_attempt(prior_running=False)
        self.assertTrue(report['passed']); self.assertEqual(1, launches)
        self.assertIn('/finalize-10/', report['reference']['path'])


class FinalProducerIntegrationTests(unittest.TestCase):
    def setUp(self):
        # This fixture runs the actual producer and actual frozen snapshot admission;
        # only database/runtime/HTTP boundaries supply synthetic observed records.
        from test_growth_b_service_verify import ServiceFixture
        self.case = ServiceFixture(); self.case.setUp(); self.addCleanup(self.case.doCleanups)
        self.report = self.case.finalize()
        self.manifest = copy.deepcopy(self.case.manifest)
        self.manifest['preparation'].update(restoreReceiptSha256=self.case.raw_ref['sha256'], restoreConfigSha256=self.case.config_ref['sha256'])
    def gate(self, report=None, restored=None):
        return sup.final_gate(self.report if report is None else report, self.case.measured,
            self.case.proof if restored is None else restored, self.case.cdc, self.manifest, self.case.ready)
    def test_actual_full_producer_gate_and_exact_three_exports(self):
        self.gate()
        paths = [self.case.root / 'result/service-reset-receipt.json', self.case.root / 'result/prepared-fingerprint.json', Path(self.case.raw_ref['path'])]
        public = self.case.root / 'public'; public.mkdir(mode=0o700)
        for name, path in zip(('service-verified-and-reset.json', 'prepared-fingerprint.json', 'restore-receipt.json'), paths):
            raw = path.read_bytes(); sup.require_public(json.loads(raw)); reference = sup.exact_write(public / name, raw)
            self.assertEqual(raw, (public / name).read_bytes()); self.assertEqual(sup.sha(raw), reference['sha256'])
    def test_false_read_service_or_reset_flag_cannot_pass_snapshot_source_gate(self):
        for area, key in [('service', 'imagesSampled'), ('service', 'reservableDatesPassed'), ('service', 'detailCacheDisabledVerified'),
                          ('service', 'domainApiMutationCdcEsPassed'), ('reset', 'testMutationRemoved'), ('reset', 'unchangedDomainAndDdl')]:
            value = copy.deepcopy(self.report); value[area][key] = False
            with self.subTest(key=key), self.assertRaises(core.Failed): self.gate(value)
    def test_consistently_changed_raw_restore_uuid_or_sha_does_not_match_selected_source(self):
        report, restored = copy.deepcopy(self.report), copy.deepcopy(self.case.proof)
        report['targetIdentity']['serverUuid'] = restored['targetIdentity']['serverUuid'] = 'f' * 36
        with self.assertRaisesRegex(core.Failed, 'ACTUAL_FINAL_SERVICE_RESET_REQUIRED'): self.gate(report, restored)
        report = copy.deepcopy(self.report); report['restoreReceiptSha256'] = report['binding']['restoreReceiptSha256'] = 'a' * 64
        with self.assertRaisesRegex(core.Failed, 'LINEAGE_CHANGED'): self.gate(report)
    def test_private_public_artifact_field_is_rejected_even_on_otherwise_valid_producer(self):
        report = copy.deepcopy(self.report); report['cookie'] = 'DO_NOT_EXPOSE'
        with self.assertRaisesRegex(core.Failed, 'PRIVATE_FIELD'): self.gate(report)


class ControllerSequenceTests(unittest.TestCase):
    setUp, tearDown, new, call = AdmissionRecoveryTests.setUp, AdmissionRecoveryTests.tearDown, AdmissionRecoveryTests.new, AdmissionRecoveryTests.call
    def prepare(self, *, qualified):
        instance = self.new(); instance.live = self.config; instance.original_configuration = copy.deepcopy(self.config)
        instance.builder.manifest, instance.builder.readiness = self.manifest, self.ready
        sup.exact_write(instance.directory / 'live-configuration.json', sup.encoded(self.config))
        sup.exact_write(instance.directory / 'cdc-configuration.json', sup.encoded(instance.original_configuration))
        instance.exported('live-service-receipt.json', sup.encoded({'state': 'LIVE_READS_OBSERVED'}))
        instance.journal.add('LIVE_APP_BEFORE', {'observation': self.config['hosts']['app']})
        instance.journal.add('LIVE_APP_AFTER', {'observation': self.config['hosts']['app']})
        instance.open_controller(); journal = instance.controller_journal
        if qualified:
            journal.add('CONTROLLER_VERIFY_COMPLETE', {'sameAppRuntimeDuringTwoPatches': True})
            journal.add('HOST_RESET', {'report': {'phasePassed': True}})
        instance.stage_input = lambda slot, value, **kw: {'path': '/opt/airbob/test/' + slot, 'sha256': sup.sha(sup.encoded(value))}
        return instance, journal
    def test_refrozen_failed_restart_requires_new_owned_restart_and_post_health(self):
        instance, journal = self.prepare(qualified=True)
        journal.add('SOURCE_CLOSED', {'writersStopped': True, 'cdcStopped': True})
        calls = []; healthy = False
        class Current:
            def public(self, action, passed):
                return {'phasePassed': passed, 'outstandingCommands': [], 'twoPatchRuntimeVerified': True,
                    'ownedResetConfirmed': True, 'postResetHealthConfirmed': healthy}
        current = Current(); instance.open_controller = lambda: current
        def action(name):
            nonlocal healthy
            calls.append(name)
            if name == 'post-reset': healthy = True
            return current, current.public(name, True)
        instance.action = action
        instance.cdc_phase({'completedAt': '2027-01-01T00:00:00Z'}, {'path': '/opt/airbob/live.json', 'sha256': 'a' * 64})
        self.assertEqual(['restart', 'post-reset', 'close'], calls)
    def test_expired_prior_business_window_never_becomes_a_fresh_verify(self):
        instance, _ = self.prepare(qualified=False); self.clock = self.config['businessDeadlineEpoch'] + 1
        before = (instance.directory / 'cdc-configuration.json').read_bytes()
        from unittest.mock import Mock
        instance.action = Mock()
        with self.assertRaisesRegex(core.Failed, 'ORIGINAL_CDC_WINDOW_EXPIRED'):
            instance.cdc_phase({'completedAt': '2027-01-01T00:00:00Z'}, {'path': '/opt/airbob/live.json', 'sha256': 'a' * 64})
        instance.action.assert_not_called(); self.assertEqual(before, (instance.directory / 'cdc-configuration.json').read_bytes())
    def test_unattested_live_reads_cannot_be_attributed_to_an_expired_lease(self):
        instance = self.new(); instance.live = self.config
        instance.context = copy.deepcopy(self.context); instance.context['lease']['fencingToken'] += 1
        with self.assertRaisesRegex(core.Failed, 'UNATTESTED_LIVE_WINDOW_LEASE_CHANGED'):
            instance.cdc_phase({'completedAt': '2027-01-01T00:00:00Z'}, {})
        self.assertFalse((instance.directory / 'cdc-configuration.json').exists())
    def test_frozen_unattested_cycle_may_reset_owned_rows_but_never_claim_r4_completion(self):
        instance, journal = self.prepare(qualified=False); journal.add('ASG_ZERO_INTENT', {'originalInstanceId': self.config['hosts']['app']['instanceId']})
        calls = []
        def reset(name):
            calls.append(name); self.assertEqual('reset', name)
            journal.add('HOST_RESET', {'report': {'phasePassed': True}})
            return None, {'phasePassed': True}
        instance.action = reset
        with self.assertRaisesRegex(core.Failed, 'OWNED_RESET_WITHOUT_COMPLETE_RUNTIME_ATTESTATION'):
            instance.cdc_phase({'completedAt': '2027-01-01T00:00:00Z'}, {'path': '/opt/airbob/live.json', 'sha256': 'a' * 64})
        self.assertEqual(['reset'], calls); self.assertIsNone(instance.journal.last('SOURCE_R4_COMPLETE'))
        self.assertFalse(instance.journal.last('UNQUALIFIED_CYCLE_RESET')['sourceSnapshotGateSatisfied'])


class AdditionalTransportTests(unittest.TestCase):
    setUp, call, run_stage = TransportTests.setUp, TransportTests.call, TransportTests.run_stage
    def test_ssm_independent_execution_and_delivery_margin_fit_original_common_deadline(self):
        self.ssm.common_deadline = self.clock + 149
        with self.assertRaisesRegex(core.Failed, 'EXCEEDS_COMMON_DEADLINE'): self.run_stage()
        self.assertEqual([], self.calls)
    def test_raw_stdout_limit_is_checked_before_json_normalization(self):
        original = self.call
        def large(*args):
            result = original(*args)
            if args[:2] == ('ssm', 'get-command-invocation'): result['StandardOutputContent'] = ' ' * sup.MAX_OUTPUT_CHARS + '{"passed":true}'
            return result
        self.ssm.aws = type('Aws', (), {'call': staticmethod(large)})()
        with self.assertRaisesRegex(core.Failed, 'SAFE_OUTPUT_BUDGET'): self.run_stage()
        self.assertIsNone(self.journal.last('SSM_TERMINAL'))


class PublicPolicyTests(unittest.TestCase):
    def test_generic_app_probe_is_byte_identical_to_source_r4_probe(self):
        with tempfile.TemporaryDirectory() as folder:
            config, _, ready = configuration(Path(folder).resolve())
            old = "set -eu\npython3 - <<'AIRBOB_R4_APP_POLICY'\n" + sup.APP_POLICY.replace('__PAYLOAD__', base64.b64encode(sup.encoded(config['hosts']['app'])).decode())
            old += '\nAIRBOB_R4_APP_POLICY\n' + sup.controller.host_program('app', 'observe', config, runtime=ready['appRuntime'])
            self.assertEqual(old, sup.observed_app_program(config['hosts']['app'], config['asg']['runtimeRevision'], ready['appRuntime']))
            self.assertEqual(old, sup.app_program(config, ready))


if __name__ == '__main__': unittest.main()
