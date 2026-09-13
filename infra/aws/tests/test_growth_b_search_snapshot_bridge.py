"""Offline host-binding checks using the explicit synthetic lineage fixture."""
import copy
import contextlib
import datetime as dt
import hashlib
from pathlib import Path
import sys
import time
import types
import unittest
import uuid
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_search_host as host
import growth_b_search_snapshot_bridge as bridge
import test_growth_b_search_snapshot as engine_tests
from test_growth_b_search_snapshot import fixture, document

NOW = host.DEADLINE - 30000


def stamp(epoch):
    return dt.datetime.fromtimestamp(epoch, dt.timezone.utc).isoformat()


def context(selected=None):
    binding, documents, envelope, base = selected or fixture()
    prepared = document(documents['targetPreparation'])
    selected = document(documents['targetManifest'])
    prior_host = document(documents['targetHostReceipt'])
    manifest = {'datasetId': binding['datasetId'], 'runId': binding['runId'], 'application': binding['application'],
        'rds': {k: binding['targetRds'][k] for k in ('identifier', 'resourceId', 'serverUuid')},
        'preparation': {'receipt': documents['targetPreparation']['reference'],
            'restoreReceiptSha256': documents['targetOperation']['reference']['sha256'],
            'preparedFingerprintSha256': documents['targetPreparedFingerprint']['reference']['sha256'],
            'restoreConfigSha256': prior_host['restoreConfigSha256']}, 'search': {'documentFingerprint': {'documents': 1}}}
    value = {'datasetId': binding['datasetId'], 'runId': binding['runId'], 'operationId': 'snapshot-native-test',
        'resourceFence': 76, 'controllerDeadlineEpoch': NOW + 1000,
        'lease': {'owner': 'snapshot-native-test', 'fencingToken': 77},
        'rds': binding['targetRds'] | {'masterSecretArn': 'unit-test-secret-reference'},
        'hosts': {'preparation': {'instanceId': binding['preparationHostInstanceId']}},
        'serviceManifest': {'value': manifest},
        'preparationManifest': {'reference': selected['awsPreparation'], 'value': {'files': {'envelope': envelope['reference']}}},
        'sourceRefs': {'preparationReceipt': {'reference': documents['targetPreparation']['reference'], 'value': prepared}},
        'snapshotLineage': {'binding': binding, 'documents': documents, 'envelope': envelope},
        'toolSources': host.sources()}
    return value, document(envelope), base


def package(value, envelope, base, observed_at=NOW):
    context_sha = hashlib.sha256(host.canonical(value)).hexdigest()
    result = {'schemaVersion': 1, 'kind': bridge.PACKAGE_KIND, 'state': bridge.PACKAGE_STATE,
        'contextSha256': context_sha, 'sourceToolSha256': value['toolSources']['growth_b_search_snapshot_bridge.py'],
        **{k: value[k] for k in ('runId', 'datasetId', 'operationId', 'resourceFence', 'lease')},
        'exportedAt': stamp(observed_at + 1), 'origin': bridge.lineage(value, envelope, base),
        'mysql': host.expected_mysql(value, envelope), 'lineage': value['snapshotLineage'],
        'currentIdentityOnly': {'serverUuid': value['rds']['serverUuid'], 'version': '8.4.11', 'tlsVerified': True,
            'outboxRows': 0, 'otherClients': 0, 'currentFullRowsRevalidated': False,
            'currentOwnedRowsRevalidated': False, 'observedAt': stamp(observed_at)}}
    return result, context_sha


