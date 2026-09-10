"""V28 AWS envelope over existing, version-pinned v4 objects.

This is a distinct contract; the V27/v3 qualification limits remain unchanged.
The source release and its historical evidence are never relabelled or edited.
"""
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import tarfile

import growth_v4_contract as consumer

require, read, sha = consumer.require, consumer.read, consumer.sha
BUCKET = 'airbob-performance-lab-dataset-942632789808'
REGION = 'ap-northeast-2'
KIND = 'growth-v4-aws-qualification'
TOPICS = sorted(s + tail for s in ['PAYMENT_OPERATION.events', 'ACCOMMODATION_INDEX.events',
    'ACCOMMODATION_CACHE.events', 'OPERATOR_ALERT.events'] for tail in ['', '.RETRY', '.DLT'])
REQUIRED = set(consumer.ARTIFACTS.values()) | {'SHA256SUMS.json', 'consumer-manifest.json',
    'profile.json', 'etl-binaries.json'}


def exact(value, keys):
    require(isinstance(value, dict) and set(value) == set(keys), 'Unexpected contract fields')


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def schema_sha(fingerprint):
    return digest({name: table['ddlSha256'] for name, table in fingerprint['tables'].items()})


def validate_manifest(m, expected_release):
    exact(m, ['schemaVersion', 'releaseKind', 'datasetRelease', 'datasetRunId', 'datasetScale',
              'releaseTuple', 'source', 'mysql', 'couponPreparation', 'kafka', 'search', 'artifacts'])
    require(m['schemaVersion'] == 4 and m['releaseKind'] == KIND, 'V4 AWS envelope required')
    source = m['source']
    exact(source, ['datasetId', 'consumerManifestSha256', 'publicationReceiptSha256'])
    require(re.fullmatch(r'korea-growth-v4-[0-9a-f]{16}', source['datasetId']) and
            m['datasetRelease'] == expected_release and
            re.fullmatch(re.escape(source['datasetId']) + r'-aws-r[1-9][0-9]{0,2}', expected_release), 'Release identity mismatch')
    require(re.fullmatch(r'[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}', m['datasetRunId']), 'Invalid assembly identity')
    require(m['datasetScale'] in ['small-qualification', 'selected-two-million'], 'Unsupported dataset scale')
    artifacts = m['artifacts']
    require(isinstance(artifacts, dict) and REQUIRED <= set(artifacts) and 20 <= len(artifacts) <= 64, 'Incomplete source inventory')
    for name, a in artifacts.items():
        exact(a, ['key', 'versionId', 'sha256', 'bytes'])
        require(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]+', name) and
                a['key'] == 'datasets/' + source['datasetId'] + '/' + name and
                isinstance(a['versionId'], str) and a['versionId'] not in ['', 'null', 'None'] and
                re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,1024}', a['versionId']) and
                re.fullmatch(r'[0-9a-f]{64}', a['sha256']) and type(a['bytes']) is int and
                0 < a['bytes'] <= (1_100_000_000 if name == 'airbob-growth.sql.gz' else 100_000_000),
                'Invalid source artifact coordinate or bound')
    db = m['mysql']
    exact(db, ['engineVersion', 'flywayVersion', 'dumpKey', 'dumpSha256', 'migrationChecksumSha256',
               'schemaFingerprintSha256', 'expectedTableRows', 'timezone', 'outboxPolicy'])
    require(db['engineVersion'] == '8.4.11' and db['flywayVersion'] == '28' and
            db['dumpKey'] == 'airbob-growth.sql.gz' and db['timezone'] == 'UTC' and db['outboxPolicy'] == 'absent', 'V28/UTC source required')
    rows = db['expectedTableRows']
    require(isinstance(rows, dict) and len(rows) == 32 and all(re.fullmatch(r'[a-z][a-z0-9_]*', k)
            and type(v) is int and v >= 0 for k, v in rows.items()), 'Invalid table inventory')
    require(rows['flyway_schema_history'] == 28 and rows['outbox'] == 0 and
            rows['accommodation_inventory_day'] > 0, 'V28 historical occupied inventory required')
    if m['datasetScale'] == 'small-qualification':
        require(rows['accommodation'] <= 1000 and rows['reservation'] <= 50000 and
                artifacts['airbob-growth.sql.gz']['bytes'] <= 10_000_000 and
                artifacts['preparation-tools.tar.gz']['bytes'] <= 20_000_000, 'Small execution bounds exceeded')
    else:
        require(rows['reservation'] == 2000268 and rows['accommodation'] == 15954 and
                rows['member'] == 150131 and rows['wishlist'] == 300026 and
                rows['accommodation_inventory_day'] == 4392547, 'Selected two-million population differs')
    require(m['couponPreparation'] == [] and m['kafka'] == {'topics': TOPICS}, 'Unexpected runtime preparation claims')
    if m['datasetScale'] == 'selected-two-million':
        from growth_v4_search import descriptor_valid
        descriptor_valid(m['search'], source['datasetId'])
    else:
        require(m['search'] == {'enabled': False}, 'Small qualification has no matching native snapshot')
    expected_tuple = {'datasetVersion': 'benchmark-dataset-v4', 'generatorVersion': 'korea-growth-v4',
        'dumpSha256': db['dumpSha256'], 'migrationChecksumSha256': db['migrationChecksumSha256'],
        'schemaFingerprintSha256': db['schemaFingerprintSha256'],
        'consumerManifestSha256': source['consumerManifestSha256'],
        'verificationRuntimeSha256': artifacts['preparation-tools.tar.gz']['sha256']}
    require(m['releaseTuple'] == expected_tuple, 'Tuple does not bind the source')
    require(all(re.fullmatch(r'[0-9a-f]{64}', str(v)) for k, v in source.items() if k.endswith('Sha256')) and
            all(re.fullmatch(r'[0-9a-f]{64}', db[k]) for k in ['dumpSha256', 'migrationChecksumSha256', 'schemaFingerprintSha256']), 'Invalid source digest')
    require(db['dumpSha256'] == artifacts['airbob-growth.sql.gz']['sha256'] and
            source['datasetId'] == 'korea-growth-v4-' + db['dumpSha256'][:16] and
            db['migrationChecksumSha256'] == artifacts['migration-files.json']['sha256'] and
            source['consumerManifestSha256'] == artifacts['consumer-manifest.json']['sha256'], 'Source binding differs')
    return m


