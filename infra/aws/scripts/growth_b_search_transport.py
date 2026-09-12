#!/usr/bin/env python3
"""Immutable transport of an already sealed filesystem-native B search snapshot.

Publish through publish-dataset-release.sh with kind growth-b-search so its
existing MFA role/trust/policy checks run first. No snapshot, credential profile,
IAM policy, or source seal is created or changed here. The sibling datasets
namespace retains the SQL publisher's exact finite release inventory.
"""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

import growth_b_search as search
from growth_b_contract import digest, read, require, sha


def _load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


publisher = _load('_growth_b_search_sql_publisher', 'publish-growth-dataset-b.py')
fetcher = _load('_growth_b_search_sql_fetcher', 'fetch-growth-dataset-b.py')
BUCKET, REGION, ACCOUNT = publisher.BUCKET, publisher.REGION, publisher.ACCOUNT
KIND = 'global-growth-b-search-transport'
MARKER = 'transport-manifest.json'
DESCRIPTOR = 'companion-descriptor.json'
SQL_RECEIPT = 'sql-publication-receipt.json'
JSON_LIMIT = 64 * 1024**2


def binding(path):
    return {'sha256': sha(path), 'bytes': Path(path).stat().st_size}


def bounded_read(path):
    require(0 < Path(path).stat().st_size <= JSON_LIMIT, 'Transport JSON exceeds its bound')
    return read(path)


def prefix_for(dataset_id, snapshot_release):
    require(isinstance(dataset_id, str) and re.fullmatch(r'global-growth-b-[0-9a-f]{16}', dataset_id), 'Invalid B dataset ID')
    require(isinstance(snapshot_release, str) and re.fullmatch(re.escape(dataset_id) + r'-search-[a-z0-9][a-z0-9._-]{0,60}', snapshot_release), 'Invalid B snapshot release')
    return f'datasets/{dataset_id}-search/{snapshot_release}/'


def relative_key(value):
    require(isinstance(value, str) and 0 < len(value) <= 900
            and re.fullmatch(r'[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*', value)
            and all(part not in {'.', '..'} for part in value.split('/')), 'Unsafe native object path')
    return value


def object_reference(value, expected_key):
    require(isinstance(value, dict) and set(value) == {'key', 'versionId', 'sha256', 'bytes'}
            and value['key'] == expected_key and digest(value['sha256'])
            and type(value['bytes']) is int and 0 < value['bytes'] <= 20 * 1024**3, 'Invalid immutable object reference')
    publisher.version({'VersionId': value['versionId']})
    return value


def source_inputs(companion, descriptor_path, descriptor_sha, native_root, dataset_id):
    companion, descriptor_path, native_root = map(Path, (companion, descriptor_path, native_root))
    require(digest(descriptor_sha) and sha(descriptor_path) == descriptor_sha, 'Reviewed companion descriptor SHA differs')
    descriptor = bounded_read(descriptor_path)
    manifest, reference = search.validate_companion(companion, descriptor)
    require(descriptor == search.descriptor_for(companion, manifest), 'Transport requires the unchanged local descriptor')
    require(descriptor['datasetId'] == dataset_id and sha(descriptor_path) == descriptor_sha, 'Companion identity or descriptor changed')
    prefix_for(dataset_id, descriptor['snapshotRelease'])
    native = read(companion / 'native-inventory.json')
    require(native['type'] == reference['repository']['type'] == 'fs', 'Only sealed filesystem-native snapshots can be transported')
    require(0 < len(native['entries']) <= 100000, 'Native inventory exceeds its bound')
    for item in native['entries']:
        require(set(item) == {'key', 'sha256', 'bytes'} and digest(item['sha256'])
                and type(item['bytes']) is int and 0 < item['bytes'] <= 20 * 1024**3, 'Invalid sealed native file')
        relative_key(item['key'])
    repository = search.Repository({'name': 'transport-source', 'type': 'fs',
        'settings': {'location': str(native_root.resolve())}, 'inventoryRoot': str(native_root.resolve())})
    require(not native_root.is_symlink() and repository.inventory() == native, 'Preserved native repository differs from the sealed file inventory')
    paths = {DESCRIPTOR: descriptor_path} | {'companion/' + name: companion / name for name in sorted(search.NAMES)}
    paths.update({'native/' + item['key']: native_root / item['key'] for item in native['entries']})
    expected = {DESCRIPTOR: {'sha256': descriptor_sha, 'bytes': descriptor_path.stat().st_size}}
    expected.update({'companion/' + name: value for name, value in descriptor['objects'].items()})
    expected.update({'native/' + item['key']: {key: item[key] for key in ('sha256', 'bytes')} for item in native['entries']})
    require(len(paths) == len(expected) == 1 + len(search.NAMES) + len(native['entries']), 'Duplicate native inventory path')
    return {'descriptor': descriptor, 'manifest': manifest, 'reference': reference, 'native': native,
            'paths': paths, 'expected': expected}


