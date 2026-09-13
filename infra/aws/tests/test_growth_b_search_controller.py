"""No-cloud admission, immutable transport and uncertain-submission regressions."""
import base64
import contextlib
import copy
import gzip
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_search_controller as c
import growth_b_search_host as h

NOW = h.DEADLINE - 30000
INSTANCE = 'i-' + '1' * 17
CID = '12345678-1234-1234-1234-123456789012'


def operation():
    return {'schemaVersion': 1, 'kind': c.KIND, 'stage': 'native-restore', 'operationId': 'native-final-01',
        'runId': 'lab-source-01', 'datasetId': c.DATASET, 'serviceRelease': 'dependencies-01',
        'executionCommit': 'a' * 40, 'sourceArchiveSha256': c.source_archive()[1]['sha256'],
        'manifest': {'key': f'datasets/{c.DATASET}-aws-service/dependencies-01/aws-service.json',
            'versionId': 'exact-v1', 'sha256': 'b' * 64, 'bytes': 3000}}


def context(op):
    return {'schemaVersion': 1, 'kind': c.CONTEXT_KIND, 'executionCommit': op['executionCommit'],
        'approvedExecutionDeadlineEpoch': h.DEADLINE, 'controllerDeadlineEpoch': NOW + 17000,
        'operator': {'schemaVersion': 2, 'runId': op['runId'], 'datasetRelease': c.DATASET, 'globalBPrepareOnly': True,
            'databaseBootstrap': 'dump', 'mode': 'performance', 'dnsMode': 'direct-only', 'rdsEngineVersion': '8.4.11',
            'cacheEnabled': False, 'loadGeneratorEnabled': False, 'fencingToken': 74, 'expiresAt': str(h.DEADLINE),
            'approvedExecutionDeadlineEpoch': h.DEADLINE},
        'lease': {'table': 'airbob-performance-lab-orchestration-lease', 'lockName': 'airbob-performance-lab',
            'owner': 'reviewed-controller-75', 'runId': op['runId'], 'command': 'up', 'fencingToken': 75},
        'phase2': {'run_id': op['runId'], 'fencing_token': 74, 'vpc_id': 'vpc-12345',
            'services': {'debezium': INSTANCE, 'elasticsearch': 'i-' + '2' * 17, 'kafka': 'i-' + '3' * 17}},
        'phase3': {'dataset_release': c.DATASET, 'database_bootstrap': 'dump', 'rds_instance_id': 'airbob-' + op['runId'],
            'rds_resource_id': 'db-' + 'A' * 24, 'rds_endpoint': 'example.invalid', 'rds_engine_version': '8.4.11', 'rds_configured_storage_gib': 100},
        'phase4': {'app_enabled': False, 'capacity': {'min': 0, 'desired': 0, 'max': 0},
            'accommodation_detail_cache_enabled': False, 'load_generator_enabled': False, 'auto_scaling_group_name': 'airbob-' + op['runId'] + '-app'},
        'serviceState': {'selected': True, 'manifest_key': op['manifest']['key'], 'manifest_version_id': 'exact-v1',
            'manifest_sha256': 'b' * 64, 'readiness_receipt': None}}


