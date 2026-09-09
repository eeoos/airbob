#!/usr/bin/env python3
"""Download the version-pinned v3 publication into a new local directory."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import tempfile

spec = importlib.util.spec_from_file_location(
    'growth_publication', Path(__file__).with_name('publish-growth-dataset-v3.py'))
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)
validator = publication.validator


def fetch(receipt_path, expected_id, expected_manifest_sha, destination, migration_dir, aws=None):
    aws = aws or publication.Aws()
    validator.require(not destination.exists() and not destination.is_symlink(),
                      'Download destination must not exist')
    receipt = validator.read(receipt_path)
    validator.require(receipt['schemaVersion'] == 1 and
                      receipt['kind'] == 'growth-v3-s3-publication' and
                      receipt['state'] == 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED' and
                      receipt['region'] == publication.REGION and
                      receipt['bucket'] == publication.BUCKET and
                      receipt['datasetId'] == expected_id,
                      'Publication receipt identity mismatch')
    validator.require(re.fullmatch(r'korea-growth-v3-[0-9a-f]{16}', expected_id) and
                      re.fullmatch(r'[0-9a-f]{64}', expected_manifest_sha) and
                      receipt['consumerManifestSha256'] == expected_manifest_sha,
                      'Consumer manifest trust anchor mismatch')
    names = set(validator.FILES.values()) | {publication.MARKER}
    validator.require(set(receipt['objects']) == names, 'Publication inventory mismatch')
    prefix = f'datasets/{expected_id}/'
    for name, item in receipt['objects'].items():
        validator.require(item['key'] == prefix + name and
                          re.fullmatch(r'[0-9a-f]{64}', str(item['sha256'])) and
                          type(item['bytes']) is int and 0 < item['bytes'] < 100_000_000,
                          'Unsafe publication object')
        publication.version_id({'VersionId': item['versionId']})
    marker = receipt['objects'][publication.MARKER]
    validator.require(marker['sha256'] == expected_manifest_sha and
                      marker['versionId'] == receipt['completionVersionId'] and
                      marker['key'] == receipt['completionKey'], 'Completion marker mismatch')
    with tempfile.TemporaryDirectory(prefix='growth-v3-fetch-', dir=destination.parent) as temp:
        stage = Path(temp) / 'release'
        stage.mkdir(mode=0o700)
        # Every read selects its recorded version, even if latest has changed.
        for name, item in receipt['objects'].items():
            target = stage / name
            result = aws.get(publication.BUCKET, item['key'], item['versionId'], target)
            target.chmod(0o600)
            validator.require(publication.version_id(result) == item['versionId'] and
                              target.stat().st_size == item['bytes'] and
                              validator.sha(target) == item['sha256'],
                              'Downloaded object mismatch: ' + name)
        validator.validate(stage, expected_id, '8.4.11', migration_dir)
        # Reserve the destination with mkdir rather than replacing an existing
        # directory created by another process while downloads were in progress.
        destination.mkdir(mode=0o700)
        for name in sorted(names - {publication.MARKER}) + [publication.MARKER]:
            (stage / name).rename(destination / name)
    return {'state': 'FETCHED_VERSIONS_AND_CONTRACT_VERIFIED',
            'datasetId': expected_id, 'consumerManifestSha256': expected_manifest_sha,
            'objects': len(names), 'directory': str(destination)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publication-receipt', type=Path, required=True)
    parser.add_argument('--expected-id', required=True)
    parser.add_argument('--expected-manifest-sha', required=True)
    parser.add_argument('--destination', type=Path, required=True)
    parser.add_argument('--migration-dir', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(fetch(args.publication_receipt, args.expected_id,
                           args.expected_manifest_sha, args.destination, args.migration_dir)))