def validate_directory(release, m, migrations):
    validate_manifest(m, m['datasetRelease'])
    require({p.name for p in release.iterdir()} == set(m['artifacts']), 'Source inventory differs from envelope')
    for name, item in m['artifacts'].items():
        require((release / name).stat().st_size == item['bytes'] and sha(release / name) == item['sha256'], 'Payload mismatch: ' + name)
    source, checks, fingerprint = consumer.validate(release, m['source']['datasetId'], migrations)
    require(source['datasetScale'] == m['datasetScale'] and
            {k: v['rows'] for k, v in fingerprint['tables'].items()} == m['mysql']['expectedTableRows'] and
            schema_sha(fingerprint) == m['mysql']['schemaFingerprintSha256'], 'Envelope and source disagree')
    return source, fingerprint


def extract_runtime(release, destination):
    require(not destination.exists(), 'Runtime destination already exists')
    with tarfile.open(release / 'preparation-tools.tar.gz') as archive:
        entries = archive.getmembers()
        names = [e.name for e in entries]
        require(len(names) == len(set(names)) and len(names) < 256 and
                sum(e.size for e in entries) < 100_000_000, 'Runtime archive bounds exceeded')
        for entry in entries:
            path = Path(entry.name)
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in entry.name and
                    entry.name.split('/')[0] in ['backend', 'backend-assets', 'prepare-growth-release.py', 'runtime', 'tools'] and
                    (entry.isfile() or entry.isdir()), 'Unsafe runtime entry')
        destination.mkdir(mode=0o700)
        for entry in entries:
            target = destination / entry.name
            if entry.isdir():
                target.mkdir(parents=True, exist_ok=True, mode=0o700)
            else:
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                with archive.extractfile(entry) as source, target.open('xb') as output:
                    import shutil
                    shutil.copyfileobj(source, output)
                target.chmod(0o700 if entry.name == 'runtime/bin/etl' else 0o600)
    require({p.name: sha(p) for p in (destination / 'runtime/lib').iterdir()} == read(release / 'etl-binaries.json'), 'Verifier binaries differ')
    migrations = destination / 'backend/src/main/resources/db/migration'
    require({p.name: sha(p) for p in migrations.glob('V*__*.sql')} == read(release / 'migration-files.json'), 'Runtime migrations differ')
    return migrations