class AdmissionTests(unittest.TestCase):
    def setUp(self):
        self.op = operation(); self.ctx = context(self.op)

    def test_actual_archive_and_unrestored_dependency_context_are_required(self):
        c.validate_operation(self.op)
        c.validate_context(self.ctx, self.op, now=NOW)
        for patch in ({'sourceArchiveSha256': '0' * 64}, {'stage': 'bootstrap'}, {'runId': 'lab-../other'},
                      {'operationId': 'native\nAWS_PROFILE=other'}, {'extra': True}, {'datasetId': 'global-growth-b-' + 'f' * 16}):
            with self.subTest(patch=patch), self.assertRaises(h.Rejected):
                c.validate_operation(self.op | patch)

    def test_public_reference_cannot_drift_to_another_release(self):
        for patch in ({'versionId': 'null'}, {'key': self.op['manifest']['key'].replace('dependencies-01', 'other-01')},
                      {'key': self.op['manifest']['key'].replace('/aws-service.json', '/../aws-service.json')}, {'bytes': 0}):
            with self.subTest(patch=patch), self.assertRaises(h.Rejected):
                c.validate_operation(self.op | {'manifest': self.op['manifest'] | patch}, check_sources=False)

    def test_no_original_deadline_or_resource_fence_extension(self):
        for field, value in (('approvedExecutionDeadlineEpoch', h.DEADLINE + 1),
                             ('controllerDeadlineEpoch', NOW + 18001), ('controllerDeadlineEpoch', NOW + 90)):
            with self.subTest(field=field, value=value), self.assertRaises(h.Rejected):
                c.validate_context(self.ctx | {field: value}, self.op, now=NOW)
        for patch in ({'cacheEnabled': True}, {'expiresAt': str(h.DEADLINE + 1)}, {'databaseBootstrap': 'snapshot'},
                      {'approvedExecutionDeadlineEpoch': h.DEADLINE - 1}, {'globalBPrepareOnly': False}):
            with self.subTest(patch=patch), self.assertRaises(h.Rejected):
                c.validate_context(self.ctx | {'operator': self.ctx['operator'] | patch}, self.op, now=NOW)
        for patch in ({'fencingToken': 74}, {'runId': 'lab-other'}, {'command': 'down'}):
            with self.subTest(patch=patch), self.assertRaises(h.Rejected):
                c.validate_context(self.ctx | {'lease': self.ctx['lease'] | patch}, self.op, now=NOW)

    def test_any_application_or_readiness_blocks_native_restore(self):
        for patch in ({'app_enabled': True}, {'capacity': {'min': 0, 'desired': 0, 'max': 1}},
                      {'load_generator_enabled': True}, {'accommodation_detail_cache_enabled': True}):
            with self.subTest(patch=patch), self.assertRaises(h.Rejected):
                c.validate_context(self.ctx | {'phase4': self.ctx['phase4'] | patch}, self.op, now=NOW)
        for patch in ({'selected': False}, {'manifest_version_id': 'other'}, {'readiness_receipt': {'published': True}}):
            with self.subTest(patch=patch), self.assertRaises(h.Rejected):
                c.validate_context(self.ctx | {'serviceState': self.ctx['serviceState'] | patch}, self.op, now=NOW)


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve(); self.journal = c.Journal(self.root / 'journal')
        self.calls = []; self.fail_send = False; self.status = 'InProgress'; self.output = '{"passed":true}'
        self.ssm = c.Ssm(self, self.journal, lambda: None, NOW + 2000, clock=lambda: NOW, sleep=lambda _: None)

    def call(self, *args):
        self.calls.append(args)
        if args[:2] == ('ssm', 'send-command'):
            if self.fail_send:
                raise RuntimeError('PRIVATE_PROVIDER_MESSAGE')
            return {'Command': {'CommandId': CID}}
        if args[:2] == ('ssm', 'list-command-invocations'):
            return {'CommandInvocations': [{'Status': self.status}]}
        if args[:2] == ('ssm', 'get-command-invocation'):
            return {'CommandId': CID, 'InstanceId': INSTANCE, 'Status': self.status,
                'ResponseCode': 0 if self.status == 'Success' else 1, 'StandardOutputContent': self.output,
                'StandardErrorContent': 'PRIVATE_PROVIDER_MESSAGE'}
        raise AssertionError('unexpected action')

    def test_unknown_submission_is_durable_and_cannot_be_repeated(self):
        self.fail_send = True
        with self.assertRaises(RuntimeError):
            self.ssm.submit('prepare-source', INSTANCE, 'public command')
        self.assertTrue(self.ssm.unknown)
        self.assertEqual(['SSM_INTENT'], [v['kind'] for v in self.journal.entries])
        with self.assertRaisesRegex(h.Rejected, 'UNKNOWN_SUBMISSION'):
            self.ssm.submit('prepare-source', INSTANCE, 'public command')
        self.assertEqual(1, len(self.calls))
        self.assertNotIn('PRIVATE_PROVIDER_MESSAGE', json.dumps(self.journal.entries))

    def test_active_worker_only_allows_closed_ack_and_connect_observation(self):
        item = self.ssm.submit('restore-on-es', INSTANCE, 'worker')
        self.assertIsNone(self.ssm.poll(item))
        with self.assertRaisesRegex(h.Rejected, 'MUST_SETTLE'):
            self.ssm.submit('restore-on-es', INSTANCE, 'duplicate')
        with self.assertRaisesRegex(h.Rejected, 'CONCURRENT_ACTION'):
            self.ssm.submit('stage-archive', INSTANCE, 'edit', acknowledgement=True)
        self.ssm.submit('lease-ack', INSTANCE, 'ack', acknowledgement=True)
        self.assertEqual(2, len([a for a in self.calls if a[:2] == ('ssm', 'send-command')]))

    def test_failed_command_never_exports_provider_stdout_or_stderr(self):
        item = self.ssm.submit('prepare-source', INSTANCE, 'worker')
        self.status = 'Failed'; self.output = '{"password":"PRIVATE_PROVIDER_MESSAGE"}'
        with self.assertRaisesRegex(h.Rejected, 'SSM_COMMAND_FAILED'):
            self.ssm.poll(item)
        self.assertFalse(self.ssm.pending)
        self.assertNotIn('PRIVATE_PROVIDER_MESSAGE', json.dumps(self.journal.entries))
        self.assertFalse(any(a[1] == 'cancel-command' for a in self.calls))

    def test_success_still_requires_closed_public_bounded_output(self):
        item = self.ssm.submit('prepare-source', INSTANCE, 'worker')
        self.status = 'Success'; self.output = '{"password":"private"}'
        with self.assertRaises(h.Rejected):
            self.ssm.poll(item)
        self.assertNotIn('private', json.dumps(self.journal.entries))

    def test_request_deadline_and_size_reject_before_intent(self):
        for kwargs in ({'seconds': 2001}, {'seconds': 0}):
            with self.assertRaises(h.Rejected): self.ssm.submit('worker', INSTANCE, 'command', **kwargs)
        with self.assertRaisesRegex(h.Rejected, 'COMMAND_LIMIT'):
            self.ssm.submit('worker', INSTANCE, 'x' * 32768)
        self.assertEqual([], self.journal.entries); self.assertEqual([], self.calls)

    def test_guard_failure_prevents_any_submission(self):
        self.ssm.guard = mock.Mock(side_effect=h.Rejected('LEASE_CHANGED'))
        with self.assertRaisesRegex(h.Rejected, 'LEASE_CHANGED'):
            self.ssm.submit('worker', INSTANCE, 'command')
        self.assertEqual([], self.calls); self.assertEqual([], self.journal.entries)


