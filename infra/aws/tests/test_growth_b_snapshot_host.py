"""Host bridge tests with a versioned in-memory object store and fenced DB stubs."""
import contextlib
import copy
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
import zipfile
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_snapshot_host as host


class MemoryAws:
    def __init__(self):
        self.objects = {}
        self.calls = []
        self.reads = {}
        self.before_put = None

    def add(self, bucket, key, value):
        raw = json.dumps(value, indent=2).encode() + b'\n'
        return self.add_bytes(bucket, key, raw)

    def add_bytes(self, bucket, key, raw):
        ref = {'key': key, 'versionId': 'v' + str(len(self.objects) + 1),
               'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)}
        self.objects[(bucket, key)] = (ref, raw)
        return ref

    def call(self, *args):
        self.calls.append(args)
        def option(name):
            return args[args.index(name) + 1]
        if args[:2] == ('s3api', 'put-object'):
            if self.before_put:
                self.before_put()
            assert option('--if-none-match') == '*'
            assert option('--server-side-encryption') == 'AES256'
            key = (option('--bucket'), option('--key'))
            if key in self.objects:
                raise ValueError('PreconditionFailed')
            raw = Path(option('--body')).read_bytes()
            ref = self.add(*key, json.loads(raw))
            # Keep the exact submitted representation, not JSON reserialization.
            ref.update(sha256=hashlib.sha256(raw).hexdigest(), bytes=len(raw))
            self.objects[key] = (ref, raw)
            return {'VersionId': ref['versionId']}
        if args[:2] == ('s3api', 'get-object'):
            ref, raw = self.objects[(option('--bucket'), option('--key'))]
            assert ref['versionId'] == option('--version-id')
            Path(args[-1]).write_bytes(raw)
            return {'VersionId': ref['versionId']}
        if args[:2] == ('s3api', 'head-object'):
            ref, raw = self.objects[(option('--bucket'), option('--key'))]
            assert ref['versionId'] == option('--version-id')
            return {'VersionId': ref['versionId'], 'ContentLength': len(raw)}
        if args[:2] in self.reads:
            return copy.deepcopy(self.reads[args[:2]])
        raise AssertionError('Unexpected AWS operation: ' + str(args[:2]))


class Fixture(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.root.chmod(0o700)
        self.dataset = 'global-growth-b-' + 'a' * 16
        self.run = 'lab-b-host'; self.operation = 'create-001'
        self.store = MemoryAws()
        self.target = {'identifier': 'airbob-' + self.run, 'resourceId': 'db-' + 'A' * 24,
                       'endpoint': 'unit.ap-northeast-2.rds.amazonaws.com', 'serverUuid': '12345678-1234-1234-1234-123456789abc'}
        self.manifest = self.new_manifest()
        self.manifest_sha = 'c' * 64
        self.context = self.new_context()

    def ref(self, key, checksum='1' * 64):
        return {'key': key, 'versionId': 'version-one', 'sha256': checksum, 'bytes': 100}

    def new_manifest(self, mode='create'):
        base = host.prefix(self.dataset, self.run, self.operation)
        manifest = {'schemaVersion': 1, 'kind': host.KIND, 'operation': mode, 'operationId': self.operation,
            'datasetId': self.dataset, 'runId': self.run, 'account': host.ACCOUNT, 'region': host.REGION,
            'mysql': host.snapshot.MYSQL, 'snapshotIdentifier': 'airbob-dataset-b-unit-snapshot',
            'application': {'mainCommit': '2' * 40, 'image': host.ACCOUNT + '.dkr.ecr.' + host.REGION + '.amazonaws.com/airbob-repo@sha256:' + '3' * 64},
            'awsPreparation': self.ref(f'datasets/{self.dataset}-aws-preparation/aws-preparation-' + '1' * 64 + '.json'),
            'consumerTools': self.ref(base + 'files/' + '1' * 64 + '-consumer-tools.tar.gz'),
            'toolSources': host.sources(), 'operationTimeoutSeconds': 600,
            'source': {'rds': self.target, 'restoreConfigSha256': '4' * 64, 'privateAccountsSha256': '5' * 64},
            'evidence': {k: self.ref(f'data-bootstrap/{self.run}/{k}.json') for k in ('restoreReceipt', 'preparedFingerprint', 'serviceResetReceipt')}}
        if mode in {'prepare', 'retire'}:
            provenance = self.ref(f'datasets/{self.dataset}-aws-snapshots/{manifest["snapshotIdentifier"]}/provenance-' + '1' * 64 + '.json')
            if mode == 'prepare':
                del manifest['source']
                manifest['evidence'] = {k: self.ref(f'data-bootstrap/{self.run}/{k}.json') for k in ('admission', 'restoreEvent')}
            manifest['evidence']['provenance'] = provenance
        return manifest

    def new_context(self):
        return {'schemaVersion': 1, 'kind': host.KIND + '-context',
            **{k: self.manifest[k] for k in ('operation', 'operationId', 'datasetId', 'runId', 'toolSources')},
            'manifest': self.ref(host.prefix(self.dataset, self.run, self.operation) + f'manifest-{self.manifest_sha}.json', self.manifest_sha),
            'lease': {'table': 'airbob-performance-lab-orchestration-lease', 'lockName': 'airbob-performance-lab',
                      'owner': 'unit-controller', 'runId': self.run, 'command': 'up', 'fencingToken': 42},
            'rds': {k: self.target[k] for k in ('identifier', 'resourceId', 'endpoint')} | {'masterSecretArn': f'arn:aws:secretsmanager:{host.REGION}:{host.ACCOUNT}:secret:rds!db-12345678-1234-1234-1234-123456789abc-AbCd12'},
            'redisImage': host.ACCOUNT + '.dkr.ecr.' + host.REGION + '.amazonaws.com/redis@sha256:' + '6' * 64,
            'hostInstanceId': 'i-' + '7' * 17, 'deadlineEpoch': int(time.time()) + 3600,
            'evidencePrefix': host.evidence_prefix(self.dataset, self.run, self.operation),
            'awsCli': {'version': '2.34.64', 'archiveSha256': '8' * 64}}

    def validate(self, manifest=None):
        return host.validate_manifest(manifest or self.manifest, self.dataset, self.run, self.operation)


class ManifestTests(Fixture):
    def test_real_nine_file_archive_passes_shell_admission_and_foreign_or_missing_members_fail(self):
        scripts = Path(host.__file__).parent
        shell = (scripts / 'bootstrap-growth-b-snapshot.sh').read_text()
        extract = 'extract_regular() {' + shell.split('extract_regular() {', 1)[1].split('\n}\n', 1)[0] + '\n}\n'
        admission = 'extract_regular "$stage/consumer-tools.tar.gz"' + shell.split(
            'extract_regular "$stage/consumer-tools.tar.gz"', 1)[1].split('\nassert_lease\n', 1)[0] + '\n'
        originals = {name: (scripts / name).read_bytes() for name in host.sources()}
        self.assertEqual(9, len(originals))
        variants = {'exact': originals, 'missing': {k: v for k, v in originals.items() if k != 'growth_b_rds_class.py'},
                    'extra': originals | {'foreign.py': b'foreign = True\n'},
                    'foreign-same-count': {k: v for k, v in originals.items() if k != 'growth_b_rds_class.py'} | {'foreign.py': b'foreign = True\n'},
                    'changed-bytes': originals | {'growth_b_rds_class.py': b'changed = True\n'}}
        for name, files in variants.items():
            with self.subTest(variant=name):
                root = self.root / name; stage = root / 'stage'; stage.mkdir(parents=True, mode=0o700)
                binary = root / 'toolchain/python/bin/python3'; binary.parent.mkdir(parents=True)
                binary.symlink_to(sys.executable)
                with tarfile.open(stage / 'consumer-tools.tar.gz', 'w:gz') as archive:
                    for filename, raw in sorted(files.items()):
                        info = tarfile.TarInfo(filename); info.size = len(raw); info.mode = 0o600; info.mtime = 0
                        archive.addfile(info, io.BytesIO(raw))
                manifest = stage / 'manifest.json'; host.write(manifest, self.manifest)
                variables = {'root': str(root), 'stage': str(stage), 'manifest': str(manifest), 'digest': host.sha(manifest),
                             'dataset': self.dataset, 'run_id': self.run, 'operation_id': self.operation}
                setup = 'set -euo pipefail\numask 077\nexport PYTHONDONTWRITEBYTECODE=1\n' + '\n'.join(
                    key + '=' + shlex.quote(value) for key, value in variables.items()) + '\n'
                result = subprocess.run(['bash', '-c', setup + 'fail() { exit 31; }\n' + extract + admission +
                    'printf SHELL_ARCHIVE_ADMISSION_PASSED\n'], capture_output=True, text=True, timeout=15,
                    env={'PATH': os.environ['PATH'], 'AWS_EC2_METADATA_DISABLED': 'true'})
                if name == 'exact':
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertIn('SHELL_ARCHIVE_ADMISSION_PASSED', result.stdout)
                    self.assertEqual(originals, {p.name: p.read_bytes() for p in (stage / 'tools').iterdir()})
                else:
                    self.assertNotEqual(0, result.returncode)
                    self.assertNotIn('SHELL_ARCHIVE_ADMISSION_PASSED', result.stdout)

    def test_rds_managed_secret_name_keeps_exact_account_and_region_boundary(self):
        self.assertEqual(host.validate_context(self.context, self.manifest, self.manifest_sha), self.context)
        for original, changed in ((host.ACCOUNT, '111111111111'), (host.REGION, 'us-east-1')):
            value = copy.deepcopy(self.context)
            value['rds']['masterSecretArn'] = value['rds']['masterSecretArn'].replace(original, changed)
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                host.validate_context(value, self.manifest, self.manifest_sha)

    def test_all_three_modes_and_exact_controller_context(self):
        for mode in ('create', 'prepare', 'retire'):
            with self.subTest(mode=mode):
                self.manifest = self.new_manifest(mode)
                self.context = self.new_context()
                self.assertEqual(self.validate()['operation'], mode)
                self.assertEqual(host.validate_context(self.context, self.manifest, self.manifest_sha), self.context)

    def test_rejects_legacy_tools_unknown_payload_wrong_source_and_unbounded_time(self):
        mutations = [lambda v: v['mysql'].update(flywayVersion=27),
            lambda v: v.update(password='PRIVATE_SENTINEL'),
            lambda v: v['source']['rds'].update(resourceId='db-wrong'),
            lambda v: v['toolSources'].update({'growth_b_snapshot.py': 'f' * 64}),
            lambda v: v.update(operationTimeoutSeconds=21600),
            lambda v: v['consumerTools'].update(key=host.prefix(self.dataset, self.run, self.operation) + '../tools.tar.gz')]
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                value = copy.deepcopy(self.manifest); mutate(value)
                with self.assertRaises(ValueError):
                    self.validate(value)

    def test_host_provenance_is_bound_to_its_source_run(self):
        manifest = self.new_manifest('prepare')
        ref = manifest['evidence']['provenance']
        ref['key'] = f'data-bootstrap/lab-old-source/{self.dataset}-snapshot/source-001/snapshot-provenance.json'
        self.validate(manifest)
        host.provenance_source_binding(ref, {'source': {'identifier': 'airbob-lab-old-source'}})
        with self.assertRaises(ValueError):
            host.provenance_source_binding(ref, {'source': {'identifier': 'airbob-lab-other-source'}})
        ref['key'] = ref['key'].replace('source-001', '../source-001')
        with self.assertRaises(ValueError):
            self.validate(manifest)

    def test_retry_requires_previous_own_operation_and_retire_is_always_fresh(self):
        self.manifest['resume'] = {'operationReceipt': self.ref(f'data-bootstrap/{self.run}/{self.dataset}-snapshot/previous-001/snapshot-operation.json'), 'restoreConfigSha256': '9' * 64}
        self.validate()
        self.manifest['resume']['operationReceipt']['key'] = self.context['evidencePrefix'] + 'snapshot-operation.json'
        with self.assertRaises(ValueError):
            self.validate()
        value = self.new_manifest('retire'); value['resume'] = self.manifest['resume']
        with self.assertRaises(ValueError):
            self.validate(value)

    def test_context_deadline_fence_endpoint_and_identity_fail_closed(self):
        for key, value in [('deadlineEpoch', int(time.time()) - 1), ('hostInstanceId', 'i-wrong'),
                           ('evidencePrefix', 'data-bootstrap/other-run/'), ('operationId', 'another-001')]:
            changed = copy.deepcopy(self.context); changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                host.validate_context(changed, self.manifest, self.manifest_sha)
        changed = copy.deepcopy(self.context); changed['lease']['fencingToken'] = 0
        with self.assertRaises(ValueError):
            host.validate_context(changed, self.manifest, self.manifest_sha)

    def test_cloudtrail_projection_preserves_restore_coordinates_without_payload(self):
        event = {'eventName': 'RestoreDBInstanceFromDBSnapshot', 'eventSource': 'rds.amazonaws.com',
            'awsRegion': host.REGION, 'recipientAccountId': host.ACCOUNT, 'eventID': '1' * 8 + '-' + '2' * 4 + '-' + '3' * 4 + '-' + '4' * 4 + '-' + '5' * 12,
            'eventTime': '2026-09-12T17:00:00+00:00', 'requestParameters': {'dBInstanceIdentifier': self.target['identifier'], 'dBSnapshotIdentifier': self.manifest['snapshotIdentifier']}}
        self.assertEqual(host.cloudtrail_projection(event), event)
        for payload in (event | {'userIdentity': {'sessionToken': 'PRIVATE_SENTINEL'}}, event | {'errorCode': 'AccessDenied'}):
            with self.assertRaises(ValueError):
                host.cloudtrail_projection(payload)

    def test_cli_offline_validation_and_bootstrap_syntax(self):
        path = self.root / 'manifest.json'; host.write(path, self.manifest)
        result = subprocess.run([sys.executable, host.__file__, 'validate', '--manifest', str(path), '--sha256', host.sha(path),
            '--dataset-id', self.dataset, '--run-id', self.run, '--operation-id', self.operation], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['cloudMutationsExecuted'], False)
        shell = Path(host.__file__).with_name('bootstrap-growth-b-snapshot.sh')
        self.assertEqual(subprocess.run(['bash', '-n', str(shell)], capture_output=True).returncode, 0)


class EvidenceAndFenceTests(Fixture):
    def test_exact_version_fetch_and_readback_tamper_are_rejected(self):
        ref = self.store.add(host.EVIDENCE, self.context['evidencePrefix'] + 'input.json', {'public': True})
        destination = self.root / 'input.json'
        self.assertEqual(host.fetch(self.store, ref, destination, host.EVIDENCE), {'public': True})
        destination.write_text('{"changed":true}')
        with self.assertRaises(ValueError):
            host.fetch(self.store, ref, destination, host.EVIDENCE)
        bad = ref | {'sha256': '0' * 64}
        with self.assertRaises(ValueError):
            host.fetch(self.store, bad, self.root / 'bad.json', host.EVIDENCE)
        self.assertFalse((self.root / 'bad.json').exists())
        self.assertTrue((self.root / 'bad.json.partial').exists())

    def test_retained_exact_bytes_still_check_the_selected_remote_version(self):
        ref = self.store.add(host.EVIDENCE, self.context['evidencePrefix'] + 'input.json', {'public': True})
        destination = self.root / 'input.json'
        host.fetch(self.store, ref, destination, host.EVIDENCE)
        host.fetch(self.store, ref, destination, host.EVIDENCE)
        self.assertEqual(self.store.calls[-1][:2], ('s3api', 'head-object'))
        with self.assertRaises(AssertionError):
            host.fetch(self.store, ref | {'versionId': 'a-different-version'}, destination, host.EVIDENCE)

    def test_publication_is_create_only_and_cannot_adopt_or_overwrite(self):
        path = self.root / 'operation.json'; host.write(path, {'state': 'VERIFIED'})
        publisher = host.Publisher(self.store, self.context, self.root)
        ref = publisher.publish('snapshot-operation.json', path)
        self.assertEqual(ref['sha256'], host.sha(path))
        self.assertEqual(publisher.publish('snapshot-operation.json', path), ref)
        self.assertEqual(sum(c[:2] == ('s3api', 'put-object') for c in self.store.calls), 1)
        with self.assertRaises(ValueError):
            host.Publisher(self.store, self.context, self.root).publish('snapshot-operation.json', path)
        host.write(path, {'state': 'REPLACEMENT'})
        with self.assertRaises(ValueError):
            publisher.publish('snapshot-operation.json', path)

    def test_private_values_never_reach_public_store(self):
        for value in ({'credentials': [{'password': 'PRIVATE_SENTINEL'}]}, {'nested': [{'payload': 'PAYLOAD_SENTINEL'}]}):
            path = self.root / 'private.json'; host.write(path, value)
            with self.assertRaises(ValueError):
                host.Publisher(self.store, self.context, self.root).publish('snapshot-operation.json', path)
        self.assertFalse(self.store.calls)
        host.public_json({'privateCredentials': {'credentialValuesRecorded': False, 'fileMode': '0600'}})

    def test_ack_is_issued_only_after_existing_read_lock_guard_and_is_immutable(self):
        publisher = host.Publisher(self.store, self.context, self.root)
        db = MagicMock(); db.snapshot_read_lock_connection_id = 123
        observations = []
        db.guard.side_effect = lambda **kw: observations.append('guard')
        self.store.before_put = lambda: observations.append('publish')
        request = {'requiredLease': self.context['lease'], 'source': self.target, 'snapshotIdentifier': self.manifest['snapshotIdentifier']}
        request_ref = self.ref(self.context['evidencePrefix'] + 'snapshot-create-request.json')
        ack = host.ReadLockAcks(publisher, db, self.manifest_sha, request_ref, request)
        with patch.object(host.time, 'monotonic', side_effect=[100, 120, 120]):
            ack(); ack()
        self.assertEqual(observations, ['guard', 'publish', 'guard', 'publish'])
        self.assertEqual(len(publisher.objects), 2)
        for ref in publisher.objects.values():
            value = json.loads(self.store.objects[(host.EVIDENCE, ref['key'])][1])
            self.assertEqual(value['requestVersionId'], request_ref['versionId'])
            self.assertEqual(value['connectionId'], 123)
            self.assertLessEqual((host.snapshot.instant(value['expiresAt']) - host.snapshot.instant(value['observedAt'])).total_seconds(), 45)
            self.assertEqual(value['lease'], self.context['lease'])
        db.command.assert_not_called()

    def test_ack_does_not_survive_lost_connection_or_guard_failure(self):
        for connection, failure in ((None, False), (88, True)):
            db = MagicMock(); db.snapshot_read_lock_connection_id = connection
            if failure:
                db.guard.side_effect = ValueError('lost lock')
            ack = host.ReadLockAcks(host.Publisher(self.store, self.context, self.root), db, self.manifest_sha,
                self.ref(self.context['evidencePrefix'] + 'snapshot-create-request.json'),
                {'requiredLease': self.context['lease'], 'source': self.target, 'snapshotIdentifier': self.manifest['snapshotIdentifier']})
            with self.assertRaises(ValueError):
                ack()
        self.assertFalse(self.store.calls)

    def test_metadata_poll_refreshes_ack_without_any_new_database_connection(self):
        self.store.reads[('rds', 'describe-db-snapshots')] = {'DBSnapshots': []}
        aws = host.HostAws(self.store, MagicMock(), self.context)
        aws.read_lock_ack = MagicMock()
        aws.call('rds', 'describe-db-snapshots')
        aws.read_lock_ack.assert_called_once_with()
        self.assertEqual(self.store.calls, [('rds', 'describe-db-snapshots')])

    def test_host_cannot_create_delete_lookup_or_write_other_prefix(self):
        aws = host.HostAws(self.store, MagicMock(), self.context)
        for args in (('rds', 'create-db-snapshot'), ('rds', 'delete-db-instance'), ('rds', 'restore-db-instance-from-db-snapshot'),
                     ('cloudtrail', 'lookup-events'), ('s3api', 'put-object', '--bucket', host.EVIDENCE, '--key', 'data-bootstrap/other/')):
            with self.subTest(operation=args[:2]), self.assertRaises(ValueError):
                aws.call(*args)
        self.assertFalse(self.store.calls)

    def test_deadline_stop_and_fencing_token_are_all_enforced(self):
        lease = self.context['lease']
        item = {'Owner': {'S': lease['owner']}, 'RunId': {'S': lease['runId']}, 'Command': {'S': lease['command']},
                'FencingToken': {'N': '42'}, 'ExpiresAt': {'N': str(int(time.time()) + 500)},
                'CommandDeadline': {'N': str(self.context['deadlineEpoch'])}}
        self.store.reads[('dynamodb', 'get-item')] = {'Item': item}
        guard = host.DeadlineGuard(self.store, self.context, self.root); guard(force=True)
        item['FencingToken']['N'] = '43'
        with self.assertRaises(ValueError):
            guard(force=True)
        item['FencingToken']['N'] = '42'; (self.root / 'STOP').touch()
        with self.assertRaises(ValueError):
            guard(force=True)
        (self.root / 'STOP').unlink(); guard.deadline = time.time() - 1
        with self.assertRaises(ValueError):
            guard(force=True)

    def test_retirement_can_read_a_stopped_source_but_deadline_still_applies(self):
        self.context['operation'] = 'retire'
        (self.root / 'STOP').touch()
        with patch.object(host.restore, 'Lease', return_value=MagicMock()):
            guard = host.DeadlineGuard(self.store, self.context, self.root)
            guard(force=True)
            guard.deadline = time.time() - 1
            with self.assertRaises(ValueError):
                guard(force=True)


class HostOrchestrationTests(Fixture):
    def setup_host(self, mode='create'):
        self.manifest = self.new_manifest(mode)
        self.context = self.new_context()
        self.output = self.root / ('snapshot-' + self.operation); self.output.mkdir(mode=0o700)
        self.release = self.root / 'release'; self.release.mkdir(mode=0o700)
        self.runtime = self.root / 'runtime-fixture'; self.runtime.mkdir(mode=0o700)
        (self.root / 'private').mkdir(mode=0o700)
        host.write(self.root / 'private/accounts.private.json', {'credentials': [{'password': 'PRIVATE_SENTINEL'}]})
        host.write(self.release / 'accounts.json', {'public': True})
        host.write(self.root / 'envelope.json', {'objects': {}})
        host.write(self.root / 'toolchain.json', {})
        self.value = {'rds': self.target | self.context['rds'], 'release': str(self.release),
            'envelopeSha256': 'e' * 64, 'privateAccounts': str(self.root / 'private/accounts.private.json')}
        host.write(self.root / 'restore-config.json', self.value)
        if mode in host.SOURCE_OPERATIONS:
            self.manifest['source']['restoreConfigSha256'] = host.sha(self.root / 'restore-config.json')
            self.manifest['source']['privateAccountsSha256'] = host.sha(self.root / 'private/accounts.private.json')
        wrapper = {'scope': 'final-b-rds', 'toolchain': {}, 'files': {'envelope': {'sha256': 'e' * 64}}}
        self.manifest['awsPreparation'] = self.store.add(host.BUCKET, self.manifest['awsPreparation']['key'], wrapper)
        for name, ref in self.manifest['evidence'].items():
            value = self.restore_event() if name == 'restoreEvent' else {'publicEvidence': name}
            self.manifest['evidence'][name] = self.store.add(host.artifact_bucket(ref), ref['key'], value)
        host.write(self.output / 'aws-snapshot-host.json', self.manifest)
        self.manifest_sha = host.sha(self.output / 'aws-snapshot-host.json')
        self.context['manifest']['sha256'] = self.manifest_sha
        self.context['manifest']['key'] = host.prefix(self.dataset, self.run, self.operation) + 'manifest-' + self.manifest_sha + '.json'
        self.context['manifest']['bytes'] = (self.output / 'aws-snapshot-host.json').stat().st_size
        self.store.reads[('sts', 'get-caller-identity')] = {'Account': host.ACCOUNT,
            'Arn': f'arn:aws:sts::{host.ACCOUNT}:assumed-role/airbob-lab-host-{self.run}-debezium/{self.context["hostInstanceId"]}'}
        self.guard = MagicMock()
        self.db = MagicMock(); self.db.guard = self.guard; self.db.snapshot_read_lock_connection_id = 456
        self.db.scalar.return_value = self.target['serverUuid']
        self.envelope = {'datasetId': self.dataset, 'finalScaleSelected': True, 'awsExecutionAllowed': True}
        self.core = {'source': self.target, 'snapshotIdentifier': self.manifest['snapshotIdentifier'],
                     'application': self.manifest['application']}
        self.trace = []

    def restore_event(self):
        return {'eventName': 'RestoreDBInstanceFromDBSnapshot', 'eventSource': 'rds.amazonaws.com', 'awsRegion': host.REGION,
                'recipientAccountId': host.ACCOUNT, 'eventID': '12345678-1234-1234-1234-123456789abc',
                'eventTime': '2026-09-12T17:00:00+00:00', 'requestParameters': {'dBInstanceIdentifier': self.target['identifier'],
                'dBSnapshotIdentifier': self.manifest['snapshotIdentifier']}}

    @contextlib.contextmanager
    def seams(self):
        with contextlib.ExitStack() as stack:
            for target, name, value in [
                (host, 'validate_host_paths', None), (host.preparation, 'validate_manifest', None),
                (host.preparation, 'qualify_toolchain', {}), (host.preparation, 'run', b'aws-cli/2.34.64 Python/3'),
                (host.restore, 'configuration', self.value), (host.restore, 'extract_runtime', self.runtime),
                (host.snapshot, 'qualify_runtime', {}), (host.snapshot, 'activated_runtime', contextlib.nullcontext()),
                (host.restore, 'live_rds', {}), (host.restore, 'connection', (self.db, {})),
                (host.restore, 'validate_inputs', self.envelope), (host.snapshot, 'config', {}),
                (host.snapshot, 'validate_provenance', self.core)]:
                stack.enter_context(patch.object(target, name, return_value=value))
            stack.enter_context(patch.object(host.restore, 'Aws', return_value=self.store))
            stack.enter_context(patch.object(host, 'DeadlineGuard', return_value=self.guard))
            # The in-memory API still receives every concrete publication call.
            stack.enter_context(patch.object(host, 'HostAws', return_value=self.store))
            yield stack

    def call_host(self):
        return host.host(self.manifest, self.context, self.root, self.output, self.manifest_sha)

    def fake_preflight(self, *args):
        self.trace.append('full-baseline-preflight')
        return {'state': 'RESTORED_TARGET_READ_ONLY_PREFLIGHT', 'currentFingerprintCanonicalSha256': 'a' * 64}

    def fake_create(self, configuration, value, envelope, reviewed, output, aws, runtime, db, environment, event):
        self.trace.append('create-lock-held')
        request = {'schemaVersion': 1, 'kind': host.snapshot.KIND + '-controller-create-request', 'state': 'SOURCE_READ_LOCK_HELD',
            'source': self.target, 'snapshotIdentifier': self.manifest['snapshotIdentifier'], 'requiredLease': self.context['lease']}
        host.write(output / 'snapshot-create-request.json', request)
        event('CREATE_REQUESTED', controllerRequestSha256=host.sha(output / 'snapshot-create-request.json'))
        self.assertIsNotNone(aws.read_lock_ack)
        event('SNAPSHOT_SOURCE_REVERIFIED', snapshotSourceReverified=True, sourceFreeze={'heldUntilSnapshotAvailable': True})
        self.assertIsNone(aws.read_lock_ack)
        provenance = {'state': host.snapshot.AVAILABLE, 'public': True}
        host.write(output / 'snapshot-provenance.json', provenance)
        event(host.snapshot.AVAILABLE)
        return provenance

    def fake_admission(self, *args):
        self.trace.append('fresh-source-deletion-check')
        return {'schemaVersion': 1, 'kind': host.snapshot.KIND + '-source-deletion-admission', 'source': self.target,
                'contractSha256': 'f' * 64, 'sourceDeletionAllowed': True, 'deletionExecuted': False,
                'expiresAt': (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=60)).isoformat()}

    def test_source_request_ack_provenance_and_fresh_admission_are_connected(self):
        self.setup_host()
        with self.seams() as stack:
            stack.enter_context(patch.object(host.snapshot, 'source_preflight', side_effect=self.fake_preflight))
            stack.enter_context(patch.object(host.snapshot, 'create_snapshot', side_effect=self.fake_create))
            stack.enter_context(patch.object(host.snapshot, 'deletion_admission', side_effect=self.fake_admission))
            result = self.call_host()
        self.assertEqual(self.trace, ['full-baseline-preflight', 'create-lock-held', 'fresh-source-deletion-check'])
        self.assertTrue({'snapshot-create-request.json', 'snapshot-provenance.json', 'source-deletion-admission.json'} <= set(result['objects']))
        self.assertFalse(result['sourceRetired']); self.assertFalse(result['sourceDeletionAllowed'])
        self.assertFalse(result['persistentAwsResourcesMutatedByHost'])
        self.assertFalse((self.output / '.private').exists())
        self.assertTrue((self.root / 'private/accounts.private.json').exists())
        self.assertNotIn('PRIVATE_SENTINEL', json.dumps(result))

    def test_fresh_retirement_never_recreates_snapshot_or_imports(self):
        self.setup_host('retire')
        with self.seams() as stack:
            create = stack.enter_context(patch.object(host.snapshot, 'create_snapshot', side_effect=AssertionError('must not create')))
            prepare = stack.enter_context(patch.object(host.snapshot, 'prepare_restored', side_effect=AssertionError('must not prepare')))
            stack.enter_context(patch.object(host.snapshot, 'verify', return_value=self.core))
            stack.enter_context(patch.object(host.snapshot, 'core_contract', return_value=self.core))
            stack.enter_context(patch.object(host.snapshot, 'deletion_admission', side_effect=self.fake_admission))
            result = self.call_host()
        create.assert_not_called(); prepare.assert_not_called()
        self.assertEqual(self.trace, ['fresh-source-deletion-check'])
        self.assertIn('source-deletion-admission.json', result['objects'])
        self.assertFalse(result['sourceRetired'])

    def test_prepare_uses_exact_preflight_and_only_snapshot_prepare(self):
        self.setup_host('prepare')
        def prepare(configuration, value, envelope, reviewed, output, aws, runtime, db, environment, event):
            self.trace.append('preparation-only')
            self.assertEqual(configuration['operation'], 'prepare')
            self.assertEqual(value['lease'], self.context['lease'])
            self.assertEqual(reviewed['configSha256'], host.sha(self.output / 'snapshot-config.json'))
            event(host.snapshot.PREPARED, snapshotWholeBaselineVerified=True, sqlImportSkipped=True)
            host.write(output / 'prepared-fingerprint.json', {'publicFingerprint': True})
            host.write(output / 'data-only-preparation.json', {'state': 'DATABASE_INVENTORY_LOGIN_VERIFIED', 'sourceMode': 'verified-global-b-snapshot'})
        with self.seams() as stack:
            stack.enter_context(patch.object(host, 'stage_target', return_value=self.value))
            stack.enter_context(patch.object(host.snapshot, 'prepare_preflight', side_effect=self.fake_preflight))
            stack.enter_context(patch.object(host.snapshot, 'prepare_restored', side_effect=prepare))
            create = stack.enter_context(patch.object(host.snapshot, 'create_snapshot', side_effect=AssertionError('must not create')))
            result = self.call_host()
        self.assertEqual(self.trace, ['full-baseline-preflight', 'preparation-only'])
        self.assertFalse(result['sqlImportExecuted']); create.assert_not_called()
        self.assertIn('data-only-preparation.json', result['objects'])

    def test_failure_retains_original_private_accounts_and_publishes_no_secret(self):
        self.setup_host()
        original = (self.root / 'private/accounts.private.json').read_bytes()
        def failure(*args):
            event = args[-1]
            event('SOURCE_FROZEN_AND_VERIFIED', contractSha256='d' * 64)
            raise RuntimeError('PRIVATE_SENTINEL payment payload')
        with self.seams() as stack:
            stack.enter_context(patch.object(host.snapshot, 'source_preflight', side_effect=self.fake_preflight))
            stack.enter_context(patch.object(host.snapshot, 'create_snapshot', side_effect=failure))
            with self.assertRaises(RuntimeError):
                self.call_host()
        self.assertEqual((self.root / 'private/accounts.private.json').read_bytes(), original)
        self.assertFalse((self.output / 'host-receipt.json').exists())
        ref, raw = self.store.objects[(host.EVIDENCE, self.context['evidencePrefix'] + 'snapshot-operation.json')]
        proof = json.loads(raw)
        self.assertEqual(proof['state'], 'FAILED_RESOURCES_RETAINED')
        self.assertNotIn(b'PRIVATE_SENTINEL', raw)
        self.assertFalse(proof['sourceRetired']); self.assertFalse(proof['resourceDeletionExecuted'])

    def test_wrong_live_uuid_stops_before_preflight_or_preparation(self):
        self.setup_host('prepare'); self.db.scalar.return_value = 'ffffffff-ffff-ffff-ffff-ffffffffffff'
        with self.seams() as stack:
            stack.enter_context(patch.object(host, 'stage_target', return_value=self.value))
            preflight = stack.enter_context(patch.object(host.snapshot, 'prepare_preflight'))
            with self.assertRaises(ValueError):
                self.call_host()
        preflight.assert_not_called()

    def test_new_target_generates_credentials_for_observed_new_uuid_before_preflight(self):
        self.setup_host('prepare')
        (self.root / 'restore-config.json').unlink()
        (self.root / 'private/accounts.private.json').unlink(); (self.root / 'private').rmdir()
        self.value['rds']['serverUuid'] = '00000000-0000-0000-0000-000000000000'
        self.core['source'] = self.target | {'serverUuid': 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'}
        generated = []
        def create_credentials(bundle, path, environment):
            self.trace.append('target-private-credentials')
            generated.append(environment); path.mkdir(mode=0o700)
            host.write(path / 'accounts.private.json', {'credentials': [{'password': 'PRIVATE_SENTINEL'}]})
        accounts = MagicMock(); accounts.create_private_credentials.side_effect = create_credentials
        def prepare(*args):
            args[-1](host.snapshot.PREPARED, snapshotWholeBaselineVerified=True)
        with self.seams() as stack:
            stack.enter_context(patch.object(host, 'stage_target', return_value=self.value))
            stack.enter_context(patch.object(host.preparation, 'load_sealed_accounts', return_value=accounts))
            stack.enter_context(patch.object(host.snapshot, 'prepare_preflight', side_effect=self.fake_preflight))
            stack.enter_context(patch.object(host.snapshot, 'prepare_restored', side_effect=prepare))
            self.call_host()
        self.assertEqual(generated, ['aws:' + self.target['resourceId'] + ':' + self.target['serverUuid']])
        self.assertEqual(self.trace, ['target-private-credentials', 'full-baseline-preflight'])
        self.assertEqual(host.read(self.root / 'restore-config.json')['rds']['serverUuid'], self.target['serverUuid'])

    def test_cleanup_failure_does_not_replace_original_preparation_failure(self):
        self.setup_host()
        def removal(path):
            raise OSError('PRIVATE_CLEANUP_SENTINEL')
        with self.seams() as stack:
            stack.enter_context(patch.object(host.snapshot, 'source_preflight', side_effect=ValueError('original preflight failure')))
            stack.enter_context(patch.object(host.shutil, 'rmtree', side_effect=removal))
            with self.assertRaisesRegex(ValueError, 'original preflight failure'):
                self.call_host()
        self.assertEqual(host.read(self.output / 'cleanup-failure.json')['state'], 'PRIVATE_CONNECTION_CLEANUP_INCOMPLETE')
        self.assertFalse((self.output / 'host-receipt.json').exists())

    def test_failed_fresh_admission_does_not_claim_source_retired_or_delete(self):
        self.setup_host()
        with self.seams() as stack:
            stack.enter_context(patch.object(host.snapshot, 'source_preflight', side_effect=self.fake_preflight))
            stack.enter_context(patch.object(host.snapshot, 'create_snapshot', side_effect=self.fake_create))
            stack.enter_context(patch.object(host.snapshot, 'deletion_admission', side_effect=ValueError('deadline')))
            with self.assertRaises(ValueError):
                self.call_host()
        self.assertFalse((self.output / 'host-receipt.json').exists())
        proof = host.read(self.output / 'execute/snapshot-operation.json')
        self.assertFalse(proof['sourceRetired'])
        self.assertTrue(proof['snapshotSourceReverified'])
        self.assertIn('snapshot-provenance.json', proof['publicObjects'])
        self.assertNotIn('source-deletion-admission.json', proof['publicObjects'])

    def test_late_publication_failure_issues_separate_retry_proof_without_overwrite(self):
        self.setup_host()
        def fail_terminal_publication():
            if self.store.calls[-1][self.store.calls[-1].index('--key') + 1].endswith('/host-receipt.json'):
                raise RuntimeError('PRIVATE_PUBLICATION_SENTINEL')
        self.store.before_put = fail_terminal_publication
        with self.seams() as stack:
            stack.enter_context(patch.object(host.snapshot, 'source_preflight', side_effect=self.fake_preflight))
            stack.enter_context(patch.object(host.snapshot, 'create_snapshot', side_effect=self.fake_create))
            stack.enter_context(patch.object(host.snapshot, 'deletion_admission', side_effect=self.fake_admission))
            with self.assertRaises(RuntimeError):
                self.call_host()
        original = json.loads(self.store.objects[(host.EVIDENCE, self.context['evidencePrefix'] + 'snapshot-operation.json')][1])
        failed = self.store.objects[(host.EVIDENCE, self.context['evidencePrefix'] + 'failure/snapshot-operation.json')][1]
        self.assertEqual(original['state'], host.snapshot.AVAILABLE)
        self.assertEqual(json.loads(failed)['state'], 'FAILED_RESOURCES_RETAINED')
        self.assertTrue(json.loads(failed)['snapshotSourceReverified'])
        self.assertNotIn(b'PRIVATE_PUBLICATION_SENTINEL', failed)


class TargetStagingTests(Fixture):
    def setUp(self):
        super().setUp()
        self.manifest = self.new_manifest('prepare'); self.context = self.new_context()
        before_ref = self.store.add(host.BUCKET, f'datasets/{self.dataset}/before-fingerprint.json', {'baseline': True})
        envelope = {'objects': {'before-fingerprint.json': before_ref},
                    'storage': {'requiredAdditionalDataHostFreeBytes': 100, 'runtimeExtractionBytes': 100}}
        payloads = {'envelope': envelope, 'publicationReceipt': {'published': True}, 'binlogBasis': {'bytes': 100},
                    'smallRdsReceipt': {'smallPrerequisite': True}}
        files = {name: self.store.add(host.BUCKET, f'datasets/{self.dataset}-aws-preparation/files/{name}.json', data)
                 for name, data in payloads.items()}
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w') as jar:
            jar.writestr('BOOT-INF/classes/db/migration/V28__unit.sql', 'SELECT 1;')
        files['appJar'] = self.store.add_bytes(host.BUCKET, f'datasets/{self.dataset}-aws-preparation/files/app.jar', archive.getvalue())
        files['rdsCaBundle'] = self.store.add_bytes(host.BUCKET, f'datasets/{self.dataset}-aws-preparation/files/rds-ca.pem', b'PUBLIC_CA')
        self.wrapper = {'files': files, 'binlogAdditionalReserveBytes': 100}

    def stage(self):
        import growth_b_aws_contract
        with patch.object(host.preparation, 'validate_envelope_metadata'), patch.object(growth_b_aws_contract, 'validate_envelope'):
            return host.stage_target(self.manifest, self.context, self.wrapper, self.root, self.root, self.store, MagicMock())

    def test_fresh_staging_builds_config_without_import_or_database_connection(self):
        value = self.stage()
        self.assertEqual(value['rds']['resourceId'], self.target['resourceId'])
        self.assertEqual(value['rds']['serverUuid'], '00000000-0000-0000-0000-000000000000')
        self.assertEqual((self.root / 'migrations/V28__unit.sql').read_text(), 'SELECT 1;')
        self.assertFalse((self.root / 'restore-config.json').exists())
        self.assertTrue(all(call[:2] == ('s3api', 'get-object') for call in self.store.calls))

    def test_preparation_retry_reuses_original_config_and_checks_every_artifact(self):
        value = self.stage(); value['rds']['serverUuid'] = self.target['serverUuid']
        host.write(self.root / 'restore-config.json', value)
        original = (self.root / 'restore-config.json').read_bytes()
        with self.assertRaises(ValueError):
            self.stage()
        self.manifest['resume'] = {'restoreConfigSha256': host.sha(self.root / 'restore-config.json'),
            'operationReceipt': self.ref(f'data-bootstrap/{self.run}/{self.dataset}-snapshot/old-001/snapshot-operation.json')}
        resumed = self.stage()
        self.assertEqual(resumed['rds']['serverUuid'], self.target['serverUuid'])
        self.assertEqual((self.root / 'restore-config.json').read_bytes(), original)
        self.assertTrue(any(call[:2] == ('s3api', 'head-object') for call in self.store.calls))
        (self.root / 'migrations/V28__unit.sql').write_text('CHANGED')
        with self.assertRaises(ValueError):
            self.stage()

    def test_retry_cannot_retarget_another_resource_even_with_new_config_hash(self):
        value = self.stage(); value['rds']['serverUuid'] = self.target['serverUuid']; value['rds']['resourceId'] = 'db-' + 'B' * 24
        host.write(self.root / 'restore-config.json', value)
        self.manifest['resume'] = {'restoreConfigSha256': host.sha(self.root / 'restore-config.json'),
            'operationReceipt': self.ref(f'data-bootstrap/{self.run}/{self.dataset}-snapshot/old-001/snapshot-operation.json')}
        with self.assertRaises(ValueError):
            self.stage()


if __name__ == '__main__':
    unittest.main()
