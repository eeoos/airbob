#!/usr/bin/env python3
"""Create-only S3 publication, with exact-version downloads and the consumer marker last.

Run through publish-dataset-release.sh, which retains the shared IAM and bucket
policy checks. Publication is not an RDS restore or application qualification.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from growth_v4_contract import require, sha, validate

REGION = 'ap-northeast-2'
ACCOUNT = '942632789808'
BUCKET = 'airbob-performance-lab-dataset-' + ACCOUNT
MARKER = 'consumer-manifest.json'


class AwsError(RuntimeError):
    def __init__(self, operation, code):
        self.code = code
        super().__init__(f'AWS {operation} failed: {code}')


class Aws:
    def call(self, *args):
        result = subprocess.run(['aws', '--region', REGION, '--no-cli-pager', '--output', 'json',
            '--cli-connect-timeout', '5', '--cli-read-timeout', '300', *args],
            capture_output=True, text=True, timeout=1800)
        if result.returncode:
            match = re.search(r'\(([^()\s]+)\) when calling', result.stderr)
            raise AwsError(args[1], match.group(1) if match else 'CLI_ERROR')
        return json.loads(result.stdout or '{}')

    def head(self, key):
        try:
            return self.call('s3api', 'head-object', '--bucket', BUCKET, '--key', key)
        except AwsError as error:
            if error.code in ('404', 'NoSuchKey', 'NotFound'):
                return None
            raise

    def keys(self, prefix):
        return {item['Key'] for item in self.call('s3api', 'list-objects-v2', '--bucket', BUCKET,
                                                 '--prefix', prefix).get('Contents', [])}


def version(response):
    value = response.get('VersionId')
    require(isinstance(value, str) and value not in ('', 'null', 'None') and
            re.fullmatch(r'[A-Za-z0-9._~+/=-]+', value), 'A real object VersionId is required')
    return value


def publish(release, dataset_id, bucket, migration_dir, receipt_path, aws=None):
    require(bucket == BUCKET, 'Unexpected dataset bucket')
    require(not receipt_path.exists(), 'Receipt must be new')
    validate(release, dataset_id, migration_dir)
    aws = aws or Aws()
    identity = aws.call('sts', 'get-caller-identity')
    require(identity['Account'] == ACCOUNT and re.fullmatch(
        rf'arn:aws:sts::{ACCOUNT}:assumed-role/airbob-dataset-publisher/[A-Za-z0-9+=,.@_-]+', identity['Arn']),
        'Assume the dataset publisher role')
    prefix = 'datasets/' + dataset_id + '/'
    names = sorted(p.name for p in release.iterdir() if p.name != MARKER) + [MARKER]
    expected_keys = {prefix + name for name in names}
    receipt = {'schemaVersion': 1, 'kind': 'growth-v4-s3-publication', 'datasetId': dataset_id,
               'state': 'PREPARING', 'bucket': bucket, 'region': REGION, 'objects': {},
               'awsDatabaseRestoreExecuted': False, 'awsApplicationReadExecuted': False}
    fd = os.open(receipt_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(fd)

    def save():
        receipt_path.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')

    try:
        with tempfile.TemporaryDirectory(prefix='growth-v4-publish-') as private:
            temp = Path(private)
            stage = temp / 'stage'
            shutil.copytree(release, stage)
            validate(stage, dataset_id, migration_dir)
            hashes = {name: sha(stage / name) for name in names}
            require(aws.keys(prefix) <= expected_keys, 'Unexpected objects under this prefix')
            remote = {name: aws.head(prefix + name) for name in names}
            completed = remote[MARKER] is not None
            require(not completed or all(remote.values()), 'Completed release has missing files')

            def verify(name, response):
                object_version = version(response)
                downloaded = aws.call('s3api', 'get-object', '--bucket', bucket, '--key', prefix + name,
                                      '--version-id', object_version, str(temp / 'download'))
                require(version(downloaded) == object_version and sha(temp / 'download') == hashes[name],
                        'Exact-version download differs: ' + name)
                return {'key': prefix + name, 'versionId': object_version,
                        'sha256': hashes[name], 'bytes': (stage / name).stat().st_size}

            for name in names:
                if remote[name] is not None:
                    receipt['objects'][name] = verify(name, remote[name])
            receipt['state'] = 'PUBLISHING'
            save()
            for name in names:
                if remote[name] is not None:
                    continue
                print(json.dumps({'publishing': name, 'bytes': (stage / name).stat().st_size}), flush=True)
                try:
                    response = aws.call('s3api', 'put-object', '--bucket', bucket, '--key', prefix + name,
                        '--body', str(stage / name), '--if-none-match', '*', '--content-type',
                        'application/gzip' if name.endswith('.gz') else 'application/json')
                except AwsError as error:
                    if error.code not in ('PreconditionFailed', '412'):
                        raise
                    response = aws.head(prefix + name)
                    require(response is not None, 'Concurrent upload disappeared')
                receipt['objects'][name] = verify(name, response)
                save()
            require(aws.keys(prefix) == expected_keys, 'Final remote inventory differs')
            for name in names:
                require(version(aws.head(prefix + name)) == receipt['objects'][name]['versionId'],
                        'Object changed during publication: ' + name)
            receipt.update(state='PUBLISHED_BYTES_AND_VERSIONS_VERIFIED', alreadyPublished=completed,
                completionKey=prefix + MARKER, completionVersionId=receipt['objects'][MARKER]['versionId'],
                consumerManifestSha256=hashes[MARKER], totalBytes=sum(item['bytes'] for item in receipt['objects'].values()))
    except BaseException as error:
        receipt.update(state='FAILED', errorType=type(error).__name__)
        raise
    finally:
        receipt['recordedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        save()
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--expected-id', required=True)
    parser.add_argument('--bucket', required=True)
    parser.add_argument('--migration-dir', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    result = publish(args.release, args.expected_id, args.bucket, args.migration_dir, args.receipt)
    print(json.dumps({'state': result['state'], 'datasetId': result['datasetId'], 'receipt': str(args.receipt)}))
