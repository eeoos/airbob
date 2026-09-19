"""Execute real Terraform admission with explicitly synthetic restored targets."""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_growth_b_mac_snapshot import future_fixture, reference, synthetic_counts
import growth_b_mac_snapshot as mac
import growth_b_service as service
from test_growth_b_service_contract import runtime_projection
from test_growth_b_service_infrastructure import evaluate
from test_global_b_infrastructure import attribute, block, LAB


def inputs():
    fixture = future_fixture()
    source, op = fixture['source'], fixture['op']
    source_ref = reference(mac.encoded(source), 'datasets/' + mac.DATASET + '-mac-snapshots/' +
        source['snapshot']['identifier'] + '/source-' + mac.digest(source) + '.json')
    op['sourceProvenance'] = source_ref
    restored = mac.validate_restore(**fixture)
    counts = synthetic_counts(source, restored)
    target = mac.make_target_receipt(source, restored, counts)
    manifest = copy.deepcopy(mac.child(source['children']['serviceManifest']))
    manifest['runId'] = op['runId']
    manifest['serviceRelease'] = 'snapshot-service-test'
    prefix = 'datasets/' + mac.DATASET + '-aws-service/' + manifest['serviceRelease'] + '/files/'
    manifest['consumerTools']['key'] = prefix + 'consumer-tools.tar.gz'
    manifest['appRuntimeBinding']['key'] = prefix + 'app-runtime-binding.json'
    manifest['rds'] = {key: target['target'][key] for key in ('identifier', 'resourceId', 'serverUuid')}
    manifest['preparation'] = {
        'sourceMode': mac.MODE, 'sourceProvenance': source_ref,
        'rdsCaBundle': manifest['preparation']['rdsCaBundle'],
        **{name: reference(mac.encoded(value), 'data-bootstrap/' + op['runId'] + '/' + name + '.json')
           for name, value in [('receipt', target), ('restoreReceipt', restored), ('countsDdlReceipt', counts)]},
    }
    manifest['cdc'] = service.cdc_identity(op['runId'], target['target']['serverUuid'])
    scripts = Path(service.__file__).parent
    manifest['toolSources'] = {name: service.sha(scripts / name) for name in service.tools_for(manifest)}
    service.validate_manifest(manifest, mac.DATASET, op['runId'], manifest['serviceRelease'])
    proof = {
        'schemaVersion': 1, 'kind': service.KIND + '-readiness', 'state': service.READY,
        **{key: manifest[key] for key in ('runId', 'datasetId', 'serviceRelease', 'mysql', 'rds', 'application',
            'appRuntimeBinding', 'debezium', 'cdc', 'toolSources')},
        'appRuntime': runtime_projection(manifest), 'preparationReceipt': manifest['preparation']['receipt'],
        'sourceMode': mac.MODE,
        **{key: manifest['preparation'][key] for key in ('sourceProvenance', 'restoreReceipt', 'countsDdlReceipt')},
        'fullDatasetValidated': False, 'sqlReplayed': False, 'searchDatasetRestored': True,
        'searchTransport': manifest['search']['transport'], 'restoredIndex': 'snapshot-fixture-index',
        'topics': list(service.TOPICS),
        **{key: True for key in ('redisSeparate', 'debeziumVerified', 'cdcRunning', 'heartbeatObserved', 'writersStopped')},
        **{key: False for key in ('redisReset', 'applicationStarted', 'deploymentReady')},
        'nativeSearch': {
            'state': 'NATIVE_SEARCH_COUNT_AND_SAMPLE_VERIFIED', 'datasetId': mac.DATASET, 'runId': op['runId'],
            'transport': manifest['search']['transport'], 'documents': manifest['search']['documentFingerprint']['documents'],
            'restoredIndex': 'snapshot-fixture-index',
            **{key: True for key in ('nativeRestoreSucceeded', 'singleWriteAlias', 'representativeSearchPassed',
                'repositoryReadOnly', 'repositoryRemoved')},
            **{key: False for key in ('fullDatasetValidated', 'allDocumentSourceFieldsEqual', 'sqlReplayed')},
        },
    }
    snap = source['snapshot']
    context = {'mode': mac.MODE, 'identifier': snap['identifier'], 'sourceRunId': source['source']['runId'],
        'sourceResourceId': source['source']['rds']['resourceId'], 'source': source,
        'reference': {'key': source_ref['key'], 'version_id': source_ref['versionId'],
            'sha256': source_ref['sha256'], 'bytes': source_ref['bytes']},
        'live': [{'db_snapshot_arn': snap['arn'], 'db_instance_identifier': snap['sourceIdentifier'], 'status': 'available',
            'snapshot_type': 'manual', 'engine': 'mysql', 'engine_version': '8.4.11', 'encrypted': True,
            'allocated_storage': 100, 'storage_type': 'gp3', 'iops': 3000, 'kms_key_id': snap['kmsKeyArn'], 'tags': copy.deepcopy(snap['tags'])}],
        'instances': [target['target']['identifier']]}
    return manifest, proof, target, context, {'fencingToken': op['resourceFence'], 'expiresAt': str(op['window']['expiresAt'])}


