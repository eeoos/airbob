"""Offline immutable B transport tests using real companion validation and fake S3."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_search as search
import growth_b_search_transport as transport
import test_growth_b_search as contracts


def checksum(data):
    return hashlib.sha256(data).hexdigest()


def fixture_write(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n')
    path.chmod(0o600)


class FakeAws:
    def __init__(self):
        self.objects, self.parts, self.calls = {}, {}, []
        self.corrupt = set()
        self.wrong_version = False
        self.fail_part = False
        self.after_put = None
        self.after_get = None
        self.extra_inventory = []

    def seed(self, key, data, encryption='AES256'):
        versions = self.objects.setdefault(key, [])
        versions.append({'versionId': 'version-' + str(len(versions) + 1), 'data': data, 'encryption': encryption})
        return versions[-1]

    def keys(self, prefix):
        return {key for key in self.objects if key.startswith(prefix)}

    def head(self, key):
        if key not in self.objects:
            return None
        item = self.objects[key][-1]
        return {'VersionId': item['versionId'], 'ContentLength': len(item['data']), 'ServerSideEncryption': item['encryption']}

    def call(self, service, operation, *args):
        self.calls.append((operation, args))
        def value(flag):
            return args[args.index(flag) + 1]
        if operation == 'get-caller-identity':
            return {'Account': transport.ACCOUNT,
                    'Arn': f'arn:aws:sts::{transport.ACCOUNT}:assumed-role/airbob-dataset-publisher/offline-test'}
        if operation == 'list-object-versions':
            prefix = value('--prefix')
            versions = [{'Key': key, 'VersionId': item['versionId'], 'IsLatest': i == len(items) - 1,
                         'Size': len(item['data']), 'ETag': 'etag'}
                        for key, items in sorted(self.objects.items()) if key.startswith(prefix)
                        for i, item in enumerate(items)]
            return {'Versions': versions + self.extra_inventory}
        key = value('--key')
        if operation == 'head-object':
            selected = (next(item for item in self.objects[key] if item['versionId'] == value('--version-id'))
                        if '--version-id' in args else self.objects[key][-1])
            return {'VersionId': selected['versionId'], 'ContentLength': len(selected['data']), 'ServerSideEncryption': selected['encryption']}
        if operation in {'put-object', 'complete-multipart-upload'}:
            assert value('--if-none-match') == '*'
            if key in self.objects:
                raise transport.publisher.AwsError(operation, 'PreconditionFailed')
            if operation == 'put-object':
                data = Path(value('--body')).read_bytes()
                assert value('--server-side-encryption') == 'AES256'
            else:
                data = b''.join(self.parts[n] for n in sorted(self.parts))
            selected = self.seed(key, data)
            if self.after_put:
                self.after_put(key)
            return {'VersionId': selected['versionId']}
        if operation == 'create-multipart-upload':
            assert value('--server-side-encryption') == 'AES256'
            self.parts = {}
            return {'UploadId': 'upload-1'}
        if operation == 'upload-part':
            if self.fail_part:
                raise transport.publisher.AwsError(operation, 'OfflinePartFailure')
            self.parts[int(value('--part-number'))] = Path(value('--body')).read_bytes()
            return {'ETag': 'part-' + value('--part-number')}
        if operation == 'abort-multipart-upload':
            self.parts = {}
            return {}
        if operation == 'get-object':
            selected = next(item for item in self.objects[key] if item['versionId'] == value('--version-id'))
            data = selected['data']
            result = {'VersionId': 'wrong-version' if self.wrong_version else selected['versionId'], 'ContentLength': len(data)}
            if '--range' in args:
                start, end = map(int, value('--range').removeprefix('bytes=').split('-'))
                result['ContentRange'] = f'bytes {start}-{end}/{len(data)}'
                data = data[start:end + 1]
                result['ContentLength'] = len(data)
            if key in self.corrupt:
                data = bytes([data[0] ^ 1]) + data[1:]
            Path(args[-1]).write_bytes(data)
            if self.after_get:
                self.after_get(key)
            return result
        raise AssertionError(operation)


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.aws = FakeAws()
        fixture = contracts.SearchContracts(); fixture.root = self.root
        self.companion, _ = fixture.companion()
        self.native = self.root / 'native'; self.native.mkdir()
        for name, data in {'index-0': b'index contents', 'indices/nativeid/0/__segment': b'native segment bytes' * 4}.items():
            path = self.native / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
        native = {'type': 'fs', 'entries': [{'key': str(p.relative_to(self.native)), **transport.binding(p)}
                  for p in sorted(self.native.rglob('*')) if p.is_file()]}
        sql_files = {'consumer-manifest.json': b'{"fixture":"consumer"}\n',
                     'SHA256SUMS.json': b'{"fixture":"sealed-checks"}\n', 'airbob-growth.sql.gz': b'fixture compressed SQL'}
        sql_files.update({f'proof-{i}.json': b'{}\n' for i in range(17)})
        self.dataset_id = 'global-growth-b-' + checksum(sql_files['airbob-growth.sql.gz'])[:16]
        self.snapshot_release = self.dataset_id + '-search-offline'
        self.prefix = transport.prefix_for(self.dataset_id, self.snapshot_release)
        anchor = {'datasetId': self.dataset_id, 'consumerManifestSha256': checksum(sql_files['consumer-manifest.json']),
                  'checksSha256': checksum(sql_files['SHA256SUMS.json']), 'appJarSha256': '4' * 64}
        for name in search.NAMES - {'mysql-baseline-fingerprint.json', 'mysql-prepared-fingerprint.json', 'native-inventory.json'}:
            path = self.companion / name; value = search.read(path); value.update(anchor)
            if 'snapshotRelease' in value:
                value['snapshotRelease'] = self.snapshot_release
            if name == 'snapshot-reference.json':
                value['snapshot'] = self.snapshot_release; value['nativeInventorySha256'] = search.digest(native)
            if name == 'source-proof.json':
                value['baseDumpSha256'] = checksum(sql_files['airbob-growth.sql.gz'])
            fixture_write(path, value)
        fixture_write(self.companion / 'native-inventory.json', native)
        self.reseal()
        self.descriptor = self.root / 'descriptor.json'
        fixture_write(self.descriptor, search.descriptor_for(self.companion, search.read(self.companion / 'manifest.json')))
        objects = {}
        for name, data in sql_files.items():
            key = 'datasets/' + self.dataset_id + '/' + name
            item = self.aws.seed(key, data)
            objects[name] = {'key': key, 'versionId': item['versionId'], 'sha256': checksum(data), 'bytes': len(data)}
        self.sql_receipt = self.root / 'sql-publication.json'
        consumer = objects['consumer-manifest.json']
        fixture_write(self.sql_receipt, {'schemaVersion': 1, 'kind': 'global-growth-b-s3-publication',
            'state': 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED', 'datasetId': self.dataset_id,
            'bucket': transport.BUCKET, 'region': transport.REGION, 'objects': objects,
            'completionKey': consumer['key'], 'completionVersionId': consumer['versionId'],
            'consumerManifestSha256': consumer['sha256'], 'totalBytes': sum(item['bytes'] for item in objects.values())})

    def reseal(self):
        seal = search.read(self.companion / 'snapshot-seal.json')
        seal['artifacts'] = {name: transport.binding(self.companion / name) for name in search.NAMES - {'manifest.json', 'snapshot-seal.json'}}
        fixture_write(self.companion / 'snapshot-seal.json', seal)
        manifest = search.read(self.companion / 'manifest.json')
        manifest['artifacts'] = {name: transport.binding(self.companion / name) for name in search.NAMES - {'manifest.json'}}
        fixture_write(self.companion / 'manifest.json', manifest)

    def publish(self, name='publication.json'):
        self.receipt_path = self.root / name
        return transport.publish(self.companion, self.descriptor, transport.sha(self.descriptor), self.native,
            self.sql_receipt, transport.sha(self.sql_receipt), self.dataset_id, transport.BUCKET, self.receipt_path, aws=self.aws)

    def fetched(self):
        receipt = self.publish()
        output = self.root / 'fetched'
        result = transport.fetch(self.receipt_path, transport.sha(self.receipt_path), self.dataset_id, output, aws=self.aws)
        return receipt, output, result

    def test_publish_is_sse_sibling_marker_last_and_reuses_exact_versions(self):
        before = {p.name: p.read_bytes() for p in self.companion.iterdir()}
        receipt = self.publish()
        puts = [args[args.index('--key') + 1] for op, args in self.aws.calls if op in {'put-object', 'complete-multipart-upload'}]
        self.assertEqual(puts[-1], self.prefix + transport.MARKER)
        self.assertTrue(all(key.startswith(self.prefix) and not key.startswith('datasets/' + self.dataset_id + '/') for key in puts))
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.companion.iterdir()})
        count = len(puts); again = self.publish('again.json')
        self.assertTrue(again['alreadyPublished'])
        self.assertEqual(receipt['manifest'], again['manifest'])
        self.assertEqual(count, len([op for op, _ in self.aws.calls if op in {'put-object', 'complete-multipart-upload'}]))
        self.assertEqual(self.receipt_path.stat().st_mode & 0o777, 0o600)

    def test_fetch_retains_exact_seals_and_native_tree(self):
        receipt, output, result = self.fetched()
        self.assertEqual(result['state'], 'FETCHED_UNCHANGED_FS_NATIVE_BYTES_VERIFIED')
        for original, directory in ((self.companion, output / 'companion'), (self.native, output / 'native')):
            self.assertEqual({str(p.relative_to(original)): p.read_bytes() for p in original.rglob('*') if p.is_file()},
                             {str(p.relative_to(directory)): p.read_bytes() for p in directory.rglob('*') if p.is_file()})
        self.assertFalse(output.with_name('fetched.partial').exists())
        self.assertTrue(all(p.stat().st_mode & 0o777 == 0o600 for p in output.rglob('*') if p.is_file()))
        self.assertEqual((output.stat().st_mode & 0o777), 0o700)
        manifest = transport.validate_published_transport(output / transport.MARKER, receipt['manifest']['sha256'],
            output / 'companion', search.read(output / transport.DESCRIPTOR))
        self.assertEqual(manifest['repository']['basePath'], self.prefix + 'native')

    def test_source_mutation_and_bad_descriptor_stop_before_aws(self):
        (self.native / 'index-0').write_bytes(b'changed')
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(self.aws.calls, [])
        with self.assertRaises(ValueError):
            transport.source_inputs(self.companion, self.descriptor, '0' * 64, self.native, self.dataset_id)

    def test_symlink_native_entry_is_rejected_before_publication(self):
        (self.native / 'link').symlink_to(self.native / 'index-0')
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(self.aws.calls, [])

    def test_sql_receipt_for_other_dataset_cannot_bind(self):
        value = search.read(self.sql_receipt); value['datasetId'] = 'global-growth-b-' + '0' * 16
        fixture_write(self.sql_receipt, value)
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(self.aws.calls, [])

    def test_private_or_unknown_receipt_fields_are_not_uploaded(self):
        value = search.read(self.sql_receipt); value['credentials'] = {'password': 'offline-secret-fixture'}
        fixture_write(self.sql_receipt, value)
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(self.aws.calls, [])

    def test_incomplete_same_bytes_resume_without_rewriting_payload(self):
        key = self.prefix + 'native/index-0'
        self.aws.seed(key, (self.native / 'index-0').read_bytes())
        receipt = self.publish()
        self.assertFalse(receipt['alreadyPublished'])
        self.assertEqual(len(self.aws.objects[key]), 1)
        self.assertFalse(any(op == 'put-object' and key in args for op, args in self.aws.calls))

    def test_divergent_existing_object_cannot_be_overwritten(self):
        key = self.prefix + 'native/index-0'; self.aws.seed(key, b'different')
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(self.aws.objects[key][0]['data'], b'different')
        self.assertNotIn(self.prefix + transport.MARKER, self.aws.objects)

    def test_completed_release_missing_object_is_never_repaired(self):
        self.publish(); del self.aws.objects[self.prefix + 'native/index-0']
        before = len([op for op, _ in self.aws.calls if op == 'put-object'])
        with self.assertRaises(ValueError):
            self.publish('cannot-repair.json')
        self.assertEqual(before, len([op for op, _ in self.aws.calls if op == 'put-object']))

    def test_native_readback_corruption_cannot_publish_marker(self):
        self.aws.corrupt.add(self.prefix + 'native/index-0')
        with self.assertRaises(ValueError):
            self.publish()
        self.assertNotIn(self.prefix + transport.MARKER, self.aws.objects)
        self.assertEqual(search.read(self.receipt_path)['state'], 'FAILED')

    def test_input_changes_after_upload_prevent_completion(self):
        self.aws.after_put = lambda key: (self.native / 'index-0').write_bytes(b'changed later') if key.endswith('/native/index-0') else None
        with self.assertRaises(ValueError):
            self.publish()
        self.assertNotIn(self.prefix + transport.MARKER, self.aws.objects)

    def test_wrong_sse_or_multiple_versions_are_rejected(self):
        key = self.prefix + 'native/index-0'
        self.aws.seed(key, (self.native / 'index-0').read_bytes(), 'aws:kms')
        with self.assertRaises(ValueError):
            self.publish()
        self.aws.seed(key, (self.native / 'index-0').read_bytes())
        with self.assertRaises(ValueError):
            self.publish('versions.json')
        self.assertNotIn(self.prefix + transport.MARKER, self.aws.objects)

    def test_multipart_failure_aborts_and_retains_recovery_coordinates(self):
        real_upload = transport.publisher.upload
        def small_parts(*args, **kwargs):
            return real_upload(*args, **kwargs, part_bytes=8)
        self.aws.fail_part = True
        with patch.object(transport.publisher, 'upload', side_effect=small_parts), self.assertRaises(transport.publisher.AwsError):
            self.publish()
        receipt = search.read(self.receipt_path)
        self.assertEqual(receipt['state'], 'FAILED')
        self.assertTrue(any(item['state'] == 'ABORTED' and item['uploadId'] == 'upload-1' for item in receipt['multipartUploads'].values()))
        self.assertEqual(self.aws.parts, {})
        self.assertNotIn(self.prefix + transport.MARKER, self.aws.objects)

    def test_s3_repository_proof_checks_all_native_bytes_and_versions(self):
        receipt, output, _ = self.fetched()
        manifest = transport.validate_published_transport(output / transport.MARKER, receipt['manifest']['sha256'],
            output / 'companion', search.read(output / transport.DESCRIPTOR))
        proof = transport.verify_s3_repository(manifest, self.aws.call)
        self.assertEqual(proof['nativeFiles'], 2)
        self.assertTrue(proof['exactVersionBytesVerified'])
        self.aws.corrupt.add(self.prefix + 'native/index-0')
        with self.assertRaises(ValueError):
            transport.verify_s3_repository(manifest, self.aws.call)

    def test_s3_repository_requires_published_completion_marker_and_metadata(self):
        _, output, _ = self.fetched(); manifest = search.read(output / transport.MARKER)
        key = self.prefix + 'companion/manifest.json'; saved = self.aws.objects.pop(key)
        with self.assertRaises(ValueError):
            transport.verify_s3_repository(manifest, self.aws.call)
        self.aws.objects[key] = saved
        del self.aws.objects[self.prefix + transport.MARKER]
        with self.assertRaises((KeyError, ValueError)):
            transport.verify_s3_repository(manifest, self.aws.call)

    def test_native_version_adapter_rejects_extra_old_or_wrong_coordinates(self):
        receipt, output, _ = self.fetched()
        manifest = search.read(output / transport.MARKER)
        inventory = {'type': 's3', 'bucket': transport.BUCKET, 'basePath': self.prefix + 'native',
            'entries': [{'kind': 'version', 'isLatest': True, **{key: item[key] for key in ('key', 'versionId', 'bytes')}, 'etag': 'e'}
                        for name, item in receipt['objects'].items() if name.startswith('native/')]}
        self.assertTrue(transport.validate_native_versions(manifest, inventory))
        for changed in (inventory | {'basePath': 'another-prefix'},
                        inventory | {'entries': inventory['entries'] + [inventory['entries'][0]]},
                        inventory | {'entries': [inventory['entries'][0] | {'kind': 'delete-marker'}]}):
            with self.assertRaises(ValueError):
                transport.validate_native_versions(manifest, changed)

    def test_tampered_manifest_mapping_or_sql_version_binding_is_rejected(self):
        receipt, output, _ = self.fetched()
        path = output / transport.MARKER; original = search.read(path)
        for field in ('native', 'sql', 'source'):
            changed = copy.deepcopy(original)
            if field == 'native':
                changed['objects']['native/index-0']['sha256'] = '9' * 64
            elif field == 'sql':
                changed['sql']['consumerManifest']['key'] = 'datasets/another/consumer-manifest.json'
            else:
                changed['source']['nativeInventorySha256'] = '9' * 64
            fixture_write(path, changed)
            with self.assertRaises(ValueError):
                transport.validate_published_transport(path, transport.sha(path), output / 'companion', search.read(output / transport.DESCRIPTOR))

    def test_bad_fetch_receipt_or_wrong_version_never_publishes_destination(self):
        self.publish(); before = len(self.aws.calls)
        with self.assertRaises(ValueError):
            transport.fetch(self.receipt_path, '0' * 64, self.dataset_id, self.root / 'bad', aws=self.aws)
        self.assertEqual(before, len(self.aws.calls))
        self.aws.wrong_version = True
        with self.assertRaises(ValueError):
            transport.fetch(self.receipt_path, transport.sha(self.receipt_path), self.dataset_id, self.root / 'bad', aws=self.aws)
        self.assertFalse((self.root / 'bad').exists())

    def test_fetch_does_not_replace_concurrently_created_output(self):
        self.publish(); output = self.root / 'concurrent'
        self.aws.after_get = lambda key: output.mkdir(exist_ok=True)
        with self.assertRaises(OSError):
            transport.fetch(self.receipt_path, transport.sha(self.receipt_path), self.dataset_id, output, aws=self.aws)
        self.assertEqual(list(output.iterdir()), [])
        self.assertTrue((self.root / 'concurrent.partial').exists())

    def test_fetch_rejects_tampered_path_before_writing_payload(self):
        receipt = self.publish()
        marker_key = self.prefix + transport.MARKER
        manifest = json.loads(self.aws.objects[marker_key][-1]['data'])
        item = manifest['objects'].pop('native/index-0')
        item['key'] = self.prefix + 'native/../../outside'
        manifest['objects']['native/../../outside'] = item
        payload = transport.manifest_bytes(manifest)
        updated = self.aws.seed(marker_key, payload)
        receipt.update(objects=manifest['objects'], totalBytes=manifest['totalBytes'] + len(payload),
                       manifest={'key': marker_key, 'versionId': updated['versionId'], 'sha256': checksum(payload), 'bytes': len(payload)})
        fixture_write(self.receipt_path, receipt)
        with self.assertRaises(ValueError):
            transport.fetch(self.receipt_path, transport.sha(self.receipt_path), self.dataset_id, self.root / 'bad-path', aws=self.aws)
        self.assertFalse((self.root / 'outside').exists())
        self.assertEqual(list((self.root / 'bad-path.partial').iterdir()), [self.root / 'bad-path.partial' / transport.MARKER])

    def test_delete_marker_or_extra_remote_file_prevents_fetch(self):
        self.publish(); self.aws.seed(self.prefix + 'unexpected.json', b'{}')
        with self.assertRaises(ValueError):
            transport.fetch(self.receipt_path, transport.sha(self.receipt_path), self.dataset_id, self.root / 'bad-prefix', aws=self.aws)
        self.assertFalse((self.root / 'bad-prefix').exists())
        with patch.object(self.aws, 'call', return_value={'DeleteMarkers': [{'Key': 'deleted'}]}), self.assertRaises(ValueError):
            transport.version_inventory(self.aws, self.prefix)

    def test_path_traversal_is_rejected(self):
        for value in ('../secret', 'native/../../secret', '/absolute', 'native//bad', 'native/./bad', 'native\\bad'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                transport.relative_key(value)


if __name__ == '__main__':
    unittest.main()
