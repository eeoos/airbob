"""Offline destructive-target checks for the real source integration harness."""
import copy
import contextlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location('search_source_harness', Path(__file__).with_name('growth-b-search-source-test.py'))
harness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(harness)


class SourceHarnessOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.resource = {'id': 'a' * 64, 'name': 'airbob-b-source-test-producer-mysql-test',
                         'image': 'sha256:' + 'b' * 64, 'volume': 'owned-volume', 'dataPath': '/var/lib/mysql',
                         'containerLabels': {'airbob.dataset.id': 'verified-dataset'},
                         'volumeLabels': {'airbob.dataset.id': 'verified-dataset', 'airbob.restore.claim': 'our-claim'}}
        self.container = {'Id': self.resource['id'], 'Name': '/' + self.resource['name'], 'Image': self.resource['image'],
                          'Config': {'Labels': dict(self.resource['containerLabels'])},
                          'Mounts': [{'Destination': '/var/lib/mysql', 'Type': 'volume', 'Name': 'owned-volume'}]}
        self.volume = {'Name': 'owned-volume', 'Driver': 'local', 'Options': None,
                       'Labels': dict(self.resource['volumeLabels'])}

    def test_exact_owned_container_and_volume_are_accepted_without_commands(self):
        harness.verify_owned(self.resource, self.container, self.volume)

    def test_same_name_recreated_container_cannot_be_deleted(self):
        changed = self.container | {'Id': 'c' * 64}
        with self.assertRaisesRegex(ValueError, 'identity changed'):
            harness.verify_owned(self.resource, changed, self.volume)

    def test_same_dataset_label_does_not_allow_another_volume_claim(self):
        changed = copy.deepcopy(self.volume)
        changed['Labels']['airbob.restore.claim'] = 'another-run'
        with self.assertRaisesRegex(ValueError, 'claim changed'):
            harness.verify_owned(self.resource, self.container, changed)

    def test_foreign_or_bind_mounted_data_is_rejected(self):
        for update in ({'Name': 'unrelated-volume'}, {'Type': 'bind'}, {'Destination': '/other'}):
            changed = copy.deepcopy(self.container)
            changed['Mounts'][0].update(update)
            with self.subTest(update=update), self.assertRaisesRegex(ValueError, 'binding changed'):
                harness.verify_owned(self.resource, changed, self.volume)

    def test_unrelated_container_label_or_external_volume_driver_is_rejected(self):
        changed = copy.deepcopy(self.container)
        changed['Config']['Labels']['airbob.dataset.id'] = 'another-dataset'
        with self.assertRaisesRegex(ValueError, 'labels changed'):
            harness.verify_owned(self.resource, changed, self.volume)
        for update in ({'Driver': 'nfs'}, {'Options': {'device': '/existing-data'}}, {'Name': 'other'}):
            with self.subTest(update=update), self.assertRaisesRegex(ValueError, 'claim changed'):
                harness.verify_owned(self.resource, self.container, self.volume | update)


class SourceHarnessFenceObservationTest(unittest.TestCase):
    def setUp(self):
        self.tables = ['accommodation', 'member']
        self.locks = [{'tableName': table, 'ownerThreadId': 58,
                       'lockType': 'SHARED_READ_ONLY', 'duration': 'TRANSACTION'} for table in self.tables]

    def test_actual_mysql_transaction_duration_read_fence_is_accepted(self):
        self.assertEqual(harness.verify_table_fence(self.locks, self.tables), self.locks)

    def test_missing_duplicate_or_wrong_mode_cannot_qualify_as_complete_fence(self):
        for locks in (self.locks[:1], self.locks + self.locks[:1],
                      [self.locks[0], self.locks[1] | {'lockType': 'SHARED_READ'}]):
            with self.subTest(locks=locks), self.assertRaises(ValueError):
                harness.verify_table_fence(locks, self.tables)

    def test_locks_on_two_connections_cannot_qualify_as_single_fence(self):
        with self.assertRaises(ValueError):
            harness.verify_table_fence([self.locks[0], self.locks[1] | {'ownerThreadId': 59}], self.tables)


class SourceHarnessRuntimeGateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.release = self.root / 'release'; self.release.mkdir()
        self.output = self.root / 'new-source-test'
        self.jar = self.root / 'app.jar'; self.jar.write_bytes(b'not executed')
        self.manifest = {'datasetScale': 'small-qualification', 'finalScaleSelected': False,
                         'datasetId': 'global-growth-b-' + '1' * 16}
        self.image = 'sha256:' + '2' * 64
        for name, data in [('consumer-manifest.json', self.manifest), ('SHA256SUMS.json', {}),
                           ('search-qualification.json', {'publishedDocuments': 770, 'imageId': self.image})]:
            (self.release / name).write_text(json.dumps(data))
        self.arguments = [str(Path(harness.__file__)), '--release', str(self.release), '--app-jar', str(self.jar),
            '--output', str(self.output), '--consumer-manifest-sha256', harness.restore.sha(self.release / 'consumer-manifest.json'),
            '--checks-sha256', harness.restore.sha(self.release / 'SHA256SUMS.json'),
            '--mysql-image', 'sha256:' + '3' * 64, '--image', self.image]
        self.checks = {'tool-sources.json': '4' * 64}
        self.patch(harness.sys, 'argv', new=self.arguments)
        self.patch(harness.restore, 'validate', return_value=(self.manifest, self.checks, {'tables': {}}))
        self.patch(harness.restore, 'extract_runtime', side_effect=self.extract_runtime)
        self.qualify = self.patch(harness.restore, 'qualify_runtime', return_value={'consumerRuntimePassed': True})
        self.engine = self.patch(harness.restore, 'require_local_engine')
        self.resources = MagicMock()
        self.patch(harness, 'Resources', return_value=self.resources)
        self.docker = self.patch(harness.restore, 'docker', side_effect=AssertionError('Offline test must not use Docker'))

    def patch(self, obj, name, **kwargs):
        item = patch.object(obj, name, **kwargs); self.addCleanup(item.stop)
        return item.start()

    def extract_runtime(self, release, target):
        (target / 'tools').mkdir(parents=True)
        (target / 'tools/growth_streaming.py').write_text(
            'from contextlib import nullcontext\ndef acquire_run_lock(): return nullcontext()\n')
        return target

    def test_unsupported_host_jdk_fails_before_engine_or_database_use(self):
        self.qualify.side_effect = RuntimeError('Consumer runtime qualification failed')
        with self.assertRaisesRegex(RuntimeError, 'runtime qualification failed'):
            harness.main()
        self.qualify.assert_called_once_with(self.release, self.output / 'runtime',
            self.output / 'host-runtime-qualification.json', expected_checks=self.checks)
        self.engine.assert_not_called(); self.resources.mysql.assert_not_called(); self.docker.assert_not_called()
        failure = json.loads((self.output / 'source-test-failure.json').read_text())
        self.assertEqual('HOST_RUNTIME_QUALIFICATION', failure['stage'])
        self.assertFalse((self.output / 'source-test-result.json').exists())

    def test_qualified_runtime_is_active_before_resource_gate_and_restored_after_failure(self):
        events = []
        @contextlib.contextmanager
        def active(value):
            self.assertEqual(value, self.qualify.return_value)
            events.append('runtime-enter')
            try:
                yield
            finally:
                events.append('runtime-exit')
        def stop_at_resource_gate():
            self.assertEqual(['runtime-enter'], events)
            raise RuntimeError('synthetic busy resource gate')
        self.patch(harness.restore, 'activated_runtime', side_effect=active)
        self.resources.require_exclusive.side_effect = stop_at_resource_gate
        with self.assertRaisesRegex(RuntimeError, 'busy resource gate'):
            harness.main()
        self.assertEqual(['runtime-enter', 'runtime-exit'], events)
        self.resources.mysql.assert_not_called(); self.resources.elasticsearch.assert_not_called()
        self.docker.assert_not_called()

    def test_another_pinned_es_image_cannot_replace_the_release_qualified_image(self):
        self.arguments[-1] = 'sha256:' + '9' * 64
        with self.assertRaisesRegex(ValueError, 'ES image qualified by this sealed small release'):
            harness.main()
        self.qualify.assert_not_called(); self.resources.mysql.assert_not_called(); self.docker.assert_not_called()
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
