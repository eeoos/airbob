#!/usr/bin/env python3
"""Publish only the new AWS envelope; source dump and evidence stay untouched.

The shared publish-dataset-release.sh IAM/bucket gates must precede this helper.
"""
import argparse
import datetime as dt
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import growth_v4_aws_contract as contract

spec = importlib.util.spec_from_file_location('v4_publisher', Path(__file__).with_name('publish-growth-dataset-v4.py'))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


def publish(release, expected, receipt_path, aws=None):
    contract.require(not receipt_path.exists() and {p.name for p in release.iterdir()} == {'manifest.json'}, 'A fresh envelope and receipt are required')
    path = release / 'manifest.json'
    m = contract.validate_manifest(contract.read(path), expected)
    sha = contract.sha(path)
    aws = aws or publisher.Aws()
    identity = aws.call('sts', 'get-caller-identity')
    contract.require(identity['Account'] == publisher.ACCOUNT and re.fullmatch(
        r'arn:aws:sts::942632789808:assumed-role/airbob-dataset-publisher/[A-Za-z0-9+=,.@_-]+', identity['Arn']), 'Dataset publisher role required')
    referenced = list(m['artifacts'].values())
    if m['search']['enabled']:
        referenced += list(m['search']['artifacts'].values()) + [m['search']['seal']]
    for item in referenced:
        remote = aws.call('s3api', 'head-object', '--bucket', contract.BUCKET, '--key', item['key'], '--version-id', item['versionId'])
        contract.require(publisher.version(remote) == item['versionId'] and remote['ContentLength'] == item['bytes'], 'Source object version is missing or differs')
    key = 'datasets/' + expected + '/manifest.json'
    remote = aws.head(key)
    if remote is None:
        remote = aws.call('s3api', 'put-object', '--bucket', contract.BUCKET, '--key', key, '--body', str(path),
            '--if-none-match', '*', '--content-type', 'application/json', '--server-side-encryption', 'AES256')
    version = publisher.version(remote)
    with tempfile.TemporaryDirectory(prefix='v4-envelope-readback-') as temp:
        downloaded = Path(temp) / 'manifest.json'
        response = aws.call('s3api', 'get-object', '--bucket', contract.BUCKET, '--key', key, '--version-id', version, str(downloaded))
        contract.require(publisher.version(response) == version and contract.sha(downloaded) == sha, 'Envelope version readback differs')
    receipt = {'state': 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED', 'kind': 'growth-v4-aws-publication',
        'datasetRelease': expected, 'bucket': contract.BUCKET, 'key': key, 'versionId': version,
        'sha256': sha, 'bytes': path.stat().st_size, 'sourceObjectsModified': False,
        'recordedAt': dt.datetime.now(dt.timezone.utc).isoformat()}
    with receipt_path.open('x') as target:
        json.dump(receipt, target, indent=2)
        target.write('\n')
    return receipt


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--release', type=Path, required=True)
    p.add_argument('--expected-id', required=True)
    p.add_argument('--receipt', type=Path, required=True)
    a = p.parse_args()
    print(json.dumps(publish(a.release, a.expected_id, a.receipt)))
