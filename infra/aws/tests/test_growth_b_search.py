"""Bounded full-source/native snapshot contracts; no cloud or running databases."""
import copy
import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from unittest.mock import MagicMock, patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_search as search
from growth_b_contract import TABLES


def doc(number):
    return {'id': str(uuid.UUID(int=number)), 'accommodationId': number, 'status': 'PUBLISHED',
            'name': '서울', 'location': {'lat': 37.0, 'lon': 126.95}, 'currency': 'EUR',
            'reservationRanges': [{'gte': '2026-09-12', 'lt': '2026-09-15'}],
            'amenityTypes': ['WIFI', 'HEATING'], 'reviewCount': 17, 'basePrice': 119000}


def runtime_identity(architecture='arm64', image_character='6'):
    return {'version': '8.18.8', 'imageId': 'sha256:' + image_character * 64,
        'image': 'registry.test/airbob/es@sha256:' + image_character * 64,
        'platform': {'os': 'linux', 'architecture': architecture},
        'productBuild': {'number': '8.18.8', 'build_flavor': 'default', 'build_hash': 'a' * 40,
            'lucene_version': '9.12.1', 'minimum_wire_compatibility_version': '7.17.0', 'minimum_index_compatibility_version': '7.0.0'},
        'requiredPluginVersions': {'analysis-nori': '8.18.8', 'repository-s3': '8.18.8'}}


def base_fingerprint():
    tables = {name: {'rows': 28 if name == 'flyway_schema_history' else 0 if name == 'outbox' else 1,
                    'rowsSha256': 'a' * 64, 'domainRowsSha256': 'b' * 64, 'ddlSha256': 'c' * 64}
              for name in TABLES}
    return {'algorithm': 'sha256-pk-order-length-prefixed-jdbc-bytes-v1', 'mysqlVersion': '8.4.11',
            'tables': tables, 'domainHashExcludedColumns': {'member': ['password']}}


def restore_health(**changes):
    # Core fields in the actual pinned ES 8.18.8 health response.
    return {'cluster_name': 'test-cluster', 'status': 'green', 'timed_out': False,
            'number_of_nodes': 1, 'number_of_data_nodes': 1, 'active_primary_shards': 1,
            'active_shards': 1, 'relocating_shards': 0, 'initializing_shards': 0,
            'unassigned_shards': 0} | changes


class Pages(search.Elasticsearch):
    def __init__(self, docs, alias=None):
        self.docs = docs; self.offset = 0; self.closed = False; self.alias_value = alias
    def api(self, method, path, body=None, **kwargs):
        if path.endswith('/_settings'): return {'accommodations-vtest': {'settings': {'index': {'number_of_shards': '1'}}}}
        if path.endswith('/_mapping'): return {'accommodations-vtest': {'mappings': {'properties': {'accommodationId': {'type': 'long'}}}}}
        if '/_pit?' in path: return {'id': 'pit'}
        if method == 'DELETE' and path == '/_pit': self.closed = True; return {'succeeded': True}
        if path == '/_search':
            batch = self.docs[self.offset:self.offset + 2]; self.offset += len(batch)
            return {'_shards': {'failed': 0}, 'timed_out': False,
                    'hits': {'total': {'value': len(self.docs), 'relation': 'eq'}, 'hits': [
                        {'_index': 'accommodations-vtest', '_id': d['id'], '_source': d,
                         'sort': [d['accommodationId'], i]} for i, d in enumerate(batch)]}}
        raise AssertionError(path)
    def optional(self, path): return self.alias_value