class SnapshotBridgeTest(unittest.TestCase):
    def setUp(self):
        self.context, self.envelope, self.base = context()

    def test_actual_lineage_maps_to_target_host_and_service_preparation(self):
        bridge.validate_context(self.context)
        result, context_sha = package(self.context, self.envelope, self.base)
        _, _, origin = bridge.validate_source_package(result, self.context, context_sha, self.envelope, self.base)
        self.assertFalse(origin['rawBaselineObservedOnTarget'])
        self.assertNotEqual(origin['ancestorSource']['serverUuid'], origin['target']['serverUuid'])

    def test_another_preparation_host_is_rejected(self):
        self.context['hosts']['preparation']['instanceId'] = 'i-' + '8' * 17
        with self.assertRaisesRegex(host.Rejected, 'SNAPSHOT_HOST_BINDING'):
            bridge.validate_context(self.context)

    def test_target_identity_cannot_be_replaced_by_original_identity(self):
        self.context['rds']['serverUuid'] = str(uuid.UUID(int=1))
        with self.assertRaisesRegex(host.Rejected, 'SNAPSHOT_HOST_BINDING'):
            bridge.validate_context(self.context)

    def test_unrelated_retained_config_hash_is_rejected(self):
        self.context['serviceManifest']['value']['preparation']['restoreConfigSha256'] = '0' * 64
        with self.assertRaisesRegex(host.Rejected, 'SNAPSHOT_COMMON_PREPARATION_BINDING'):
            bridge.validate_context(self.context)

    def test_unrelated_preparation_manifest_is_rejected(self):
        self.context['preparationManifest']['reference'] = self.context['preparationManifest']['reference'] | {'versionId': 'changed'}
        with self.assertRaisesRegex(host.Rejected, 'SNAPSHOT_COMMON_PREPARATION_BINDING'):
            bridge.validate_context(self.context)

    def test_loaded_sealed_baseline_and_envelope_cannot_be_changed(self):
        with self.assertRaisesRegex(host.Rejected, 'SNAPSHOT_LOADED_ENVELOPE_CHANGED'):
            bridge.lineage(self.context, self.envelope | {'extra': True}, self.base)
        changed = copy.deepcopy(self.base)
        previous = changed['tables']['member']['rowsSha256']
        changed['tables']['member']['rowsSha256'] = ('1' if previous[0] == '0' else '0') + previous[1:]
        with self.assertRaises(ValueError):
            bridge.lineage(self.context, self.envelope, changed)

    def test_current_identity_export_does_not_claim_full_rows_were_rescanned(self):
        result, context_sha = package(self.context, self.envelope, self.base)
        for key in ('currentFullRowsRevalidated', 'currentOwnedRowsRevalidated'):
            changed = copy.deepcopy(result); changed['currentIdentityOnly'][key] = True
            with self.subTest(key=key), self.assertRaisesRegex(host.Rejected, 'SNAPSHOT_CURRENT_IDENTITY_ONLY'):
                bridge.validate_source_package(changed, self.context, context_sha, self.envelope, self.base)

    def test_source_package_cannot_relabel_its_evidence_or_operation(self):
        result, context_sha = package(self.context, self.envelope, self.base)
        for changes in ({'state': 'FROZEN_IMPORT_BASELINE_PROOFS_EXPORTED'}, {'kind': host.PACKAGE_KIND},
                        {'operationId': 'another-operation'}, {'contextSha256': '0' * 64}):
            with self.subTest(changes=changes), self.assertRaisesRegex(host.Rejected, 'SNAPSHOT_PACKAGE_BINDING'):
                bridge.validate_source_package(result | changes, self.context, context_sha, self.envelope, self.base)

    def test_current_identity_observation_must_follow_actual_target_preparation(self):
        result, context_sha = package(self.context, self.envelope, self.base)
        result['currentIdentityOnly']['observedAt'] = '2000-01-01T00:00:00+00:00'
        with self.assertRaisesRegex(host.Rejected, 'SNAPSHOT_CURRENT_IDENTITY_ONLY'):
            bridge.validate_source_package(result, self.context, context_sha, self.envelope, self.base)

    def test_snapshot_preparation_path_is_the_original_operation_namespace(self):
        self.assertEqual(host.retained_root(self.context) / 'snapshot-prepare-target-01/aws-preparation.json',
            host.input_paths(self.context, 'prepare-source')['preparation'])
        self.assertEqual(host.op_root(self.context) / 'inputs/aws-preparation.json',
            host.input_paths(self.context, 'restore-on-es')['preparation'])

    def test_host_phase_calls_explicit_target_engine_with_guard_and_validates_exact_returned_bytes(self):
        # Produce a real contract-valid synthetic native receipt first. The
        # phase wiring then substitutes host I/O and that already-tested engine
        # invocation, while its lineage/result/completion validators stay real.
        native = engine_tests.RestoreTest('test_full_native_restore_and_honest_receipt')
        native.setUp(); self.addCleanup(native.doCleanups)
        native.es.alias_value = None
        expected = native.execute()
        ctx, envelope, base = context((native.binding, native.docs, native.envelope, native.base))
        root = native.root.resolve(); output = root / 'host-phase'; output.mkdir(mode=0o700)
        (output / '.private').mkdir(mode=0o700); (output / 'public').mkdir(mode=0o700)
        ctx.update(targetIndex=native.config['targetIndex'], repositoryName=native.config['repository']['name'],
            controllerDeadlineEpoch=int(time.time()) + 1000)
        ctx['hosts']['elasticsearch'] = expected['elasticsearch'] | {'container': 'unit-es', 'instanceId': 'i-' + '5' * 17}
        ctx['serviceManifest']['value']['search'] = {'snapshotRelease': expected['snapshotRelease'],
            'documentFingerprint': expected['fullDocumentFingerprint'], 'image': expected['elasticsearch']['image'],
            'transport': {'sha256': expected['nativeTransport']['manifestSha256']}}
        prepared, context_sha = package(ctx, envelope, base, int(time.time()) - 3)
        package_path = root / 'phase-package.json'; host.write(package_path, prepared)
        package_sha = host.sha(package_path)
        descriptor_path = root / 'phase-descriptor.json'; host.write(descriptor_path, native.descriptor)
        paths = {'descriptor': descriptor_path, 'transport': root / 'unused-transport-path', 'companion': native.companion}
        marker = {'sql': {'publicationReceiptSha256': envelope['publicationReceiptSha256']},
            'source': {'consumerManifestSha256': envelope['consumerManifestSha256'], 'checksSha256': envelope['checksumsSha256']}}
        guard = Mock(); guard.live = {'MasterUsername': 'unit-master'}
        guard.ack = types.SimpleNamespace(latest={'sequence': 0, 'sha256': '1' * 64,
            'issuedAt': int(time.time()) - 5, 'expiresAt': int(time.time()) + 60})
        es = Mock(); es.identity.return_value = expected['elasticsearch']; es.alias.return_value = None; es.optional.return_value = None
        def dispatch(*args, **kwargs):
            self.assertEqual(ctx['snapshotLineage']['binding'], args[3])
            self.assertEqual(ctx['snapshotLineage']['documents'], args[4])
            self.assertEqual(ctx['snapshotLineage']['envelope'], args[5])
            self.assertIs(guard, kwargs['guard']); self.assertIs(True, kwargs['activate_alias'])
            destination = Path(args[6]); destination.mkdir(mode=0o700)
            for name in ('snapshot-target-baseline.json', 'search-restore-receipt.json'):
                host.new_file(destination / name, (root / 'output' / name).read_bytes())
            return expected
        with patch.object(host, 'load_inputs', return_value=(paths, envelope, base, {})), \
             patch.object(host, 'db_config', return_value={}), \
             patch.object(host.runtime, 'activated_runtime', return_value=contextlib.nullcontext()), \
             patch.object(host.restore, 'connection', return_value=(Mock(), {'AIRBOB_ETL_DB_PASSWORD': 'unit-only'})), \
             patch.object(host, 'identity_only'), patch.object(host, 'search_config', return_value=native.config), \
             patch.object(host.transport, 'validate_published_transport', return_value=marker), \
             patch.object(host.search, 'Elasticsearch', return_value=es), \
             patch.object(host.search, 'restore', side_effect=AssertionError('Target cannot call dump restore')), \
             patch.object(bridge.engine, 'restore', side_effect=dispatch) as selected_engine:
            receipt = bridge.restore_on_es(ctx, context_sha, package_path, package_sha, output, guard=guard)
        selected_engine.assert_called_once()
        raw = host.regular(output / 'public/search-restore-receipt.json')
        bridge.validate_completion(ctx, context_sha, prepared, package_sha, raw, receipt)
        self.assertFalse(receipt['sqlImportExecuted'])
        self.assertFalse(receipt['baselineOrigin']['rawBaselineObservedOnTarget'])


if __name__ == '__main__':
    unittest.main()