class AckTests(unittest.TestCase):
    def setUp(self):
        self.ctx = {'controllerDeadlineEpoch': NOW + 1000, 'expiresAt': h.DEADLINE,
            'approvedExecutionDeadlineEpoch': h.DEADLINE, 'operationId': 'native-001', 'runId': 'lab-source-01',
            'resourceFence': 74, 'lease': {'owner': 'owner-75', 'fencingToken': 75},
            'hosts': {'elasticsearch': {'instanceId': INSTANCE}}}

    def test_ack_keeps_original_fence_lease_and_short_deadline(self):
        first = c.ack_value(self.ctx, 'a' * 64, 0, '0' * 64, NOW)
        second = c.ack_value(self.ctx, 'a' * 64, 1, c.checksum(c.encoded(first)), NOW + 40)
        self.assertEqual(74, first['resourceFence']); self.assertEqual(75, first['lease']['fencingToken'])
        self.assertEqual(NOW + 90, first['expiresAt']); self.assertEqual(c.checksum(c.encoded(first)), second['previousSha256'])
        self.assertEqual(NOW + 1000, c.ack_value(self.ctx, 'a' * 64, 20, 'b' * 64, NOW + 950)['expiresAt'])
        for sequence, issued in ((500, NOW), (20, NOW + 990)):
            with self.assertRaises(h.Rejected): c.ack_value(self.ctx, 'a' * 64, sequence, '0' * 64, issued)

    def test_new_writer_blocks_ack_before_remote_write(self):
        controller = object.__new__(c.Controller)
        controller.clock = lambda: NOW; controller.last_ack_at = 0
        controller.topology = mock.Mock(side_effect=h.Rejected('WRITER_ASG_NOT_ZERO'))
        controller.ssm = mock.Mock(); controller.discovery = mock.Mock()
        with self.assertRaisesRegex(h.Rejected, 'WRITER_ASG_NOT_ZERO'):
            controller.acknowledge(force=True)
        controller.discovery.assert_not_called(); controller.ssm.run.assert_not_called()

    def test_host_success_requires_terminal_worker_and_completed_private_cleanup(self):
        for phase, state in (('prepare-source', 'FROZEN_IMPORT_BASELINE_PROOFS_EXPORTED'),
                             ('restore-on-es', 'NATIVE_SEARCH_RESTORED_AND_SOURCE_VERIFIED')):
            result = {'state': state, 'output': str(h.op_root(self.ctx) / phase), 'sha256': 'a' * 64,
                      'ownedWorkerTerminal': True, 'privateMaterialRemoved': True}
            self.assertEqual(result, c.validate_host_phase_result(self.ctx, phase, result))
            for patch in ({'ownedWorkerTerminal': False}, {'privateMaterialRemoved': False},
                          {'sha256': None}, {'output': '/opt/other'}, {'state': 'FAILED'}, {'unknown': True}):
                with self.subTest(phase=phase, patch=patch), self.assertRaises(h.Rejected):
                    c.validate_host_phase_result(self.ctx, phase, result | patch)


