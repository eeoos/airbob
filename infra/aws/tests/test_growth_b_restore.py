import gzip
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('growth_b_restore_tested', ROOT/'scripts/restore-growth-b-local.py')
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)


class RemovalGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name)
        (self.output/'.private').mkdir()
        self.baseline = {'tables': {'member': {'ddlSha256': 'a'*64, 'rowsSha256': 'b'*64}}}
        self.config = {'source': {'containerId': '1'*64, 'container': 'owned-old', 'volume': 'owned-volume',
                                  'dump': '/preserved/dump.sql.gz', 'fingerprint': '/preserved/fingerprint.json'},
                       'target': {'image': 'sha256:'+'2'*64, 'redisImage': 'sha256:'+'3'*64}, 'release': str(self.output),
                       'preservationPolicy': 'require-current-match',
                       'capacity': {'reserveHostBytes': 0, 'reserveDockerBytes': 0, 'requiredDatabaseBytes': 1, 'maxBackupBytes': 1}}
        self.observed = {'volume': {'CreatedAt': 'one'}, 'writers': [], 'dockerFreeBytes': 100, 'mysqlAllocatedBytes': 10}
        self.plan = {'source': self.observed, 'sourceFingerprint': self.baseline, 'changedTablesFromPreservedDump': []}
        self.calls = []
        for name, value in [
            ('source_observation', ({}, self.observed)), ('fingerprint', self.baseline),
            ('preservation', (self.baseline, {'gzipIntegrityPassed': True})), ('validate_inputs', None),
            ('existing', set()), ('inspect', {'CreatedAt': 'one'}), ('sha', 'f'*64),
        ]:
            mocked = patch.object(restore, name, return_value=value).start()
            setattr(self, name, mocked)
        self.addCleanup(patch.stopall)
        self.docker = patch.object(restore, 'docker', side_effect=lambda *args, **kwargs: self.calls.append(args) or b'').start()
        self.db = patch.object(restore, 'Database').start().return_value
        self.db.identity.return_value = {'superReadOnly': 1}
        self.db.scalar.return_value = 0

    def remove(self):
        restore.remove_source(self.config, self.plan, self.output, self.output, {}, lambda *args, **kwargs: None)

    def assert_no_removal(self):
        self.assertFalse(any(call[0] == 'rm' or call[:2] == ('volume', 'rm') for call in self.calls))

    def test_crc_failure_at_boundary_never_removes_source(self):
        self.validate_inputs.side_effect = EOFError('truncated gzip')
        with self.assertRaises(EOFError): self.remove()
        self.assert_no_removal()

    def test_source_change_after_review_never_removes_source(self):
        self.fingerprint.return_value = {'changed': True}
        with self.assertRaisesRegex(ValueError, 'Source changed after preflight'): self.remove()
        self.assert_no_removal()

    def test_undeclared_live_database_client_blocks_removal(self):
        self.db.scalar.return_value = 1
        with self.assertRaisesRegex(ValueError, 'undeclared'): self.remove()
        self.assert_no_removal()

    def test_failed_backup_retains_the_only_database(self):
        self.config['preservationPolicy'] = 'capture-current'
        with patch.object(restore, 'streaming', side_effect=OSError('disk full')):
            with self.assertRaises(OSError): self.remove()
        self.assert_no_removal()

    def test_removal_targets_only_the_reviewed_container_and_volume(self):
        self.remove()
        self.assertEqual(self.calls, [('stop', '--time', '30', '1'*64), ('rm', '1'*64), ('volume', 'rm', 'owned-volume')])

    def test_container_absence_is_observed_before_volume_removal(self):
        self.existing.return_value = {'1'*64}
        with self.assertRaisesRegex(ValueError, 'container still exists'): self.remove()
        self.assertNotIn(('volume', 'rm', 'owned-volume'), self.calls)

    def test_volume_replacement_race_retains_the_changed_volume(self):
        self.inspect.side_effect = [{}, {}, {'CreatedAt': 'another'}]
        with self.assertRaisesRegex(ValueError, 'Volume identity changed'): self.remove()
        self.assertNotIn(('volume', 'rm', 'owned-volume'), self.calls)


