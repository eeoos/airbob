#!/usr/bin/env python3
"""Fetch the exact S3 versions in a separately pinned v4 publication receipt.

This prepares immutable local inputs. It creates no cloud resources, restores no
database, and does not upgrade a V27 lab contract to V28.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import tempfile

from growth_v4_contract import read, require, sha, validate

spec = importlib.util.spec_from_file_location('growth_v4_publisher',
    Path(__file__).with_name('publish-growth-dataset-v4.py'))
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)


def fetch(receipt_path, receipt_sha, dataset_id, destination, migrations, aws=None):
    require(re.fullmatch(r'[0-9a-f]{64}', receipt_sha) and sha(receipt_path) == receipt_sha,
            'Publication receipt trust anchor mismatch')
    require(re.fullmatch(r'korea-growth-v4-[0-9a-f]{16}', dataset_id), 'Invalid v4 dataset identity')
    require(not destination.exists() and not destination.is_symlink(), 'Destination must be new')
    require(destination.parent.is_dir(), 'Destination parent must exist')
    receipt = read(receipt_path)
    require(receipt['schemaVersion'] == 1 and receipt['kind'] == 'growth-v4-s3-publication' and
            receipt['state'] == 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED' and
            receipt['datasetId'] == dataset_id and receipt['bucket'] == publication.BUCKET and
            receipt['region'] == publication.REGION, 'Publication identity mismatch')
    objects = receipt['objects']
    require(isinstance(objects, dict) and 16 <= len(objects) <= 64, 'Invalid publication inventory')
    prefix = 'datasets/' + dataset_id + '/'
    for name, item in objects.items():
        require(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]+', name) and
                set(item) == {'key', 'versionId', 'sha256', 'bytes'} and item['key'] == prefix + name and
                re.fullmatch(r'[0-9a-f]{64}', item['sha256']) and type(item['bytes']) is int and
                0 < item['bytes'] <= 2_000_000_000, 'Unsafe publication object')
        publication.version({'VersionId': item['versionId']})
    require('SHA256SUMS.json' in objects and publication.MARKER in objects, 'Missing completion inventory')
    marker = objects[publication.MARKER]
    require(marker['key'] == receipt['completionKey'] and
            marker['versionId'] == receipt['completionVersionId'] and
            marker['sha256'] == receipt['consumerManifestSha256'] and
            sum(item['bytes'] for item in objects.values()) == receipt['totalBytes'],
            'Completion marker or total bytes mismatch')
    aws = aws or publication.Aws()
    require(aws.call('sts', 'get-caller-identity')['Account'] == publication.ACCOUNT,
            'Unexpected AWS account')
    names = sorted(set(objects) - {publication.MARKER}) + [publication.MARKER]
    with tempfile.TemporaryDirectory(prefix='growth-v4-fetch-', dir=destination.parent) as temporary:
        stage = Path(temporary) / 'release'
        stage.mkdir(mode=0o700)
        for name in names:
            item = objects[name]
            target = stage / name
            result = aws.call('s3api', 'get-object', '--bucket', publication.BUCKET,
                '--key', item['key'], '--version-id', item['versionId'], str(target))
            target.chmod(0o600)
            require(publication.version(result) == item['versionId'] and
                    target.stat().st_size == item['bytes'] and sha(target) == item['sha256'],
                    'Exact-version download differs: ' + name)
        validate(stage, dataset_id, migrations)
        # Exclusive creation avoids replacing a directory created during download.
        # Link each verified file without overwriting; expose the marker last.
        destination.mkdir(mode=0o700)
        for name in names:
            os.link(stage / name, destination / name)
    return {'state': 'FETCHED_VERSIONS_AND_CONTRACT_VERIFIED', 'datasetId': dataset_id,
            'publicationReceiptSha256': receipt_sha,
            'consumerManifestSha256': receipt['consumerManifestSha256'],
            'objects': len(objects), 'totalBytes': receipt['totalBytes'],
            'directory': str(destination), 'awsDatabaseRestoreExecuted': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publication-receipt', type=Path, required=True)
    parser.add_argument('--expected-publication-sha', required=True)
    parser.add_argument('--expected-id', required=True)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--migration-dir', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(fetch(args.publication_receipt, args.expected_publication_sha,
                           args.expected_id, args.destination, args.migration_dir)))