def sql_binding(receipt_path, receipt_sha, source):
    require(digest(receipt_sha) and sha(receipt_path) == receipt_sha, 'Reviewed SQL publication receipt SHA differs')
    receipt = bounded_read(receipt_path)
    allowed = {'schemaVersion', 'kind', 'state', 'datasetId', 'bucket', 'region', 'objects',
        'completionKey', 'completionVersionId', 'consumerManifestSha256', 'totalBytes', 'recordedAt',
        'maximumCompressedScratchBytes', 'plaintextTemporaryDumpBytes', 'awsDatabaseRestoreExecuted',
        'awsApplicationReadExecuted', 'alreadyPublished', 'multipartUploads'}
    require(set(receipt) <= allowed, 'SQL publication receipt contains unrecognized public fields')
    dataset_id = source['descriptor']['datasetId']
    require(receipt.get('schemaVersion') == 1 and receipt.get('kind') == 'global-growth-b-s3-publication'
            and receipt.get('state') == 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED'
            and receipt.get('datasetId') == dataset_id and receipt.get('bucket') == BUCKET and receipt.get('region') == REGION,
            'Completed SQL publication belongs to another dataset')
    objects = receipt.get('objects')
    require(isinstance(objects, dict) and 20 <= len(objects) <= 100, 'SQL publication inventory is invalid')
    for name, item in objects.items():
        require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', name), 'Unsafe SQL publication name')
        object_reference(item, f'datasets/{dataset_id}/{name}')
    for name, item in receipt.get('multipartUploads', {}).items():
        require(name in objects and isinstance(item, dict)
                and set(item) <= {'state', 'key', 'uploadId', 'versionId', 'errorType'}
                and item.get('key') == objects[name]['key'], 'Unrecognized SQL multipart receipt fields')
    require({'consumer-manifest.json', 'SHA256SUMS.json', 'airbob-growth.sql.gz'} <= objects.keys(), 'SQL completion coordinates are missing')
    consumer, checks, dump = (objects[name] for name in ('consumer-manifest.json', 'SHA256SUMS.json', 'airbob-growth.sql.gz'))
    require(receipt.get('completionKey') == consumer['key'] and receipt.get('completionVersionId') == consumer['versionId']
            and receipt.get('consumerManifestSha256') == consumer['sha256']
            and receipt.get('totalBytes') == sum(item['bytes'] for item in objects.values())
            and consumer['sha256'] == source['descriptor']['consumerManifestSha256']
            and checks['sha256'] == source['manifest']['checksSha256']
            and dump['sha256'] == read(source['paths']['companion/source-proof.json'])['baseDumpSha256'],
            'SQL bytes/versions are not bound to this sealed companion')
    require(sha(receipt_path) == receipt_sha, 'SQL publication receipt changed during validation')
    return {'datasetId': dataset_id, 'publicationReceiptSha256': receipt_sha,
            'consumerManifest': consumer, 'checksums': checks, 'dump': dump}


class SseAws:
    """Keep the existing bounded uploader; make SSE-S3 explicit on object creation."""
    def __init__(self, backend):
        self.backend = backend

    def call(self, *args):
        args = list(args)
        if tuple(args[:2]) in {('s3api', 'put-object'), ('s3api', 'create-multipart-upload')}:
            require('--server-side-encryption' not in args, 'Unexpected encryption override')
            args += ['--server-side-encryption', 'AES256']
            if '/native/' in args[args.index('--key') + 1] and '--content-type' in args:
                args[args.index('--content-type') + 1] = 'application/octet-stream'
        return self.backend.call(*args)

    def head(self, key):
        return self.backend.head(key)


class CallableAws:
    def __init__(self, callback):
        self.call = callback