class SourceObjectBoundaryTest(unittest.TestCase):
    """Exercise production observation/preflight/removal with synthetic metadata."""
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name); (self.output / '.private').mkdir()
        (self.output / 'runtime-database-measurement.json').write_text('{"allocatedTablespaceBytes": 10}')
        self.baseline = {'tables': {'member': {'rows': 1, 'ddlSha256': 'a' * 64, 'rowsSha256': 'b' * 64}}}
        self.source = {'containerId': '1' * 64, 'container': 'owned-old', 'volume': 'owned-volume',
                       'mysqlServerUuid': '11111111-1111-1111-1111-111111111111',
                       'datasetId': 'global-growth-b-' + '2' * 16,
                       'dump': '/preserved/dump.sql.gz', 'fingerprint': '/preserved/fingerprint.json'}
        self.config = {'mode': 'local', 'source': self.source, 'release': str(self.output),
                       'target': {'container': 'owned-new', 'volume': 'owned-new-volume',
                                  'image': 'sha256:' + '3' * 64, 'redisImage': 'sha256:' + '4' * 64},
                       'writerContainers': [{'name': 'owned-writer', 'id': '5' * 64}],
                       'preservationPolicy': 'capture-current',
                       'capacity': {'reserveHostBytes': 0, 'reserveDockerBytes': 0,
                                    'requiredDatabaseBytes': 10, 'maxBackupBytes': 10}}
        self.volume = {'Name': self.source['volume'], 'Driver': 'local', 'Options': None,
                       'Mountpoint': '/unit/mysql', 'CreatedAt': 'unit-created'}
        self.unsupported = None
        self.appear_at = 1
        self.observations = 0
        self.frozen = False
        self.writer_running = True
        self.calls = []
        self.db = self.mock('Database').return_value
        self.db.identity.side_effect = lambda: {'version': '8.4.11', 'uuid': self.source['mysqlServerUuid'],
                                               'readOnly': int(self.frozen), 'superReadOnly': int(self.frozen)}
        self.db.rows.side_effect = lambda sql: [{'Database': name} for name in (*restore.SYSTEM_SCHEMAS, 'airbobdb')]
        self.db.scalar.side_effect = self.scalar
        self.db.execute.side_effect = self.execute
        self.mock('inspect', side_effect=self.inspect)
        self.mock('docker', side_effect=self.docker)
        self.mock('existing', return_value=set())
        self.fingerprint = self.mock('fingerprint', return_value=self.baseline)
        self.preservation = self.mock('preservation', return_value=(self.baseline, {'gzipIntegrityPassed': True}))
        self.streaming = self.mock('streaming')
        self.mock('inspect_dump', return_value={'gzipIntegrityPassed': True})
        self.mock('validate_inputs')
        self.mock('sha', return_value='f' * 64)
        self.event = MagicMock()

    def mock(self, name, **kwargs):
        item = patch.object(restore, name, **kwargs)
        self.addCleanup(item.stop)
        return item.start()

    def inspect(self, kind, name):
        if kind == 'container' and name == self.source['container']:
            self.observations += 1
            return {'Id': self.source['containerId'], 'State': {'Running': True},
                    'Mounts': [{'Destination': '/var/lib/mysql', 'Type': 'volume', 'Name': self.source['volume']}],
                    'Config': {'Labels': {'airbob.dataset.id': self.source['datasetId']}}}
        if kind == 'container' and name in ('owned-writer', '5' * 64):
            return {'Id': '5' * 64, 'State': {'Running': self.writer_running}}
        if kind == 'volume' and name == self.source['volume']:
            return self.volume
        if kind == 'image':
            return {}
        raise AssertionError('Unexpected synthetic Docker inspection')

    def docker(self, *args, **kwargs):
        self.calls.append(args)
        if args[:2] == ('ps', '-aq'):
            return (self.source['containerId'] + '\n').encode()
        if args[:2] == ('ps', '-a'):
            return b'owned-old\nowned-writer\n'
        if args[:3] == ('exec', self.source['containerId'], 'du'):
            return b'10 /var/lib/mysql\n'
        if args[:3] == ('exec', self.source['containerId'], 'df'):
            return b'Avail\n1000000\n'
        if args == ('stop', '--time', '30', '5' * 64):
            self.writer_running = False
            return b''
        raise AssertionError('Unexpected synthetic Docker operation')

    def scalar(self, sql):
        if self.unsupported and self.observations >= self.appear_at and \
                'FROM information_schema.' + self.unsupported + ' ' in sql:
            return 1
        return 0

    def execute(self, sql):
        self.assertEqual(sql, 'SET GLOBAL super_read_only=ON')
        self.frozen = True

    def plan(self):
        return restore.build_preflight(self.config, self.output, self.output)

    def remove(self, plan):
        restore.remove_source(self.config, plan, self.output, self.output, {}, self.event)

    def assert_no_mutation(self):
        self.db.execute.assert_not_called()
        self.streaming.assert_not_called()
        self.assertTrue(all(call[0] in ('ps', 'exec') for call in self.calls))
        self.event.assert_not_called()

    def test_preflight_rejects_each_unsupported_object_under_every_preservation_policy(self):
        for kind in ('tables', 'routines', 'events', 'triggers'):
            for policy in ('require-current-match', 'capture-current', 'discard-changes'):
                with self.subTest(kind=kind, policy=policy):
                    self.unsupported = kind; self.config['preservationPolicy'] = policy
                    with self.assertRaisesRegex(ValueError, 'unsupported views, routines, events, or triggers'):
                        self.plan()
                    self.assert_no_mutation()
                    self.fingerprint.assert_not_called()
                    self.preservation.assert_not_called()

    def test_objects_added_after_preflight_block_apply_before_freeze_backup_or_writer_stop(self):
        plan = self.plan()
        self.fingerprint.reset_mock(); self.preservation.reset_mock()
        for kind in ('tables', 'routines', 'events', 'triggers'):
            for policy in ('require-current-match', 'capture-current', 'discard-changes'):
                with self.subTest(kind=kind, policy=policy):
                    self.unsupported = kind; self.config['preservationPolicy'] = policy
                    with self.assertRaisesRegex(ValueError, 'unsupported views, routines, events, or triggers'):
                        self.remove(plan)
                    self.assert_no_mutation()
                    self.fingerprint.assert_not_called()
                    self.preservation.assert_not_called()

    def test_last_source_reobservation_rejects_an_object_appearing_after_capture(self):
        for kind in ('tables', 'routines', 'events', 'triggers'):
            with self.subTest(kind=kind):
                self.observations = 0; self.appear_at = 3; self.unsupported = kind
                self.frozen = False; self.writer_running = True
                self.calls.clear(); self.streaming.reset_mock()
                plan = self.plan()
                with self.assertRaisesRegex(ValueError, 'unsupported views, routines, events, or triggers'):
                    self.remove(plan)
                self.assertTrue(self.frozen)
                self.streaming.assert_called_once()
                self.assertEqual(self.observations, 3)
                self.assertNotIn(('stop', '--time', '30', self.source['containerId']), self.calls)
                self.assertFalse(any(call[0] == 'rm' or call[:2] == ('volume', 'rm') for call in self.calls))

    def test_unreadable_schema_catalog_prevents_freeze_or_capture(self):
        plan = self.plan()
        self.db.scalar.side_effect = ValueError('Synthetic metadata permission failure')
        for phase in (self.plan, lambda: self.remove(plan)):
            with self.assertRaisesRegex(ValueError, 'metadata permission'):
                phase()
            self.assert_no_mutation()

    def test_base_table_only_preflight_records_zero_counts_without_changing_source(self):
        plan = self.plan()
        self.assertEqual(plan['source']['unsupportedSchemaObjects'],
                         {'nonBaseTables': 0, 'routines': 0, 'events': 0, 'triggers': 0})
        queries = [call.args[0] for call in self.db.scalar.call_args_list]
        for catalog, schema_column in (('tables', 'table_schema'), ('routines', 'routine_schema'),
                                       ('events', 'event_schema'), ('triggers', 'trigger_schema')):
            query = next(sql for sql in queries if 'FROM information_schema.' + catalog + ' ' in sql)
            self.assertIn(schema_column + "='airbobdb'", query)
            if catalog == 'tables':
                self.assertIn("table_type<>'BASE TABLE'", query)
        self.assertEqual(plan['source']['mysql']['superReadOnly'], 0)
        self.assert_no_mutation()