class SearchContracts(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name)
    def tearDown(self): self.temp.cleanup()
    def test_isolated_reader_has_region_without_inheriting_real_aws_credentials(self):
        with patch.dict(search.os.environ, {'PATH': '/usr/bin', 'AWS_REGION': 'us-east-1',
                'AWS_PROFILE': 'operator', 'AWS_ACCESS_KEY_ID': 'real-key',
                'AWS_SECRET_ACCESS_KEY': 'real-secret', 'AWS_SESSION_TOKEN': 'real-token',
                'SPRING_CLOUD_AWS_REGION_STATIC': 'eu-west-1', 'JAVA_TOOL_OPTIONS': '-Dinjected=true'}, clear=True):
            environment = search.clean_java_environment()
        self.assertEqual('ap-northeast-2', environment['AWS_REGION'])
        self.assertEqual('true', environment['AWS_EC2_METADATA_DISABLED'])
        self.assertEqual('dummy', environment['AWS_ACCESS_KEY_ID'])
        self.assertEqual('dummy', environment['AWS_SECRET_ACCESS_KEY'])
        self.assertFalse({'AWS_PROFILE', 'AWS_SESSION_TOKEN', 'SPRING_CLOUD_AWS_REGION_STATIC',
                          'JAVA_TOOL_OPTIONS'} & environment.keys())
    def documents(self, docs):
        path = self.root / ('docs-' + uuid.uuid4().hex + '.jsonl')
        path.write_text(''.join(json.dumps(d) + '\n' for d in docs)); return path
    def test_numeric_order_and_nested_full_sources(self):
        docs = [doc(3), doc(10), doc(100)]
        expected = search.file_fingerprint(self.documents(docs))
        es = Pages(docs); actual = es.fingerprint('accommodations-vtest', self.documents(docs))
        self.assertEqual(expected, search.document_fingerprint(actual))
        self.assertTrue(es.closed)
    def test_lexicographic_or_duplicate_id_order_is_rejected(self):
        for docs in ([doc(10), doc(100), doc(3)], [doc(3), doc(3)]):
            with self.subTest(docs=docs), self.assertRaises(ValueError): search.file_fingerprint(self.documents(docs))
    def test_full_source_currency_location_ranges_and_extra_field_cannot_drift(self):
        original = [doc(1), doc(2)]
        for key, value in [('currency', 'USD'), ('location', {'lat': 0, 'lon': 0}), ('reservationRanges', []), ('newField', 'unexpected')]:
            changed = copy.deepcopy(original); changed[1][key] = value; es = Pages(changed)
            with self.subTest(key=key), self.assertRaises(ValueError): es.fingerprint('accommodations-vtest', self.documents(original))
            self.assertTrue(es.closed)
    def test_omitted_and_unpublished_documents_are_rejected(self):
        es = Pages([doc(1)])
        with self.assertRaises(ValueError): es.fingerprint('accommodations-vtest', self.documents([doc(1), doc(2)]))
        unpublished = doc(1); unpublished['status'] = 'DELETED'
        with self.assertRaises(ValueError): search.file_fingerprint(self.documents([unpublished]))
    def test_document_uuid_must_equal_es_id(self):
        acc = search.DocumentDigest()
        with self.assertRaises(ValueError): acc.add({'id': doc(2)['id'], 'source': doc(1)})
    def test_integral_numeric_serializations_normalize_but_array_order_does_not(self):
        self.assertEqual(search.canonical({'x': 3.0}), search.canonical({'x': 3}))
        self.assertNotEqual(search.canonical({'x': [1, 2]}), search.canonical({'x': [2, 1]}))
        with self.assertRaises(ValueError): search.canonical({'x': float('nan')})
    def test_alias_requires_single_explicit_versioned_write_target(self):
        valid = {'accommodations-vtest': {'aliases': {'accommodations': {'is_write_index': True}}}}
        self.assertEqual(Pages([], valid).alias(), 'accommodations-vtest')
        for invalid in ({}, {'accommodations': next(iter(valid.values()))},
                        {'accommodations-vtest': {'aliases': {'accommodations': {}}}},
                        valid | {'accommodations-vother': next(iter(valid.values()))}):
            with self.subTest(alias=invalid), self.assertRaises(ValueError): Pages([], invalid).alias()
    def test_only_password_and_valid_free_rows_may_change(self):
        base = base_fingerprint(); prepared = copy.deepcopy(base)
        prepared['tables']['member']['rowsSha256'] = 'd' * 64
        prepared['tables']['accommodation_inventory_day']['rows'] += 100
        prepared['tables']['accommodation_inventory_day']['rowsSha256'] = 'e' * 64
        owners = {'rows': 1, 'rowsSha256': 'a' * 64, 'invalidFreeRows': 0}
        proof = search.verify_prepared(base, prepared, owners)
        self.assertTrue(proof['historicalInventoryAllRowsEqual'])
        for name, key in [('member', 'domainRowsSha256'), ('reservation', 'rowsSha256'), ('common_code', 'rowsSha256'), ('accommodation', 'ddlSha256')]:
            wrong = copy.deepcopy(prepared); wrong['tables'][name][key] = 'f' * 64
            with self.subTest(name=name), self.assertRaises(ValueError): search.verify_prepared(base, wrong, owners)
    def test_owner_hash_or_invalid_free_row_cannot_be_hidden_in_preparation(self):
        base = base_fingerprint(); owners = {'rows': 1, 'rowsSha256': 'a' * 64, 'invalidFreeRows': 0}
        for key, value in [('rows', 2), ('rowsSha256', 'f' * 64), ('invalidFreeRows', 1)]:
            with self.subTest(key=key), self.assertRaises(ValueError): search.verify_prepared(base, base, owners | {key: value})
    def test_fs_inventory_detects_mutation_and_rejects_symlinks(self):
        repo = search.Repository({'name': 'test', 'type': 'fs', 'settings': {'location': '/backup'}, 'inventoryRoot': str(self.root)})
        (self.root / 'index-0').write_bytes(b'first'); before = repo.inventory()
        (self.root / 'index-0').write_bytes(b'second'); self.assertNotEqual(before, repo.inventory())
        (self.root / 'link').symlink_to(self.root / 'index-0')
        with self.assertRaises(ValueError): repo.inventory()
    def test_s3_inventory_requires_versions_and_pagination_progress(self):
        config = {'name': 'test', 'type': 's3', 'settings': {'bucket': 'test-bucket', 'base_path': 'elasticsearch/releases/global-growth-b-' + 'a' * 16 + '-search-test'}}
        bad = {'Versions': [{'Key': 'k', 'VersionId': 'null', 'IsLatest': True, 'Size': 1, 'ETag': 'e'}]}
        with self.assertRaises(ValueError): search.Repository(config, lambda *args: bad).inventory()
        repeated = {'IsTruncated': True, 'NextKeyMarker': 'k', 'NextVersionIdMarker': 'v'}
        with self.assertRaises(ValueError): search.Repository(config, lambda *args: repeated).inventory()
    def test_repository_configuration_cannot_contain_credentials(self):
        with self.assertRaises(ValueError): search.Repository({'name': 'test', 'type': 'fs', 'settings': {'location': '/backup', 'secret_key': 'hidden'}, 'inventoryRoot': str(self.root)})
    def test_private_properties_escape_injection(self):
        path = self.root / 'connection.properties'; search.properties(path, {'password': 'x\nspring.flyway.enabled=true\\', 'username': 'a b'})
        self.assertEqual(len(path.read_text().splitlines()), 2)
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertIn('\\u000a', path.read_text())
    def test_java_environment_drops_external_spring_and_java_injection(self):
        with patch.dict('os.environ', {'SPRING_FLYWAY_ENABLED': 'true', 'JAVA_TOOL_OPTIONS': '-Dsecret=1'}):
            env = search.clean_java_environment()
        self.assertNotIn('SPRING_FLYWAY_ENABLED', env); self.assertNotIn('JAVA_TOOL_OPTIONS', env)
    def test_snapshot_global_state_partial_shards_or_uuid_drift_fails(self):
        info = {'snapshot': 'test', 'state': 'SUCCESS', 'indices': ['accommodations-vtest'], 'failures': [],
                'shards': {'failed': 0, 'successful': 1, 'total': 1}, 'include_global_state': False,
                'feature_states': [], 'uuid': 'exact', 'version': search.SNAPSHOT_METADATA_VERSION, 'version_id': search.SNAPSHOT_METADATA_VERSION_ID}
        search.validate_snapshot(info, 'test', 'accommodations-vtest', 'exact')
        for update in ({'include_global_state': True}, {'uuid': 'changed'}, {'indices': ['other']},
                       {'shards': {'failed': 1, 'successful': 1, 'total': 2}}):
            with self.subTest(update=update), self.assertRaises(ValueError): search.validate_snapshot(info | update, 'test', 'accommodations-vtest', 'exact')
    def test_restore_waits_for_actual_primary_after_transient_yellow(self):
        class Recovering:
            def __init__(self): self.health_calls = 0
            def optional(self, path): return None
            def api(self, method, path, body=None):
                if path.endswith('/_restore'): return {'accepted': True}
                if path.startswith('/_cluster/health/'):
                    self.health_calls += 1
                    ready = self.health_calls > 1
                    return restore_health(status='green' if ready else 'yellow', active_primary_shards=int(ready),
                                          active_shards=int(ready), unassigned_shards=0 if ready else 1)
                if path.endswith('/_settings'): return {'accommodations-vnew': {'settings': {'index': {'blocks': {'write': 'true'}}}}}
                if path.endswith('/_alias'): return {'accommodations-vnew': {'aliases': {}}}
                raise AssertionError(path)
        es = Recovering(); repo = type('Repo', (), {'name': 'test'})()
        with patch.object(search.time, 'sleep'):
            search.restore_index(es, repo, {'snapshot': 'test', 'snapshotIndex': 'accommodations-vold'}, 'accommodations-vnew', 60)
        self.assertEqual(es.health_calls, 2)
    def test_arm_to_x86_uses_each_immutable_pin_with_equal_runtime_contract(self):
        source = runtime_identity(); target = runtime_identity('amd64', '7')
        result = search.runtime_compatibility(source, target)
        self.assertFalse(result['sameImageBytes'])
        self.assertEqual(result['sourcePlatform']['architecture'], 'arm64')
        self.assertEqual(result['targetPlatform']['architecture'], 'amd64')
        self.assertNotEqual(result['sourceImmutablePin'], result['targetImmutablePin'])
    def test_actual_product_build_lucene_or_plugin_difference_is_rejected(self):
        source = runtime_identity()
        for container, key, value in [('productBuild', 'number', '8.18.7'), ('productBuild', 'build_hash', 'b' * 40),
                ('productBuild', 'lucene_version', '9.11.0'), ('requiredPluginVersions', 'analysis-nori', '8.18.7'),
                ('requiredPluginVersions', 'repository-s3', '8.19.0')]:
            target = runtime_identity('amd64', '7'); target[container][key] = value
            with self.subTest(field=key), self.assertRaises(ValueError): search.runtime_compatibility(source, target)
    def test_analyzer_similarity_and_pipeline_are_part_of_index_fingerprint(self):
        original = {'number_of_shards': '1', 'analysis': {'normalizer': {'lower': {'type': 'custom', 'filter': ['lowercase']}}}}
        stable = search.index_semantics(original)
        self.assertEqual(stable, search.index_semantics(original | {'uuid': 'host-specific', 'creation_date': '123', 'blocks': {'write': 'true'}}))
        for update in ({'analysis': {}}, {'similarity': {'default': {'type': 'boolean'}}}, {'default_pipeline': 'changed'}):
            self.assertNotEqual(search.digest(stable), search.digest(search.index_semantics(original | update)))
    def test_live_process_requires_a_fresh_connection_acknowledgement(self):
        adapter = object.__new__(search.SourceAdapter); adapter.heartbeat_path = self.root / 'heartbeat'
        class Pipe:
            def write(self, value): adapter.heartbeat_path.write_text(value.decode().strip())
            def flush(self): pass
        adapter._fence = type('Fence', (), {'poll': lambda self: None, 'stdin': Pipe()})()
        adapter.heartbeat_path.write_text('stale')
        adapter.check_fence()
        self.assertNotEqual(adapter.heartbeat_path.read_text(), 'stale')
    def test_living_jvm_without_connection_ack_fails_closed(self):
        adapter = object.__new__(search.SourceAdapter); adapter.heartbeat_path = self.root / 'heartbeat'
        class Pipe:
            def write(self, value): pass
            def flush(self): pass
        adapter._fence = type('Fence', (), {'poll': lambda self: None, 'stdin': Pipe()})()
        with patch.object(search.time, 'monotonic', side_effect=[0, 16]), self.assertRaises(ValueError): adapter.check_fence()
    def companion(self):
        directory = self.root / 'companion'; directory.mkdir()
        dataset = 'global-growth-b-' + 'a' * 16; release = dataset + '-search-unit-test'
        anchor = {'datasetId': dataset, 'consumerManifestSha256': '1' * 64,
                  'checksSha256': '2' * 64, 'appJarSha256': '3' * 64}
        fingerprint = search.file_fingerprint(self.documents([doc(1)])) | {
            'mappingSha256': search.digest({'properties': {'accommodationId': {'type': 'long'}}}),
            'indexSemanticsSha256': search.digest(search.index_semantics({}))}
        base = base_fingerprint(); owners = {'rows': 1, 'rowsSha256': 'a' * 64, 'invalidFreeRows': 0}
        inventory = {'type': 'fs', 'entries': [{'key': 'index-0', 'sha256': '5' * 64, 'bytes': 42}]}
        proof = {'schemaVersion': 1, **anchor, 'state': 'PINNED_MYSQL_SOURCE_VERIFIED',
            'baselineVerifiedBeforePreparation': True, 'everyElasticsearchSourceFieldCompared': True, 'baseDumpSha256': 'a' * 64,
            'sourceDrift': {'mysqlUuidUnchanged': True, 'completeRowsAndDdlUnchanged': True, 'allCurrentDocumentFieldsUnchanged': True},
            'projection': search.document_fingerprint(fingerprint),
            'mysql': {'publishedDocuments': 1, 'version': '8.4.11', 'serverUuid': str(uuid.UUID(int=1))},
            'baselineFingerprintSha256': search.digest(base), 'preparedFingerprintSha256': search.digest(base),
            'preparedAllowedChanges': search.verify_prepared(base, base, owners), 'ownedInventory': owners}
        reference = {'schemaVersion': 1, **anchor, 'snapshotRelease': release, 'snapshot': release, 'snapshotUuid': 'native-uuid',
            'snapshotMetadataVersion': search.SNAPSHOT_METADATA_VERSION, 'snapshotMetadataVersionId': search.SNAPSHOT_METADATA_VERSION_ID,
            'snapshotIndex': 'accommodations-vtest', 'logicalAlias': 'accommodations', 'fingerprint': fingerprint,
            'elasticsearch': runtime_identity(),
            'repository': {'type': 'fs', 'layout': 'native-elasticsearch-repository'}, 'nativeInventorySha256': search.digest(inventory)}
        receipt = {'schemaVersion': 1, **anchor, 'snapshotRelease': release, 'snapshotUuid': 'native-uuid',
            'state': 'NATIVE_SNAPSHOT_PRODUCED_AND_RESTORED', 'sourceFingerprint': fingerprint, 'restoredFingerprint': fingerprint,
            'storage': {'measuredSourcePlusTemporaryRestoreStoreBytes': 4096},
            'temporaryRestoreDeleted': True, 'repositoryRegistrationRemoved': True, 'repositoryUnchangedAfterReadOnlyRestore': True}
        payloads = {'source-proof.json': proof, 'snapshot-reference.json': reference, 'snapshot-producer-receipt.json': receipt,
                    'native-inventory.json': inventory, 'mysql-baseline-fingerprint.json': base, 'mysql-prepared-fingerprint.json': base}
        for name, value in payloads.items(): search.write(directory / name, value)
        search.write(directory / 'snapshot-seal.json', {'schemaVersion': 1, **anchor, 'snapshotRelease': release,
                     'state': 'SEALED_AFTER_NATIVE_ROUND_TRIP', 'artifacts': {name: search.file_binding(directory / name) for name in payloads}})
        manifest = {'schemaVersion': 1, **anchor, 'snapshotRelease': release, 'snapshotUuid': 'native-uuid',
                    'kind': 'airbob-global-growth-b-native-search-companion', 'fullDocumentFingerprint': fingerprint,
                    'artifacts': {name: search.file_binding(directory / name) for name in search.NAMES - {'manifest.json'}}}
        search.write(directory / 'manifest.json', manifest)
        return directory, search.descriptor_for(directory, manifest)
    def test_companion_exact_bytes_and_external_descriptor(self):
        directory, descriptor = self.companion()
        manifest, reference = search.validate_companion(directory, descriptor)
        self.assertEqual(manifest['datasetId'], descriptor['datasetId'])
        path = directory / 'source-proof.json'; path.write_text(path.read_text() + ' ')
        with self.assertRaises(ValueError): search.validate_companion(directory, descriptor)
    def test_companion_rejects_extra_file_or_changed_external_anchor(self):
        directory, descriptor = self.companion()
        bad = copy.deepcopy(descriptor); bad['consumerManifestSha256'] = '9' * 64
        with self.assertRaises(ValueError): search.validate_companion(directory, bad)
        (directory / 'unexpected.json').write_text('{}')
        with self.assertRaises(ValueError): search.validate_companion(directory, descriptor)
    def test_remote_descriptor_requires_exact_nonnull_object_versions(self):
        _, descriptor = self.companion()
        with self.assertRaises(ValueError): search.validate_descriptor(descriptor, remote=True)
        for name, item in descriptor['objects'].items(): item.update(bucket='test-bucket', key='companion/' + name, versionId='immutable-version')
        search.validate_descriptor(descriptor, remote=True)
        descriptor['objects']['manifest.json']['versionId'] = 'null'
        with self.assertRaises(ValueError): search.validate_descriptor(descriptor, remote=True)
    def test_disk_gate_observes_remaining_space_and_rejects_shortfall(self):
        es = Pages([]); es.config = {}; es.api = lambda *args: {'nodes': {'one': {'fs': {'total': {'available_in_bytes': 2 * 1024**3}}}}}
        self.assertTrue(search.disk_gate(es, 1024)['existingIndicesRetained'])
        with self.assertRaises(ValueError): search.disk_gate(es, 2 * 1024**3)
    def test_restore_receipt_records_recovery_and_comparison_only_after_complete_equality(self):
        companion, descriptor = self.companion()
        manifest, reference = search.validate_companion(companion, descriptor)
        config = {key: manifest[key] for key in ('datasetId', 'consumerManifestSha256', 'checksSha256')}
        config.update(elasticsearch={}, targetIndex='accommodations-vnew')
        source = MagicMock(); source.config = config; source.manifest = manifest; source.base = base_fingerprint()
        source.host_runtime_path = self.root / 'host-runtime.json'; source.host_runtime_path.write_text('{}')
        source.checks = {'airbob-growth.sql.gz': 'a' * 64}
        source.mapping = {'mappings': {'properties': {'accommodationId': {'type': 'long'}}}, 'settings': {}}
        source.documents.return_value = search.document_fingerprint(reference['fingerprint'])
        mysql = {'serverUuid': str(uuid.UUID(int=1))}
        es = MagicMock(); es.identity.return_value = runtime_identity(); es.alias.return_value = None; es.optional.return_value = None
        def api(method, path, body=None):
            if path.endswith('/_restore'): return {'accepted': True}
            if path.startswith('/_cluster/health/'): return restore_health()
            if path.endswith('/_settings'): return {'accommodations-vnew': {'settings': {'index': {'blocks': {'write': 'true'}}}}}
            if path.endswith('/_alias'): return {'accommodations-vnew': {'aliases': {}}}
            raise AssertionError(path)
        es.api.side_effect = api
        repository = MagicMock(); repository.name = 'test'; repository.binding.return_value = reference['repository']
        repository.inventory.return_value = search.read(companion / 'native-inventory.json')
        preparation = {'baselineReceiptSha256': 'f' * 64, 'preparedAllowedChanges': {}}
        for matches in (True, False):
            source.fence.return_value = contextlib.nullcontext(mysql)
            es.fingerprint.return_value = reference['fingerprint'] if matches else reference['fingerprint'] | {'contentSha256': 'f' * 64}
            output = self.root / ('restore-' + str(matches))
            with self.subTest(matches=matches), patch.object(search, 'SourceAdapter', return_value=source), \
                 patch.object(search, 'Elasticsearch', return_value=es), patch.object(search, 'repository_for', return_value=repository), \
                 patch.object(search, 'disk_gate', return_value={}), patch.object(search, 'snapshot_info', return_value={}), \
                 patch.object(search, 'validate_snapshot'), patch.object(search, 'prepared_source', return_value=(source.base, preparation)), \
                 patch.object(search, 'source_drift_check', return_value={}), \
                 patch.object(search.time, 'monotonic_ns', side_effect=[1_000_000_000, 4_000_000_000, 5_000_000_000, 9_000_000_000]) as clock:
                if matches:
                    receipt = search.restore(config, companion, descriptor, self.root / 'baseline', output)
                    measured = receipt['restoreMeasurements']
                    self.assertEqual(measured['requestToRecoverySeconds'], 3)
                    self.assertEqual(measured['recoveryToComparisonStartSeconds'], 1)
                    self.assertEqual(measured['fullComparisonSeconds'], 4)
                    self.assertEqual(measured['requestToFullComparisonSeconds'], 8)
                    self.assertEqual(measured['elapsedClock'], 'monotonic')
                    for stage in search.RestoreMeasurements.stages:
                        self.assertEqual(search.dt.datetime.fromisoformat(measured[stage + 'At']).utcoffset(), search.dt.timedelta())
                    self.assertEqual(search.read(output / 'search-restore-receipt.json')['restoreMeasurements'], measured)
                    self.assertEqual(clock.call_count, 4)
                else:
                    with self.assertRaisesRegex(ValueError, 'Restored full ES source differs'):
                        search.restore(config, companion, descriptor, self.root / 'baseline', output)
                    self.assertFalse((output / 'search-restore-receipt.json').exists())
                    self.assertEqual(clock.call_count, 3)


