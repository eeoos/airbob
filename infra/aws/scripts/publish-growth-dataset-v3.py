#!/usr/bin/env python3
"""Publish the eight v3 consumer files after the shared shell publisher's IAM gate.

Objects are create-only, read back by exact version and checked against staged
bytes. consumer-manifest.json is the last write. No remote deletion is supported.
"""
import argparse
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

spec = importlib.util.spec_from_file_location(
    'growth_validator', Path(__file__).with_name('validate-growth-dataset-v3.py'))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)

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
        result = subprocess.run(
            ['aws', '--region', REGION, '--no-cli-pager', '--output', 'json',
             '--cli-connect-timeout', '5', '--cli-read-timeout', '30', *args],
            capture_output=True, text=True, timeout=45)
        if result.returncode:
            match = re.search(r'\(([^()\s]+)\) when calling', result.stderr)
            raise AwsError(args[1], match.group(1) if match else 'CLI_ERROR')
        return json.loads(result.stdout or '{}')

    def identity(self):
        return self.call('sts', 'get-caller-identity')

    def keys(self, bucket, prefix):
        result = self.call('s3api', 'list-objects-v2', '--bucket', bucket, '--prefix', prefix)
        return {item['Key'] for item in result.get('Contents', [])}

    def head(self, bucket, key):
        try:
            return self.call('s3api', 'head-object', '--bucket', bucket, '--key', key)
        except AwsError as error:
            if error.code in ('404', 'NoSuchKey', 'NotFound'):
                return None
            raise

    def get(self, bucket, key, version, destination):
        return self.call('s3api', 'get-object', '--bucket', bucket, '--key', key,
                         '--version-id', version, str(destination))

    def put(self, bucket, key, source):
        return self.call('s3api', 'put-object', '--bucket', bucket, '--key', key,
                         '--body', str(source), '--if-none-match', '*')


def version_id(response):
    value = response.get('VersionId')
    validator.require(isinstance(value, str) and value not in ('', 'null', 'None')
                      and re.fullmatch(r'[A-Za-z0-9._~+/=-]+', value),
                      'Every published object must have a version ID')
    return value


def publish(release, dataset_id, bucket, migration_dir, receipt_path, aws=None, aws_contract=None):
    aws = aws or Aws()
    validator.require(bucket == BUCKET, 'Dataset bucket is outside the foundation boundary')
    validator.require(not receipt_path.exists() and not receipt_path.is_symlink(),
                      'Publication receipt must be a new file')
    def validate(path):
        if aws_contract is None:
            return validator.validate(path, dataset_id, '8.4.11', migration_dir)
        return aws_contract.validate_directory(path, dataset_id, migration_dir)
    validate(release)
    identity = aws.identity()
    validator.require(identity['Account'] == ACCOUNT and re.fullmatch(
        rf'arn:aws:sts::{ACCOUNT}:assumed-role/airbob-dataset-publisher/[A-Za-z0-9+=,.@_-]+',
        identity['Arn']), 'Publication requires the dataset publisher role')
    # Copy only digest-bound consumer inputs; local logs and execution evidence
    # outside this contract are not implicitly uploaded.
    marker = MARKER if aws_contract is None else 'manifest.json'
    payload_names = validator.FILES.values() if aws_contract is None else aws_contract.PAYLOAD_FILES
    names = sorted(payload_names) + [marker]
    prefix = f'datasets/{dataset_id}/'
    expected_keys = {prefix + name for name in names}
    receipt = {'schemaVersion': 1, 'kind': 'growth-v3-s3-publication' if aws_contract is None else 'growth-aws-s3-publication',
               'state': 'PREPARING', 'datasetId': dataset_id, 'region': REGION,
               'bucket': bucket, 'completionKey': prefix + marker, 'objects': {},
               'awsDatabaseRestoreExecuted': False, 'awsApplicationReadExecuted': False}
    fd = os.open(receipt_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as receipt_file:
        try:
            with tempfile.TemporaryDirectory(prefix='growth-v3-publish-') as temp:
                stage = Path(temp) / 'stage'
                stage.mkdir(mode=0o700)
                for name in names:
                    shutil.copyfile(release / name, stage / name)
                    (stage / name).chmod(0o600)
                validate(stage)
                hashes = {name: validator.sha(stage / name) for name in names}
                validator.require(aws.keys(bucket, prefix) <= expected_keys,
                                  'Unexpected objects exist under the release prefix')
                remote = {name: aws.head(bucket, prefix + name) for name in names}
                completed = remote[marker] is not None
                if completed:
                    validator.require(all(remote.values()), 'Completed release has missing payloads')

                def verify(name, response):
                    version = version_id(response)
                    copy = Path(temp) / 'download'
                    downloaded = aws.get(bucket, prefix + name, version, copy)
                    validator.require(version_id(downloaded) == version,
                                      'S3 download returned another object version')
                    validator.require(validator.sha(copy) == hashes[name],
                                      'Remote bytes differ: ' + name)
                    return {'key': prefix + name, 'versionId': version,
                            'sha256': hashes[name], 'bytes': (stage / name).stat().st_size}

                # Discover all conflicts before the first write, including when
                # resuming an incomplete upload. A 403 is never treated as absent.
                for name in names:
                    if remote[name] is not None:
                        receipt['objects'][name] = verify(name, remote[name])
                receipt['state'] = 'PUBLISHING'
                for name in names:
                    if remote[name] is not None:
                        continue
                    try:
                        response = aws.put(bucket, prefix + name, stage / name)
                    except AwsError as error:
                        if error.code not in ('PreconditionFailed', '412'):
                            raise
                        response = aws.head(bucket, prefix + name)
                        validator.require(response is not None, 'Concurrent publication disappeared')
                    receipt['objects'][name] = verify(name, response)
                # Detect a competing replacement before claiming this current
                # release is complete. The receipt always retains exact versions.
                validator.require(aws.keys(bucket, prefix) == expected_keys,
                                  'Published release inventory mismatch')
                for name in names:
                    validator.require(version_id(aws.head(bucket, prefix + name)) ==
                                      receipt['objects'][name]['versionId'],
                                      'Object version changed during publication: ' + name)
                receipt.update(state='PUBLISHED_BYTES_AND_VERSIONS_VERIFIED',
                               alreadyPublished=completed,
                               completionVersionId=receipt['objects'][marker]['versionId'])
                receipt['consumerManifestSha256' if aws_contract is None else 'manifestSha256'] = hashes[marker]
        except BaseException as error:
            receipt.update(state='FAILED', errorType=type(error).__name__)
            raise
        finally:
            receipt['recordedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
            json.dump(receipt, receipt_file, ensure_ascii=False, indent=2)
            receipt_file.write('\n')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--expected-id', required=True)
    parser.add_argument('--bucket', required=True)
    parser.add_argument('--migration-dir', type=Path, required=True)
    parser.add_argument('--receipt', type=Path, required=True)
    parser.add_argument('--aws-qualification', action='store_true')
    args = parser.parse_args()
    contract = None
    if args.aws_qualification:
        import growth_aws_contract as contract
    print(json.dumps(publish(args.release, args.expected_id, args.bucket,
                             args.migration_dir, args.receipt, aws_contract=contract)))