def checked_head(aws, item):
    got = aws.call('s3api', 'head-object', '--bucket', BUCKET, '--key', item['key'], '--version-id', item['versionId'])
    require(publisher.version(got) == item['versionId'] and got.get('ContentLength') == item['bytes']
            and got.get('ServerSideEncryption') == 'AES256' and not got.get('SSEKMSKeyId'), 'Pinned object version, size or SSE-S3 encryption differs')
    return got


def version_inventory(aws, prefix):
    entries, markers = [], []
    while True:
        page = aws.call('s3api', 'list-object-versions', '--no-paginate', '--max-keys', '1000',
                        '--bucket', BUCKET, '--prefix', prefix, *markers)
        require(not page.get('DeleteMarkers'), 'Immutable transport prefix contains a delete marker')
        for item in page.get('Versions', []):
            require(item['Key'].startswith(prefix) and item.get('IsLatest') is True, 'Unexpected or superseded transport version')
            entries.append({'key': item['Key'], 'versionId': publisher.version(item), 'bytes': item['Size']})
        require(len(entries) <= 100012, 'Transport version inventory exceeds its bound')
        if not page.get('IsTruncated'):
            break
        following = ['--key-marker', page['NextKeyMarker'], '--version-id-marker', page['NextVersionIdMarker']]
        require(following != markers, 'Transport version pagination made no progress')
        markers = following
    require(len({item['key'] for item in entries}) == len(entries), 'Duplicate transport object versions')
    return sorted(entries, key=lambda item: item['key'])


def expected_versions(objects):
    return sorted(({key: item[key] for key in ('key', 'versionId', 'bytes')} for item in objects.values()), key=lambda item: item['key'])


