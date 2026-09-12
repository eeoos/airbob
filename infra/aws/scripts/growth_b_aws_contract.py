#!/usr/bin/env python3
"""Offline AWS coordinates and disk budget for the sealed Global B release.

This does not publish, provision, or restore anything. The envelope SHA is a
reviewed input to the separate restore command, not a substitute for a signature.
"""
import argparse
import json
import math
from pathlib import Path
import re
import tarfile

from growth_b_contract import digest, integer, read, require, sha, validate

ACCOUNT = '942632789808'
REGION = 'ap-northeast-2'
BUCKET = 'airbob-performance-lab-dataset-' + ACCOUNT
KIND = 'global-growth-b-aws-restore'
GIB = 1024**3


def publication(release, receipt, dataset_id):
    require(receipt.get('schemaVersion') == 1 and receipt.get('kind') == 'global-growth-b-s3-publication'
            and receipt.get('state') == 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED', 'A completed B publication receipt is required')
    require(receipt.get('datasetId') == dataset_id and receipt.get('bucket') == BUCKET
            and receipt.get('region') == REGION, 'Publication source differs')
    names = {path.name for path in release.iterdir()}
    require(names == set(receipt['objects']), 'Publication file inventory differs')
    for name, item in receipt['objects'].items():
        require(set(item) == {'key', 'versionId', 'sha256', 'bytes'} and
                item['key'] == f'datasets/{dataset_id}/{name}', 'Publication key differs')
        require(isinstance(item['versionId'], str) and item['versionId'] not in ('', 'null', 'None')
                and re.fullmatch(r'[A-Za-z0-9._~+/=-]+', item['versionId']), 'Exact S3 VersionId is required')
        require(integer(item['bytes'], 1) and item['bytes'] == (release / name).stat().st_size
                and digest(item['sha256']) and sha(release / name) == item['sha256'], 'Publication bytes differ: ' + name)
    marker = receipt['objects']['consumer-manifest.json']
    require(receipt.get('completionKey') == marker['key'] and receipt.get('completionVersionId') == marker['versionId']
            and receipt.get('consumerManifestSha256') == marker['sha256']
            and receipt.get('totalBytes') == sum(item['bytes'] for item in receipt['objects'].values()),
            'Publication completion marker differs')
    return receipt['objects']


def storage_budget(release):
    """Use final observed pages/rows with the same 15% margin as the B pilot.

    The observed runtime already includes today's horizon. Only the difference
    to the maximum 99-day horizon and 30 FREE retention days is added. Physical
    allocation and the 15% growth margin are applied once; there is no arbitrary
    per-row floor or second percentage reserve. The distinct 8 GiB reserve is
    for RDS redo/import/verification temporary space, not dataset growth. An
    enabled binlog has a separate reviewed budget in the restore configuration.
    """
    metrics = read(release / 'measurements.json')
    current = read(release / 'runtime-database-measurement.json')
    base = metrics['databases']['restoredBase']
    base_bytes = max(base['allocatedTablespaceBytes'], metrics['databases']['generatedBase']['allocatedTablespaceBytes'])
    published = read(release / 'scenario-qualification.json')['repeats'][0]['bootstrap']['readiness']['publishedListings']
    require(integer(base_bytes, 1) and integer(published, 1), 'Measured database/published population is missing')
    inventory = [next(row for row in measured['tables'] if row['TABLE_NAME'] == 'accommodation_inventory_day')
                 for measured in (base, current)]
    added_rows = inventory[1]['rows'] - inventory[0]['rows']
    added_pages = sum(inventory[1][key] - inventory[0][key] for key in ('DATA_LENGTH', 'INDEX_LENGTH'))
    require(integer(added_rows, 1) and integer(added_pages, 1), 'Measured current FREE inventory delta is missing')
    per_row = added_pages / added_rows
    horizon = read(release / 'scenario-qualification.json')['repeats'][0]['bootstrap']['readiness']
    require(horizon['currentHorizonRows'] == added_rows, 'Measured FREE inventory differs from the verified current horizon')
    extra_current_rows, retained_rows = max(0, published * 99 - added_rows), published * 30
    extra_current_bytes = math.ceil(extra_current_rows * per_row)
    retained_bytes = math.ceil(retained_rows * per_row)
    require(integer(current['dataAndIndexBytes'], 1) and integer(current['allocatedTablespaceBytes'], 1), 'Measured physical allocation is missing')
    ratio = max(1.0, current['allocatedTablespaceBytes'] / current['dataAndIndexBytes'])
    live = math.ceil((current['dataAndIndexBytes'] + extra_current_bytes + retained_bytes) * ratio * 1.15)
    temporary = 8 * GIB
    with tarfile.open(release / 'preparation-tools.tar.gz') as archive:
        unpacked = sum(member.size for member in archive.getmembers())
    require(0 < unpacked <= 256 * 1024**2, 'Preparation archive exceeds its extraction contract')
    return {'schemaVersion': 1, 'basis': 'sealed observed pages/rows; B pilot allocation model with one 15% growth margin',
        'baseDatabaseAllocatedBytes': base_bytes, 'publishedListings': published,
        'maximumCurrentDays': 99, 'retainedFreeDays': 30, 'measuredFreeInventoryBytesPerRow': per_row,
        'observedCurrentHorizonRows': added_rows, 'extraCurrentFreeRowAllowance': extra_current_rows,
        'retainedFreeRowAllowance': retained_rows, 'retainedFreeDataAndIndexBytes': retained_bytes,
        'observedPhysicalAllocationRatio': ratio, 'datasetGrowthMargin': 0.15,
        'databaseWithCurrentAndRetainedFreeBytes': live, 'rdsTemporaryReserveBytes': temporary,
        'requiredRdsFreeAfterRemovalBytes': live + temporary,
        'sourceCompressedBytesAlreadyStaged': sum(path.stat().st_size for path in release.iterdir()),
        'runtimeExtractionBytes': unpacked, 'requiredAdditionalDataHostFreeBytes': unpacked + 4 * GIB,
        'plaintextSqlTemporaryBytes': 0, 'sourceReleaseCopies': 1,
        'elasticsearchBudget': 'separate companion and live ES-node disk gate; not included in RDS/data-host bytes'}


def assemble(release, dataset_id, migration_dir, receipt_path, receipt_sha, app_jar, *, allow_small=False):
    release, receipt_path, app_jar = Path(release), Path(receipt_path), Path(app_jar)
    require(digest(receipt_sha) and sha(receipt_path) == receipt_sha, 'Publication receipt trust anchor differs')
    manifest, checks, baseline = validate(release, dataset_id, migration_dir,
        allow_small=allow_small, expected_app_sha=sha(app_jar))
    objects = publication(release, read(receipt_path), dataset_id)
    return {'schemaVersion': 1, 'kind': KIND, 'datasetId': dataset_id, 'account': ACCOUNT, 'region': REGION,
        'mysql': {'version': '8.4.11', 'flywayVersion': 28, 'schema': 'airbobdb'},
        'finalScaleSelected': manifest['finalScaleSelected'], 'awsExecutionAllowed': manifest['finalScaleSelected'],
        'smallRehearsalEligible': manifest['finalScaleSelected'] is False and manifest['qualification'] == 'SMALL_LOCAL_SCENARIOS',
        'finalRequiresSameToolSmallRdsReceipt': True,
        'publicationReceiptSha256': receipt_sha, 'consumerManifestSha256': checks['consumer-manifest.json'],
        'checksumsSha256': sha(release / 'SHA256SUMS.json'), 'appJarSha256': manifest['appJarSha256'],
        'baselineFingerprintSha256': checks['before-fingerprint.json'], 'bucket': BUCKET, 'objects': objects,
        'storage': storage_budget(release), 'searchRequiredForApplicationDeployment': True,
        'claims': {'awsExecuted': False, 'albReady': False, 'kafkaCdcReady': False}}


def validate_envelope(path, expected_sha, release, migration_dir, receipt_path, app_jar, *, allow_small=False):
    require(digest(expected_sha) and sha(path) == expected_sha, 'Reviewed AWS envelope SHA differs')
    envelope = read(path)
    expected = assemble(release, envelope['datasetId'], migration_dir, receipt_path,
        envelope['publicationReceiptSha256'], app_jar, allow_small=allow_small)
    require(envelope == expected, 'AWS envelope differs from its sealed source and measured budget')
    return envelope


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('release', 'migration-dir', 'publication-receipt', 'app-jar', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--dataset-id', required=True)
    parser.add_argument('--publication-receipt-sha256', required=True)
    parser.add_argument('--allow-small', action='store_true', help='Accept qualified small in this offline envelope; RDS rehearsal needs separate explicit opt-ins')
    args = parser.parse_args()
    result = assemble(args.release, args.dataset_id, args.migration_dir, args.publication_receipt,
        args.publication_receipt_sha256, args.app_jar, allow_small=args.allow_small)
    require(not args.output.exists(), 'Output already exists')
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2); stream.write('\n')
    print(json.dumps({'state': 'OFFLINE_CONTRACT_PREPARED', 'sha256': sha(args.output),
                      'awsExecutionAllowed': result['awsExecutionAllowed']}))