def assemble(release, publication_path, migrations, output, revision=1, search_directory=None, search_publication=None):
    require(not output.exists() and type(revision) is int and 1 <= revision <= 999, 'Use a new reviewed revision')
    publication = read(publication_path)
    require(publication['state'] == 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED' and
            publication['kind'] == 'growth-v4-s3-publication' and publication['bucket'] == BUCKET and publication['region'] == REGION,
            'Verified original v4 publication required')
    source, _, fingerprint = consumer.validate(release, publication['datasetId'], migrations)
    artifacts = publication['objects']
    require(set(artifacts) == {p.name for p in release.iterdir()}, 'Publication inventory differs')
    db = {'engineVersion': '8.4.11', 'flywayVersion': '28', 'dumpKey': 'airbob-growth.sql.gz',
        'dumpSha256': artifacts['airbob-growth.sql.gz']['sha256'],
        'migrationChecksumSha256': artifacts['migration-files.json']['sha256'],
        'schemaFingerprintSha256': schema_sha(fingerprint), 'timezone': 'UTC', 'outboxPolicy': 'absent',
        'expectedTableRows': {name: item['rows'] for name, item in fingerprint['tables'].items()}}
    m = {'schemaVersion': 4, 'releaseKind': KIND, 'datasetRelease': source['datasetId'] + '-aws-r' + str(revision),
         'datasetRunId': dt.datetime.fromisoformat(publication['recordedAt']).strftime('%Y%m%dT%H%M%SZ-') + artifacts['consumer-manifest.json']['sha256'][:8],
         'datasetScale': source['datasetScale'], 'source': {'datasetId': source['datasetId'],
             'consumerManifestSha256': artifacts['consumer-manifest.json']['sha256'], 'publicationReceiptSha256': sha(publication_path)},
         'mysql': db, 'couponPreparation': [], 'kafka': {'topics': TOPICS}, 'search': {'enabled': False}, 'artifacts': artifacts,
         'releaseTuple': {'datasetVersion': 'benchmark-dataset-v4', 'generatorVersion': 'korea-growth-v4',
             'dumpSha256': db['dumpSha256'], 'migrationChecksumSha256': db['migrationChecksumSha256'],
             'schemaFingerprintSha256': db['schemaFingerprintSha256'], 'consumerManifestSha256': artifacts['consumer-manifest.json']['sha256'],
             'verificationRuntimeSha256': artifacts['preparation-tools.tar.gz']['sha256']}}
    if source['datasetScale'] == 'selected-two-million':
        require(search_directory is not None and search_publication is not None, 'Final AWS restore requires the existing search companion')
        from growth_v4_search import assemble_search
        m['search'] = assemble_search(search_directory, search_publication, m)
    else:
        require(search_directory is None and search_publication is None, 'Small source cannot be paired with the final snapshot')
    validate_directory(release, m, migrations)
    output.mkdir(parents=True, mode=0o700)
    (output / 'manifest.json').write_text(json.dumps(m, indent=2) + '\n')
    return m
