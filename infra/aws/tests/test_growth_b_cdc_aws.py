"""AWS R4 admission, no-replay/session bounds, SSM fencing and safe evidence."""
import contextlib
import base64
import copy
from datetime import datetime, timezone
import json
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import re
import tempfile
import unittest
import uuid
from unittest.mock import patch
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_cdc_core as aws_core
import growth_b_cdc as host
import growth_b_cdc_controller as control
import growth_b_cdc_mysql as mysql
from test_growth_b_cdc_aws_fixtures import baseline, changed, kafka_record, warm_metadata_fixture, write, ORIGINAL, UID, OWNER, TARGET
from test_growth_b_service_contract import fixture as service_fixture, receipt as service_receipt

core = aws_core.core
NOW = 1800000000


def configuration(root):
    manifest = service_fixture(); run, dataset = manifest['runId'], manifest['datasetId']
    def local(name, value):
        p = root / name; p.write_bytes(core.encoded(value)); p.chmod(0o600)
        return {'path': str(p), 'sha256': core.digest(p.read_bytes())}
    prepared = local('prepared.json', {'state': 'PREPARED_TEST_ONLY'})
    preparation = local('preparation.json', {'state': 'PREPARATION_TEST_ONLY'})
    manifest['preparation']['preparedFingerprintSha256'] = prepared['sha256']
    manifest['preparation']['receipt']['sha256'] = preparation['sha256']
    mref = local('manifest.json', manifest)
    mref.update(key=f'datasets/{dataset}-aws-service/{manifest["serviceRelease"]}/aws-service.json', versionId='manifest-v1', bytes=Path(mref['path']).stat().st_size)
    readiness = service_receipt(manifest); readiness['manifestSha256'] = mref['sha256']
    rref = local('readiness.json', readiness)
    rref.update(key=f'data-bootstrap/{run}/{dataset}-service-{manifest["serviceRelease"]}.json', versionId='ready-v1', bytes=Path(rref['path']).stat().st_size)
    ca = root / 'ca.pem'; ca.write_bytes(b'PUBLIC_TEST_CA')
    hosts = {}
    for index, role in enumerate(('app', 'connect', 'kafka', 'elasticsearch'), 1):
        image = {'app': manifest['application']['image'], 'connect': manifest['debezium']['image'],
            'elasticsearch': manifest['search']['image'], 'kafka': '942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/kafka@sha256:' + '8' * 64}[role]
        hosts[role] = {'instanceId': 'i-' + str(index) * 17, 'privateIp': f'10.42.1.{index + 10}', 'containerId': str(index) * 64,
            'imageId': 'sha256:' + str(index + 4) * 64, 'image': image, 'startedAt': '2027-01-01T00:00:00.000000000Z'}
    hosts['elasticsearch'].update(clusterUuid='cluster_uuid_known', indexName='accommodations_v01')
    group_name = 'airbob-' + run + '-app'
    value = {'schemaVersion': 1, 'kind': aws_core.KIND, 'operationId': 'r4-cdc-01', 'runId': run, 'datasetId': dataset,
        'account': aws_core.ACCOUNT, 'region': aws_core.REGION, 'executionCommit': 'c' * 40, 'resourceFencingToken': 7,
        'expiresAt': NOW + 30000, 'approvedExecutionDeadlineEpoch': NOW + 40000, 'businessStartedEpoch': NOW, 'businessDeadlineEpoch': NOW + 900,
        'lease': {'table': 'airbob-performance-lab-orchestration-lease', 'lockName': 'airbob-performance-lab', 'owner': 'reviewed-controller',
            'runId': run, 'command': 'up', 'fencingToken': 9}, 'releaseDirectory': str(root / 'release'),
        'consumerManifestSha256': '1' * 64, 'checksumsSha256': '2' * 64,
        'privateAccounts': '/opt/airbob/global-b/' + run + '/private/accounts.private.json',
        'accountEnvironment': 'aws:' + manifest['rds']['resourceId'] + ':' + manifest['rds']['serverUuid'],
        'ownerMemberId': OWNER, 'accommodationId': TARGET, 'mysqlServerUuid': manifest['rds']['serverUuid'],
        'rds': dict(manifest['rds'], endpoint='airbob-test.abcdefghijk.ap-northeast-2.rds.amazonaws.com',
            masterSecretArn='arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-test-AbCdEf', caBundle=str(ca), caSha256=core.digest(ca.read_bytes())),
        'asg': {'name': group_name, 'arn': 'arn:aws:autoscaling:ap-northeast-2:942632789808:autoScalingGroup:' + UID + ':autoScalingGroupName/' + group_name,
            'launchTemplate': {'LaunchTemplateId': 'lt-' + 'b' * 17, 'Version': '3'}, 'runtimeRevision': 'f' * 64,
            'originalCapacity': {'min': 1, 'desired': 1, 'max': 1}}, 'hosts': hosts, 'networkCidr': '10.42.0.0/16',
        'preconditions': {'preparedFingerprint': prepared, 'preparationReceipt': preparation,
            'serviceReceipt': {k: rref[k] for k in ('path', 'sha256')}, 'exclusiveWriterWindow': True, 'detailCacheState': 'DISABLED'},
        'serviceManifest': mref, 'serviceReadiness': rref, 'requestTimeoutSeconds': 5, 'propagationTimeoutSeconds': 15, 'maximumSeconds': 900}
    return value, manifest, readiness


def frozen(config):
    commands = control.expected_freeze_commands(config)
    return {'schemaVersion': 1, 'kind': 'global-b-aws-cdc-frozen-observation', 'operationId': config['operationId'],
        'configurationSha256': aws_core.journal_binding(config), 'lease': config['lease'], 'observedEpoch': NOW,
        'appTerminatedIds': [config['hosts']['app']['instanceId']], 'connectFinishedAt': '2027-01-01T01:00:00.000000000Z',
        'heartbeat': {'topic': '__debezium-heartbeat.' + control.service.cdc_identity(config['runId'], config['mysqlServerUuid'])['topicPrefix'],
            'offsets': {'0': 10}, 'instanceId': config['hosts']['kafka']['instanceId'], 'containerId': config['hosts']['kafka']['containerId']},
        'commands': [dict(v, name=name, commandId=str(uuid.uuid4()), status='Success', startedEpoch=NOW - 2, completedEpoch=NOW - 1)
                     for name, v in commands.items()], 'outstandingCommandIds': [],
        'controllerToolSha256': aws_core.source_identity()['infra/aws/scripts/growth_b_cdc_controller.py']}