class SourceArchiveTests(unittest.TestCase):
    def test_archive_is_deterministic_and_includes_exact_host_and_controller_sources(self):
        raw, meta = c.source_archive()
        self.assertEqual(raw, c.source_archive()[0]); self.assertEqual(14, len(meta['files']))
        self.assertEqual(c.source_files(), {name: ref['sha256'] for name, ref in meta['files'].items()})
        self.assertLessEqual(len(gzip.decompress(raw)), c.MAX_EXPANDED)

    def test_source_symlinks_drift_and_paths_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); name = 'infra/aws/scripts/one.py'
            path = root / name; path.parent.mkdir(parents=True); path.write_bytes(b'pass\n')
            selected = {name: c.checksum(path.read_bytes())}
            c.source_archive(selected, root)
            path.write_bytes(b'changed\n')
            with self.assertRaises(h.Rejected): c.source_archive(selected, root)
            other = path.with_name('two.py'); other.write_bytes(b'pass\n'); path.unlink(); path.symlink_to(other)
            with self.assertRaises(h.Rejected): c.source_archive(selected, root)
            with self.assertRaises(h.Rejected): c.source_archive({'infra/aws/scripts/../secret.py': 'a' * 64}, root)

    def test_remote_programs_compile_without_changing_payload_bytes(self):
        payload = {'instanceId': INSTANCE, 'runId': 'lab-source-01', 'operationId': 'native-01', 'deadlineEpoch': NOW + 1000}
        expected = base64.b64encode(c.encoded(payload)).decode()
        for body in (c.DISCOVER, c.INIT, c.CHUNK_WRITE, c.ASSEMBLE, c.ACK_WRITE, c.PUBLIC_READ, c.BOOTSTRAP_ES, c.LAUNCH):
            program = c.remote_program(body, payload)
            self.assertIn(expected, program); self.assertLess(len(program), c.MAX_COMMAND)

    def test_controller_cannot_call_resource_mutations_or_cancellation(self):
        aws = c.Aws(NOW + 1000, clock=lambda: NOW)
        for action in (('rds', 'delete-db-instance'), ('ec2', 'terminate-instances'), ('autoscaling', 'update-auto-scaling-group'),
                       ('ssm', 'cancel-command'), ('iam', 'put-role-policy'), ('secretsmanager', 'get-secret-value')):
            with self.subTest(action=action), self.assertRaisesRegex(h.Rejected, 'AWS_ACTION_NOT_ALLOWED'):
                aws.call(*action)