class RestoreHealthPolling(unittest.TestCase):
    def http_error(self, code, body):
        raw = body if isinstance(body, bytes) else json.dumps(body).encode()
        error = search.urllib.error.HTTPError('http://es.invalid/_cluster/health/accommodations-vnew', code,
                                             'inert test response', {}, io.BytesIO(raw))
        self.addCleanup(error.close)
        return error

    def run_restore(self, health, *, clock=None, timeout=60, restore_error=None):
        health = iter(health); self.calls = []
        def response(request, **kwargs):
            path = search.urllib.parse.urlsplit(request.full_url).path
            self.calls.append((request.method, path))
            if path == '/accommodations-vnew': raise self.http_error(404, {})
            if path.endswith('/_restore'):
                if restore_error: raise restore_error
                result = {'accepted': True}
            elif path.startswith('/_cluster/health/'):
                result = next(health)
                if isinstance(result, Exception): raise result
            elif path.endswith('/_settings'): result = {'accommodations-vnew': {'settings': {'index': {'blocks': {'write': 'true'}}}}}
            elif path.endswith('/_alias'): result = {'accommodations-vnew': {'aliases': {}}}
            else: raise AssertionError(path)
            return io.BytesIO(json.dumps(result).encode())
        es = search.Elasticsearch({'url': 'http://es.invalid', 'requestTimeoutSeconds': 1})
        repository = type('Repo', (), {'name': 'test'})()
        with patch.object(search.urllib.request, 'urlopen', side_effect=response), patch.object(search.time, 'sleep') as sleep, \
                patch.object(search.time, 'monotonic', side_effect=clock or (lambda: 0)):
            self.sleep = sleep
            search.restore_index(es, repository, {'snapshot': 'test', 'snapshotIndex': 'accommodations-vold'}, 'accommodations-vnew', timeout)

    def health_calls(self): return sum(path.startswith('/_cluster/health/') for _, path in self.calls)

    def test_actual_http_error_path_retries_only_until_complete_non_timed_out_health(self):
        recovering = restore_health(timed_out=True, status='red', active_primary_shards=0, active_shards=0, initializing_shards=1)
        self.run_restore([self.http_error(408, recovering), self.http_error(408, restore_health(timed_out=True)), restore_health()])
        self.assertEqual(3, self.health_calls()); self.assertEqual(2, self.sleep.call_count)
        self.assertEqual(1, sum(method == 'POST' for method, _ in self.calls))
        self.assertTrue(self.calls[-1][1].endswith('/_alias'))

    def test_other_http_errors_do_not_retry_even_with_a_health_shaped_body(self):
        for code in (401, 403, 404, 409, 429, 500, 503):
            with self.subTest(code=code), self.assertRaises(search.urllib.error.HTTPError) as raised:
                self.run_restore([self.http_error(code, restore_health(timed_out=True))])
            self.assertEqual(code, raised.exception.code); self.assertEqual(1, self.health_calls()); self.sleep.assert_not_called()

    def test_http_408_malformed_nonhealth_false_or_oversized_bodies_fail_without_retry(self):
        incomplete = restore_health(timed_out=True); incomplete.pop('initializing_shards')
        bodies = [b'not-json', b'\xff', b'[]', {}, {'timed_out': True}, restore_health(), incomplete,
                  restore_health(timed_out='true'), restore_health(timed_out=True, error={'type': 'test_error'}),
                  restore_health(timed_out=True, active_shards=True), restore_health(timed_out=True, active_shards=-1),
                  restore_health(timed_out=True, status='unknown'), b' ' * (64 * 1024 + 1)]
        for number, body in enumerate(bodies):
            with self.subTest(case=number), self.assertRaises(ValueError): self.run_restore([self.http_error(408, body)])
            self.assertEqual(1, self.health_calls()); self.sleep.assert_not_called()

    def test_http_200_missing_health_fields_cannot_be_mistaken_for_success(self):
        for missing in ('timed_out', 'cluster_name', 'active_primary_shards', 'initializing_shards', 'unassigned_shards'):
            body = restore_health(); body.pop(missing)
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, 'incomplete or invalid'): self.run_restore([body])
            self.sleep.assert_not_called()

    def test_408_on_restore_post_is_not_a_health_wait_and_is_not_retried(self):
        with self.assertRaises(search.urllib.error.HTTPError): self.run_restore([], restore_error=self.http_error(408, restore_health(timed_out=True)))
        self.assertEqual(0, self.health_calls()); self.sleep.assert_not_called()

    def test_repeated_health_wait_timeouts_do_not_reset_total_deadline(self):
        replies = [self.http_error(408, restore_health(timed_out=True)) for _ in range(3)]
        with self.assertRaisesRegex(ValueError, 'recovery timed out'):
            self.run_restore(replies, timeout=3, clock=[0, 0, 1, 1, 2, 2, 3])
        self.assertEqual(3, self.health_calls()); self.assertEqual([2, 1], [call.args[0] for call in self.sleep.call_args_list])
        self.assertFalse(any(path.endswith('/_settings') for _, path in self.calls))

    def test_green_response_after_total_deadline_is_still_failure(self):
        with self.assertRaisesRegex(ValueError, 'recovery timed out'): self.run_restore([restore_health()], clock=[0, 0, 61])
        self.sleep.assert_not_called(); self.assertFalse(any(path.endswith('/_settings') for _, path in self.calls))


if __name__ == '__main__': unittest.main()
