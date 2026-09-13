"""Offline native restore integration with real seals and exact fake-S3 versions."""
import contextlib
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_search as search


class TransportRestoreTests(unittest.TestCase):
    def setUp(self):
        import test_growth_b_search_transport as fixtures
        import test_growth_b_search as contracts
        self.contracts = contracts; self.fixture = fixtures.TransportTests(); self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.publication, self.downloaded, _ = self.fixture.fetched()
        self.companion = self.downloaded / 'companion'
        self.descriptor = search.read(self.downloaded / fixtures.transport.DESCRIPTOR)
        self.manifest, self.reference = search.validate_companion(self.companion, self.descriptor)
        self.config = {key: self.manifest[key] for key in ('datasetId', 'consumerManifestSha256', 'checksSha256')}
        self.config.update(elasticsearch={}, targetIndex='accommodations-vtest-restored',
            repository={'type': 's3', 'name': 'transport-restore', 'aws': {'region': fixtures.transport.REGION},
                'settings': {'bucket': fixtures.transport.BUCKET, 'base_path': self.fixture.prefix + 'native',
                             'region': fixtures.transport.REGION},
                'transport': {'manifest': str(self.downloaded / fixtures.transport.MARKER),
                              'sha256': self.publication['manifest']['sha256']}})
        self.source = MagicMock(); self.source.config = self.config; self.source.manifest = self.manifest
        self.source.base = search.read(self.companion / 'mysql-baseline-fingerprint.json')
        self.source.checks = {'airbob-growth.sql.gz': search.read(self.companion / 'source-proof.json')['baseDumpSha256']}
        self.source.host_runtime_path = self.fixture.root / 'host-runtime.json'; self.source.host_runtime_path.write_text('{}')
        self.source.mapping = {'mappings': {'properties': {'accommodationId': {'type': 'long'}}}, 'settings': {}}
        self.source.documents.return_value = search.document_fingerprint(self.reference['fingerprint'])
        self.es = MagicMock(); self.es.identity.return_value = contracts.runtime_identity()
        self.es.alias.return_value = 'accommodations-vold'; self.es.optional.return_value = None
        self.es.fingerprint.return_value = self.reference['fingerprint']; self.es.api.side_effect = self.api
        self.registered = None; self.registrations = []; self.restores = 0
        self.output = self.fixture.root / 'restored'

    def api(self, method, path, body=None):
        repository = '/_snapshot/' + self.config['repository']['name']
        target = '/' + self.config['targetIndex']
        if path == repository:
            if method == 'PUT':
                self.registered = body; self.registrations.append(body)
                return {'acknowledged': True}
            if method == 'GET': return {self.config['repository']['name']: self.registered}
            if method == 'DELETE': self.registered = None; return {'acknowledged': True}
        if path.endswith('/_restore'):
            self.assertFalse(body['include_aliases']); self.assertFalse(body['include_global_state'])
            self.assertTrue(body['index_settings']['index.blocks.write'])
            self.restores += 1; return {'accepted': True}
        if path.startswith('/_cluster/health/'): return self.contracts.restore_health()
        if path == target + '/_settings': return {self.config['targetIndex']: {'settings': {'index': {'blocks': {'write': 'true'}}}}}
        if path == target + '/_alias': return {self.config['targetIndex']: {'aliases': {}}}
        if path == repository + '/' + self.reference['snapshot']:
            return {'snapshots': [{'snapshot': self.reference['snapshot'], 'state': 'SUCCESS',
                'indices': [self.reference['snapshotIndex']], 'failures': [], 'uuid': self.reference['snapshotUuid'],
                'shards': {'failed': 0, 'successful': 1, 'total': 1}, 'include_global_state': False, 'feature_states': [],
                'version': search.SNAPSHOT_METADATA_VERSION, 'version_id': search.SNAPSHOT_METADATA_VERSION_ID}]}
        raise AssertionError((method, path))

    def restore(self):
        self.source.fence.return_value = contextlib.nullcontext({'serverUuid': '11111111-1111-1111-1111-111111111111'})
        preparation = {'baselineReceiptSha256': 'f' * 64, 'preparedAllowedChanges': {}}
        with patch.object(search, 'SourceAdapter', return_value=self.source), patch.object(search, 'Elasticsearch', return_value=self.es), \
             patch.object(search, 'aws_cli', return_value=self.fixture.aws.call), patch.object(search, 'disk_gate', return_value={}), \
             patch.object(search, 'prepared_source', return_value=(self.source.base, preparation)), \
             patch.object(search, 'source_drift_check', return_value={}):
            return search.restore(self.config, self.companion, self.descriptor, self.fixture.root / 'baseline', self.output)

    def test_full_restore_uses_exact_versions_readonly_without_changing_fs_seals(self):
        before = {path.name: path.read_bytes() for path in self.companion.iterdir()}
        result = self.restore()
        self.assertEqual(result['state'], 'SEARCH_VERIFIED_NOT_ACTIVATED')
        self.assertEqual(result['nativeTransport']['manifestSha256'], self.publication['manifest']['sha256'])
        self.assertTrue(result['nativeTransport']['exactVersionBytesVerified'])
        self.assertEqual(result['nativeTransport']['nativeFiles'], 2)
        self.assertFalse(result['nativeTransport']['sourceSealsChanged'])
        self.assertEqual(len(self.registrations), 1); self.assertEqual(self.restores, 1)
        self.assertTrue(self.registrations[0]['settings']['readonly'])
        self.assertEqual(self.registrations[0]['settings']['base_path'], self.fixture.prefix + 'native')
        self.assertEqual(result['activeAlias'], 'accommodations-vold'); self.assertIsNone(self.registered)
        self.assertEqual(before, {path.name: path.read_bytes() for path in self.companion.iterdir()})
        self.assertIn('fullComparisonCompletedAt', result['restoreMeasurements'])

    def test_corrupt_exact_version_fails_before_repository_or_restore_request(self):
        self.fixture.aws.corrupt.add(self.fixture.prefix + 'native/index-0')
        with self.assertRaises(ValueError): self.restore()
        self.assertEqual(self.registrations, []); self.assertEqual(self.restores, 0)
        self.assertFalse((self.output / 'search-restore-receipt.json').exists())

    def test_superseded_version_fails_before_repository_registration(self):
        key = self.fixture.prefix + 'native/index-0'
        self.fixture.aws.seed(key, self.fixture.aws.objects[key][0]['data'])
        with self.assertRaises(ValueError): self.restore()
        self.assertEqual(self.registrations, []); self.assertEqual(self.restores, 0)

    def test_native_version_drift_after_full_comparison_prevents_success(self):
        def fingerprint(*args):
            key = self.fixture.prefix + 'native/index-0'
            self.fixture.aws.seed(key, self.fixture.aws.objects[key][0]['data'])
            return self.reference['fingerprint']
        self.es.fingerprint.side_effect = fingerprint
        with self.assertRaisesRegex(ValueError, 'native inventory drifted'): self.restore()
        self.assertEqual(self.restores, 1); self.assertIsNone(self.registered)
        self.assertFalse((self.output / 'search-restore-receipt.json').exists())

    def test_unreviewed_manifest_hash_or_region_fails_before_es_registration(self):
        self.config['repository']['transport']['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'manifest SHA differs'): self.restore()
        self.assertEqual(self.registrations, [])

    def test_transport_region_cannot_redirect_repository_configuration(self):
        self.config['repository']['settings']['region'] = 'us-east-1'
        with self.assertRaisesRegex(ValueError, 'AWS region differ'): self.restore()
        self.assertEqual(self.registrations, [])

    def test_transport_never_allows_writable_or_unverified_registration(self):
        repository = search.Repository(self.config['repository'], self.fixture.aws.call)
        for readonly in (True, False):
            with self.subTest(readonly=readonly), self.assertRaisesRegex(ValueError, 'verified native bytes'):
                repository.register(self.es, readonly)
        repository.transport_proof = {'exactVersionBytesVerified': True}
        with self.assertRaisesRegex(ValueError, 'read-only'): repository.register(self.es, False)
        self.es.api.assert_not_called()

    def test_transport_prefix_requires_an_explicit_pin_and_matching_dataset(self):
        repository = dict(self.config['repository']); repository.pop('transport')
        with self.assertRaisesRegex(ValueError, 'Release-scoped'): search.Repository(repository, self.fixture.aws.call)
        repository = dict(self.config['repository'])
        for change in ({'endpoint': 'https://other.invalid'}, {'client': 'custom'},
                       {'base_path': self.fixture.prefix.replace(self.fixture.dataset_id + '-search/', 'global-growth-b-' + 'f' * 16 + '-search/', 1) + 'native'}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                search.Repository(repository | {'settings': repository['settings'] | change}, self.fixture.aws.call)

    def test_fs_to_s3_is_still_rejected_without_validated_transport(self):
        repository = search.Repository(self.config['repository'], self.fixture.aws.call)
        with self.assertRaisesRegex(ValueError, 'type differs'): search.same_repository(repository, self.reference)


if __name__ == '__main__': unittest.main()