class RemoteFilesystemTests(unittest.TestCase):
    """Execute the real remote bodies against owned temporary files, no cloud."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve(); self.area = self.root / 'operation'
        self.area.mkdir(mode=0o700)
        for name in ('chunks', 'acks'): (self.area / name).mkdir(mode=0o700)
        self.payload = {'runId': 'lab-source-01', 'operationId': 'native-001', 'instanceId': INSTANCE, 'deadlineEpoch': NOW + 1000}

    def execute(self, body, patch):
        payload = self.payload | patch
        # DMI/root checks belong to admission; emulate the same owner with the
        # current test user for actual filesystem execution on macOS and Linux.
        code = c.REMOTE_COMMON.split('need(os.getuid()', 1)[0].replace('__PAYLOAD__', base64.b64encode(c.encoded(payload)).decode())
        namespace = {}; exec(compile(code, '<remote-library>', 'exec'), namespace)
        namespace['area'] = self.area
        namespace['time'] = types.SimpleNamespace(time=lambda: NOW)
        namespace['regular'] = lambda path, maximum=8388608: h.regular(path, maximum, private=True)
        def parent(path, make=False):
            self.assertTrue(path.is_relative_to(self.root))
            if make: path.mkdir(mode=0o700, parents=True, exist_ok=True)
            self.assertEqual(path, path.resolve())
        namespace['safe_parent'] = parent
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            exec(compile(body, '<remote-body>', 'exec'), namespace)
        return json.loads(output.getvalue())

    def chunk(self, kind, sequence, raw):
        return self.execute(c.CHUNK_WRITE, {'kind': kind, 'sequence': sequence,
            'data': base64.b64encode(raw).decode(), 'sha256': c.checksum(raw)})

    def test_exact_chunks_assemble_to_private_bytes_and_replay_is_rejected(self):
        raw = b'{"public":"' + b'a' * 7000 + b'"}\n'
        for seq, offset in enumerate(range(0, len(raw), c.CHUNK)):
            self.chunk('context', seq, raw[offset:offset + c.CHUNK])
        self.execute(c.ASSEMBLE, {'kind': 'context', 'bytes': len(raw), 'sha256': c.checksum(raw), 'count': 2})
        self.assertEqual(raw, (self.area / 'context.json').read_bytes())
        self.assertEqual(0o600, (self.area / 'context.json').stat().st_mode & 0o777)
        with self.assertRaises(FileExistsError): self.chunk('context', 0, raw[:c.CHUNK])
        self.assertEqual(raw, (self.area / 'context.json').read_bytes())

    def test_mismatched_missing_and_extra_chunks_cannot_publish_context(self):
        self.chunk('context', 0, b'first')
        with self.assertRaises(RuntimeError):
            self.execute(c.ASSEMBLE, {'kind': 'context', 'bytes': 10, 'sha256': 'a' * 64, 'count': 2})
        self.assertFalse((self.area / 'context.json').exists())
        with self.assertRaises(RuntimeError):
            self.execute(c.CHUNK_WRITE, {'kind': 'context', 'sequence': 1, 'data': base64.b64encode(b'wrong').decode(), 'sha256': 'a' * 64})
        self.assertFalse((self.area / 'chunks/context-000001').exists())

    def test_started_worker_prevents_new_source_or_context_writes(self):
        h.new_file(self.area / 'worker-intent.json', b'{}')
        for kind in ('context', 'archive', 'package'):
            with self.subTest(kind=kind), self.assertRaises(RuntimeError): self.chunk(kind, 0, b'input')
        self.assertEqual([], list((self.area / 'chunks').iterdir()))

    def test_ack_writes_continue_during_worker_but_bad_identity_and_replay_fail(self):
        ctx = AckTests(); ctx.setUp(); selected = ctx.ctx
        raw_context = c.encoded(selected); h.new_file(self.area / 'context.json', raw_context)
        h.new_file(self.area / 'worker-intent.json', b'{}')
        ack = c.ack_value(selected, c.checksum(raw_context), 0, '0' * 64, NOW)
        def write(value):
            raw = c.encoded(value)
            return self.execute(c.ACK_WRITE, {'data': base64.b64encode(raw).decode(), 'sha256': c.checksum(raw)})
        with self.assertRaises(RuntimeError): write(ack | {'resourceFence': 73})
        self.assertEqual([], list((self.area / 'acks').iterdir()))
        self.assertTrue(write(ack)['passed'])
        with self.assertRaises(FileExistsError): write(ack)
        self.assertEqual(c.encoded(ack), (self.area / 'acks/000000.json').read_bytes())

    def test_public_reader_cannot_read_connection_files_or_follow_links(self):
        private = self.area / '.private'; private.mkdir(mode=0o700)
        h.new_file(private / 'connection.json', b'{"password":"PRIVATE_SENTINEL"}')
        with self.assertRaises(RuntimeError):
            self.execute(c.PUBLIC_READ, {'name': '../.private/connection.json', 'offset': 0})
        public = self.area / 'prepare-source/public'; public.mkdir(mode=0o700, parents=True)
        (public / 'source-package.json').symlink_to(private / 'connection.json')
        with self.assertRaises(h.Rejected): self.execute(c.PUBLIC_READ, {'name': 'source', 'offset': 0})

    def test_real_archive_extract_matches_every_source_and_contains_no_links(self):
        raw, metadata = c.source_archive()
        for seq, offset in enumerate(range(0, len(raw), c.CHUNK)): self.chunk('archive', seq, raw[offset:offset + c.CHUNK])
        self.execute(c.ASSEMBLE, {'kind': 'archive', 'bytes': len(raw), 'sha256': c.checksum(raw),
            'count': (len(raw) + c.CHUNK - 1) // c.CHUNK, 'files': metadata['files']})
        for name, ref in metadata['files'].items():
            target = self.area / 'tools' / name
            self.assertFalse(target.is_symlink()); self.assertEqual(ref['sha256'], c.checksum(target.read_bytes()))


if __name__ == '__main__':
    unittest.main()