class BoundaryTest(unittest.TestCase):
    def test_runtime_drops_unrelated_spring_and_java_injection(self):
        with patch.dict(restore.os.environ, {'JAVA_TOOL_OPTIONS': '-Dspring.datasource.url=unrelated',
                'SPRING_DATASOURCE_URL': 'unrelated', 'AIRBOB_ETL_DB_URL': 'unrelated', 'AWS_SECRET_ACCESS_KEY': 'unused'}, clear=False):
            environment = restore.runtime_environment()
            self.assertFalse(any(key in environment for key in
                    ('JAVA_TOOL_OPTIONS', 'SPRING_DATASOURCE_URL', 'AIRBOB_ETL_DB_URL', 'AWS_SECRET_ACCESS_KEY')))
            self.assertIn('PATH', environment)

    def test_racing_volume_creation_never_mounts_an_unowned_volume(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); (output/'.private').mkdir()
            config = {'datasetId': 'global-growth-b-'+'1'*16, 'target': {'container': 'new', 'volume': 'new-data'}}
            calls = []
            def docker(*args, **kwargs):
                calls.append(args)
                return b''
            with patch.object(restore, 'existing', return_value=set()), patch.object(restore, 'docker', side_effect=docker), \
                 patch.object(restore, 'inspect', return_value={'Driver': 'local', 'Options': None,
                        'Labels': {'airbob.restore.claim': 'another-operation'}}):
                with self.assertRaisesRegex(ValueError, 'concurrently claimed'):
                    restore.create_target(config, output)
            self.assertFalse(any(args[0] == 'run' or args[:2] == ('volume', 'rm') for args in calls))

    def test_large_virtual_docker_disk_does_not_bypass_physical_host_budget(self):
        capacity = {'requiredDatabaseBytes': 100, 'reserveHostBytes': 10, 'reserveDockerBytes': 10}
        with self.assertRaisesRegex(ValueError, 'physical host'):
            restore.check_capacity(capacity, host_free=50, docker_free=1000)
        restore.check_capacity(capacity, host_free=50, docker_free=1000, reclaimable=60)
        with self.assertRaisesRegex(ValueError, 'physical host'):
            restore.check_capacity(capacity, host_free=50, docker_free=1000, reclaimable=60, backup=1)
        with self.assertRaisesRegex(ValueError, 'Docker space'):
            restore.check_capacity(capacity, host_free=1000, docker_free=20)

    def test_plaintext_is_streamed_and_not_written_to_disk(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'source.sql.gz'
            with gzip.open(path, 'wb') as stream: stream.write(b'SELECT 1;\n')
            script = 'import sys; data=sys.stdin.buffer.read(); assert data==b"SET SESSION sql_log_bin=0;\\nSELECT 1;\\n"'
            restore.streaming(['python3', '-c', script], source=path, timeout=5)
            self.assertEqual([p.name for p in Path(directory).iterdir()], ['source.sql.gz'])

    def test_stream_failure_propagates(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'source.sql.gz'
            with gzip.open(path, 'wb') as stream: stream.write(b'SELECT 1;')
            with self.assertRaises((ValueError, BrokenPipeError)):
                restore.streaming(['python3', '-c', 'raise SystemExit(2)'], source=path, timeout=5)

    def test_retired_target_requires_matching_proof_and_observed_absence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'retired.json'
            source = dict(containerId='1'*64, container='old', volume='old-volume', mysqlServerUuid='a'*36,
                          datasetId='korea-growth-v4-'+'2'*16, dumpSha256='3'*64, fingerprintSha256='4'*64)
            proof = {'schemaVersion': 1, 'kind': 'airbob-experiment-database-retirement', 'state': 'REMOVED',
                'datasetId': source['datasetId'], 'containerId': source['containerId'], 'containerName': 'old',
                'volumeName': 'old-volume', 'mysqlServerUuid': source['mysqlServerUuid'],
                'preservedDumpSha256': source['dumpSha256'], 'preservedFingerprintSha256': source['fingerprintSha256'],
                'deletion': {'containerAbsentObserved': True, 'volumeAbsentObserved': True}}
            path.write_text(json.dumps(proof))
            source.update(retirementReceipt=str(path), retirementReceiptSha256=restore.sha(path))
            config = {'source': source, 'diskObserver': {'name': 'observer', 'id': '5'*64}}
            def docker(*args, **kwargs):
                return b'Filesystem 1024-blocks Used Available Capacity Mounted\noverlay 100 1 99 1% /\n' if args[0] == 'exec' else b'observer\n'
            with patch.object(restore, 'docker', side_effect=docker), patch.object(restore, 'existing', return_value=set()), \
                 patch.object(restore, 'inspect', return_value={'Id':'5'*64, 'State': {'Running': True}}):
                self.assertTrue(restore.retired_observation(config)['alreadyRetired'])
                with patch.object(restore, 'existing', return_value={'old-volume'}):
                    with self.assertRaisesRegex(ValueError, 'exists again'): restore.retired_observation(config)

    def test_private_input_permissions_and_symlinks_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'accounts.private.json'
            path.write_text('{}'); path.chmod(0o644)
            with self.assertRaises(ValueError): restore.private_file(path)
            path.chmod(0o600)
            self.assertEqual(restore.private_file(path), path)
            alias = Path(directory)/'alias'; alias.symlink_to(path)
            with self.assertRaises(ValueError): restore.private_file(alias)


if __name__ == '__main__':
    unittest.main()