class FakeAdapter:
    def __init__(self, journal):
        self.journal = journal; self.current = baseline(); self.patches = []; self.frozen = False; self.counter_resets = []
        self.heartbeat = 1; self.restarted = False; self.commit_lag = False; self.wrong_es = False
    def guard(self, mode='running'):
        core.need(self.frozen if mode in ('frozen', 'cleanup') else not self.frozen, 'WRITER_FENCE_REQUIRED')
        return {'containers': {'debezium': {'startedAt': 'new' if self.restarted else 'old'}}}
    def snapshot(self): return copy.deepcopy(self.current)
    def connector_health(self): return {'connector': 'RUNNING'}
    def consumer_health(self): return {'group': core.GROUP, 'state': 'Stable'}
    def end_offsets(self, heartbeat=False): return {0: self.heartbeat} if heartbeat else {0: 0, 1: 7 + len(self.patches), 2: 0}
    def committed(self): return {0: None, 1: 7 + len(self.patches) - int(self.commit_lag), 2: None}
    def document(self, uid): return 'PRIVATE_WRONG_NAME' if self.wrong_es else core.text_cell(self.current['accommodation'][0]['name'])
    def records(self, partition, start, end, event): return core.parse_records(kafka_record(event, start, partition), partition, start, end, event)
    @contextlib.contextmanager
    def cleanup_window(self):
        self.guard('frozen'); yield
    def reset_rows(self, baseline, expected):
        core.need(self.current == expected, 'ROW_CAS_FAILED'); counters = self.current['meta']['autoIncrement']
        self.current = copy.deepcopy(baseline); self.current['meta']['autoIncrement'] = counters
    def reset_counter(self, table, value): self.current['meta']['autoIncrement'][table] = value; self.counter_resets.append(table)


class FakeSession:
    def __init__(self, adapter, journal, inputs, names):
        self.adapter, self.journal = adapter, journal; self.wrong_owner = False; self.ambiguous = False; self.logouts = 0
    def login(self):
        self.journal.add('LOGIN_INTENT', {}); core.need(not self.wrong_owner, 'AUTHENTICATED_HOST_MISMATCH')
        self.journal.add('LOGIN_OWNER_VERIFIED', {})
    def request(self, method, path, body):
        self.adapter.patches.append(body['name']); self.adapter.current = changed(self.adapter.current, body['name'], len(self.adapter.patches))
        if self.ambiguous: self.ambiguous = False; raise core.Failed('HTTP_RESULT_UNCONFIRMED')
        return 200, {'success': True}
    def logout(self): self.logouts += 1; self.journal.add('LOGOUT_VERIFIED', {'meStatus': 401})


class AwsCdcTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name).resolve()
        self.config, self.manifest, self.ready = configuration(self.root)
        self.journal = core.Journal(self.root / 'journal', 'a' * 64, 'b' * 64); self.addCleanup(self.journal.close)
        self.adapter = FakeAdapter(self.journal); self.tick = 0
        self.inputs = {'config': self.config, 'binding': {'datasetId': self.config['datasetId']}}
        self.sessions = []
    def verifier(self, **flags):
        def session(*args):
            result = FakeSession(*args)
            for key, value in flags.items(): setattr(result, key, value)
            self.sessions.append(result); return result
        def sleep(seconds): self.tick += seconds
        return aws_core.Verifier(self.inputs, self.adapter, self.journal, session_factory=session,
            clock=lambda: self.tick, wall=lambda: NOW + self.tick, sleep=sleep)
    def test_two_patches_offsets_es_logout_and_own_reset_reuse_core(self):
        verifier = self.verifier(); verifier.verify()
        self.assertEqual(2, len(self.adapter.patches)); self.assertEqual(ORIGINAL, self.adapter.patches[-1])
        self.assertTrue(self.journal.session_clean())
        self.adapter.frozen = True; verifier.reset(); self.assertEqual(baseline(), self.adapter.current)
        self.adapter.frozen = False; self.adapter.restarted = True; self.adapter.heartbeat = 2; verifier.post_reset()
        public = verifier.public('post-reset', True); raw = json.dumps(public)
        self.assertFalse(public['sourceSnapshotGateSatisfied']); self.assertFalse(public['r7ScaleOutTest'])
        for secret in (ORIGINAL, 'PRIVATE_HISTORY_REASON', 'PRIVATE_WRONG_NAME', 'password', 'rawHex'):
            self.assertNotIn(secret, raw)
    def test_wrong_owner_logs_out_before_any_patch(self):
        with self.assertRaisesRegex(core.Failed, 'AUTHENTICATED_HOST_MISMATCH'): self.verifier(wrong_owner=True).verify()
        self.assertEqual([], self.adapter.patches); self.assertEqual(1, self.sessions[0].logouts)
    def test_lost_http_response_is_never_replayed_or_qualified(self):
        with self.assertRaisesRegex(core.Failed, 'HTTP_RESULT_UNCONFIRMED'): self.verifier(ambiguous=True).verify()
        with self.assertRaisesRegex(core.Failed, 'HTTP_OUTCOME_REMAINS_UNCONFIRMED'): self.verifier().verify()
        self.assertEqual(2, len(self.adapter.patches)); self.assertTrue(self.journal.session_clean())
        self.assertIsNone(self.journal.last('AWS_BUSINESS_COMPLETE'))
    def test_deadline_persists_across_resume_without_new_login(self):
        self.tick = 901
        with self.assertRaisesRegex(core.Failed, 'CDC_WORK_DEADLINE_REACHED'): self.verifier().verify()
        self.assertEqual([], self.sessions); self.assertEqual([], self.adapter.patches)
        changed_config = copy.deepcopy(self.config); changed_config['lease']['fencingToken'] += 1
        self.assertEqual(aws_core.journal_binding(self.config), aws_core.journal_binding(changed_config))
        changed_config['businessDeadlineEpoch'] += 1
        self.assertNotEqual(aws_core.journal_binding(self.config), aws_core.journal_binding(changed_config))
    def test_expired_resume_only_invalidates_prior_owned_session(self):
        self.verifier().baseline(); self.journal.add('LOGIN_INTENT', {})
        self.tick = 901
        with self.assertRaisesRegex(core.Failed, 'CDC_WORK_DEADLINE_REACHED'): self.verifier().verify()
        self.assertEqual([], self.adapter.patches); self.assertEqual(1, len(self.sessions)); self.assertEqual(1, self.sessions[0].logouts)
        self.assertTrue(self.journal.session_clean()); self.assertIsNone(self.journal.last('AWS_BUSINESS_COMPLETE'))
    def test_embedded_ssm_programs_compile_without_executing_remote_commands(self):
        compile(control.OBSERVE.replace('__PAYLOAD__', 'e30='), '<closed-observer>', 'exec')
        compile(control.HOST_ACTION.replace('__PAYLOAD__', 'e30='), '<closed-action>', 'exec')
    def test_full_aws_account_metadata_admission_uses_no_dump_and_rejects_wrong_owner_or_environment(self):
        area = self.root / 'metadata'; area.mkdir()
        original, _, _, _ = warm_metadata_fixture(area)
        release = Path(original['releaseDirectory'])
        accounts = json.loads((release / 'accounts.json').read_bytes())
        next(row for row in accounts['representativeAccounts'] if row['key'] == 'host')['ownership'] = {'publishedListings': {'sampleIds': [TARGET, TARGET + 1]}}
        write(release / 'accounts.json', accounts)
        write(release / 'representative-accounts.json', {'accounts': accounts['representativeAccounts'], 'finalScaleSelected': True})
        consumer = json.loads((release / 'consumer-manifest.json').read_bytes()); checks = json.loads((release / 'SHA256SUMS.json').read_bytes())
        for name, key in [('accounts.json', 'accounts'), ('representative-accounts.json', 'representativeAccounts')]:
            checks[name] = core.digest((release / name).read_bytes()); consumer['artifacts'][key] = {'file': name, 'sha256': checks[name]}
        write(release / 'consumer-manifest.json', consumer); checks['consumer-manifest.json'] = core.digest((release / 'consumer-manifest.json').read_bytes())
        write(release / 'SHA256SUMS.json', checks)
        original.update(ownerMemberId=2, accommodationId=TARGET, consumerManifestSha256=checks['consumer-manifest.json'], checksumsSha256=core.digest((release / 'SHA256SUMS.json').read_bytes()))
        config = copy.deepcopy(self.config)
        for key in ('releaseDirectory', 'datasetId', 'consumerManifestSha256', 'checksumsSha256', 'ownerMemberId', 'accommodationId'):
            config[key] = original[key]
        original_dataset = self.manifest['datasetId']
        manifest = json.loads(json.dumps(self.manifest).replace(original_dataset, config['datasetId']))
        manifest_path = Path(config['serviceManifest']['path']); manifest_path.write_bytes(core.encoded(manifest))
        config['serviceManifest'].update(key=config['serviceManifest']['key'].replace(original_dataset, config['datasetId']),
            sha256=core.digest(manifest_path.read_bytes()), bytes=manifest_path.stat().st_size)
        ready = service_receipt(manifest); ready['manifestSha256'] = config['serviceManifest']['sha256']
        ready_path = Path(config['serviceReadiness']['path']); ready_path.write_bytes(core.encoded(ready))
        config['serviceReadiness'].update(key=config['serviceReadiness']['key'].replace(original_dataset, config['datasetId']),
            sha256=core.digest(ready_path.read_bytes()), bytes=ready_path.stat().st_size)
        config['preconditions']['serviceReceipt'] = {k: config['serviceReadiness'][k] for k in ('path', 'sha256')}
        private_path = Path(original['privateAccounts'])
        private = json.loads(private_path.read_bytes()); private['environment'] = config['accountEnvironment']
        for row in private['credentials']: row['environment'] = config['accountEnvironment']
        private_path.write_bytes(core.encoded(private))
        real_private_path = core.private_path
        def mapped(path, **kwargs):
            return real_private_path(private_path if str(path) == config['privateAccounts'] else path, **kwargs)
        with patch.object(core, 'private_path', side_effect=mapped):
            inputs = aws_core.read_inputs(config)
            self.assertEqual(config['ownerMemberId'], inputs['host']['memberId'])
            self.assertFalse((Path(config['releaseDirectory']) / 'airbob-growth.sql.gz').exists())
            wrong = copy.deepcopy(config); wrong['ownerMemberId'] += 1
            with self.assertRaisesRegex(core.Failed, 'EXACT_REPRESENTATIVE_OWNER_REQUIRED'): aws_core.read_inputs(wrong)
            private['environment'] = 'aws:WRONG:' + config['mysqlServerUuid']; private_path.write_bytes(core.encoded(private))
            with self.assertRaisesRegex(core.warm.CheckFailed, 'PRIVATE_ENVIRONMENT_OR_PURPOSE_MISMATCH'): aws_core.read_inputs(config)
    def test_commit_must_exceed_record_offset_and_propagation_timeout_logs_out(self):
        self.adapter.commit_lag = True
        with self.assertRaisesRegex(core.Failed, 'CDC_PROPAGATION_UNCONFIRMED'): self.verifier().verify()
        self.assertEqual(1, len(self.adapter.patches)); self.assertEqual(1, self.sessions[0].logouts)
    def test_route_and_config_tampering_rejected(self):
        aws_core.validate_config(self.config)
        mutations = [lambda c: c.update(arbitraryRoute='/admin'), lambda c: c.update(accountEnvironment='oci:mysql:' + c['datasetId']),
            lambda c: c['hosts']['app'].update(privateIp='127.0.0.1'), lambda c: c['asg'].update(originalCapacity={'min': 1, 'desired': 2, 'max': 2}),
            lambda c: c['rds'].update(endpoint='example.com'), lambda c: c['serviceManifest'].update(versionId='null')]
        for mutation in mutations:
            candidate = copy.deepcopy(self.config); mutation(candidate)
            with self.subTest(mutation=mutation), self.assertRaises((core.Failed, ValueError)): aws_core.validate_config(candidate)
    def test_real_session_rejects_extra_field_route_and_write_method_before_http(self):
        self.adapter.business_remaining = lambda: 900; self.adapter.cleanup_remaining = lambda: 60
        self.inputs.update(credential={'email': 'host@airbob.test', 'password': 'PRIVATE_TEST_PASSWORD_ONLY'}, host={'memberId': OWNER})
        session = aws_core.Session(self.adapter, self.journal, self.inputs, ['allowed'])
        with patch.object(session.opener, 'open', side_effect=AssertionError('No HTTP expected')) as opener:
            for method, route, body in [('PATCH', '/api/v1/accommodations/999', {'name': 'allowed'}),
                    ('PATCH', '/api/v1/accommodations/' + str(TARGET), {'name': 'allowed', 'description': 'tamper'}),
                    ('PUT', '/api/v1/accommodations/' + str(TARGET), {'name': 'allowed'})]:
                with self.subTest(method=method, route=route), self.assertRaisesRegex(core.Failed, 'HTTP_REQUEST_OUTSIDE_CDC_CONTRACT'):
                    session.request(method, route, body)
            opener.assert_not_called()
    def test_frozen_observation_rejects_stale_wrong_topic_cid_and_pending_command(self):
        value = frozen(self.config); commands = control.expected_freeze_commands(self.config)
        aws_core.validate_freeze(value, self.config, now=NOW, expected_commands=commands)
        mutations = [lambda v: v.update(observedEpoch=NOW - 121), lambda v: v['heartbeat'].update(topic=core.HEARTBEAT),
            lambda v: v['heartbeat'].update(containerId='f' * 64), lambda v: v['commands'][0].update(status='InProgress'),
            lambda v: v['commands'][0].update(commandSha256='0' * 64), lambda v: v.update(outstandingCommandIds=[str(uuid.uuid4())])]
        for mutation in mutations:
            candidate = copy.deepcopy(value); mutation(candidate)
            with self.subTest(mutation=mutation), self.assertRaises(core.Failed): aws_core.validate_freeze(candidate, self.config, now=NOW, expected_commands=commands)
    def test_host_aws_cannot_send_ssm_or_mutate_cloud(self):
        fake = type('Aws', (), {'call': lambda self, *args: {'safe': True}})()
        aws = host.HostAws(fake)
        for args in [('ssm', 'send-command'), ('ssm', 'start-session'), ('rds', 'modify-db-instance'), ('autoscaling', 'update-auto-scaling-group')]:
            with self.assertRaisesRegex(core.Failed, 'HOST_AWS_ACTION_NOT_ALLOWED'): aws.call(*args)
    def test_host_cli_uses_only_instance_credentials_without_editing_user_profiles(self):
        with patch.dict(os.environ, {'AWS_PROFILE': 'PRIVATE_PROFILE', 'AWS_ENDPOINT_URL': 'https://invalid.test', 'AWS_EC2_METADATA_SERVICE_ENDPOINT': 'http://invalid.test'}), \
             patch.object(host.restore, 'command', return_value=b'{}') as command:
            host.HostAws().call('sts', 'get-caller-identity')
        environment = command.call_args.kwargs['env']
        self.assertEqual('/dev/null', environment['AWS_CONFIG_FILE']); self.assertEqual('/dev/null', environment['AWS_SHARED_CREDENTIALS_FILE'])
        self.assertNotIn('AWS_PROFILE', environment); self.assertNotIn('AWS_SESSION_TOKEN', environment)
        self.assertNotIn('AWS_ENDPOINT_URL', environment); self.assertNotIn('AWS_EC2_METADATA_SERVICE_ENDPOINT', environment)
    def test_session_cleanup_budget_starts_at_logout_even_after_long_verification(self):
        adapter = host.AwsAdapter.__new__(host.AwsAdapter)
        adapter.config = self.config; adapter.journal = self.journal; adapter.clock = lambda: self.tick; adapter.wall = lambda: NOW + self.tick
        adapter.session_cleanup_end = adapter.session_cleanup_intent = None
        self.tick = 700; self.journal.add('LOGOUT_INTENT', {})
        self.assertEqual(60, adapter.cleanup_remaining())
        self.tick = 759; self.assertEqual(1, adapter.cleanup_remaining())
        self.tick = 761; self.assertLess(adapter.cleanup_remaining(), 0)
    def test_fixed_programs_have_no_private_bundle_or_arbitrary_command(self):
        script = control.host_program('kafka', 'heartbeat', self.config)
        self.assertNotIn('accounts.private.json', script); self.assertNotIn('database.password', script)
        with self.assertRaisesRegex(core.Failed, 'CLOSED_HOST_OPERATION_REQUIRED'): control.host_program('app', 'stop', self.config)
        self.assertTrue(all((ROOT / name).is_file() for name in control.package_sources()))
    def test_vendor_hash_tamper_missing_and_links_fail_before_import(self):
        vendor = self.root / 'vendor'; shutil.copytree(aws_core.VENDOR_ROOT, vendor)
        first, second = list(aws_core.ORIGINALS)[:2]
        raw = (vendor / first).read_bytes(); (vendor / first).write_bytes(raw + b'\n')
        with self.assertRaisesRegex(RuntimeError, 'PINNED_CDC_SOURCE_CHANGED'): aws_core.load_original(vendor)
        (vendor / first).write_bytes(raw); (vendor / second).unlink()
        with self.assertRaisesRegex(RuntimeError, 'PINNED_CDC_SOURCE_CHANGED'): aws_core.load_original(vendor)
        (vendor / second).symlink_to(aws_core.VENDOR_ROOT / second)
        with self.assertRaisesRegex(RuntimeError, 'PINNED_CDC_SOURCE_CHANGED'): aws_core.load_original(vendor)
    def test_embedded_connect_start_rejects_unknown_or_changed_running_lifetime(self):
        pin = self.config['hosts']['connect']; calls = []
        state = {'Id': pin['containerId'], 'Image': pin['imageId'], 'Config': {'Image': pin['image']},
                 'State': {'Running': True, 'Paused': False, 'Restarting': False, 'StartedAt': pin['startedAt'], 'FinishedAt': '2027-01-01T01:00:00Z'}}
        def docker(argv, **kwargs):
            calls.append(argv)
            self.assertEqual(['docker', '--host', 'unix:///var/run/docker.sock', 'inspect', pin['containerId']], argv)
            return SimpleNamespace(returncode=0, stdout=json.dumps([state]).encode())
        def run(expected):
            program = control.host_program('connect', 'start', self.config, restart_fence={'finishedAt': state['State']['FinishedAt'], 'expectedStartedAt': expected})
            source = program.split('\n', 1)[1].rsplit('\nAIRBOB_CDC_CLOSED', 1)[0]
            output = io.StringIO()
            with patch.object(subprocess, 'run', side_effect=docker), contextlib.redirect_stdout(output):
                try: exec(compile(source, '<closed-connect>', 'exec'), {})
                except SystemExit: pass
            return json.loads(output.getvalue())
        self.assertFalse(run(None)['passed'])
        self.assertFalse(run('2027-01-01T02:00:00Z')['passed'])
        self.assertTrue(run(pin['startedAt'])['passed'])
        self.assertTrue(all('start' not in argv for argv in calls))
    def test_sql_plan_has_full_cas_without_global_privileges_or_transaction_restart(self):
        before = baseline(); after = changed(changed(before, 'temporary', 1), ORIGINAL, 2)
        sql = mysql.owned_rows_sql(before, after, TARGET)
        for forbidden in ('START TRANSACTION', 'SET GLOBAL', 'sql_log_bin=0', 'TRUNCATE', 'INSERT INTO outbox', 'UPDATE outbox'):
            self.assertNotIn(forbidden, sql)
        self.assertEqual(4, sql.count('DELETE FROM outbox')); self.assertEqual(2, sql.count('DELETE FROM accommodation_history'))
        self.assertIn('COMMIT;', sql); self.assertIn('`outbox` WRITE', mysql.lock_statement()); self.assertIn('`member` READ', mysql.lock_statement())
    def test_one_hour_credentials_and_feature_ref_rejected_without_cloud_mutation(self):
        env = {'GITHUB_ACTIONS': 'true', 'GITHUB_REF': 'refs/heads/main', 'GITHUB_WORKFLOW_REF': 'eeoos/airbob/.github/workflows/aws-performance-lab.yml@refs/heads/main',
            'GITHUB_SHA': self.config['executionCommit'], 'AWS_ACCESS_KEY_ID': 'PRIVATE_FAKE', 'AWS_SECRET_ACCESS_KEY': 'PRIVATE_FAKE', 'AWS_SESSION_TOKEN': 'PRIVATE_FAKE',
            'AIRBOB_AWS_CREDENTIAL_EXPIRATION': datetime.fromtimestamp(NOW + 3600, timezone.utc).isoformat()}
        calls = []
        fake = type('Aws', (), {'call': lambda _, *args: calls.append(args) or {'Account': aws_core.ACCOUNT, 'Arn': 'arn:aws:sts::942632789808:assumed-role/airbob-lab-operator/test'}})()
        with self.assertRaisesRegex(core.Failed, 'SIX_HOUR'): control.verify_environment(self.config, fake, environ=env, now=lambda: NOW)
        self.assertEqual([], calls)
        env['AIRBOB_AWS_CREDENTIAL_EXPIRATION'] = datetime.fromtimestamp(NOW + 21600, timezone.utc).isoformat()
        control.verify_environment(self.config, fake, environ=env, now=lambda: NOW)
        self.assertEqual([('sts', 'get-caller-identity')], calls)
        env['GITHUB_REF'] = 'refs/heads/feature'
        with self.assertRaisesRegex(core.Failed, 'REVIEWED_MAIN'): control.verify_environment(self.config, fake, environ=env, now=lambda: NOW)


