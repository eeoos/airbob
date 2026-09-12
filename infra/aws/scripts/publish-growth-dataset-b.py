#!/usr/bin/env python3
"""Create-only B publication with bounded compressed scratch and exact-version readback.

Invoke through publish-dataset-release.sh so the existing IAM policy/trust boundary
is checked first. No entire release copy or plaintext SQL file is created.
"""
import argparse
import contextlib
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import threading

from growth_b_contract import require, sha, validate

REGION = 'ap-northeast-2'
ACCOUNT = '942632789808'
BUCKET = 'airbob-performance-lab-dataset-' + ACCOUNT
MARKER = 'consumer-manifest.json'
PART_BYTES = 64 * 1024 * 1024


class PublicationInterrupted(RuntimeError):
    pass


@contextlib.contextmanager
def termination_signals():
    """Let bounded cleanup run on graceful CLI termination; SIGKILL is unrecoverable."""
    require(threading.current_thread() is threading.main_thread(), 'Publisher must run on the main thread')
    watched = (signal.SIGTERM, signal.SIGHUP)
    previous = {number: signal.getsignal(number) for number in watched}
    def interrupted(number, frame):
        for item in watched:
            signal.signal(item, signal.SIG_IGN)
        raise PublicationInterrupted('Publication interrupted by signal ' + str(number))
    try:
        for number in watched:
            signal.signal(number, interrupted)
        yield
    finally:
        for number, handler in previous.items():
            signal.signal(number, handler)


class AwsError(RuntimeError):
    def __init__(self, operation, code):
        self.code = code
        super().__init__(f'AWS {operation} failed: {code}')


class Aws:
    def call(self, *args):
        cleanup = args[:2] == ('s3api', 'abort-multipart-upload')
        result = subprocess.run(['aws', '--region', REGION, '--no-cli-pager', '--output', 'json',
            '--cli-connect-timeout', '5', '--cli-read-timeout', '15' if cleanup else '300', *args],
            capture_output=True, text=True, timeout=45 if cleanup else 1800)
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
        # AWS CLI automatically follows all list-objects-v2 pages.
        return {item['Key'] for item in self.call('s3api', 'list-objects-v2', '--bucket', BUCKET,
            '--prefix', prefix).get('Contents', [])}


def version(response):
    value = response.get('VersionId')
    require(isinstance(value, str) and value not in ('', 'null', 'None') and
            re.fullmatch(r'[A-Za-z0-9._~+/=-]+', value), 'A real object VersionId is required')
    return value


def upload(aws, path, key, scratch, part_bytes=PART_BYTES, recovery=None):
    """Multipart completion is conditional, just like the small-object PutObject."""
    mime = 'application/gzip' if path.name.endswith('.gz') else 'application/json'
    common = ['--bucket', BUCKET, '--key', key]
    if path.stat().st_size <= part_bytes:
        return aws.call('s3api', 'put-object', *common, '--body', str(path),
            '--if-none-match', '*', '--content-type', mime)
    if recovery:
        recovery({'state': 'CREATING', 'key': key, 'uploadId': None})
    created = aws.call('s3api', 'create-multipart-upload', *common, '--content-type', mime)
    upload_id = created['UploadId']
    require(bool(upload_id), 'Multipart upload ID is missing')
    completed = False
    try:
        if recovery:
            recovery({'state': 'UPLOADING', 'key': key, 'uploadId': upload_id})
        parts = []
        with path.open('rb') as source:
            while chunk := source.read(part_bytes):
                number = len(parts) + 1
                require(number <= 10000, 'Object exceeds the bounded multipart limit')
                scratch.write_bytes(chunk)
                response = aws.call('s3api', 'upload-part', *common, '--upload-id', upload_id,
                    '--part-number', str(number), '--body', str(scratch))
                require(bool(response.get('ETag')), 'Multipart ETag is missing')
                parts.append({'PartNumber': number, 'ETag': response['ETag']})
        scratch.unlink(missing_ok=True)
        response = aws.call('s3api', 'complete-multipart-upload', *common,
            '--upload-id', upload_id, '--multipart-upload', json.dumps({'Parts': parts}),
            '--if-none-match', '*')
        completed = True
        if recovery:
            recovery({'state': 'COMPLETED', 'key': key, 'uploadId': upload_id,
                      'versionId': version(response)})
        return response
    except BaseException:
        if not completed:
            try:
                aws.call('s3api', 'abort-multipart-upload', *common, '--upload-id', upload_id)
                event = {'state': 'ABORTED', 'key': key, 'uploadId': upload_id}
            except BaseException as cleanup_error:
                event = {'state': 'ABORT_FAILED', 'key': key, 'uploadId': upload_id,
                         'errorType': type(cleanup_error).__name__}
            if recovery:
                # Preserve the original failure even if the receipt filesystem fails.
                try:
                    recovery(event)
                except BaseException:
                    pass
        raise
    finally:
        scratch.unlink(missing_ok=True)


def verify_remote(aws, key, response, expected_sha, size, scratch, part_bytes=PART_BYTES):
    """Hash the exact object version in bounded ranges, discarding each range."""
    pinned = version(response)
    digest = hashlib.sha256()
    require(size > 0, 'Empty release artifacts are not allowed')
    try:
        for start in range(0, size, part_bytes):
            end = min(start + part_bytes, size) - 1
            got = aws.call('s3api', 'get-object', '--bucket', BUCKET, '--key', key,
                '--version-id', pinned, '--range', f'bytes={start}-{end}', str(scratch))
            require(version(got) == pinned, 'Downloaded a different object version')
            require(got.get('ContentRange') == f'bytes {start}-{end}/{size}', 'Object range differs')
            require(got.get('ContentLength') == end-start+1 and scratch.stat().st_size == end-start+1,
                    'Object range length differs')
            with scratch.open('rb') as source:
                while chunk := source.read(1024 * 1024):
                    digest.update(chunk)
            scratch.unlink()
        require(digest.hexdigest() == expected_sha, 'Exact-version bytes differ: ' + key)
    finally:
        scratch.unlink(missing_ok=True)
    return {'key': key, 'versionId': pinned, 'sha256': expected_sha, 'bytes': size}


