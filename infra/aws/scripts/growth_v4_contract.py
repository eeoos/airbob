"""Validate the sealed V28 dataset without changing its historical evidence."""
import hashlib
import json
from pathlib import Path
import re

ARTIFACTS = {'dump': 'airbob-growth.sql.gz', 'migrations': 'migration-files.json',
    'fingerprint': 'before-fingerprint.json', 'reads': 'read-scenarios.json', 'runtime': 'runtime-plan.json',
    'runtimeEvidence': 'runtime-scenarios.json', 'scenarioQualification': 'scenario-qualification.json',
    'cache': 'cache-qualification.json', 'search': 'search-qualification.json', 'reset': 'final-reset-fingerprint.json',
    'catalog': 'query-catalog.json', 'httpCoverage': 'http-coverage.json', 'targets': 'measured-workload-targets.json',
    'tools': 'preparation-tools.tar.gz', 'toolSources': 'tool-sources.json', 'measurements': 'measurements.json'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    require(path.is_file() and not path.is_symlink(), 'Unsafe artifact: ' + path.name)
    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, 'Duplicate JSON key')
            value[key] = item
        return value
    return json.loads(path.read_text(), object_pairs_hook=unique)


def sha(path):
    require(path.is_file() and not path.is_symlink(), 'Unsafe artifact: ' + path.name)
    with path.open('rb') as source:
        digest = hashlib.sha256()
        for chunk in iter(lambda: source.read(4 * 1024 * 1024), b''):
            digest.update(chunk)
        return digest.hexdigest()


def validate(release, dataset_id, migration_dir):
    require(release.is_dir() and not release.is_symlink(), 'Unsafe release directory')
    checks = read(release / 'SHA256SUMS.json')
    require(isinstance(checks, dict) and 16 <= len(checks) <= 64, 'Invalid artifact inventory')
    require(set(checks) == {p.name for p in release.iterdir()} - {'SHA256SUMS.json'}, 'Unexpected release files')
    for name, digest in checks.items():
        require(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]+', name) and re.fullmatch(r'[0-9a-f]{64}', digest),
                'Unsafe artifact inventory')
        require(sha(release / name) == digest, 'Artifact checksum mismatch: ' + name)
    manifest = read(release / 'consumer-manifest.json')
    require(set(manifest) == {'schemaVersion', 'datasetVersion', 'datasetId', 'qualification', 'mysql', 'time',
            'artifacts', 'appJarSha256', 'capabilities', 'finalScaleSelected', 'datasetScale', 'integratedEvidence'},
            'Unexpected consumer contract')
    require(manifest['schemaVersion'] == 4 and manifest['datasetVersion'] == 'benchmark-dataset-v4', 'V4 required')
    require(manifest['datasetId'] == dataset_id == 'korea-growth-v4-' + checks['airbob-growth.sql.gz'][:16],
            'Dataset identity is not bound to the dump')
    require(manifest['mysql'] == {'version': '8.4.11', 'flywayVersion': 28}, 'MySQL 8.4.11 / V28 required')
    require(manifest['capabilities'] == {'immutableReads': True, 'runtimePreparation': True, 'searchSnapshot': False,
            'searchRebuildValidated': True, 'cacheHandlerValidated': True, 'kafkaCdcEndToEndValidated': False,
            'awsExecutionValidated': False}, 'Unsupported capability claims')
    require(manifest['qualification'] == 'SMALL_LOCAL_SCENARIOS' and manifest['integratedEvidence'] == {
            'validated': True, 'repeatCount': 2, 'quoteContract': 'V28 revalidation without quote expiry'},
            'Integrated qualification required')
    require(set(manifest['artifacts']) == set(ARTIFACTS), 'Incomplete consumer artifact contract')
    for key, artifact in manifest['artifacts'].items():
        require(set(artifact) == {'file', 'sha256'} and artifact['file'] == ARTIFACTS[key] and
                checks.get(artifact['file']) == artifact['sha256'],
                'Consumer artifact binding mismatch')
    migrations = read(release / 'migration-files.json')
    require({p.name: sha(p) for p in migration_dir.glob('V*__*.sql')} == migrations,
            'Application migrations do not match the dump')
    versions = [int(re.fullmatch(r'V(\d+)__[A-Za-z0-9_]+\.sql', name)[1]) for name in migrations]
    require(sorted(versions) == list(range(1, 29)), 'Incomplete migration inventory')
    fingerprint = read(release / 'before-fingerprint.json')
    require(fingerprint['mysqlVersion'] == '8.4.11' and len(fingerprint['tables']) == 32, 'Fingerprint engine or schema differs')
    tables = fingerprint['tables']
    require(tables['flyway_schema_history']['rows'] == 28 and tables['outbox']['rows'] == 0, 'Unqualified historical base')
    for name, info in tables.items():
        require(re.fullmatch(r'[a-z][a-z0-9_]*', name) and type(info['rows']) is int and info['rows'] >= 0,
                'Invalid table inventory')
        require(all(re.fullmatch(r'[0-9a-f]{64}', info[key]) for key in ['rowsSha256', 'domainRowsSha256', 'ddlSha256']),
                'Invalid table fingerprint')
    require(fingerprint == read(release / 'final-reset-fingerprint.json'), 'Final reset did not preserve all rows and DDL')
    qualification = read(release / 'scenario-qualification.json')
    require(qualification['state'] == 'SMALL_SCENARIOS_QUALIFIED' and qualification['resetVerified'] is True,
            'Scenarios are not qualified')
    require(qualification['appJarSha256'] == manifest['appJarSha256'], 'Qualified application identity differs')
    repeats = qualification['repeats']
    require(len(repeats) == 2 and all(r['runtime']['passed'] and r['readModel']['passed'] and
            r['reset']['allRowsAndDdlEqual'] for r in repeats), 'Two scenario executions and resets required')
    require(repeats[0]['bootstrap']['passed'], 'Inventory bootstrap evidence missing')
    require(all(r['runtime']['currentBooking']['agedQuoteAccepted'] and
                r['runtime']['currentBooking']['stalePriceRejected'] for r in repeats), 'V28 checkout evidence missing')
    for name in ['cache-qualification.json', 'search-qualification.json']:
        require(read(release / name)['state'] == 'PASSED', 'Missing cache/search evidence')
    require(read(release / 'http-coverage.json')['passed'], 'Incomplete read-route evidence')
    profile = read(release / 'profile.json')
    require(profile['version'] == 'korea-growth-v4' and profile['queryBoundaries'] is True,
            'Unexpected generation profile')
    require(manifest['time'] == {'timezone': 'Asia/Seoul', 'snapshotAsOf': profile['snapshotAsOf'],
                                'activityCutoffExclusive': profile['activityCutoffExclusive']}, 'Historical time mismatch')
    if manifest['finalScaleSelected']:
        require(manifest['datasetScale'] == 'selected-two-million', 'Unsupported final scale')
        require([profile[k] for k in ['listingLimit', 'members', 'reservations', 'wishlists']] ==
                [0, 150000, 2000000, 300000], 'Final profile differs from the selected scale')
        require(tables['reservation']['rows'] == 2000268 and tables['member']['rows'] == 150131 and
                tables['accommodation']['rows'] == 15954 and tables['wishlist']['rows'] == 300026,
                'Final population differs from the selected dataset')
    else:
        require(tables['reservation']['rows'] <= 50268, 'Unselected large population')
    return manifest, checks, fingerprint
