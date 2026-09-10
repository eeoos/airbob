import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

scripts = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(scripts))
spec = importlib.util.spec_from_file_location('growth_v4_fetch', scripts / 'fetch-growth-dataset-v4.py')
fetcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetcher)


class FakeAws:
    def __init__(self, fixture):
        self.fixture = fixture
        self.reads = []
        self.account = fetcher.publication.ACCOUNT
        self.corrupt = False
        self.wrong_version = False
        self.before_read = None

    def call(self, *args):
        if args == ('sts', 'get-caller-identity'):
            return {'Account': self.account}
        if self.before_read:
            callback, self.before_read = self.before_read, None
            callback()
        assert args[:2] == ('s3api', 'get-object')
        key = args[args.index('--key') + 1]
        selected_version = args[args.index('--version-id') + 1]
        name = key.rsplit('/', 1)[1]
        assert selected_version == self.fixture['objects'][name]['versionId']
        Path(args[-1]).write_bytes(b'corrupted' if self.corrupt else name.encode())
        self.reads.append((name, selected_version))
        return {'VersionId': 'different' if self.wrong_version else selected_version}


class FetchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.destination = self.root / 'download'
        self.receipt_path = self.root / 'publication.json'
        self.dataset_id = 'korea-growth-v4-' + 'a' * 16
        self.prefix = 'datasets/' + self.dataset_id + '/'
        names = ['file-' + str(n) + '.json' for n in range(14)] + ['SHA256SUMS.json', 'consumer-manifest.json']
        objects = {name: {'key': self.prefix + name, 'sha256': hashlib.sha256(name.encode()).hexdigest(),
                         'bytes': len(name), 'versionId': 'pinned-' + str(n)} for n, name in enumerate(names)}
        marker = objects['consumer-manifest.json']
        self.receipt = {'schemaVersion': 1, 'kind': 'growth-v4-s3-publication',
            'state': 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED', 'datasetId': self.dataset_id,
            'bucket': fetcher.publication.BUCKET, 'region': fetcher.publication.REGION,
            'objects': objects, 'completionKey': marker['key'], 'completionVersionId': marker['versionId'],
            'consumerManifestSha256': marker['sha256'], 'totalBytes': sum(v['bytes'] for v in objects.values())}
        self.aws = FakeAws(self.receipt)
        # This suite tests publication consumption. The real v4 semantic validator
        # is exercised separately with the sealed release, including the live fetch.
        self.validation = patch.object(fetcher, 'validate').start()
        self.addCleanup(patch.stopall)

    def run_fetch(self, anchor=None):
        self.receipt_path.write_text(json.dumps(self.receipt))
        digest = fetcher.sha(self.receipt_path)
        return fetcher.fetch(self.receipt_path, anchor or digest, self.dataset_id,
                             self.destination, self.root, self.aws)

    def test_uses_recorded_versions_and_publishes_marker_last(self):
        report = self.run_fetch()
        self.assertEqual(report['state'], 'FETCHED_VERSIONS_AND_CONTRACT_VERIFIED')
        self.assertFalse(report['awsDatabaseRestoreExecuted'])
        self.assertEqual(self.aws.reads[-1][0], 'consumer-manifest.json')
        self.assertEqual({p.name for p in self.destination.iterdir()}, set(self.receipt['objects']))
        self.validation.assert_called_once()

    def test_rejects_changed_receipt_before_network(self):
        with self.assertRaisesRegex(ValueError, 'trust anchor'):
            self.run_fetch('b' * 64)
        self.assertEqual(self.aws.reads, [])

    def test_rejects_foreign_prefix(self):
        self.receipt['objects']['file-0.json']['key'] = 'unrelated/file-0.json'
        with self.assertRaisesRegex(ValueError, 'Unsafe publication'):
            self.run_fetch()
        self.assertEqual(self.aws.reads, [])

    def test_rejects_missing_version(self):
        self.receipt['objects']['file-0.json']['versionId'] = 'null'
        with self.assertRaisesRegex(ValueError, 'VersionId'):
            self.run_fetch()

    def test_rejects_corrupted_bytes_without_exposing_destination(self):
        self.aws.corrupt = True
        with self.assertRaisesRegex(ValueError, 'download differs'):
            self.run_fetch()
        self.assertFalse(self.destination.exists())

    def test_rejects_wrong_response_version(self):
        self.aws.wrong_version = True
        with self.assertRaisesRegex(ValueError, 'download differs'):
            self.run_fetch()
        self.assertFalse(self.destination.exists())

    def test_existing_destination_is_preserved(self):
        self.destination.mkdir()
        sentinel = self.destination / 'keep'
        sentinel.write_text('preserve')
        with self.assertRaisesRegex(ValueError, 'Destination must be new'):
            self.run_fetch()
        self.assertEqual(sentinel.read_text(), 'preserve')

    def test_concurrent_destination_is_preserved(self):
        def create():
            self.destination.mkdir()
            (self.destination / 'keep').write_text('preserve')
        self.aws.before_read = create
        with self.assertRaises(FileExistsError):
            self.run_fetch()
        self.assertEqual([p.name for p in self.destination.iterdir()], ['keep'])

    def test_incompatible_contract_is_not_committed(self):
        self.validation.side_effect = ValueError('V28 required')
        with self.assertRaisesRegex(ValueError, 'V28 required'):
            self.run_fetch()
        self.assertFalse(self.destination.exists())

    def test_wrong_account_cannot_download(self):
        self.aws.account = '000000000000'
        with self.assertRaisesRegex(ValueError, 'AWS account'):
            self.run_fetch()
        self.assertEqual(self.aws.reads, [])


if __name__ == '__main__':
    unittest.main()
