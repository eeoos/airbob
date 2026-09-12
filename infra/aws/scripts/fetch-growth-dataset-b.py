#!/usr/bin/env python3
"""Fetch an externally pinned B publication without a duplicate compressed release."""
import argparse
import ctypes
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sys

from growth_b_contract import read, require, sha, validate

_spec = importlib.util.spec_from_file_location('growth_b_publisher',
    Path(__file__).with_name('publish-growth-dataset-b.py'))
_publisher = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_publisher)
Aws, ACCOUNT, BUCKET, REGION, MARKER = (_publisher.Aws, _publisher.ACCOUNT,
    _publisher.BUCKET, _publisher.REGION, _publisher.MARKER)


def publish_directory_exclusively(source, destination):
    """Atomically refuse every pre-existing destination, including an empty directory."""
    libc = ctypes.CDLL(None, use_errno=True)
    if sys.platform == 'darwin':
        rename = libc.renamex_np
        rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        rename.restype = ctypes.c_int
        result = rename(os.fsencode(source), os.fsencode(destination), 0x00000004)  # RENAME_EXCL
    elif sys.platform.startswith('linux') and hasattr(libc, 'renameat2'):
        rename = libc.renameat2
        rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        rename.restype = ctypes.c_int
        result = rename(-100, os.fsencode(source), -100, os.fsencode(destination), 1)  # AT_FDCWD / RENAME_NOREPLACE
    else:
        raise RuntimeError('Atomic exclusive directory publication requires supported macOS or Linux')
    if result != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error), str(destination))


def fetch(receipt_path, receipt_sha, dataset_id, output, migration_dir, *, aws=None,
          allow_small=False, reserved_free_bytes=256*1024*1024):
    receipt_path, output = Path(receipt_path), Path(output)
    require(re.fullmatch(r'[a-f0-9]{64}', receipt_sha) and sha(receipt_path) == receipt_sha,
            'Publication receipt differs from the externally pinned SHA-256')
    receipt = read(receipt_path)
    require(receipt.get('schemaVersion') == 1 and receipt.get('kind') == 'global-growth-b-s3-publication'
        and receipt.get('state') == 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED', 'Publication is incomplete')
    require(re.fullmatch(r'global-growth-b-[a-f0-9]{16}', dataset_id)
        and receipt.get('datasetId') == dataset_id, 'Dataset ID differs')
    require(receipt.get('bucket') == BUCKET and receipt.get('region') == REGION, 'Unexpected bucket or region')
    objects = receipt['objects']
    require(isinstance(objects, dict) and 20 <= len(objects) <= 100, 'Invalid artifact inventory')
    prefix = 'datasets/' + dataset_id + '/'
    for name, item in objects.items():
        require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', name) and name not in ('.', '..'),
                'Unsafe artifact name')
        require(item.get('key') == prefix+name and re.fullmatch(r'[a-f0-9]{64}', item.get('sha256', '')),
                'Artifact key or SHA differs')
        require(type(item.get('bytes')) is int and item['bytes'] > 0, 'Invalid artifact size')
        _publisher.version({'VersionId': item.get('versionId')})
    require(MARKER in objects and 'SHA256SUMS.json' in objects and 'airbob-growth.sql.gz' in objects,
            'Required sealed artifacts are missing')
    require(receipt['completionKey'] == objects[MARKER]['key']
        and receipt['completionVersionId'] == objects[MARKER]['versionId']
        and receipt['consumerManifestSha256'] == objects[MARKER]['sha256'], 'Completion marker differs')
    total = sum(item['bytes'] for item in objects.values())
    require(total == receipt['totalBytes'], 'Total artifact bytes differ')
    require(type(reserved_free_bytes) is int and reserved_free_bytes >= 0, 'Invalid disk reserve')
    require(not output.exists() and not output.is_symlink(), 'Output must be new')
    stage = output.with_name(output.name+'.partial')
    require(not stage.exists() and not stage.is_symlink(), 'Inspect the existing incomplete download first')
    output.parent.mkdir(parents=True, exist_ok=True)
    require(shutil.disk_usage(output.parent).free >= total+reserved_free_bytes, 'Insufficient download space')
    aws = aws or Aws()
    require(aws.call('sts', 'get-caller-identity')['Account'] == ACCOUNT, 'Unexpected AWS account')
    stage.mkdir(mode=0o700)
    # Only complete verified files exist in stage. The directory is published last.
    for name in sorted(n for n in objects if n != MARKER)+[MARKER]:
        item = objects[name]
        path = stage/(name+'.part')
        response = aws.call('s3api', 'get-object', '--bucket', BUCKET, '--key', item['key'],
            '--version-id', item['versionId'], str(path))
        require(_publisher.version(response) == item['versionId'], 'Wrong object version: '+name)
        require(response.get('ContentLength') == item['bytes'] and path.stat().st_size == item['bytes'],
                'Wrong object size: '+name)
        require(sha(path) == item['sha256'], 'Wrong object bytes: '+name)
        os.replace(path, stage/name)
    validate(stage, dataset_id, migration_dir, allow_small=allow_small)
    publish_directory_exclusively(stage, output)
    return {'state': 'FETCHED_BYTES_VERSIONS_AND_CONTRACT_VERIFIED', 'datasetId': dataset_id,
        'release': str(output.resolve()), 'publicationReceiptSha256': receipt_sha,
        'totalBytes': total, 'duplicateReleaseBytes': 0, 'plaintextTemporaryDumpBytes': 0}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('receipt', 'output', 'migration-dir'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--receipt-sha256', required=True)
    parser.add_argument('--expected-id', required=True)
    parser.add_argument('--allow-small', action='store_true')
    parser.add_argument('--reserved-free-bytes', type=int, default=256*1024*1024)
    args = parser.parse_args()
    print(json.dumps(fetch(args.receipt, args.receipt_sha256, args.expected_id, args.output,
        args.migration_dir, allow_small=args.allow_small, reserved_free_bytes=args.reserved_free_bytes)))