@termination_signals()
def publish(release, dataset_id, bucket, migration_dir, receipt_path, aws=None, *, allow_small=False):
    release, receipt_path = Path(release), Path(receipt_path)
    require(bucket == BUCKET, 'Unexpected dataset bucket')
    require(not receipt_path.exists(), 'Receipt must be new')
    checksum_path = release/'SHA256SUMS.json'
    require(checksum_path.is_file() and not checksum_path.is_symlink(), 'Sealed checksums must be a regular file')
    checksum_bytes = checksum_path.read_bytes()
    checksum_sha = hashlib.sha256(checksum_bytes).hexdigest()
    _, validated_hashes, _ = validate(release, dataset_id, migration_dir, allow_small=allow_small)
    require(sha(checksum_path) == checksum_sha and json.loads(checksum_bytes) == validated_hashes,
            'Sealed checksum bytes changed during validation')
    # Never promote a post-validation rehash to the upload's expected value.
    hashes = dict(validated_hashes, **{'SHA256SUMS.json': checksum_sha})
    aws = aws or Aws()
    identity = aws.call('sts', 'get-caller-identity')
    require(identity['Account'] == ACCOUNT and re.fullmatch(
        rf'arn:aws:sts::{ACCOUNT}:assumed-role/airbob-dataset-publisher/[A-Za-z0-9+=,.@_-]+', identity['Arn']),
        'Assume the dataset publisher role')
    prefix = 'datasets/' + dataset_id + '/'
    names = sorted(p.name for p in release.iterdir() if p.name != MARKER) + [MARKER]
    require(set(names) == set(hashes), 'Release inventory changed after validation')
    require(all((release/name).is_file() and not (release/name).is_symlink() for name in names),
            'Release must contain only regular sealed files')
    expected_keys = {prefix + name for name in names}
    sizes = {name: (release/name).stat().st_size for name in names}
    receipt = {'schemaVersion': 1, 'kind': 'global-growth-b-s3-publication', 'datasetId': dataset_id,
        'state': 'PREPARING', 'bucket': bucket, 'region': REGION, 'objects': {},
        'maximumCompressedScratchBytes': PART_BYTES, 'plaintextTemporaryDumpBytes': 0,
        'awsDatabaseRestoreExecuted': False, 'awsApplicationReadExecuted': False}
    fd = os.open(receipt_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    os.close(fd)
    def save():
        fd, temporary = tempfile.mkstemp(prefix=receipt_path.name+'.', dir=receipt_path.parent)
        try:
            with os.fdopen(fd, 'w') as destination:
                json.dump(receipt, destination, ensure_ascii=False, indent=2)
                destination.write('\n')
                destination.flush()
                os.fsync(destination.fileno())
            os.replace(temporary, receipt_path)
        finally:
            Path(temporary).unlink(missing_ok=True)
    def recovery(name, event):
        receipt.setdefault('multipartUploads', {})[name] = event
        save()
    try:
        with tempfile.TemporaryDirectory(prefix='growth-b-publish-') as directory:
            scratch = Path(directory)/'compressed-object-part'
            require(aws.keys(prefix) <= expected_keys, 'Unexpected objects under this prefix')
            remote = {name: aws.head(prefix + name) for name in names}
            completed = remote[MARKER] is not None
            require(not completed or all(remote.values()), 'Completed release has missing files')
            receipt['state'] = 'PUBLISHING'
            save()
            for name in names:
                path = release/name
                require(path.stat().st_size == sizes[name] and sha(path) == hashes[name],
                        'Local release changed during publication: ' + name)
                response = remote[name]
                if response is None:
                    try:
                        response = upload(aws, path, prefix+name, scratch,
                            recovery=lambda event: recovery(name, event))
                    except AwsError as error:
                        if error.code not in ('PreconditionFailed', '412'):
                            raise
                        response = aws.head(prefix + name)
                        require(response is not None, 'Concurrent upload disappeared')
                receipt['objects'][name] = verify_remote(aws, prefix+name, response,
                    hashes[name], sizes[name], scratch)
                save()
            require(aws.keys(prefix) == expected_keys, 'Final remote inventory differs')
            for name in names:
                require(version(aws.head(prefix+name)) == receipt['objects'][name]['versionId'],
                        'Object changed during publication: ' + name)
                require(sha(release/name) == hashes[name], 'Local release changed: ' + name)
            receipt.update(state='PUBLISHED_BYTES_AND_VERSIONS_VERIFIED', alreadyPublished=completed,
                completionKey=prefix+MARKER, completionVersionId=receipt['objects'][MARKER]['versionId'],
                consumerManifestSha256=hashes[MARKER], totalBytes=sum(sizes.values()))
    except BaseException as error:
        receipt.update(state='FAILED', errorType=type(error).__name__)
        raise
    finally:
        receipt['recordedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
        save()
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ('release', 'migration-dir', 'receipt'):
        parser.add_argument('--'+option, type=Path, required=True)
    parser.add_argument('--expected-id', required=True)
    parser.add_argument('--bucket', required=True)
    parser.add_argument('--allow-small', action='store_true')
    args = parser.parse_args()
    result = publish(args.release, args.expected_id, args.bucket, args.migration_dir,
        args.receipt, allow_small=args.allow_small)
    print(json.dumps({'state': result['state'], 'datasetId': result['datasetId'], 'receipt': str(args.receipt)}))
