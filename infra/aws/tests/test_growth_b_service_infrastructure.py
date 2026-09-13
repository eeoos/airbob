"""Evaluate the real B Terraform admission/IAM expressions without providers or cloud."""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_global_b_infrastructure import attribute, block
from test_growth_b_service_contract import fixture, receipt, runtime_projection, ROOT

LAB = ROOT / 'infra/aws/lab'


def evaluate(manifest=None, proof=None, mode='service', *, preparation_extra=None, database_bootstrap='dump'):
    manifest = manifest or fixture()
    proof = proof or receipt(manifest)
    preparation = {'kind': 'global-growth-b-aws-data-only-preparation', 'state': 'DATABASE_INVENTORY_LOGIN_VERIFIED',
        'runId': manifest['runId'], 'datasetId': manifest['datasetId'], 'rdsResourceId': manifest['rds']['resourceId'],
        'serverUuid': manifest['rds']['serverUuid'], 'restoreReceiptSha256': manifest['preparation']['restoreReceiptSha256'],
        'preparation': {'preparedFingerprintSha256': manifest['preparation']['preparedFingerprintSha256']},
        'deploymentReady': False, 'applicationLeftRunning': False}
    preparation.update(preparation_extra or {})
    prefix = f"datasets/{manifest['datasetId']}-search/{manifest['search']['snapshotRelease']}"
    transport = {'schemaVersion': 1, 'kind': 'global-growth-b-search-transport', 'bucket': 'airbob-performance-lab-dataset-942632789808', 'region': 'ap-northeast-2', 'datasetId': manifest['datasetId'], 'snapshotRelease': manifest['search']['snapshotRelease'],
        'source': {'appJarSha256': manifest['application']['appJarSha256']},
        'repository': {'type': 's3', 'bucket': 'airbob-performance-lab-dataset-942632789808', 'basePath': prefix + '/native'},
        'objects': {'native/a': {'key': prefix + '/native/a', 'versionId': 'native-v1'},
                    'companion/manifest.json': {'key': prefix + '/companion/manifest.json', 'versionId': 'companion-v1'}},
        'sql': {name: {'key': f"datasets/{manifest['datasetId']}/{file}", 'versionId': name + '-v1'}
            for name, file in [('consumerManifest', 'consumer-manifest.json'), ('checksums', 'SHA256SUMS.json'), ('dump', 'airbob-growth.sql.gz')]}}
    import hashlib
    prep_text, transport_text = json.dumps(preparation), json.dumps(transport)
    runtime_text = json.dumps({'schemaVersion': 1, 'kind': 'global-b-app-runtime-binding', 'runtime': runtime_projection(manifest)})
    # Bind the fixture's refs to actual serialized fixture bytes.
    manifest['preparation']['receipt']['sha256'] = hashlib.sha256(prep_text.encode()).hexdigest()
    manifest['search']['transport']['sha256'] = hashlib.sha256(transport_text.encode()).hexdigest()
    manifest['appRuntimeBinding']['sha256'] = hashlib.sha256(runtime_text.encode()).hexdigest()
    manifest_text = json.dumps(manifest)
    manifest_sha = hashlib.sha256(manifest_text.encode()).hexdigest()
    proof['manifestSha256'] = manifest_sha
    proof['preparationReceipt'] = manifest['preparation']['receipt']
    proof['searchTransport'] = manifest['search']['transport']
    proof['appRuntimeBinding'] = manifest['appRuntimeBinding']
    proof_text = json.dumps(proof)
    object_data = {'dataset_manifest': [{'body': manifest_text, 'version_id': 'manifest-v1'}],
        'growth_b_service_preparation': [{'body': prep_text}], 'growth_b_service_transport': [{'body': transport_text}],
        'growth_b_service_runtime': [{'body': runtime_text, 'version_id': manifest['appRuntimeBinding']['versionId']}],
        'data_bootstrap_receipt': [{'body': proof_text, 'version_id': 'readiness-v1'}]}
    variables = {'global_b_services': mode == 'service', 'global_b_prepare_only': mode == 'prepare',
        'global_b_service_bootstrap_enabled': True, 'database_bootstrap': database_bootstrap, 'rds_engine_version': '8.4.11',
        'global_b_snapshot_provenance': {'sha256': 'd' * 64},
        'mode': 'performance', 'dns_mode': 'direct-only', 'load_generator_enabled': False,
        'dataset_release': manifest['datasetId'], 'run_id': manifest['runId'], 'global_b_service_release': manifest['serviceRelease'],
        'dataset_manifest_sha256': manifest_sha, 'global_b_manifest_version_id': 'manifest-v1',
        'account_id': '942632789808', 'aws_region': 'ap-northeast-2', 'app_image_reference': manifest['application']['image'],
        'bundle_commit': manifest['application']['mainCommit'], 'infra_image_references': {'ELASTICSEARCH_IMAGE': manifest['search']['image'],
            'DEBEZIUM_IMAGE': manifest['debezium']['image']},
        'global_b_readiness_receipt': {'key': f"data-bootstrap/{manifest['runId']}/{manifest['datasetId']}-service-{manifest['serviceRelease']}.json",
            'version_id': 'readiness-v1', 'sha256': hashlib.sha256(proof_text.encode()).hexdigest(), 'bytes': len(proof_text)}}
    growth, iam = (LAB / 'growth-b.tf').read_text(), (LAB / 'iam.tf').read_text()
    names = ('growth_b_service_prefix', 'growth_b_search_prefix', 'growth_b_service_refs', 'growth_b_cdc_suffix',
        'growth_b_service_manifest_valid', 'growth_b_service_preparation', 'growth_b_service_transport', 'growth_b_service_runtime', 'growth_b_readiness_valid')
    expressions = {name: attribute(growth, name) for name in names}
    expressions.update({name: attribute(iam, name) for name in ('growth_b_preparation_refs', 'growth_b_service_read_refs',
        'growth_b_app_read_refs', 'growth_b_transport_refs')})
    for name in ('growth_b_preparation_inputs', 'growth_b_service_inputs', 'growth_b_app_inputs', 'growth_b_search_inputs'):
        expressions[name] = attribute(block(iam, 'resource', 'aws_iam_role_policy', name), 'policy')
    vars_text = ''.join('variable "' + key + '" { default = ' + json.dumps(value) + ' }\n' for key, value in variables.items())
    config = vars_text + '''locals {
      services_enabled = true
      data_ready = true
      dataset_manifest = jsondecode(file("manifest.json"))
      dataset_prefix = "datasets/${var.dataset_release}"
      dataset_manifest_key = "datasets/${var.dataset_release}-aws-service/${var.global_b_service_release}/aws-service.json"
      growth_b_service_helper_sources = local.dataset_manifest.toolSources
      lab_contract = { dataset_bucket_name = "airbob-performance-lab-dataset-942632789808", evidence_bucket_name = "airbob-performance-lab-evidence-942632789808" }
      growth_b_envelope = { objects = {} }
      dataset_kafka_topics = toset(jsondecode(file("topics.json")))
      data_bootstrap_receipt = jsondecode(file("proof.json"))
      object_data = jsondecode(file("objects.json"))
      rds = [{ resource_id = "db-AAAAAAAAAAAAAAAAAAAAAAAA", arn = "arn:aws:rds:ap-northeast-2:942632789808:db:airbob-lab-b-services-test", master_secret_arn = "arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-selected" }]
    '''
    for key, expression in expressions.items():
        config += key + ' = ' + expression.replace('data.aws_s3_object.', 'local.object_data.').replace('module.rds', 'local.rds') + '\n'
    config += '}\n'
    with tempfile.TemporaryDirectory(prefix='airbob-b-service-static-') as directory:
        root = Path(directory)
        for name, value in [('manifest', manifest), ('proof', proof), ('objects', object_data), ('topics', proof['topics'])]:
            (root / (name + '.json')).write_text(json.dumps(value))
        (root / 'main.tf').write_text(config)
        env = dict(PATH=os.environ['PATH'], CHECKPOINT_DISABLE='1', TF_IN_AUTOMATION='1', AWS_EC2_METADATA_DISABLED='true')
        result = subprocess.run(['terraform', '-chdir=' + directory, 'console', '-no-color'], env=env, text=True, capture_output=True,
            input='jsonencode({valid=local.growth_b_service_manifest_valid,ready=local.growth_b_readiness_valid,app=jsondecode(local.growth_b_app_inputs),search=jsondecode(local.growth_b_search_inputs),service=jsondecode(local.growth_b_service_inputs)})\n', timeout=30)
        if not result.stdout.strip() or result.returncode:
            raise AssertionError(result.stderr)
        return json.loads(json.loads(result.stdout))


