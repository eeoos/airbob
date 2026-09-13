"""Evaluate actual S3 JSON read/admission HCL with MIME-independent body bytes.

Only a provider-free Terraform console runs. Its temporary configuration has
variables and locals, never providers, resources, backends or data sources.
Provider-returned S3 fields are values; all decoding, request-coordinate and
admission expressions come from the production HCL. No AWS calls are made.
"""
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from test_global_b_infrastructure import attribute, block
from test_growth_b_service_infrastructure import evaluate as evaluate_service
from test_growth_b_snapshot_infrastructure import evaluate as evaluate_snapshot


ROOT = Path(__file__).resolve().parents[3]
LAB = ROOT / 'infra/aws/lab'
DATASET = 'global-growth-b-' + 'a' * 16
BUCKET = 'airbob-performance-lab-dataset-942632789808'
GIB = 1024 ** 3
HELPERS = ('growth_b_prepare.py', 'growth_b_aws_restore.py', 'growth_b_aws_contract.py',
           'growth_b_contract.py', 'growth_b_runtime.py', 'growth_b_inventory.py')


def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def s3_object(raw, version='version-1', *, mime='binary/octet-stream', body=''):
    return {'body': body, 'body_base64': base64.b64encode(raw).decode(),
            'content_type': mime, 'content_length': len(raw), 'version_id': version}


def expressions(filename, names):
    source = (LAB / filename).read_text()
    return {name: attribute(source, name) for name in names}


def request(filename, name):
    source = block((LAB / filename).read_text(), 'data', 'aws_s3_object', name)
    return '{' + ','.join(key + '=(' + attribute(source, key) + ')' for key in
                         ('count', 'bucket', 'key', 'version_id', 'download_body')) + '}'


def console(values, variables, objects, seeds, result):
    """Replace provider identifiers with fixture values, leaving HCL logic intact."""
    terraform = shutil.which('terraform')
    if not terraform:
        raise AssertionError('Terraform is required for the S3 body regression')
    declarations = ''.join('variable "' + name + '" { default = ' + json.dumps(value) + ' }\n'
                           for name, value in variables.items())
    config = declarations + 'locals {\n  object_data = jsondecode(file("objects.json"))\n'
    config += ''.join('  ' + name + ' = ' + json.dumps(value) + '\n' for name, value in seeds.items())
    for name, expression in values.items():
        expression = expression.replace('data.aws_s3_object.', 'local.object_data.')
        if re.search(r'\b(?:data|module|aws_[A-Za-z_0-9]+)\.', expression):
            raise AssertionError('Unexpected provider reference: ' + name)
        config += '  ' + name + ' = ' + expression + '\n'
    config += '}\n'
    with tempfile.TemporaryDirectory(prefix='airbob-b-s3-body-') as directory:
        root = Path(directory)
        (root / 'main.tf').write_text(config)
        (root / 'objects.json').write_bytes(encoded(objects))
        (root / 'terraform.rc').write_text('disable_checkpoint = true\n')
        env = {'PATH': os.environ.get('PATH', ''), 'TF_IN_AUTOMATION': '1', 'TF_INPUT': '0',
               'CHECKPOINT_DISABLE': '1', 'AWS_EC2_METADATA_DISABLED': 'true',
               'TF_CLI_CONFIG_FILE': str(root / 'terraform.rc'), 'TF_DATA_DIR': str(root / '.terraform')}
        completed = subprocess.run([terraform, '-chdir=' + directory, 'console', '-no-color'],
            input='jsonencode(' + result + ')\n', text=True, capture_output=True, timeout=30, env=env)
        if completed.returncode or not completed.stdout.strip():
            raise AssertionError('Provider-free S3 HCL evaluation failed: ' + completed.stderr)
        return json.loads(json.loads(completed.stdout))


def selected_variables(mode):
    return {'global_b_prepare_only': mode == 'prepare', 'global_b_services': mode == 'service',
            'global_b_snapshot_restore_only': mode == 'snapshot', 'database_bootstrap': 'snapshot' if mode == 'snapshot' else 'dump',
            'global_b_snapshot_provenance': {'key': 'datasets/snapshot/provenance.json', 'version_id': 'snapshot-version'},
            'global_b_readiness_receipt': {'key': 'data-bootstrap/selected-readiness.json', 'version_id': 'readiness-version'},
            'global_b_manifest_version_id': 'manifest-version', 'global_b_service_release': 'service-01',
            'dataset_release': DATASET, 'dataset_manifest_sha256': 'a' * 64, 'run_id': 'lab-s3-body-test'}


