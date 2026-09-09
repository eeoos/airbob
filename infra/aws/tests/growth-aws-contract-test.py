#!/usr/bin/env python3
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPTS = Path(__file__).parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import growth_aws_contract as contract

spec = importlib.util.spec_from_file_location('bootstrap', SCRIPTS / 'bootstrap-growth-aws.py')
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


class ContractTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((Path(__file__).parents[1] / 'lab/tests/fixtures/growth-aws-manifest.json').read_text())

    def test_qualified_envelope(self):
        contract.validate_manifest(self.manifest, self.manifest['datasetRelease'])

    def test_rejects_wrong_claims_and_artifact_bindings(self):
        for label, mutate in [
            ('engine', lambda m: m['mysql'].update(engineVersion='8.0.46')),
            ('migration', lambda m: m['mysql'].update(flywayVersion='28')),
            ('dataset', lambda m: m['source'].update(datasetId='korea-growth-v3-' + '0' * 16)),
            ('search', lambda m: m.update(search={'enabled': True})),
            ('runtime', lambda m: m.update(couponPreparation=[{'id': 1}])),
            ('dump', lambda m: m['mysql'].update(dumpSha256='0' * 64)),
            ('source', lambda m: m['source'].update(consumerManifestSha256='0' * 64)),
            ('extra', lambda m: m['artifacts'].update({'../secret': {'sha256': '0' * 64, 'bytes': 10}})),
            ('size', lambda m: m['artifacts']['verification-runtime.zip'].update(bytes=1_000_000_000)),
            ('open-state', lambda m: m['mysql']['expectedTableRows'].update(outbox=1)),
            ('unsupported-key', lambda m: m.update(extra=True)),
        ]:
            with self.subTest(label=label):
                value = copy.deepcopy(self.manifest)
                mutate(value)
                with self.assertRaises(ValueError):
                    contract.validate_manifest(value, self.manifest['datasetRelease'])

    def test_runtime_path_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'runtime.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('../outside', 'bad')
            with self.assertRaisesRegex(ValueError, 'Unsafe runtime archive path'):
                contract.validate_runtime(path, {})
            self.assertFalse((Path(directory).parent / 'outside').exists())

    def test_mode_rejected_before_reading_any_secret(self):
        with patch.dict(bootstrap.os.environ, {'AIRBOB_QUALIFICATION_ONLY': 'false', 'AIRBOB_DATABASE_BOOTSTRAP': 'dump'}), patch.object(bootstrap, 'aws') as aws:
            with self.assertRaisesRegex(ValueError, 'qualification-only'):
                bootstrap.main(Path('/does-not-exist'))
            aws.assert_not_called()

    def test_aws_boundary_rejected_before_secret_access(self):
        # The main path always constructs both trust stores; its boundary must
        # also reject a forged external target before consulting Secrets Manager.
        env = {'AIRBOB_QUALIFICATION_ONLY': 'true', 'AIRBOB_DATABASE_BOOTSTRAP': 'dump',
               'AIRBOB_REGION': 'us-east-1', 'AIRBOB_DATASET_BUCKET': 'untrusted',
               'AIRBOB_EVIDENCE_BUCKET': 'untrusted', 'AIRBOB_RDS_ENGINE_VERSION': '8.4.11'}
        with patch.dict(bootstrap.os.environ, env), patch.object(bootstrap, 'aws') as aws:
            with self.assertRaisesRegex(ValueError, 'boundary mismatch'):
                bootstrap.main(Path('/does-not-exist'))
            aws.assert_not_called()

    def test_remote_restore_requires_verified_tls(self):
        with patch.object(bootstrap, 'execute') as execute:
            with self.assertRaisesRegex(ValueError, 'verified TLS before import'):
                bootstrap.restore_and_verify(None, None, None, None, None,
                                             {'host': 'external.example'})
            execute.assert_not_called()


if __name__ == '__main__':
    unittest.main()