class ServiceInfrastructure(unittest.TestCase):
    def test_actual_hcl_admits_b_and_exact_readiness(self):
        result = evaluate()
        self.assertTrue(result['valid']); self.assertTrue(result['ready'])

    def test_hcl_rejects_wrong_preparation_or_legacy_engine(self):
        manifest = fixture(); manifest['mysql']['flywayVersion'] = 27
        self.assertFalse(evaluate(manifest)['valid'])

    def test_snapshot_services_require_actual_preparation_and_exact_snapshot_lineage(self):
        proof = {'sourceMode': 'verified-global-b-snapshot', 'snapshotProvenanceSha256': 'd' * 64,
            'snapshotRestoreEvidence': {'evidenceSource': 'controller-pinned-cloudtrail-event', 'eventSha256': 'e' * 64}}
        self.assertTrue(evaluate(database_bootstrap='snapshot', preparation_extra=proof)['valid'])
        self.assertFalse(evaluate(database_bootstrap='snapshot')['valid'])
        self.assertFalse(evaluate(preparation_extra=proof)['valid'])
        proof['snapshotProvenanceSha256'] = 'f' * 64
        self.assertFalse(evaluate(database_bootstrap='snapshot', preparation_extra=proof)['valid'])
        manifest = fixture(); manifest['consumerTools']['key'] = f"datasets/{manifest['datasetId']}/consumer-tools.tar.gz"
        self.assertFalse(evaluate(manifest)['valid'])
        manifest = fixture(); manifest['cdc']['topicPrefix'] = 'airbob_server'
        self.assertFalse(evaluate(manifest)['valid'])

    def test_hcl_rejects_ready_for_changed_rds_or_unverified_dependencies(self):
        for field, changed in [('cdcRunning', False), ('redisSeparate', False), ('deploymentReady', True)]:
            manifest = fixture(); proof = receipt(manifest); proof[field] = changed
            with self.subTest(field=field): self.assertFalse(evaluate(manifest, proof)['ready'])
        manifest = fixture(); proof = copy.deepcopy(receipt(manifest)); proof['rds']['resourceId'] = 'db-' + 'B' * 24
        self.assertFalse(evaluate(manifest, proof)['ready'])

    def test_readonly_search_is_narrowed_to_selected_native_prefix_and_exact_refs(self):
        result = evaluate()
        statements = result['search']['Statement']
        by_sid = {item['Sid']: item for item in statements}
        listing = by_sid['ListExactBSearchRelease']
        self.assertEqual(['s3:ListBucket', 's3:ListBucketVersions'], listing['Action'])
        self.assertEqual(1, len(listing['Condition']['StringLike']['s3:prefix']))
        self.assertIn('-search/', listing['Condition']['StringLike']['s3:prefix'][0])
        native = by_sid['ReadExactNativeBRepository']
        self.assertTrue(native['Resource'].endswith('/native/*'))
        self.assertEqual(['s3:GetObject', 's3:GetObjectVersion'], native['Action'])
        pinned = [item for item in statements if item['Sid'].startswith('ReadPinnedBSearch')]
        self.assertEqual(5, len(pinned))
        for item in pinned:
            self.assertEqual('s3:GetObjectVersion', item['Action']); self.assertNotIn('*', item['Resource'])
            self.assertIn('s3:VersionId', item['Condition']['StringEquals'])
        self.assertLess(len(json.dumps(result['search'], separators=(',', ':'))), 8000)
        self.assertFalse(any('s3:Put' in json.dumps(item) or 's3:Delete' in json.dumps(item) for item in statements))

    def test_application_has_only_pinned_ca_runtime_readiness_and_exact_rds_describe(self):
        result = evaluate()
        statements = result['app']['Statement']
        self.assertEqual(4, len(statements))
        self.assertEqual('rds:DescribeDBInstances', statements[0]['Action'])
        for item in statements[1:]:
            self.assertEqual('s3:GetObjectVersion', item['Action']); self.assertNotIn('*', item['Resource'])
            self.assertIn('s3:VersionId', item['Condition']['StringEquals'])
        self.assertTrue(any('/data-bootstrap/' in item.get('Resource', '') for item in statements))
        self.assertTrue(any('/files/app-runtime-binding.json' in item.get('Resource', '') for item in statements))

    def test_runtime_binding_and_live_readiness_projection_cannot_be_changed(self):
        manifest = fixture(); proof = receipt(manifest)
        proof['appRuntime']['runtimeDigest'] = '0'*64
        self.assertFalse(evaluate(manifest, proof)['ready'])
        manifest = fixture(); manifest['appRuntimeBinding']['key'] = f"datasets/{manifest['datasetId']}/runtime.json"
        self.assertFalse(evaluate(manifest)['valid'])


if __name__ == '__main__': unittest.main()