def shared_seeds():
    return {'services_enabled': True, 'data_ready': True, 'dataset_prefix': 'datasets/' + DATASET,
            'growth_b_snapshot_bucket': BUCKET,
            'lab_contract': {'dataset_bucket_name': BUCKET,
                'evidence_bucket_name': 'airbob-performance-lab-evidence-942632789808'}}


def preparation_fixture():
    filenames = {'envelope': 'envelope.json', 'publicationReceipt': 'publication-receipt.json',
        'appJar': 'app.jar', 'consumerTools': 'consumer-tools.tar.gz', 'toolchain': 'toolchain.tar.gz',
        'toolchainManifest': 'toolchain.json', 'rdsCaBundle': 'rds-ca.pem', 'binlogBasis': 'binlog-budget.json'}
    files = {key: {'sha256': sha(name.encode()), 'versionId': key + '-version', 'bytes': 64}
             for key, name in filenames.items()}
    envelope = {'kind': 'global-growth-b-aws-restore', 'datasetId': DATASET,
        'mysql': {'version': '8.4.11', 'flywayVersion': 28, 'schema': 'airbobdb'},
        'account': '942632789808', 'region': 'ap-northeast-2', 'bucket': BUCKET,
        'appJarSha256': files['appJar']['sha256'], 'publicationReceiptSha256': files['publicationReceipt']['sha256'],
        'finalScaleSelected': False, 'awsExecutionAllowed': False, 'smallRehearsalEligible': True,
        'objects': {'sample': {'bytes': 1024}}, 'storage': {'requiredRdsFreeAfterRemovalBytes': GIB,
            'requiredAdditionalDataHostFreeBytes': 1024, 'runtimeExtractionBytes': 1024}}
    envelope_raw = encoded(envelope)
    files['envelope'].update(sha256=sha(envelope_raw), bytes=len(envelope_raw))
    for name, item in files.items():
        item['key'] = f"datasets/{DATASET}-aws-preparation/files/{item['sha256']}-{filenames[name]}"
    helpers = {name: sha((ROOT / 'infra/aws/scripts' / name).read_bytes()) for name in HELPERS}
    manifest = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-data-only-preparation', 'datasetId': DATASET,
        'account': '942632789808', 'region': 'ap-northeast-2', 'scope': 'small-rds-rehearsal',
        'mysql': {'version': '8.4.11', 'flywayVersion': 28}, 'files': files, 'toolSources': helpers,
        'toolchain': {'system': 'Linux', 'architecture': 'x86_64', 'pythonVersion': '3.12.14',
            'javaVersion': '21.0.12.1', 'mysqlVersion': '8.4.11', 'awsCliVersion': '2.34.64', 'unpackedBytes': 1024},
        'storage': {'rdsAllocatedGiB': 100, 'dataHostRootGiB': 20, 'minimumStagingFreeBytes': 4 * GIB},
        'binlogAdditionalReserveBytes': 4 * GIB}
    raw = encoded(manifest)
    variables = selected_variables('prepare') | {'rds_engine_version': '8.4.11', 'app_enabled': False,
        'load_generator_enabled': False, 'mode': 'performance', 'dns_mode': 'direct-only',
        'account_id': '942632789808', 'aws_region': 'ap-northeast-2', 'dataset_manifest_sha256': sha(raw)}
    objects = {'dataset_manifest': [s3_object(raw, 'manifest-version')],
               'growth_b_envelope': [s3_object(envelope_raw, files['envelope']['versionId'])]}
    return manifest, envelope, variables, objects, helpers


