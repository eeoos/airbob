"""Closed AWS qualification envelope for the small growth consumer contract."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import zipfile

spec = importlib.util.spec_from_file_location('growth_validator', Path(__file__).with_name('validate-growth-dataset-v3.py'))
consumer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(consumer)
require = consumer.require
read = consumer.read
sha = consumer.sha
exact = consumer.exact
CONSUMER_FILES = set(consumer.FILES.values()) | {'consumer-manifest.json'}
PAYLOAD_FILES = CONSUMER_FILES | {'verification-runtime.zip', 'publication-receipt.json'}
TOPICS = sorted(stream + suffix for stream in ['PAYMENT_OPERATION.events', 'ACCOMMODATION_INDEX.events',
    'ACCOMMODATION_CACHE.events', 'OPERATOR_ALERT.events'] for suffix in ['', '.RETRY', '.DLT'])


def canonical_sha(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def schema_sha(fingerprint):
    return canonical_sha({table: item['ddlSha256'] for table, item in fingerprint['tables'].items()})


def validate_manifest(manifest, expected_release):
    exact(manifest, ['schemaVersion', 'releaseKind', 'datasetRelease', 'datasetRunId', 'releaseTuple',
                     'source', 'mysql', 'couponPreparation', 'kafka', 'search', 'artifacts'])
    require(manifest['schemaVersion'] == 3 and manifest['releaseKind'] == 'growth-aws-qualification',
            'Unsupported AWS growth qualification envelope')
    source = manifest['source']
    exact(source, ['datasetId', 'consumerManifestSha256', 'publicationReceiptSha256'])
    require(re.fullmatch(r'korea-growth-v3-[0-9a-f]{16}', source['datasetId']) and
            manifest['datasetRelease'] == expected_release and
            re.fullmatch(re.escape(source['datasetId']) + r'-aws(?:-r[1-9][0-9]{0,2})?', expected_release),
            'AWS growth release identity mismatch')
    require(re.fullmatch(r'[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}', manifest['datasetRunId']), 'Invalid assembly identity')
    exact(manifest['artifacts'], PAYLOAD_FILES)
    for name, item in manifest['artifacts'].items():
        exact(item, ['sha256', 'bytes'])
        require(re.fullmatch(r'[0-9a-f]{64}', str(item['sha256'])) and
                type(item['bytes']) is int and 0 < item['bytes'] < 100_000_000, 'Invalid payload metadata')
    mysql = manifest['mysql']
    exact(mysql, ['engineVersion', 'flywayVersion', 'dumpKey', 'dumpSha256', 'migrationChecksumSha256',
                 'schemaFingerprintSha256', 'expectedTableRows', 'timezone', 'outboxPolicy'])
    require(mysql['engineVersion'] == '8.4.11' and mysql['flywayVersion'] == '27' and
            mysql['dumpKey'] == 'airbob-growth.sql.gz' and mysql['timezone'] == 'UTC' and
            mysql['outboxPolicy'] == 'absent', 'Invalid MySQL qualification contract')
    rows = mysql['expectedTableRows']
    require(isinstance(rows, dict) and len(rows) == 32 and all(re.fullmatch(r'[a-z][a-z0-9_]*', k)
            and type(v) is int and v >= 0 for k, v in rows.items()), 'Invalid table inventory')
    require(rows['flyway_schema_history'] == 27 and rows['accommodation_inventory_day'] == rows['outbox'] == 0,
            'Expected closed historical base data')
    require(manifest['couponPreparation'] == [] and manifest['search'] == {'enabled': False} and
            manifest['kafka'] == {'topics': TOPICS}, 'Qualification cannot claim prepared experiments or search')
    expected_tuple = {'datasetVersion': 'benchmark-dataset-v3', 'generatorVersion': 'korea-growth-v3',
                      'dumpSha256': mysql['dumpSha256'], 'migrationChecksumSha256': mysql['migrationChecksumSha256'],
                      'schemaFingerprintSha256': mysql['schemaFingerprintSha256'],
                      'consumerManifestSha256': source['consumerManifestSha256'],
                      'verificationRuntimeSha256': manifest['artifacts']['verification-runtime.zip']['sha256']}
    require(manifest['releaseTuple'] == expected_tuple, 'Release tuple mismatch')
    for name in ['dumpSha256', 'migrationChecksumSha256', 'schemaFingerprintSha256']:
        require(re.fullmatch(r'[0-9a-f]{64}', mysql[name]), 'Invalid MySQL digest')
    require(mysql['dumpSha256'] == manifest['artifacts']['airbob-growth.sql.gz']['sha256'] and
            source['datasetId'] == 'korea-growth-v3-' + mysql['dumpSha256'][:16] and
            mysql['migrationChecksumSha256'] == manifest['artifacts']['migration-files.json']['sha256'] and
            source['consumerManifestSha256'] == manifest['artifacts']['consumer-manifest.json']['sha256'] and
            source['publicationReceiptSha256'] == manifest['artifacts']['publication-receipt.json']['sha256'],
            'Envelope does not bind its payloads')
    return manifest


def validate_directory(directory, expected_release, migration_dir=None):
    directory = Path(directory)
    manifest = validate_manifest(read(directory / 'manifest.json'), expected_release)
    require({p.name for p in directory.iterdir()} == PAYLOAD_FILES | {'manifest.json'}, 'Unexpected release files')
    for name, item in manifest['artifacts'].items():
        require(sha(directory / name) == item['sha256'] and (directory / name).stat().st_size == item['bytes'],
                'AWS payload mismatch: ' + name)
    qualified, fingerprint, targets = consumer.validate(directory, manifest['source']['datasetId'], '8.4.11', migration_dir)
    require(schema_sha(fingerprint) == manifest['mysql']['schemaFingerprintSha256'] and
            {name: item['rows'] for name, item in fingerprint['tables'].items()} == manifest['mysql']['expectedTableRows'],
            'Fingerprint and envelope disagree')
    publication = read(directory / 'publication-receipt.json')
    require(publication['state'] == 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED' and
            publication['datasetId'] == manifest['source']['datasetId'] and
            publication['bucket'] == 'airbob-performance-lab-dataset-942632789808' and
            publication['region'] == 'ap-northeast-2' and
            publication['consumerManifestSha256'] == manifest['source']['consumerManifestSha256'] and
            set(publication['objects']) == CONSUMER_FILES, 'Source publication receipt mismatch')
    for name, item in publication['objects'].items():
        require(item['key'] == 'datasets/' + publication['datasetId'] + '/' + name and
                item['sha256'] == manifest['artifacts'][name]['sha256'] and
                item['bytes'] == manifest['artifacts'][name]['bytes'] and
                isinstance(item['versionId'], str) and item['versionId'] not in ['', 'null', 'None'],
                'Source publication object mismatch')
    validate_runtime(directory / 'verification-runtime.zip', read(directory / 'migration-files.json'))
    return manifest, qualified, fingerprint, targets


def validate_runtime(path, migrations, destination=None):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and len(names) < 100, 'Invalid runtime archive inventory')
        require(all(re.fullmatch(r'(runtime/bin/etl|runtime/lib/[A-Za-z0-9_.-]+\.jar|migrations/V[0-9]+__[A-Za-z0-9_]+\.sql|profile\.json|runtime-files\.json)', name)
                    for name in names), 'Unsafe runtime archive path')
        require(sum(item.file_size for item in archive.infolist()) < 200_000_000, 'Runtime archive is too large')
        inventory = json.loads(archive.read('runtime-files.json'))
        require(set(inventory) == set(names) - {'runtime-files.json'}, 'Runtime file seal mismatch')
        require({name[11:] for name in names if name.startswith('migrations/')} == set(migrations),
                'Runtime migrations differ from the qualified database')
        for name in names:
            data = archive.read(name)
            if name != 'runtime-files.json':
                require(hashlib.sha256(data).hexdigest() == inventory[name], 'Runtime file digest mismatch')
            if name.startswith('migrations/'):
                require(inventory[name] == migrations[name[11:]], 'Runtime migration digest mismatch')
            if destination is not None:
                target = Path(destination) / name
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                with target.open('xb') as output:
                    output.write(data)
                target.chmod(0o700 if name == 'runtime/bin/etl' else 0o600)
    return inventory
