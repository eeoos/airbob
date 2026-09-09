#!/usr/bin/env python3
"""Assemble a small AWS qualification envelope from the sealed release and verifier."""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import shutil
import zipfile
import growth_aws_contract as contract


def assemble(release, publication_path, runtime, migrations, output, revision=1):
    contract.require(type(revision) is int and 1 <= revision <= 999, 'Invalid envelope revision')
    publication = contract.read(publication_path)
    source_id = publication['datasetId']
    consumer, fingerprint, _ = contract.consumer.validate(release, source_id, '8.4.11', migrations)
    expected_binaries = contract.read(release / 'etl-binaries.json')
    actual_binaries = {path.name: contract.sha(path) for path in (runtime / 'lib').iterdir()}
    contract.require(actual_binaries == expected_binaries, 'Verifier binary differs from the locally qualified runtime')
    output.mkdir(mode=0o700)
    for name in contract.CONSUMER_FILES:
        shutil.copyfile(release / name, output / name)
        (output / name).chmod(0o600)
    shutil.copyfile(publication_path, output / 'publication-receipt.json')
    files = {'runtime/bin/etl': runtime / 'bin/etl', 'profile.json': release / 'profile.json'}
    files.update({'runtime/lib/' + name: runtime / 'lib' / name for name in expected_binaries})
    files.update({'migrations/' + path.name: path for path in migrations.glob('V*__*.sql')})
    inventory = {name: contract.sha(path) for name, path in files.items()}
    with zipfile.ZipFile(output / 'verification-runtime.zip', 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, path in sorted(files.items()):
            archive.writestr(zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)), path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
        archive.writestr(zipfile.ZipInfo('runtime-files.json', (2026, 1, 1, 0, 0, 0)), json.dumps(inventory, sort_keys=True))
    artifacts = {name: {'sha256': contract.sha(output / name), 'bytes': (output / name).stat().st_size}
                 for name in sorted(contract.PAYLOAD_FILES)}
    schema = contract.schema_sha(fingerprint)
    source = {'datasetId': source_id, 'consumerManifestSha256': artifacts['consumer-manifest.json']['sha256'],
              'publicationReceiptSha256': artifacts['publication-receipt.json']['sha256']}
    mysql = {'engineVersion': '8.4.11', 'flywayVersion': '27', 'dumpKey': 'airbob-growth.sql.gz',
             'dumpSha256': artifacts['airbob-growth.sql.gz']['sha256'],
             'migrationChecksumSha256': artifacts['migration-files.json']['sha256'],
             'schemaFingerprintSha256': schema, 'timezone': 'UTC', 'outboxPolicy': 'absent',
             'expectedTableRows': {name: item['rows'] for name, item in fingerprint['tables'].items()}}
    release_id = source_id + '-aws' + (('-r' + str(revision)) if revision > 1 else '')
    manifest = {'schemaVersion': 3, 'releaseKind': 'growth-aws-qualification', 'datasetRelease': release_id,
                'datasetRunId': dt.datetime.fromisoformat(publication['recordedAt']).strftime('%Y%m%dT%H%M%SZ-') + source['consumerManifestSha256'][:8],
                'source': source, 'mysql': mysql, 'couponPreparation': [], 'kafka': {'topics': contract.TOPICS},
                'search': {'enabled': False}, 'artifacts': artifacts,
                'releaseTuple': {'datasetVersion': 'benchmark-dataset-v3', 'generatorVersion': 'korea-growth-v3',
                                 'dumpSha256': mysql['dumpSha256'], 'migrationChecksumSha256': mysql['migrationChecksumSha256'],
                                 'schemaFingerprintSha256': schema, 'consumerManifestSha256': source['consumerManifestSha256'],
                                 'verificationRuntimeSha256': artifacts['verification-runtime.zip']['sha256']}}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    for path in output.iterdir(): path.chmod(0o600)
    contract.validate_directory(output, manifest['datasetRelease'], migrations)
    return {'datasetRelease': manifest['datasetRelease'], 'manifestSha256': contract.sha(output / 'manifest.json'),
            'files': len(artifacts) + 1, 'scope': 'RDS qualification only; no app or search approval'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['release', 'publication-receipt', 'runtime', 'migration-dir', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--revision', type=int, default=1)
    args = parser.parse_args()
    print(json.dumps(assemble(args.release, args.publication_receipt, args.runtime, args.migration_dir, args.output, args.revision)))