class MacSnapshotInfrastructure(unittest.TestCase):
    def test_initial_restore_has_no_importer_or_application_and_allows_nat_tunnel(self):
        variables = {'global_b_prepare_only': False, 'global_b_import_from_mac': False,
            'global_b_snapshot_restore_only': True, 'global_b_snapshot_source_mode': mac.MODE,
            'global_b_services': False, 'global_b_service_bootstrap_enabled': False, 'deployment_phase': 'services'}
        config = ''.join('variable "' + name + '" { default = ' + json.dumps(value) + ' }\n' for name, value in variables.items())
        config += 'locals {\ngrowth_b_service_mac_source = false\nprivate_ips = {}\n'
        source = (LAB / 'locals.tf').read_text()
        for name in ('services_enabled', 'legacy_services_enabled', 'application_infrastructure_enabled',
            'dependency_services_enabled', 'data_bootstrap_enabled', 'legacy_service_hosts', 'service_hosts'):
            config += name + ' = ' + attribute(source, name) + '\n'
        config += 'private_dns_records = ' + attribute((LAB / 'private-dns.tf').read_text(), 'private_dns_records').replace(
            'module.service_hosts.private_ips', 'local.private_ips') + '\n'
        for name in ('data_bootstrap', 'growth_b_snapshot_read', 'growth_b_snapshot_restore_inputs'):
            config += name + '_role_count = ' + attribute(block((LAB / 'iam.tf').read_text(), 'resource', 'aws_iam_role_policy', name), 'count') + '\n'
        config += 'nat_tunnel_count = ' + attribute(block((LAB / 'security.tf').read_text(), 'resource',
            'aws_vpc_security_group_ingress_rule', 'mac_import_rds'), 'count') + '\n}\n'
        with tempfile.TemporaryDirectory(prefix='airbob-mac-initial-topology-') as directory:
            (Path(directory) / 'main.tf').write_text(config)
            result = subprocess.run(['terraform', '-chdir=' + directory, 'console', '-no-color'],
                input='jsonencode({hosts=keys(local.service_hosts),dns=local.private_dns_records,app=local.application_infrastructure_enabled,dependencies=local.dependency_services_enabled,bootstrap=local.data_bootstrap_enabled,roles=[local.data_bootstrap_role_count,local.growth_b_snapshot_read_role_count,local.growth_b_snapshot_restore_inputs_role_count],tunnel=local.nat_tunnel_count})\n',
                env={'PATH': os.environ['PATH'], 'CHECKPOINT_DISABLE': '1'}, text=True, capture_output=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual({'hosts': [], 'dns': {}, 'app': False, 'dependencies': False, 'bootstrap': False,
                'roles': [0, 0, 0], 'tunnel': 1}, json.loads(json.loads(result.stdout)))

    def evaluate(self, change=None):
        manifest, proof, target, context, operator = inputs()
        if change:
            change(manifest, proof, target, context, operator)
        return evaluate(manifest, proof, preparation_document=target, database_bootstrap='snapshot',
            retained_operator=operator, snapshot_context=context)

    def test_new_target_and_scoped_readiness_are_admitted(self):
        result = self.evaluate()
        self.assertTrue(result['valid'])
        self.assertTrue(result['ready'])
        resources = [str(item) for item in result['service']['Statement']]
        self.assertTrue(any('countsDdlReceipt.json' in item for item in resources))
        self.assertTrue(any('-mac-snapshots/' in item for item in resources))

    def test_live_source_and_changed_snapshot_are_rejected(self):
        for change in (
            lambda m, p, t, c, o: c['instances'].append(c['source']['source']['rds']['identifier']),
            lambda m, p, t, c, o: c['live'][0].update(allocated_storage=200),
            lambda m, p, t, c, o: c['live'][0].update(status='creating'),
            lambda m, p, t, c, o: c['live'][0].update(snapshot_type='automated'),
            lambda m, p, t, c, o: c.update(mode='verified-global-b-snapshot'),
        ):
            with self.subTest(change=change):
                self.assertFalse(self.evaluate(change)['valid'])

    def test_snapshot_tag_map_requires_exact_saved_keys_and_values(self):
        for change in (
            lambda m, p, t, c, o: c['live'][0]['tags'].pop('SourceRunId'),
            lambda m, p, t, c, o: c['live'][0]['tags'].update(Unreviewed='extra'),
            lambda m, p, t, c, o: c['live'][0]['tags'].update(SourceRunId='lab-other-source'),
        ):
            with self.subTest(change=change):
                self.assertFalse(self.evaluate(change)['valid'])

    def test_snapshot_tag_values_cannot_coerce_saved_numbers_or_booleans_to_strings(self):
        for malformed, live_value in ((28, '28'), (True, 'true')):
            for saved_value, expected in ((live_value, True), (malformed, False)):
                def change(manifest, proof, target, context, operator):
                    # Re-pin synthetic source bytes so this exercises tag value
                    # types, rather than failing an unrelated source hash check.
                    source = context['source']
                    source['snapshot']['tags']['TypeCheck'] = saved_value
                    context['live'][0]['tags']['TypeCheck'] = live_value
                    selected = reference(mac.encoded(source), 'datasets/' + mac.DATASET + '-mac-snapshots/' +
                        source['snapshot']['identifier'] + '/source-' + mac.digest(source) + '.json')
                    context['reference'] = {'key': selected['key'], 'version_id': selected['versionId'],
                        'sha256': selected['sha256'], 'bytes': selected['bytes']}
                    manifest['preparation']['sourceProvenance'] = selected
                    proof['sourceProvenance'] = selected
                    target['sourceSha256'] = selected['sha256']
                with self.subTest(saved_value=saved_value):
                    self.assertEqual(expected, self.evaluate(change)['valid'])

    def test_target_cannot_reuse_source_identity_or_wrong_window(self):
        for change in (
            lambda m, p, t, c, o: t.update(resourceFence=t['resourceFence'] + 1),
            lambda m, p, t, c, o: t['window'].update(expiresAt=t['window']['expiresAt'] + 1),
            lambda m, p, t, c, o: t.update(fullDatasetValidated=True),
            lambda m, p, t, c, o: t.update(sqlReplayed=True),
            lambda m, p, t, c, o: t.update(countsCanonicalSha256='0' * 64),
        ):
            with self.subTest(change=change):
                self.assertFalse(self.evaluate(change)['valid'])

    def test_readiness_binds_current_counts_and_native_alias(self):
        for change in (
            lambda m, p, t, c, o: p.update(countsDdlReceipt={}),
            lambda m, p, t, c, o: p['nativeSearch'].update(singleWriteAlias=False),
            lambda m, p, t, c, o: p.update(sourceMode='mac-sql-postcheck'),
        ):
            with self.subTest(change=change):
                result = self.evaluate(change)
                self.assertTrue(result['valid'])
                self.assertFalse(result['ready'])


if __name__ == '__main__':
    unittest.main()
