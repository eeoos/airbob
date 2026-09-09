#!/usr/bin/env python3
"""Exercise publication ordering, immutable replay and failure behavior without AWS."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('publisher',
    Path(__file__).parents[1] / 'scripts/publish-growth-dataset-v3.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)
fetch_spec = importlib.util.spec_from_file_location('fetcher',
    Path(__file__).parents[1] / 'scripts/fetch-growth-dataset-v3.py')
fetcher = importlib.util.module_from_spec(fetch_spec)
fetch_spec.loader.exec_module(fetcher)


class FakeAws:
    def __init__(self):
        self.objects = {}
        self.writes = []
        self.denied = False
        self.corrupt = False
        self.race = False
        self.unversioned = False
        self.content_type = 'application/json'
        self.arn = 'arn:aws:sts::942632789808:assumed-role/airbob-dataset-publisher/test'

    def identity(self):
        return {'Account': publisher.ACCOUNT, 'Arn': self.arn}

    def keys(self, bucket, prefix):
        return set(self.objects)

    def head(self, bucket, key):
        if self.denied:
            raise publisher.AwsError('HeadObject', '403')
        if key not in self.objects:
            return None
        return {'VersionId': self.objects[key][0], 'ContentType': self.content_type}

    def get(self, bucket, key, version, destination):
        actual, data = self.objects[key]
        assert version == actual
        destination.write_bytes(b'corrupted' if self.corrupt else data)
        return {'VersionId': actual}

    def put(self, bucket, key, source):
        assert key not in self.objects
        self.writes.append(key)
        version = 'null' if self.unversioned else f'version-{len(self.writes)}'
        self.objects[key] = (version, source.read_bytes())
        if self.race:
            raise publisher.AwsError('PutObject', 'PreconditionFailed')
        return {'VersionId': version}


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.release = self.root / 'release'
        self.release.mkdir()
        for name in list(publisher.validator.FILES.values()) + [publisher.MARKER]:
            (self.release / name).write_text('fixture: ' + name)
        self.validator = patch.object(publisher.validator, 'validate', return_value=None)
        self.validator.start()
        self.addCleanup(self.validator.stop)
        self.aws = FakeAws()
        self.dataset = 'korea-growth-v3-' + '0' * 16
        self.prefix = f'datasets/{self.dataset}/'
        self.receipt = self.root / 'receipt.json'

    def publish(self):
        return publisher.publish(self.release, self.dataset, publisher.BUCKET,
                                 self.root, self.receipt, self.aws)

    def test_marker_is_last_and_every_object_has_version_and_hash(self):
        result = self.publish()
        self.assertEqual(len(self.aws.writes), 8)
        self.assertEqual(self.aws.writes[-1], self.prefix + publisher.MARKER)
        self.assertEqual(result['state'], 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED')
        self.assertFalse(result['awsDatabaseRestoreExecuted'])
        self.assertEqual(self.receipt.stat().st_mode & 0o777, 0o600)
        self.assertTrue(all(len(obj['sha256']) == 64 for obj in result['objects'].values()))

    def test_aws_wrapper_has_its_own_last_marker_and_immutable_replay(self):
        for name in ['manifest.json', 'publication-receipt.json', 'verification-runtime.zip']:
            (self.release / name).write_text('wrapper fixture: ' + name)
        payloads = set(publisher.validator.FILES.values()) | {
            publisher.MARKER, 'publication-receipt.json', 'verification-runtime.zip'}
        contract = SimpleNamespace(PAYLOAD_FILES=payloads, validate_directory=lambda *args: None)
        identity = self.dataset + '-aws'
        result = publisher.publish(self.release, identity, publisher.BUCKET,
                                   self.root, self.receipt, self.aws, contract)
        self.assertEqual(len(self.aws.writes), 11)
        self.assertEqual(self.aws.writes[-1], 'datasets/' + identity + '/manifest.json')
        self.assertEqual(result['kind'], 'growth-aws-s3-publication')
        self.assertIn('manifestSha256', result)
        self.assertNotIn('consumerManifestSha256', result)
        self.assertFalse(result['awsDatabaseRestoreExecuted'])
        self.aws.writes.clear()
        replay = publisher.publish(self.release, identity, publisher.BUCKET,
                                   self.root, self.root / 'replay.json', self.aws, contract)
        self.assertTrue(replay['alreadyPublished'])
        self.assertEqual(self.aws.writes, [])
        self.aws.content_type = 'binary/octet-stream'
        with self.assertRaisesRegex(ValueError, 'Content-Type'):
            publisher.publish(self.release, identity, publisher.BUCKET,
                              self.root, self.root / 'invalid-metadata.json', self.aws, contract)
        self.assertEqual(self.aws.writes, [])

    def test_real_aws_adapter_sets_readable_json_metadata(self):
        aws = publisher.Aws()
        with patch.object(aws, 'call', return_value={}) as call:
            aws.put(publisher.BUCKET, self.prefix + 'manifest.json', self.release / publisher.MARKER)
            arguments = call.call_args.args
            self.assertEqual(arguments[arguments.index('--content-type') + 1], 'application/json')
            self.assertEqual(arguments[arguments.index('--if-none-match') + 1], '*')

    def test_identical_replay_performs_no_write(self):
        self.publish()
        self.aws.writes.clear()
        self.receipt = self.root / 'replay.json'
        self.assertTrue(self.publish()['alreadyPublished'])
        self.assertEqual(self.aws.writes, [])

    def test_conflict_is_found_before_any_write(self):
        self.aws.objects[self.prefix + 'runtime-plan.json'] = ('old', b'conflict')
        with self.assertRaisesRegex(ValueError, 'Remote bytes differ'):
            self.publish()
        self.assertEqual(self.aws.writes, [])

    def test_partial_publication_resumes(self):
        name = 'airbob-growth.sql.gz'
        self.aws.objects[self.prefix + name] = ('old', (self.release / name).read_bytes())
        result = self.publish()
        self.assertEqual(len(self.aws.writes), 7)
        self.assertEqual(result['objects'][name]['versionId'], 'old')

    def test_completed_release_with_missing_payload_is_rejected(self):
        self.aws.objects[self.prefix + publisher.MARKER] = (
            'old', (self.release / publisher.MARKER).read_bytes())
        with self.assertRaisesRegex(ValueError, 'missing payloads'):
            self.publish()
        self.assertEqual(self.aws.writes, [])

    def test_access_denied_is_not_absence(self):
        self.aws.denied = True
        with self.assertRaises(publisher.AwsError):
            self.publish()
        self.assertEqual(self.aws.writes, [])

    def test_corrupt_readback_cannot_publish_marker(self):
        self.aws.corrupt = True
        with self.assertRaisesRegex(ValueError, 'Remote bytes differ'):
            self.publish()
        self.assertNotIn(self.prefix + publisher.MARKER, self.aws.objects)
        self.assertEqual(json.loads(self.receipt.read_text())['state'], 'FAILED')

    def test_unversioned_bucket_cannot_publish_marker(self):
        self.aws.unversioned = True
        with self.assertRaisesRegex(ValueError, 'version ID'):
            self.publish()
        self.assertNotIn(self.prefix + publisher.MARKER, self.aws.objects)

    def test_concurrent_identical_create_is_verified(self):
        self.aws.race = True
        self.assertEqual(self.publish()['state'], 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED')

    def test_extra_remote_object_is_rejected(self):
        self.aws.objects[self.prefix + 'unexpected.json'] = ('old', b'other')
        with self.assertRaisesRegex(ValueError, 'Unexpected objects'):
            self.publish()
        self.assertEqual(self.aws.writes, [])

    def test_wrong_identity_is_rejected(self):
        self.aws.arn = 'arn:aws:iam::942632789808:user/admin-eeoos'
        with self.assertRaisesRegex(ValueError, 'publisher role'):
            self.publish()
        self.assertFalse(self.receipt.exists())

    def test_receipt_never_overwrites_existing_bytes(self):
        self.receipt.write_text('keep me')
        with self.assertRaisesRegex(ValueError, 'new file'):
            self.publish()
        self.assertEqual(self.receipt.read_text(), 'keep me')

    def fetch(self, sha):
        with patch.object(fetcher.validator, 'validate', return_value=None):
            return fetcher.fetch(self.receipt, self.dataset, sha,
                                 self.root / 'download', self.root, self.aws)

    def test_fetch_uses_published_versions(self):
        receipt = self.publish()
        result = self.fetch(receipt['consumerManifestSha256'])
        self.assertEqual(result['objects'], 8)
        for source in self.release.iterdir():
            self.assertEqual(source.read_bytes(), (self.root / 'download' / source.name).read_bytes())

    def test_fetch_rejects_changed_trust_anchor(self):
        self.publish()
        with self.assertRaisesRegex(ValueError, 'trust anchor'):
            self.fetch('f' * 64)
        self.assertFalse((self.root / 'download').exists())

    def test_fetch_corruption_leaves_no_result(self):
        receipt = self.publish()
        self.aws.corrupt = True
        with self.assertRaisesRegex(ValueError, 'Downloaded object mismatch'):
            self.fetch(receipt['consumerManifestSha256'])
        self.assertFalse((self.root / 'download').exists())

    def test_fetch_rejects_path_traversal(self):
        receipt = self.publish()
        receipt['objects']['airbob-growth.sql.gz']['key'] = self.prefix + '../another-dump'
        self.receipt.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, 'Unsafe publication object'):
            self.fetch(receipt['consumerManifestSha256'])

    def test_fetch_does_not_replace_existing_directory(self):
        receipt = self.publish()
        (self.root / 'download').mkdir()
        (self.root / 'download/keep').write_text('keep')
        with self.assertRaisesRegex(ValueError, 'must not exist'):
            self.fetch(receipt['consumerManifestSha256'])
        self.assertEqual((self.root / 'download/keep').read_text(), 'keep')


if __name__ == '__main__':
    unittest.main()