def _save(path, value):
    fd, temporary = tempfile.mkstemp(prefix=Path(path).name + '.', dir=Path(path).parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, sort_keys=True, indent=2); stream.write('\n')
            stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def manifest_bytes(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()


def make_manifest(source, sql, objects):
    descriptor, reference = source['descriptor'], source['reference']
    prefix = prefix_for(descriptor['datasetId'], descriptor['snapshotRelease'])
    return {'schemaVersion': 1, 'kind': KIND, 'datasetId': descriptor['datasetId'],
        'snapshotRelease': descriptor['snapshotRelease'], 'snapshotUuid': reference['snapshotUuid'],
        'bucket': BUCKET, 'region': REGION, 'sql': sql,
        'source': {'descriptorSha256': source['expected'][DESCRIPTOR]['sha256'],
            'companionManifestSha256': descriptor['objects']['manifest.json']['sha256'],
            'consumerManifestSha256': descriptor['consumerManifestSha256'],
            'checksSha256': source['manifest']['checksSha256'], 'appJarSha256': descriptor['appJarSha256'],
            'nativeInventorySha256': reference['nativeInventorySha256'],
            'nativeInventoryFileSha256': descriptor['objects']['native-inventory.json']['sha256']},
        'repository': {'type': 's3', 'bucket': BUCKET, 'basePath': prefix + 'native'}, 'objects': objects,
        'totalBytes': sum(item['bytes'] for item in objects.values())}


@publisher.termination_signals()
def publish(companion, descriptor_path, descriptor_sha, native_root, sql_receipt, sql_receipt_sha,
            dataset_id, bucket, receipt_path, *, aws=None):
    require(bucket == BUCKET, 'Unexpected dataset bucket')
    source = source_inputs(companion, descriptor_path, descriptor_sha, native_root, dataset_id)
    sql = sql_binding(sql_receipt, sql_receipt_sha, source)
    source['paths'][SQL_RECEIPT] = Path(sql_receipt)
    source['expected'][SQL_RECEIPT] = {'sha256': sql_receipt_sha, 'bytes': Path(sql_receipt).stat().st_size}
    prefix = prefix_for(dataset_id, source['descriptor']['snapshotRelease'])
    aws = SseAws(aws or publisher.Aws())
    caller = aws.call('sts', 'get-caller-identity')
    require(caller.get('Account') == ACCOUNT and re.fullmatch(
        rf'arn:aws:sts::{ACCOUNT}:assumed-role/airbob-dataset-publisher/[A-Za-z0-9+=,.@_-]+', caller.get('Arn', '')),
        'Assume the existing dataset publisher role')
    receipt_path = Path(receipt_path)
    fd = os.open(receipt_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    os.close(fd)
    receipt = {'schemaVersion': 1, 'kind': KIND + '-publication', 'state': 'PREPARING',
        'datasetId': dataset_id, 'snapshotRelease': source['descriptor']['snapshotRelease'],
        'bucket': BUCKET, 'region': REGION, 'prefix': prefix, 'objects': {},
        'maximumScratchBytes': publisher.PART_BYTES, 'sourceSealsChanged': False, 'elasticsearchRestoreExecuted': False}
    def recovery(name, event):
        receipt.setdefault('multipartUploads', {})[name] = event; _save(receipt_path, receipt)
    try:
        with tempfile.TemporaryDirectory(prefix='growth-b-search-publish-') as work:
            scratch = Path(work) / 'object-part'
            expected_keys = {prefix + name for name in source['paths']} | {prefix + MARKER}
            existing = version_inventory(aws, prefix)
            require({item['key'] for item in existing} <= expected_keys, 'Unexpected files under the search transport prefix')
            remote = {name: aws.head(prefix + name) for name in source['paths'] | {MARKER: None}}
            completed = remote[MARKER] is not None
            require(not completed or all(remote.values()), 'Completed search transport has missing objects')
            for item in (sql['consumerManifest'], sql['checksums'], sql['dump']):
                checked_head(aws, item)
            receipt['state'] = 'PUBLISHING'; _save(receipt_path, receipt)
            for name, path in sorted(source['paths'].items()):
                expected = source['expected'][name]
                require(binding(path) == expected, 'Sealed transport input changed: ' + name)
                response = remote[name]
                if response is None:
                    try:
                        response = publisher.upload(aws, path, prefix + name, scratch,
                            recovery=lambda event, name=name: recovery(name, event))
                    except publisher.AwsError as error:
                        if error.code not in ('PreconditionFailed', '412'):
                            raise
                        response = aws.head(prefix + name)
                        require(response is not None, 'Concurrent transport object disappeared')
                item = publisher.verify_remote(aws, prefix + name, response, expected['sha256'], expected['bytes'], scratch)
                checked_head(aws, item)
                receipt['objects'][name] = item; _save(receipt_path, receipt)
            for name, path in source['paths'].items():
                require(binding(path) == source['expected'][name], 'Transport input changed before completion: ' + name)
            before_marker = dict(receipt['objects'])
            if completed:
                before_marker[MARKER] = {'key': prefix + MARKER, 'versionId': publisher.version(remote[MARKER]),
                                       'bytes': remote[MARKER]['ContentLength']}
            require(version_inventory(aws, prefix) == expected_versions(before_marker), 'Transport versions drifted before completion')
            manifest = make_manifest(source, sql, receipt['objects'])
            manifest_path = Path(work) / MARKER; _save(manifest_path, manifest)
            response = remote[MARKER]
            if response is None:
                try:
                    response = publisher.upload(aws, manifest_path, prefix + MARKER, scratch)
                except publisher.AwsError as error:
                    if error.code not in ('PreconditionFailed', '412'):
                        raise
                    response = aws.head(prefix + MARKER)
                    require(response is not None, 'Concurrent transport completion marker disappeared')
            marker = publisher.verify_remote(aws, prefix + MARKER, response, sha(manifest_path), manifest_path.stat().st_size, scratch)
            checked_head(aws, marker)
            require(version_inventory(aws, prefix) == expected_versions(receipt['objects'] | {MARKER: marker}), 'Completed transport version inventory differs')
            receipt.update(state='PUBLISHED_BYTES_AND_VERSIONS_VERIFIED', manifest=marker, alreadyPublished=completed,
                           totalBytes=manifest['totalBytes'] + marker['bytes'])
    except BaseException as error:
        receipt.update(state='FAILED', errorType=type(error).__name__)
        raise
    finally:
        receipt['recordedAt'] = dt.datetime.now(dt.timezone.utc).isoformat(); _save(receipt_path, receipt)
    return receipt


def validate_published_transport(path, expected_sha, companion_directory, descriptor):
    """Authenticate a transport marker against unchanged local companion seals."""
    require(digest(expected_sha) and sha(path) == expected_sha, 'Reviewed search transport manifest SHA differs')
    value = bounded_read(path)
    require(hashlib.sha256(manifest_bytes(value)).hexdigest() == expected_sha, 'Transport completion marker must retain its canonical published bytes')
    manifest, reference = search.validate_companion(companion_directory, descriptor)
    require(descriptor == search.descriptor_for(companion_directory, manifest), 'Local companion descriptor differs')
    required = {'schemaVersion', 'kind', 'datasetId', 'snapshotRelease', 'snapshotUuid', 'bucket', 'region',
                'sql', 'source', 'repository', 'objects', 'totalBytes'}
    require(set(value) == required and value['schemaVersion'] == 1 and value['kind'] == KIND
            and value['bucket'] == BUCKET and value['region'] == REGION
            and value['datasetId'] == descriptor['datasetId'] and value['snapshotRelease'] == descriptor['snapshotRelease']
            and value['snapshotUuid'] == reference['snapshotUuid'], 'Search transport identity differs')
    prefix = prefix_for(value['datasetId'], value['snapshotRelease'])
    native = read(Path(companion_directory) / 'native-inventory.json')
    require(native['type'] == reference['repository']['type'] == 'fs', 'Transport source must retain its fs seal')
    objects = value['objects']
    require(isinstance(objects, dict) and len(native['entries']) <= 100000
            and set(objects) == {DESCRIPTOR, SQL_RECEIPT} | {'companion/' + name for name in search.NAMES}
                | {'native/' + relative_key(item['key']) for item in native['entries']}, 'Transport payload inventory differs')
    for name, item in objects.items():
        object_reference(item, prefix + name)
    for name, item in descriptor['objects'].items():
        require({key: objects['companion/' + name][key] for key in ('sha256', 'bytes')} == item, 'Transport companion bytes differ')
    for item in native['entries']:
        require({key: objects['native/' + item['key']][key] for key in ('sha256', 'bytes')}
                == {key: item[key] for key in ('sha256', 'bytes')}, 'Transport native bytes differ')
    expected_source = {'descriptorSha256': objects[DESCRIPTOR]['sha256'],
        'companionManifestSha256': descriptor['objects']['manifest.json']['sha256'],
        'consumerManifestSha256': descriptor['consumerManifestSha256'], 'checksSha256': manifest['checksSha256'],
        'appJarSha256': descriptor['appJarSha256'], 'nativeInventorySha256': reference['nativeInventorySha256'],
        'nativeInventoryFileSha256': descriptor['objects']['native-inventory.json']['sha256']}
    require(value['source'] == expected_source and value['totalBytes'] == sum(item['bytes'] for item in objects.values())
            and value['repository'] == {'type': 's3', 'bucket': BUCKET, 'basePath': prefix + 'native'}, 'Transport source/repository binding differs')
    sql = value['sql']
    require(set(sql) == {'datasetId', 'publicationReceiptSha256', 'consumerManifest', 'checksums', 'dump'}
            and sql['datasetId'] == descriptor['datasetId'] and sql['publicationReceiptSha256'] == objects[SQL_RECEIPT]['sha256'], 'SQL receipt anchor differs')
    for key, filename, expected in (
        ('consumerManifest', 'consumer-manifest.json', descriptor['consumerManifestSha256']),
        ('checksums', 'SHA256SUMS.json', manifest['checksSha256']),
        ('dump', 'airbob-growth.sql.gz', read(Path(companion_directory) / 'source-proof.json')['baseDumpSha256'])):
        object_reference(sql[key], f'datasets/{descriptor["datasetId"]}/{filename}')
        require(sql[key]['sha256'] == expected, 'SQL release linkage differs')
    require(sha(path) == expected_sha, 'Transport manifest changed during validation')
    return value


def validate_native_versions(manifest, inventory):
    """Accept Repository.inventory() only at exactly the published native versions."""
    expected = manifest['repository']
    require(inventory.get('type') == 's3' and inventory.get('bucket') == expected['bucket']
            and inventory.get('basePath') == expected['basePath'], 'Live native S3 repository coordinates differ')
    observed = []
    for item in inventory.get('entries', []):
        require(item.get('kind') == 'version' and item.get('isLatest') is True, 'Native repository contains old versions or delete markers')
        observed.append({key: item[key] for key in ('key', 'versionId', 'bytes')})
    require(sorted(observed, key=lambda item: item['key']) == expected_versions({
        name: item for name, item in manifest['objects'].items() if name.startswith('native/')}), 'Live native version inventory differs')
    return True


def verify_s3_repository(manifest, aws_call):
    """Read exact native versions before readonly ES registration; never create a snapshot."""
    aws = CallableAws(aws_call)
    native = {name: item for name, item in manifest['objects'].items() if name.startswith('native/')}
    prefix = manifest['repository']['basePath'] + '/'
    expected = expected_versions(native)
    release_prefix = prefix_for(manifest['datasetId'], manifest['snapshotRelease'])
    raw_marker = manifest_bytes(manifest)
    marker_key = release_prefix + MARKER
    current = aws.call('s3api', 'head-object', '--bucket', BUCKET, '--key', marker_key)
    marker = {'key': marker_key, 'versionId': publisher.version(current),
              'sha256': hashlib.sha256(raw_marker).hexdigest(), 'bytes': len(raw_marker)}
    complete_versions = expected_versions(manifest['objects'] | {MARKER: marker})
    require(version_inventory(aws, release_prefix) == complete_versions, 'Search transport is incomplete or its published versions differ')
    require(version_inventory(aws, prefix) == expected, 'Native S3 inventory differs before byte verification')
    with tempfile.TemporaryDirectory(prefix='growth-b-s3-native-verify-') as work:
        scratch = Path(work) / 'object-part'
        checked_head(aws, marker)
        publisher.verify_remote(aws, marker['key'], {'VersionId': marker['versionId']}, marker['sha256'], marker['bytes'], scratch)
        for item in native.values():
            checked_head(aws, item)
            publisher.verify_remote(aws, item['key'], {'VersionId': item['versionId']}, item['sha256'], item['bytes'], scratch)
        for item in (manifest['sql']['consumerManifest'], manifest['sql']['checksums'], manifest['sql']['dump']):
            checked_head(aws, item)
    require(version_inventory(aws, release_prefix) == complete_versions
            and version_inventory(aws, prefix) == expected, 'Published S3 versions changed during verification')
    return {'state': 'IMMUTABLE_NATIVE_S3_BYTES_VERIFIED', 'repository': manifest['repository'],
            'nativeFiles': len(native), 'nativeBytes': sum(item['bytes'] for item in native.values()),
            'nativeInventorySha256': manifest['source']['nativeInventorySha256'],
            'nativeInventoryFileSha256': manifest['source']['nativeInventoryFileSha256'],
            'completionMarker': marker, 'exactVersionBytesVerified': True, 'sourceSealsChanged': False}


def fetch(receipt_path, receipt_sha, dataset_id, output, *, aws=None, reserved_free_bytes=1024**3):
    require(digest(receipt_sha) and sha(receipt_path) == receipt_sha, 'Reviewed transport publication receipt SHA differs')
    receipt = bounded_read(receipt_path)
    require(receipt.get('schemaVersion') == 1 and receipt.get('kind') == KIND + '-publication'
            and receipt.get('state') == 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED'
            and receipt.get('datasetId') == dataset_id and receipt.get('bucket') == BUCKET and receipt.get('region') == REGION,
            'Completed search publication receipt is required')
    prefix = prefix_for(dataset_id, receipt['snapshotRelease'])
    marker = object_reference(receipt['manifest'], prefix + MARKER)
    require(marker['bytes'] <= JSON_LIMIT and type(reserved_free_bytes) is int and reserved_free_bytes >= 0, 'Invalid fetch bound')
    output = Path(output); stage = output.with_name(output.name + '.partial')
    require(not output.exists() and not output.is_symlink() and not stage.exists() and not stage.is_symlink(), 'Inspect existing fetch destination/partial first')
    require(output.parent.is_dir() and not output.parent.is_symlink(), 'Fetch parent must already be a real directory')
    aws = aws or publisher.Aws()
    require(aws.call('sts', 'get-caller-identity').get('Account') == ACCOUNT, 'Unexpected AWS account')
    require(shutil.disk_usage(output.parent).free >= marker['bytes'] + reserved_free_bytes, 'Insufficient manifest download space')
    stage.mkdir(mode=0o700)
    def download(item, destination):
        checked_head(aws, item)
        destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        part = destination.with_name(destination.name + '.part')
        got = aws.call('s3api', 'get-object', '--bucket', BUCKET, '--key', item['key'], '--version-id', item['versionId'], str(part))
        part.chmod(0o600)
        require(publisher.version(got) == item['versionId'] and got.get('ContentLength') == item['bytes']
                and binding(part) == {key: item[key] for key in ('sha256', 'bytes')}, 'Fetched exact-version bytes differ')
        os.replace(part, destination)
    download(marker, stage / MARKER)
    manifest = bounded_read(stage / MARKER)
    require(manifest.get('kind') == KIND and manifest.get('datasetId') == dataset_id
            and manifest.get('snapshotRelease') == receipt['snapshotRelease']
            and manifest.get('objects') == receipt.get('objects') and isinstance(manifest.get('objects'), dict)
            and 11 <= len(manifest['objects']) <= 100010, 'Pinned transport payload differs from publication receipt')
    for name, item in manifest['objects'].items():
        relative_key(name); object_reference(item, prefix + name)
        require(name in {DESCRIPTOR, SQL_RECEIPT} or name.startswith('companion/') or name.startswith('native/'), 'Unexpected transport file scope')
    versions = expected_versions(manifest['objects'] | {MARKER: marker})
    require(version_inventory(aws, prefix) == versions, 'Completed transport prefix or versions differ before fetch')
    total = sum(item['bytes'] for item in manifest['objects'].values())
    require(total == manifest.get('totalBytes') and receipt.get('totalBytes') == total + marker['bytes']
            and shutil.disk_usage(stage).free >= total + reserved_free_bytes, 'Transport size or available disk budget differs')
    for name, item in sorted(manifest['objects'].items()):
        download(item, stage / name)
    descriptor = bounded_read(stage / DESCRIPTOR)
    require(sha(stage / DESCRIPTOR) == manifest['source']['descriptorSha256'], 'Fetched descriptor differs')
    validate_published_transport(stage / MARKER, marker['sha256'], stage / 'companion', descriptor)
    source = source_inputs(stage / 'companion', stage / DESCRIPTOR, manifest['source']['descriptorSha256'], stage / 'native', dataset_id)
    require(sql_binding(stage / SQL_RECEIPT, manifest['sql']['publicationReceiptSha256'], source) == manifest['sql'], 'Fetched SQL receipt differs')
    require(version_inventory(aws, prefix) == versions, 'Transport versions changed during fetch')
    fetcher.publish_directory_exclusively(stage, output)
    return {'state': 'FETCHED_UNCHANGED_FS_NATIVE_BYTES_VERIFIED', 'datasetId': dataset_id,
            'output': str(output.resolve()), 'manifestSha256': marker['sha256'],
            'nativeFiles': len(source['native']['entries']), 'sourceSealsChanged': False,
            'elasticsearchRestoreExecuted': False, 'totalBytes': total + marker['bytes']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    publication = commands.add_parser('publish')
    source = commands.add_parser('validate-source')
    for child in (publication, source):
        for name in ('companion', 'descriptor', 'native-repository'):
            child.add_argument('--' + name, type=Path, required=True)
        child.add_argument('--descriptor-sha256', required=True)
        child.add_argument('--expected-id', required=True)
    for name in ('sql-publication-receipt', 'receipt'):
        publication.add_argument('--' + name, type=Path, required=True)
    publication.add_argument('--sql-publication-receipt-sha256', required=True)
    publication.add_argument('--bucket', required=True)
    download = commands.add_parser('fetch')
    for name in ('receipt', 'output'):
        download.add_argument('--' + name, type=Path, required=True)
    download.add_argument('--receipt-sha256', required=True)
    download.add_argument('--expected-id', required=True)
    download.add_argument('--reserved-free-bytes', type=int, default=1024**3)
    args = parser.parse_args()
    if args.command == 'fetch':
        result = fetch(args.receipt, args.receipt_sha256, args.expected_id, args.output, reserved_free_bytes=args.reserved_free_bytes)
    elif args.command == 'validate-source':
        value = source_inputs(args.companion, args.descriptor, args.descriptor_sha256, args.native_repository, args.expected_id)
        result = {'state': 'SEALED_FS_NATIVE_SOURCE_VERIFIED', 'datasetId': args.expected_id,
                  'nativeFiles': len(value['native']['entries']), 'nativeBytes': sum(item['bytes'] for item in value['native']['entries']),
                  'awsCallsExecuted': False}
    else:
        result = publish(args.companion, args.descriptor, args.descriptor_sha256, args.native_repository,
            args.sql_publication_receipt, args.sql_publication_receipt_sha256, args.expected_id, args.bucket, args.receipt)
        result = {'state': result['state'], 'datasetId': result['datasetId'], 'receipt': str(args.receipt), 'manifest': result['manifest']}
    print(json.dumps(result))


if __name__ == '__main__':
    main()