class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name).resolve()
        self.config, self.manifest, self.ready = configuration(self.root)
        self.journal = core.Journal(self.root / 'journal', 'a' * 64, 'b' * 64); self.addCleanup(self.journal.close)
        self.now = NOW; self.calls = []; self.commands = []; self.changed_app = False; self.fail_reset = False; self.fail_post = False; self.pending_ids = []
        self.taglist = [{'Key': k, 'Value': v} for k, v in host.run_tags(self.config).items()]
        self.asg = {'AutoScalingGroupName': self.config['asg']['name'], 'AutoScalingGroupARN': self.config['asg']['arn'],
            'LaunchTemplate': self.config['asg']['launchTemplate'], 'Tags': self.taglist, 'MinSize': 1, 'DesiredCapacity': 1, 'MaxSize': 1,
            'NewInstancesProtectedFromScaleIn': False, 'SuspendedProcesses': [], 'Instances': [self.member(self.config['hosts']['app']['instanceId'])]}
        self.instances = {p['instanceId']: dict(InstanceId=p['instanceId'], PrivateIpAddress=p['privateIp'], State={'Name': 'running'}, Tags=self.taglist)
                          for p in self.config['hosts'].values()}
        self.connect_running = True; self.connect_started = self.config['hosts']['connect']['startedAt']
        self.connect_finished = '0001-01-01T00:00:00Z'; self.generation = 0; self.activities = []
        self.app_tags = self.taglist + [{'Key': k, 'Value': v} for k, v in {'Service': 'app', 'RuntimeRevision': self.config['asg']['runtimeRevision'],
            'aws:autoscaling:groupName': self.config['asg']['name']}.items()]
        self.asg['Tags'] = self.app_tags
        app = self.instances[self.config['hosts']['app']['instanceId']]
        app.update(Tags=self.app_tags, ImageId='ami-' + 'c' * 17, InstanceType='c6i.large', LaunchTime=datetime.fromtimestamp(NOW - 1000, timezone.utc).isoformat())
        self.phase4 = {'app_enabled': True, 'capacity': {'min': 1, 'desired': 1, 'max': 1}, 'load_generator_enabled': False,
            'auto_scaling_group_name': self.config['asg']['name'], 'runtime_revision': self.config['asg']['runtimeRevision']}
        mref, rref = self.config['serviceManifest'], self.config['serviceReadiness']
        self.state = {'selected': True, 'manifest_key': mref['key'], 'manifest_version_id': mref['versionId'], 'manifest_sha256': mref['sha256'],
            'readiness_receipt': {'key': rref['key'], 'version_id': rref['versionId'], 'sha256': rref['sha256'], 'bytes': rref['bytes']}}
        self.controller = control.Controller(self.config, self.manifest, self.ready, self.phase4, self.journal,
            service_state=self.state, aws=self, clock=lambda: self.now, sleep=self.sleep,
            transport=SimpleNamespace(run=self.ssm_run, settle=self.settle, pending=self.pending))
    def member(self, identifier):
        return {'InstanceId': identifier, 'ProtectedFromScaleIn': False, 'LifecycleState': 'InService', 'HealthStatus': 'Healthy',
                'LaunchTemplate': self.config['asg']['launchTemplate']}
    def sleep(self, seconds): self.now += seconds
    def call(self, *args):
        self.calls.append(args)
        if args[:2] == ('dynamodb', 'get-item'):
            lease = self.config['lease']
            return {'Item': {'Owner': {'S': lease['owner']}, 'RunId': {'S': lease['runId']}, 'Command': {'S': 'up'},
                'FencingToken': {'N': str(lease['fencingToken'])}, 'ExpiresAt': {'N': str(NOW + 30000)}, 'CommandDeadline': {'N': str(NOW + 20000)}}}
        if args[:2] == ('rds', 'describe-db-instances'):
            pin = self.config['rds']
            return {'DBInstances': [{'DBInstanceIdentifier': pin['identifier'], 'DbiResourceId': pin['resourceId'], 'Endpoint': {'Address': pin['endpoint'], 'Port': 3306},
                'EngineVersion': '8.4.11', 'PubliclyAccessible': False, 'DBInstanceStatus': 'available', 'TagList': self.taglist}]}
        if args[:2] == ('autoscaling', 'describe-auto-scaling-groups'): return {'AutoScalingGroups': [copy.deepcopy(self.asg)]}
        if args[:2] == ('autoscaling', 'describe-scaling-activities'): return {'Activities': copy.deepcopy(self.activities)}
        if args[:2] == ('ec2', 'describe-launch-template-versions'):
            return {'LaunchTemplateVersions': [dict(self.config['asg']['launchTemplate'], VersionNumber=int(self.config['asg']['launchTemplate']['Version']),
                LaunchTemplateData={'ImageId': 'ami-' + 'c' * 17, 'InstanceType': 'c6i.large'})]}
        if args[:2] == ('ec2', 'describe-instances'):
            rows = [self.instances[args[-1]]] if '--instance-ids' in args else [v for v in self.instances.values()
                if v['State']['Name'] != 'terminated' and host.tags(v['Tags']).get('Service') == 'app']
            return {'Reservations': [{'Instances': copy.deepcopy(rows)}]}
        if args[:2] == ('autoscaling', 'update-auto-scaling-group'):
            values = [int(args[args.index(k) + 1]) for k in ('--min-size', '--desired-capacity', '--max-size')]
            self.assertIn(values, ([0, 0, 0], [1, 1, 1]))
            self.asg.update(zip(('MinSize', 'DesiredCapacity', 'MaxSize'), values))
            if values == [0, 0, 0]:
                for row in self.asg['Instances']: self.instances[row['InstanceId']]['State']['Name'] = 'terminated'
                self.asg['Instances'] = []
            else:
                self.generation += 1; identifier = 'i-' + f'{self.generation + 10:017x}'
                self.instances[identifier] = {'InstanceId': identifier, 'PrivateIpAddress': '10.42.1.99', 'State': {'Name': 'running'}, 'Tags': self.app_tags,
                    'ImageId': 'ami-' + 'c' * 17, 'InstanceType': 'c6i.large', 'LaunchTime': datetime.fromtimestamp(self.now, timezone.utc).isoformat()}
                self.asg['Instances'] = [self.member(identifier)]
            return {}
        raise AssertionError('Unapproved fake action: ' + str(args[:2]))
    def pending(self): return self.pending_ids
    def settle(self, deadline): core.need(not self.pending_ids, 'SSM_COMMAND_STILL_UNCONFIRMED')
    def ssm_run(self, name, instance, cid, program, deadline, seconds=90):
        self.settle(deadline)
        data = json.loads(base64.b64decode(re.search(r"base64.b64decode\('([^']+)'\)", program)[1]))
        command = {'name': name, 'instanceId': instance, 'containerId': cid, 'commandId': str(uuid.uuid4()), 'commandSha256': core.digest(program.encode()),
            'status': 'Success', 'startedEpoch': self.now, 'completedEpoch': self.now}
        self.commands.append(command)
        if name.startswith('host-'):
            action = name.removeprefix('host-'); passed = not ((action == 'reset' and self.fail_reset) or (action == 'post-reset' and self.fail_post))
            result = {'action': action, 'operationId': self.config['operationId'], 'runId': self.config['runId'], 'mysqlServerUuid': self.config['mysqlServerUuid'],
                'inputBinding': {'toolIdentity': aws_core.source_identity()}, 'phasePassed': passed, 'apiRoundTripVerified': True,
                'businessWindow': {'normalPatchCount': 2}, 'journalHeadSha256': 'd' * 64, 'cleanup': {'passed': True} if action == 'reset' else None}
            if action == 'verify' and self.changed_app: self.app_changed_now = True
            return {'passed': passed, 'report': result}, command
        pin = data['pin']; observation = copy.deepcopy(pin)
        observation.update(running=True, finishedAt='0001-01-01T00:00:00Z')
        if name == 'app-observe':
            if instance != self.config['hosts']['app']['instanceId']:
                observation.update(containerId='f' * 64, startedAt='2027-01-01T02:00:00.000000000Z')
            elif getattr(self, 'app_changed_now', False): observation['startedAt'] = '2027-01-01T00:10:00.000000000Z'
            observation.update(readiness=True, normalProfile='aws', runtimeRevision=self.config['asg']['runtimeRevision'], appJarSha256=self.ready['appRuntime']['imageJarSha256'])
        if name in ('connect-stop', 'source-connect-stop'):
            core.need(pin['startedAt'] == self.connect_started, 'CLOSED_HOST_OPERATION_UNCONFIRMED')
            if self.connect_running: self.connect_finished = datetime.fromtimestamp(self.now, timezone.utc).isoformat().replace('+00:00', 'Z')
            self.connect_running = False; observation.update(running=False, startedAt=self.connect_started, finishedAt=self.connect_finished)
        if name in ('connect-start', 'connect-restart-observe'):
            fence = data['restartFence']; expected = fence['expectedStartedAt']
            if expected is None:
                core.need(not self.connect_running and self.connect_finished == fence['finishedAt'], 'CLOSED_HOST_OPERATION_UNCONFIRMED')
                self.connect_running = True; self.connect_started = datetime.fromtimestamp(self.now + 1, timezone.utc).isoformat().replace('+00:00', 'Z')
            else: core.need(self.connect_running and self.connect_started == expected, 'CLOSED_HOST_OPERATION_UNCONFIRMED')
            self.connect_finished = '0001-01-01T00:00:00Z'; observation.update(startedAt=self.connect_started, finishedAt=self.connect_finished)
            if name == 'connect-start': self.journal.add('SSM_TERMINAL', command | {'safeObservation': observation})
        if name == 'connect-observe': observation.update(startedAt=self.connect_started, finishedAt=self.connect_finished)
        if name == 'heartbeat': observation['heartbeat'] = {'topic': data['topic'], 'offsets': {'0': 10}, 'instanceId': instance, 'containerId': cid}
        return {'passed': True, 'observation': observation}, command
    def test_verify_freeze_reset_new_identity_restart_and_post_health(self):
        self.controller.verify(); proof = self.controller.freeze()
        self.assertEqual([0, 0, 0], [self.asg[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')]); self.assertFalse(self.connect_running)
        self.assertEqual([self.config['hosts']['app']['instanceId']], proof['appTerminatedIds'])
        self.controller.reset(); new = self.controller.restart()
        self.assertNotEqual(self.config['hosts']['app']['instanceId'], new['instanceId'])
        self.assertNotEqual(self.config['hosts']['app']['containerId'], new['containerId'])
        self.controller.post_reset(); report = self.controller.public('post-reset', True)
        self.assertTrue(report['twoPatchRuntimeVerified'] and report['ownedResetConfirmed'] and report['postResetHealthConfirmed'])
        self.assertFalse(report['sourceSnapshotGateSatisfied']); self.assertFalse(report['r7ScaleOutTest'])
        mutations = [x for x in self.calls if x[:2] == ('autoscaling', 'update-auto-scaling-group')]
        self.assertEqual(2, len(mutations)); self.assertTrue(all(x[x.index('--max-size') + 1] in ('0', '1') for x in mutations))
        self.controller.close(); self.assertEqual([], self.asg['Instances']); self.assertFalse(self.connect_running)
        self.assertTrue(self.controller.public('close', True)['sourceClosed']['writersStopped'])
    def test_failed_post_health_recloses_owned_replacement_without_sql_reset_or_cancel(self):
        self.controller.verify(); self.controller.reset(); self.controller.restart(); self.fail_post = True
        with self.assertRaisesRegex(core.Failed, 'HOST_PHASE_UNCONFIRMED'): self.controller.post_reset()
        self.assertEqual([], self.asg['Instances']); self.assertFalse(self.connect_running)
        self.assertIsNotNone(self.journal.last('SOURCE_CLOSED')); self.assertIsNone(self.journal.last('CONTROLLER_POST_RESET_COMPLETE'))
        self.assertEqual(1, sum(c['name'] == 'host-reset' for c in self.commands))
    def test_changed_app_during_two_patch_window_never_qualifies(self):
        self.changed_app = True
        with self.assertRaisesRegex(core.Failed, 'APP_RESTARTED_DURING_VERIFY'): self.controller.verify()
        self.assertIsNone(self.journal.last('CONTROLLER_VERIFY_COMPLETE'))
        self.assertFalse(any(x[:2] == ('autoscaling', 'update-auto-scaling-group') for x in self.calls))
    def test_failed_reset_keeps_all_writers_frozen_and_does_not_restart(self):
        self.controller.verify(); self.fail_reset = True
        with self.assertRaisesRegex(core.Failed, 'HOST_PHASE_UNCONFIRMED'): self.controller.reset()
        self.assertFalse(self.connect_running); self.assertEqual([], self.asg['Instances'])
        with self.assertRaisesRegex(core.Failed, 'OWNED_RESET_REQUIRED_BEFORE_RESTART'): self.controller.restart()
        self.assertFalse(any(c['name'] == 'connect-start' for c in self.commands))
    def test_pending_ssm_and_changed_asg_settings_prevent_zero_mutation(self):
        self.controller.verify(); self.pending_ids = [{'commandId': str(uuid.uuid4())}]
        with self.assertRaisesRegex(core.Failed, 'SSM_COMMAND_STILL_UNCONFIRMED'): self.controller.freeze()
        self.pending_ids = []; self.asg['LaunchTemplate'] = dict(self.asg['LaunchTemplate'], Version='999')
        with self.assertRaisesRegex(core.Failed, 'EXACT_NORMAL_ASG_REQUIRED'): self.controller.freeze()
        self.assertFalse(any(x[:2] == ('autoscaling', 'update-auto-scaling-group') for x in self.calls))
    def test_current_terraform_exact_version_cannot_be_substituted(self):
        self.state['manifest_version_id'] = 'other-version'
        with self.assertRaisesRegex(core.Failed, 'CURRENT_TERRAFORM_SERVICE_REFERENCE_CHANGED'):
            control.validate_selected_service(self.config, self.manifest, self.ready, self.phase4, self.state)
        self.assertEqual([], self.calls)
    def test_aws_only_package_without_root_scripts_imports_and_validates_plan(self):
        package = self.root / 'package'; package.mkdir()
        for name, sha in control.package_sources().items():
            target = package / name; target.parent.mkdir(parents=True, exist_ok=True)
            raw = (ROOT / name).read_bytes(); self.assertEqual(sha, core.digest(raw)); target.write_bytes(raw)
        self.assertFalse((package / 'scripts').exists())
        config, phase4, state, output = [self.root / name for name in ('config.json', 'phase4.json', 'state.json', 'plan.json')]
        for path, value in ((config, self.config), (phase4, self.phase4), (state, self.state)): write(path, value)
        result = subprocess.run([sys.executable, str(package / 'infra/aws/scripts/growth_b_cdc_controller.py'), 'plan',
            '--config', str(config), '--manifest', self.config['serviceManifest']['path'], '--readiness', self.config['serviceReadiness']['path'],
            '--phase4', str(phase4), '--service-state', str(state), '--journal', str(self.root / 'unused-journal'), '--output', str(output)],
            cwd=package, env={k: v for k, v in os.environ.items() if k != 'PYTHONPATH'}, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stdout.decode()); plan = json.loads(output.read_bytes())
        self.assertEqual(control.package_sources(), plan['helperSources']); self.assertEqual(17, len(plan['helperSources']))
        self.assertEqual(aws_core.original_source_layout(), plan['originalSourceLayout'])
        self.assertFalse(plan['remoteWritesExecuted']); self.assertFalse((self.root / 'unused-journal').exists()); self.assertEqual([], self.calls)
    def test_saved_zero_intent_never_authorizes_replacement_or_unknown_draining_member(self):
        self.controller.verify()
        self.journal.add('ASG_ZERO_INTENT', {'originalInstanceId': self.config['hosts']['app']['instanceId']})
        foreign = 'i-' + 'b' * 17
        self.asg['Instances'] = [self.member(foreign)]
        for shape in ((1, 1, 1), (0, 0, 0)):
            with self.subTest(shape=shape):
                self.asg.update(zip(('MinSize', 'DesiredCapacity', 'MaxSize'), shape))
                with self.assertRaisesRegex(core.Failed, 'ASG_MEMBER_CAS_MISMATCH'): self.controller.freeze()
        self.assertIsNone(self.journal.last('ASG_ZERO_CONFIRMED'))
        self.assertFalse(any(x[:2] == ('autoscaling', 'update-auto-scaling-group') for x in self.calls))
    def test_unknown_running_connect_after_start_intent_is_not_adopted(self):
        self.controller.verify(); self.controller.reset()
        cycle = self.journal.add('ASG_RESTART_INTENT', {'originalCapacity': self.config['asg']['originalCapacity'], 'closedSequence': self.journal.latest_sequence('FROZEN_OBSERVATION')})
        self.journal.add('CONNECT_START_INTENT', {'cycleSequence': cycle['sequence'], 'containerId': self.config['hosts']['connect']['containerId'], 'commandSha256': 'f' * 64})
        self.connect_running = True; self.connect_started = '2027-01-01T04:00:00Z'
        with self.assertRaisesRegex(core.Failed, 'OWNED_CONNECT_START_OUTCOME_UNCONFIRMED'): self.controller.restart()
        self.assertIsNone(self.journal.last('CONNECT_RESTART_CONFIRMED')); self.assertIsNone(self.journal.last('SOURCE_CLOSED'))
        self.assertIsNotNone(self.journal.last('REFREEZE_UNCONFIRMED')); self.assertFalse(any(c['name'] == 'connect-start' for c in self.commands))
    def test_exact_ssm_start_result_survives_lost_confirmation_without_another_start(self):
        self.controller.verify(); self.controller.reset()
        original = self.journal.add
        def lost(kind, data):
            if kind == 'CONNECT_RESTART_CONFIRMED': raise core.Failed('SIMULATED_CONNECT_CONFIRMATION_LOST')
            return original(kind, data)
        with patch.object(self.journal, 'add', side_effect=lost):
            with self.assertRaisesRegex(core.Failed, 'SIMULATED_CONNECT_CONFIRMATION_LOST'): self.controller._restart()
        self.assertTrue(self.connect_running); self.assertIsNotNone(self.journal.last('SSM_TERMINAL')['safeObservation'])
        self.controller._restart()
        self.assertEqual(1, sum(c['name'] == 'connect-start' for c in self.commands))
        self.assertEqual(1, sum(c['name'] == 'connect-restart-observe' for c in self.commands))
        self.assertEqual(self.connect_started, self.journal.last('CONNECT_RESTART_CONFIRMED')['observation']['startedAt'])
    def test_changed_connect_lifetime_cannot_resume_from_past_success(self):
        self.controller.verify(); self.controller.reset(); self.controller.restart()
        original = self.journal.last('CONNECT_RESTART_CONFIRMED')
        self.connect_started = '2027-01-01T05:00:00Z'
        with self.assertRaisesRegex(core.Failed, 'CLOSED_HOST_OPERATION_UNCONFIRMED'): self.controller._restart()
        self.assertEqual(original, self.journal.last('CONNECT_RESTART_CONFIRMED'))
    def test_post_health_is_bound_to_current_restart_and_runtime(self):
        self.controller.verify(); self.controller.reset(); self.controller.restart(); self.controller.post_reset(); self.controller.close()
        self.assertTrue(self.controller.public('close', True)['postResetHealthConfirmed'])
        self.controller.restart()
        self.assertFalse(self.controller.public('restart', True)['postResetHealthConfirmed'])
        self.assertIsNone(self.controller.public('restart', True)['sourceClosed'])
        self.controller.post_reset(); self.assertTrue(self.controller.public('post-reset', True)['postResetHealthConfirmed'])
    def test_close_waits_for_late_launch_activity_ec2_and_stable_zero(self):
        self.controller.verify(); self.controller.reset(); started = self.now
        original_call = self.call; pending = {}; stage = 0
        def delayed(*args):
            reply = original_call(*args)
            if args[:2] == ('autoscaling', 'update-auto-scaling-group') and args[args.index('--desired-capacity') + 1] == '1':
                identifier = self.asg['Instances'][0]['InstanceId']; pending[identifier] = self.instances.pop(identifier)
                self.asg['Instances'] = []
                self.activities = [{'AutoScalingGroupName': self.config['asg']['name'], 'ActivityId': str(uuid.uuid4()), 'StatusCode': 'InProgress'}]
                raise core.Failed('SIMULATED_ASG_START_RESPONSE_LOST')
            return reply
        def advance(seconds):
            nonlocal stage
            self.now += seconds; stage += 1
            if stage <= 2: self.assertIsNone(self.journal.last('SOURCE_CLOSED'))
            if stage == 1:
                self.instances.update(pending); self.asg['Instances'] = [self.member(next(iter(pending)))]
            elif stage == 2:
                self.instances[next(iter(pending))]['State']['Name'] = 'terminated'; self.asg['Instances'] = []; self.activities = []
        self.controller.sleep = advance
        with patch.object(self, 'call', side_effect=delayed):
            with self.assertRaisesRegex(core.Failed, 'SIMULATED_ASG_START_RESPONSE_LOST'): self.controller.restart()
        receipt = self.journal.last('SOURCE_CLOSED'); self.assertIsNotNone(receipt)
        self.assertEqual(list(pending), receipt['replacementInstanceIds']); self.assertGreaterEqual(self.now - started, 20)
        self.assertGreaterEqual(receipt['zeroObservation']['stableSeconds'], 10); self.assertFalse(self.connect_running)


class SsmTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.journal = core.Journal(Path(self.temp.name).resolve() / 'journal', 'a' * 64, 'b' * 64); self.addCleanup(self.journal.close)
        self.clock = NOW; self.calls = []; self.command = str(uuid.uuid4()); self.status = 'Success'; self.uncertain = False
        self.instance = 'i-' + '1' * 17; self.output = {'passed': True}
        self.ssm = control.Ssm(self, self.journal, lambda: None, clock=lambda: self.clock, sleep=self.sleep)
    def sleep(self, seconds): self.clock += seconds
    def call(self, *args):
        self.calls.append(args)
        if args[:2] == ('ssm', 'send-command'):
            if self.uncertain: raise RuntimeError('PRIVATE_REMOTE_EXCEPTION')
            self.instance = args[args.index('--instance-ids') + 1]
            return {'Command': {'CommandId': self.command}}
        if args[:2] == ('ssm', 'list-command-invocations'): return {'CommandInvocations': [{'Status': self.status}]}
        if args[:2] == ('ssm', 'get-command-invocation'):
            return {'Status': self.status, 'CommandId': self.command, 'InstanceId': self.instance, 'ResponseCode': 0,
                    'StandardOutputContent': json.dumps(self.output), 'StandardErrorContent': 'PRIVATE_NOT_EMITTED'}
        raise AssertionError(args[:2])
    def run_command(self): return self.ssm.run('heartbeat', 'i-' + '1' * 17, 'a' * 64, 'closed program', self.clock + 5)
    def test_only_exact_runshell_document_and_safe_output(self):
        value, record = self.run_command(); self.assertEqual({'passed': True}, value)
        self.assertEqual('Success', record['status']); self.assertEqual([], self.ssm.pending())
        args = self.calls[0]; self.assertEqual('AWS-RunShellScript', args[args.index('--document-name') + 1])
        self.assertFalse(any('cancel-command' in args or 'start-session' in args for args in self.calls))
        self.assertNotIn('PRIVATE', json.dumps(self.journal.entries))
    def test_ambiguous_send_is_not_replayed(self):
        self.uncertain = True
        with self.assertRaises(RuntimeError): self.run_command()
        with self.assertRaisesRegex(core.Failed, 'UNCERTAIN_SSM_SUBMISSION_NO_REPLAY'): self.run_command()
        self.assertEqual(1, len(self.calls))
    def test_pending_command_blocks_new_mutation_and_retains_id(self):
        self.status = 'InProgress'
        with self.assertRaisesRegex(core.Failed, 'SSM_COMMAND_STILL_UNCONFIRMED'): self.run_command()
        self.assertEqual(self.command, self.ssm.pending()[0]['commandId'])
        with self.assertRaisesRegex(core.Failed, 'SSM_COMMAND_STILL_UNCONFIRMED'): self.run_command()
        self.assertEqual(1, sum(args[:2] == ('ssm', 'send-command') for args in self.calls))
    def test_successful_connect_terminal_persists_closed_identity_before_caller_confirmation(self):
        config, _, _ = configuration(Path(self.temp.name))
        pin = config['hosts']['connect']; observation = pin | {'running': True, 'finishedAt': '0001-01-01T00:00:00Z'}
        self.output = {'passed': True, 'observation': observation}
        _, result = self.ssm.run('connect-start', pin['instanceId'], pin['containerId'], 'fixed start program', self.clock + 5)
        self.assertEqual(observation, self.journal.last('SSM_TERMINAL')['safeObservation'])
        self.assertEqual(observation, result['safeObservation']); self.assertNotIn('PRIVATE', json.dumps(self.journal.entries))
    def test_unexpected_ssm_connect_output_is_never_copied_into_journal(self):
        config, _, _ = configuration(Path(self.temp.name)); pin = config['hosts']['connect']
        self.output = {'passed': True, 'observation': pin | {'running': True, 'finishedAt': '0001-01-01T00:00:00Z', 'password': 'PRIVATE_VALUE'}}
        with self.assertRaisesRegex(core.Failed, 'CLOSED_CONNECT_OBSERVATION_REQUIRED'):
            self.ssm.run('connect-start', pin['instanceId'], pin['containerId'], 'fixed start program', self.clock + 5)
        self.assertNotIn('PRIVATE', json.dumps(self.journal.entries)); self.assertIsNone(self.journal.last('SSM_TERMINAL'))


if __name__ == '__main__': unittest.main()
