"""Immutable publication failure boundaries; no AWS calls are made."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('growth_v4_publisher', SCRIPTS / 'publish-growth-dataset-v4.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class FakeAws:
    def __init__(self):
        self.objects = {}
        self.puts = []
        self.deny_head = False
        self.corrupt_download = False
        self.no_version = False
        self.account = publisher.ACCOUNT

    def keys(self, prefix):
        return {key for key in self.objects if key.startswith(prefix)}

    def head(self, key):
        if self.deny_head:
            raise publisher.AwsError('HeadObject', '403')
        return {'VersionId': self.objects[key][0]} if key in self.objects else None

    def call(self, *args):
        if args[:2] == ('sts', 'get-caller-identity'):
            return {'Account': self.account,
                    'Arn': f'arn:aws:sts::{self.account}:assumed-role/airbob-dataset-publisher/test'}
        operation = args[1]
        key = args[args.index('--key') + 1]
        if operation == 'put-object':
            assert args[args.index('--if-none-match') + 1] == '*'
            if key in self.objects:
                raise publisher.AwsError(operation, '412')
            self.puts.append(key)
            self.objects[key] = ('version-' + str(len(self.puts)), Path(args[args.index('--body') + 1]).read_bytes())
            return {'VersionId': None if self.no_version else self.objects[key][0]}
        if operation == 'get-object':
            version, data = self.objects[key]
            assert args[args.index('--version-id') + 1] == version
            Path(args[-1]).write_bytes(b'corrupt' if self.corrupt_download else data)
            return {'VersionId': version}
        raise AssertionError(operation)


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.release = self.root / 'release'
        self.release.mkdir()
        for name in ['airbob-growth.sql.gz', 'SHA256SUMS.json', publisher.MARKER]:
            (self.release / name).write_text(name)
        self.dataset = 'korea-growth-v4-0123456789abcdef'
        self.prefix = 'datasets/' + self.dataset + '/'
        self.aws = FakeAws()

    def publish(self):
        with patch.object(publisher, 'validate'):
            return publisher.publish(self.release, self.dataset, publisher.BUCKET, self.root,
                                     self.root / 'receipt.json', self.aws)

    def test_completion_marker_is_last_and_versions_are_recorded(self):
        result = self.publish()
        self.assertEqual(self.aws.puts[-1], self.prefix + publisher.MARKER)
        self.assertEqual(result['state'], 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED')
        self.assertEqual(len(result['objects']), 3)
        self.assertFalse(result['awsDatabaseRestoreExecuted'])

    def test_conflicting_existing_bytes_prevent_all_writes(self):
        self.aws.objects[self.prefix + 'airbob-growth.sql.gz'] = ('old', b'other-dump')
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(self.aws.puts, [])

    def test_access_denied_is_not_treated_as_absent(self):
        self.aws.deny_head = True
        with self.assertRaises(publisher.AwsError):
            self.publish()
        self.assertEqual(self.aws.puts, [])

    def test_corrupt_download_never_creates_completion_marker(self):
        self.aws.corrupt_download = True
        with self.assertRaises(ValueError):
            self.publish()
        self.assertNotIn(self.prefix + publisher.MARKER, self.aws.puts)

    def test_missing_object_version_is_rejected(self):
        self.aws.no_version = True
        with self.assertRaises(ValueError):
            self.publish()
        self.assertNotIn(self.prefix + publisher.MARKER, self.aws.puts)

    def test_partial_upload_resumes_without_overwriting(self):
        key = self.prefix + 'airbob-growth.sql.gz'
        self.aws.objects[key] = ('existing', (self.release / 'airbob-growth.sql.gz').read_bytes())
        result = self.publish()
        self.assertNotIn(key, self.aws.puts)
        self.assertEqual(result['objects']['airbob-growth.sql.gz']['versionId'], 'existing')

    def test_completed_release_with_missing_payload_is_rejected(self):
        self.aws.objects[self.prefix + publisher.MARKER] = ('marker', b'marker')
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(self.aws.puts, [])

    def test_unexpected_account_is_rejected(self):
        self.aws.account = '000000000000'
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(self.aws.puts, [])


if __name__ == '__main__':
    unittest.main()