def evaluate_preparation(change=None):
    manifest, envelope, variables, objects, helpers = preparation_fixture()
    if change:
        change(objects)
    values = expressions('locals.tf', ('global_b_selected', 'dataset_manifest_body', 'dataset_manifest', 'dataset_manifest_key'))
    values.update(expressions('growth-b.tf', ('growth_b_envelope', 'growth_b_file_names', 'growth_b_dataset_valid')))
    values.update(dataset_request=request('data.tf', 'dataset_manifest'),
                  envelope_request=request('growth-b.tf', 'growth_b_envelope'))
    result = console(values, variables, objects, shared_seeds() | {'data_ready': False, 'growth_b_helper_sources': helpers},
        '{valid=local.growth_b_dataset_valid,manifest=local.dataset_manifest,envelope=local.growth_b_envelope,'
        'datasetRequest=local.dataset_request,envelopeRequest=local.envelope_request}')
    return result, manifest, envelope


def binary_objects(objects):
    for rows in objects.values():
        for item in rows:
            item.update(body='', content_type='binary/octet-stream')


class S3BodyRegression(unittest.TestCase):
    def test_binary_small_cas_manifest_resolves_real_envelope_and_preserves_pins(self):
        observed, manifest, envelope = evaluate_preparation()
        self.assertTrue(observed['valid'])
        self.assertEqual(manifest, observed['manifest'])
        self.assertEqual(envelope, observed['envelope'])
        self.assertEqual('manifest-version', observed['datasetRequest']['version_id'])
        self.assertEqual(f"datasets/{DATASET}-aws-preparation/aws-preparation-{sha(encoded(manifest))}.json",
                         observed['datasetRequest']['key'])
        self.assertEqual(manifest['files']['envelope']['key'], observed['envelopeRequest']['key'])
        self.assertEqual(manifest['files']['envelope']['versionId'], observed['envelopeRequest']['version_id'])
        self.assertTrue(observed['datasetRequest']['download_body'])
        self.assertTrue(observed['envelopeRequest']['download_body'])

    def test_b_reads_same_exact_bytes_for_json_text_and_binary_mime(self):
        for mime in ('application/json', 'text/plain', 'application/octet-stream', 'binary/octet-stream'):
            with self.subTest(mime=mime):
                def change(objects):
                    for rows in objects.values():
                        rows[0].update(content_type=mime, body='{}')
                self.assertTrue(evaluate_preparation(change)[0]['valid'])

    def test_same_json_with_changed_raw_bytes_is_rejected_without_repinning(self):
        for name in ('dataset_manifest', 'growth_b_envelope'):
            with self.subTest(name=name):
                def change(objects):
                    item = objects[name][0]
                    raw = base64.b64decode(item['body_base64'])
                    item['body_base64'] = base64.b64encode(raw + b'\n').decode()
                self.assertFalse(evaluate_preparation(change)[0]['valid'])

    def test_foreign_manifest_version_and_changed_envelope_reference_are_rejected(self):
        def wrong_version(objects):
            objects['dataset_manifest'][0]['version_id'] = 'foreign-version'
        self.assertFalse(evaluate_preparation(wrong_version)[0]['valid'])
        def changed_reference(objects):
            item = objects['dataset_manifest'][0]
            value = json.loads(base64.b64decode(item['body_base64']))
            value['files']['envelope']['versionId'] = 'foreign-envelope-version'
            item['body_base64'] = base64.b64encode(encoded(value)).decode()
        self.assertFalse(evaluate_preparation(changed_reference)[0]['valid'])

    def test_b_does_not_fall_back_to_text_body_for_invalid_base64_or_json(self):
        def invalid_base64(objects):
            item = objects['dataset_manifest'][0]
            item['body'] = base64.b64decode(item['body_base64']).decode()
            item['body_base64'] = '!invalid-base64!'
        with self.assertRaisesRegex(AssertionError, 'base64decode'):
            evaluate_preparation(invalid_base64)
        def invalid_json(objects):
            item = objects['growth_b_envelope'][0]
            item['body'] = base64.b64decode(item['body_base64']).decode()
            item['body_base64'] = base64.b64encode(b'not-json').decode()
        self.assertFalse(evaluate_preparation(invalid_json)[0]['valid'])

    def test_shared_download_option_uses_true_for_b_and_null_for_legacy(self):
        for mode in ('prepare', 'service', 'snapshot', 'legacy'):
            with self.subTest(mode=mode):
                values = expressions('locals.tf', ('global_b_selected', 'dataset_manifest_key'))
                values.update(dataset_request=request('data.tf', 'dataset_manifest'),
                              bootstrap_request=request('data.tf', 'data_bootstrap_receipt'))
                observed = console(values, selected_variables(mode), {}, shared_seeds(),
                    '{dataset=local.dataset_request,bootstrap=local.bootstrap_request}')
                self.assertEqual(None if mode == 'legacy' else True, observed['dataset']['download_body'])
                self.assertEqual(True if mode == 'service' else None, observed['bootstrap']['download_body'])
                self.assertEqual('snapshot-version' if mode == 'snapshot' else
                                 None if mode == 'legacy' else 'manifest-version', observed['dataset']['version_id'])
                self.assertEqual('readiness-version' if mode == 'service' else None, observed['bootstrap']['version_id'])

    def test_all_b_only_json_sources_force_download_body(self):
        values = {name: attribute(block((LAB / filename).read_text(), 'data', 'aws_s3_object', name), 'download_body')
            for filename, name in [('growth-b.tf', 'growth_b_envelope'), ('growth-b.tf', 'growth_b_service_preparation'),
                ('growth-b.tf', 'growth_b_service_transport'), ('growth-b.tf', 'growth_b_service_runtime'),
                ('growth-b-snapshot.tf', 'growth_b_snapshot_provenance')]}
        observed = console(values, {}, {}, {}, '{' + ','.join(k + '=local.' + k for k in values) + '}')
        self.assertEqual({name: True for name in values}, observed)

    def test_legacy_shared_decoders_keep_body_even_with_conflicting_or_invalid_base64(self):
        expected = {'legacy': True}
        for alternative in (base64.b64encode(encoded({'legacy': False})).decode(), '!invalid-base64!'):
            with self.subTest(base64=alternative):
                item = s3_object(encoded(expected), body=encoded(expected).decode())
                item['body_base64'] = alternative
                objects = {name: [copy.deepcopy(item)] for name in ('dataset_manifest', 'data_bootstrap_receipt')}
                values = expressions('locals.tf', ('global_b_selected', 'dataset_manifest_body', 'dataset_manifest'))
                values.update(expressions('checks.tf', ('data_bootstrap_receipt',)))
                observed = console(values, selected_variables('legacy'), objects, shared_seeds(),
                    '{manifest=local.dataset_manifest,bootstrap=local.data_bootstrap_receipt}')
                self.assertEqual({'manifest': expected, 'bootstrap': expected}, observed)

    def test_service_manifest_all_referenced_json_and_readiness_admit_binary_bodies(self):
        result = evaluate_service(object_change=binary_objects)
        self.assertTrue(result['valid'])
        self.assertTrue(result['ready'])

    def test_service_and_readiness_hashes_check_decoded_raw_bytes(self):
        for name in ('dataset_manifest', 'growth_b_service_preparation', 'growth_b_service_transport',
                     'growth_b_service_runtime', 'data_bootstrap_receipt'):
            with self.subTest(name=name):
                def change(objects):
                    binary_objects(objects)
                    item = objects[name][0]
                    item['body_base64'] = base64.b64encode(base64.b64decode(item['body_base64']) + b'\n').decode()
                observed = evaluate_service(object_change=change)
                self.assertFalse(observed['ready' if name == 'data_bootstrap_receipt' else 'valid'])

    def test_service_and_readiness_reject_foreign_returned_versions(self):
        for name in ('dataset_manifest', 'growth_b_service_runtime', 'data_bootstrap_receipt'):
            with self.subTest(name=name):
                def change(objects):
                    binary_objects(objects)
                    objects[name][0]['version_id'] = 'foreign-version'
                observed = evaluate_service(object_change=change)
                self.assertFalse(observed['ready' if name == 'data_bootstrap_receipt' else 'valid'])

    def test_snapshot_provenance_admits_binary_body_and_keeps_exact_version_and_sha(self):
        def binary(rows):
            rows[0].update(body='', content_type='binary/octet-stream')
        self.assertTrue(evaluate_snapshot(object_change=binary))
        for field in ('version_id', 'body_base64'):
            with self.subTest(field=field):
                def change(rows):
                    binary(rows)
                    rows[0][field] = ('foreign-version' if field == 'version_id' else
                        base64.b64encode(base64.b64decode(rows[0][field]) + b'\n').decode())
                self.assertFalse(evaluate_snapshot(object_change=change))


if __name__ == '__main__':
    unittest.main()
