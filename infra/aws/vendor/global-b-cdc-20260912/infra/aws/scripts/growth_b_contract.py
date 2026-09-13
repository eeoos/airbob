"""Validate a sealed global B v4 release; this module never changes a database."""
import gzip
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import stat
import tarfile

DATASET_RE = r'global-growth-b-[0-9a-f]{16}'
FINAL_SCALE = 'selected-global-b-ten-million'
ARTIFACTS = {
    'dump': 'airbob-growth.sql.gz', 'migrations': 'migration-files.json',
    'fingerprint': 'before-fingerprint.json', 'reads': 'read-scenarios.json',
    'runtime': 'runtime-plan.json', 'runtimeEvidence': 'runtime-scenarios.json',
    'scenarioQualification': 'scenario-qualification.json', 'cache': 'cache-qualification.json',
    'search': 'search-qualification.json', 'reset': 'final-reset-fingerprint.json',
    'catalog': 'query-catalog.json', 'httpCoverage': 'http-coverage.json',
    'targets': 'measured-workload-targets.json', 'tools': 'preparation-tools.tar.gz',
    'toolSources': 'tool-sources.json', 'measurements': 'measurements.json',
    'accounts': 'accounts.json', 'accountLogins': 'account-login-qualification.json',
    'baseWorkloads': 'base-scenario-targets.json', 'images': 'image-qualification.json',
    'dumpIntegrity': 'dump-integrity.json', 'currentDatabaseMeasurement': 'runtime-database-measurement.json',
    'timeZoneQualification': 'timezone-qualification.json',
    'temporalDataValidation': 'temporal-data-validation.json',
    'representativeAccounts': 'representative-accounts.json',
}
REPRESENTATIVES = {'demo': ('demo@airbob.test', 'MEMBER'),
                   'host': ('host@airbob.test', 'MEMBER'), 'admin': ('admin@airbob.test', 'ADMIN')}
TIME_ZONE_POLICY = 'per-listing IANA coordinate zone; source-market identity retained'
TEMPORAL_CASES_SHA256 = '7a09f445b51209e4a4b4a51048faf5985c6eb82cc7beb43b826f3b08e6030e41'
REQUIRED_STAGES = {'generate-generate', 'dump-and-compression',
                   'restore-and-decompression', 'runtime-scenarios'}
BOUNDARY_ROWS = {'accommodation': 28, 'member': 131, 'reservation': 268, 'review': 120, 'wishlist': 26}
TABLES = frozenset(('accommodation accommodation_amenity accommodation_history accommodation_image '
    'accommodation_inventory_day accommodation_review_summary address common_code common_code_group coupon '
    'daily_revenue_stats failed_indexing_events flyway_schema_history member member_coupon member_history '
    'occupancy_policy outbox payment payment_operation payment_operation_resolution payment_transaction '
    'reservation reservation_checkout_request reservation_history reservation_quote review review_image '
    'settlement settlement_history wishlist wishlist_accommodation').split())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def regular(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), 'A regular, non-symlink file is required: ' + path.name)
    return path


def read(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(regular(path).read_text(), object_pairs_hook=unique)


def sha(path):
    with regular(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def digest(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value) is not None


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def representative_rows(value):
    rows = value['accounts'] if isinstance(value, dict) else value
    require(isinstance(rows, list) and len(rows) == 3, 'Exactly three representative accounts are required')
    require(len({row['memberId'] for row in rows}) == len({row['key'] for row in rows}) == 3,
            'Representative identities must be distinct')
    for row in rows:
        require(integer(row['memberId'], 1) and row['key'] in REPRESENTATIVES
                and (row['email'], row['role']) == REPRESENTATIVES[row['key']] and row['status'] == 'ACTIVE',
                'Representative alias, existing role, or identity differs')
    return rows


def public_account_union(bundle):
    """The qualification sample and the three representatives may overlap."""
    require(bundle.get('schemaVersion') == 2 and bundle.get('datasetProfile') == 'global-growth-b'
            and integer(bundle.get('accountCount'), 1) and bundle['accountCount'] == len(bundle['accounts'])
            and bundle.get('representativeAccountCount') == 3, 'Missing v2 qualified account inventory')
    representatives = representative_rows(bundle['representativeAccounts'])
    representative_ids = {row['memberId']: row for row in representatives}
    selected = {}
    for row in bundle['accounts']:
        require(integer(row['memberId'], 1) and row['memberId'] not in selected and row['status'] == 'ACTIVE'
                and row['role'] in {'MEMBER', 'ADMIN'} and isinstance(row['email'], str),
                'Invalid or duplicate qualification account')
        selected[row['memberId']] = row
    for row in representatives:
        previous = selected.get(row['memberId'])
        require(previous is None or all(previous[key] == row[key] for key in ('email', 'role', 'status')),
                'Qualification and representative identities disagree')
        selected[row['memberId']] = row
    require(len({row['email'] for row in selected.values()}) == len(selected), 'Account emails must be distinct')
    for member, row in selected.items():
        require(member in representative_ids or row['role'] == 'MEMBER' and row['email'].endswith('@example.test'),
                'Only the sealed three representatives may use canonical aliases or ADMIN role')
    require(bundle.get('loadPoolPolicy') == {'state': 'NOT_CONFIGURED', 'targetConcurrentMembers': None,
            'qualificationAccountsAreLoadPool': False, 'oneMemberPerConcurrentWriter': True},
            'The qualification sample must not be declared as an unspecified load pool')
    return [selected[key] for key in sorted(selected)]


def validate_private_accounts(path, release, *, expected_environment=None):
    """Validate private input without importing or executing a dataset's tools."""
    path = regular(path)
    require(stat.S_IMODE(path.stat().st_mode) == 0o600 and not path.parent.is_symlink()
            and stat.S_IMODE(path.parent.stat().st_mode) == 0o700,
            'Private account input needs mode 0600 in a real 0700 directory')
    value, bundle = read(path), read(Path(release) / 'accounts.json')
    accounts = public_account_union(bundle)
    require(value.get('schemaVersion') == 2 and 'password' not in value
            and value.get('datasetProfile') == bundle['datasetProfile']
            and isinstance(value.get('environment'), str) and value['environment'].strip()
            and (expected_environment is None or value['environment'] == expected_environment)
            and value.get('loadPool') == {'state': 'NOT_CONFIGURED', 'targetConcurrentMembers': None, 'memberCount': 0},
            'Private v2 dataset, environment, or load-pool contract differs')
    credentials = value.get('credentials')
    require(isinstance(credentials, list) and len(credentials) == len(accounts), 'Private account coverage differs')
    expected = {row['memberId']: row for row in accounts}
    representatives = {row['memberId']: row for row in bundle['representativeAccounts']}
    ids, passwords = set(), set()
    for row in credentials:
        member, password = row.get('memberId'), row.get('password')
        require(integer(member, 1) and member in expected and member not in ids
                and all(row.get(key) == expected[member][key] for key in ('email', 'role')),
                'Private account identity differs from the sealed public union')
        group = ('administrator' if representatives[member]['key'] == 'admin' else 'representative') if member in representatives else 'qualification'
        require(row.get('group') == group and row.get('environment') == value['environment']
                and isinstance(row.get('purpose'), str) and row['purpose'].strip()
                and isinstance(password, str) and 16 <= len(password.encode()) <= 72 and password not in passwords,
                'Private account purposes, environment, or distinct passwords differ')
        ids.add(member); passwords.add(password)
    return value


def validate_accounts(release, scenario):
    bundle = read(release / 'accounts.json')
    manifest = read(release / 'representative-accounts.json')
    profile, plan = read(release / 'profile.json'), read(release / 'generation-plan.json')
    require(manifest.get('schemaVersion') == 1 and manifest.get('datasetProfile') == 'global-growth-b'
            and manifest.get('selectionScope') == 'actual rows of this generated dataset'
            and manifest.get('baseRows') == {'members': profile['members'], 'accommodations': plan['accommodations'],
                                           'reservations': profile['reservations']}
            and manifest.get('finalScaleSelected') is (profile['listingLimit'] == 0 and profile['reservations'] == 10000000),
            'Representative selection must describe the actual generated dataset')
    representatives = representative_rows(manifest)
    require(bundle['representativeAccounts'] == representatives, 'Representative manifest and account bundle differ')
    accounts = public_account_union(bundle)
    for row in representatives:
        require(isinstance(row.get('originalEmail'), str) and row['originalEmail'].endswith('@example.test')
                and isinstance(row.get('purpose'), str) and row['purpose'].strip(), 'Representative selection provenance is missing')
        metrics = {'demo': ('listings', 'publishedListings', 'reservations', 'reviews', 'wishlists'),
                   'host': ('listings', 'publishedListings', 'receivedReservations', 'receivedReviews', 'settlements'), 'admin': ()}[row['key']]
        require(all(integer(row.get('ownership', {}).get(metric, {}).get('count'), 1) for metric in metrics),
                'Representative account ownership evidence is incomplete')
        if row['key'] == 'demo':
            require(row['ownership']['listings']['count'] <= 100, 'The demonstration account must own a bounded listing set')
    logins = read(release / 'account-login-qualification.json')
    observed = logins['accounts']
    require(len(observed) == len(accounts) and len({row['memberId'] for row in observed}) == len(observed),
            'Login evidence must cover the distinct qualification and representative union')
    expected = {row['memberId']: row for row in accounts}
    reps = {row['memberId']: row for row in representatives}
    environment = logins.get('environment')
    require(isinstance(environment, str) and environment.strip(), 'Actual account login environment is required')
    for row in observed:
        member = row['memberId']
        group = ('administrator' if reps[member]['key'] == 'admin' else 'representative') if member in reps else 'qualification'
        require(member in expected and all(row.get(key) == expected[member][key] for key in ('email', 'role', 'status'))
                and row.get('group') == group and row.get('loginStatus') == 200
                and row.get('authenticatedIdentityMatches') is True and row.get('environment') == environment,
                'Normal login evidence differs from its sealed identity or purpose')
    mapping = {'passed': True, 'accountCount': 3, 'identityRoleAndOwnershipVerified': True, 'emailMutationAfterRestore': False,
               'accounts': [{key: row[key] for key in ('key', 'memberId', 'email', 'role', 'status')} for row in representatives]}
    summary = scenario.get('accountLogins', {})
    require(logins.get('representativeMapping') == mapping and summary.get('representatives') == mapping
            and summary.get('passed') is True and summary.get('accounts') == len(accounts)
            and summary.get('qualificationAccounts') == len(bundle['accounts']) and summary.get('representativeAccounts') == 3
            and summary.get('identityAndLogoutVerified') is True and summary.get('crossCredentialRejected') is True
            and summary.get('environment') == environment,
            'Representative normal-login mapping must survive in the preparation receipt')
    require(logins.get('representatives') == [row for row in observed if row['memberId'] in reps]
            and all(logins.get(key) is True for key in ('passed', 'unauthenticatedRejected', 'invalidPasswordRejected', 'logoutInvalidatesSession'))
            and logins.get('credentialAndSessionValuesRecorded') is False,
            'Account identity, logout, and private-value exclusion evidence is incomplete')
    cross = logins.get('crossCredentialRejected', [])
    required = {row['memberId'] for row in representatives if row['key'] in {'host', 'admin'}}
    seen = set()
    for row in cross:
        member = row['memberId']
        require(member in expected and member not in seen and row.get('demoPasswordRejected') is True
                and row.get('group') == next(item['group'] for item in observed if item['memberId'] == member),
                'Credential separation evidence is invalid')
        seen.add(member)
    require(required <= seen and any(row['group'] == 'qualification' for row in cross),
            'Demo password rejection is required for host, administrator, and qualification accounts')


def inspect_dump(path):
    """Read every gzip member through its CRC/footer, without a plaintext file."""
    path = regular(path)
    content = hashlib.sha256()
    size = 0
    with gzip.open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            content.update(chunk)
            size += len(chunk)
    require(size > 0, 'Empty dump')
    return {'gzipIntegrityPassed': True, 'uncompressedBytes': size,
            'uncompressedSha256': content.hexdigest(), 'compressedBytes': path.stat().st_size,
            'compressedSha256': sha(path), 'plaintextTemporaryBytes': 0}


def validate_fingerprint(fingerprint, *, require_sealed=True):
    require(fingerprint.get('mysqlVersion') == '8.4.11', 'MySQL 8.4.11 fingerprint required')
    tables = fingerprint.get('tables', {})
    require(set(tables) == TABLES, 'The complete V28 table inventory is required')
    for name, info in tables.items():
        require(re.fullmatch(r'[a-z][a-z0-9_]*', name) and integer(info.get('rows')),
                'Invalid table inventory')
        require(all(digest(info.get(key)) for key in ('rowsSha256', 'domainRowsSha256', 'ddlSha256')),
                'Incomplete full-row/DDL fingerprint')
    require(tables['flyway_schema_history']['rows'] == 28 and (not require_sealed or tables['outbox']['rows'] == 0),
            'V28 history and an empty sealed outbox are required')
    return tables


def validate_population(release, manifest, fingerprint, allow_small):
    """Keep exact population checks, deriving base counts before adding the reviewed fixtures."""
    profile = read(release / 'profile.json')
    plan = read(release / 'generation-plan.json')
    boundaries = read(release / 'boundary-fixtures.json')
    runtime = read(release / 'runtime-plan.json')
    provenance = read(release / 'source-provenance.json')
    require(profile.get('version') == 'global-growth-b' and profile.get('queryBoundaries') is True,
            'Global B with reviewed boundary fixtures is required')
    require(plan['profile'] == profile and plan['feasibilityPassed'] is True, 'Generation plan differs from profile')
    require(boundaries['version'] == 'query-boundaries-v1' and boundaries['rowCounts'] == BOUNDARY_ROWS
            and runtime['baseIdentities'] == boundaries, 'Boundary population differs from the reviewed fixture contract')
    shape = tuple(profile[key] for key in ('listingLimit', 'members', 'reservations', 'wishlists'))
    require(all(integer(value) for value in shape), 'Population fields must be nonnegative integers')
    if manifest['finalScaleSelected'] is True:
        require(manifest['datasetScale'] == FINAL_SCALE and shape == (0, 3000000, 10000000, 1500000),
                'The selected B final population is required')
    else:
        require(manifest['finalScaleSelected'] is False and allow_small and
                manifest['datasetScale'] == 'small-qualification' and 0 < shape[0] <= 1000
                and 0 < shape[2] <= 50000, 'Only explicitly allowed bounded small qualification is accepted')
    require(plan['members'] == profile['members'] and integer(plan['accommodations'], 1), 'Plan base population differs')
    require(provenance['selectedListings'] == plan['accommodations'] and
            (plan['accommodations'] == profile['listingLimit'] if profile['listingLimit'] else
             plan['accommodations'] == provenance['validUniqueListings']), 'Source listing population differs')
    counts = plan['counts']
    require(set(counts) == {'CONFIRMED', 'CANCELLED', 'EXPIRED', 'reviews'} and
            all(integer(value) for value in counts.values()) and
            sum(counts[key] for key in ('CONFIRMED', 'CANCELLED', 'EXPIRED')) == profile['reservations'],
            'Planned reservation states do not sum to the selected base population')
    expected = {'accommodation': plan['accommodations'], 'member': profile['members'],
                'reservation': profile['reservations'], 'wishlist': profile['wishlists'], 'review': counts['reviews']}
    tables = fingerprint['tables']
    for name, base in expected.items():
        require(tables[name]['rows'] == base + BOUNDARY_ROWS[name], 'Exact base-plus-boundary row count differs: ' + name)
    for name in ('address', 'occupancy_policy'):
        require(tables[name]['rows'] == tables['accommodation']['rows'], 'Listing companion row count differs')
    for name, rows in read(release / 'integrated-data.json')['rows'].items():
        require(name in tables and integer(rows) and tables[name]['rows'] == rows, 'Integrated table population differs')
    require(manifest['time'] == {'snapshotAsOf': profile['snapshotAsOf'],
            'stayTimeZonePolicy': TIME_ZONE_POLICY, 'cohortReportingTimezone': 'Asia/Seoul',
            'activityCutoffExclusive': profile['activityCutoffExclusive']}, 'Global source-zone time contract differs')


def canonical_sha(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def validate_timezones(release, fingerprint):
    """Read independent temporal evidence; never execute code supplied by a release."""
    proof = read(release / 'timezone-qualification.json')
    require(proof.get('schemaVersion') == 1 and proof.get('kind') == 'airbob-growth-timezone-qualification'
            and proof.get('state') == 'TIMEZONE_RUNTIME_QUALIFIED'
            and all(proof.get(key) is True for key in ('passed', 'allFixedCasesPassed', 'runtimeIdentityPassed', 'selectedZonesPassed'))
            and proof.get('fixedCaseVersion') == 'global-temporal-v1'
            and proof.get('fixedCasesSha256') == TEMPORAL_CASES_SHA256 and proof.get('caseCount') == 18,
            'Current Java/Python timezone qualification is required')
    cases = proof['cases']
    require(len(cases) == 18 and len({case['id'] for case in cases}) == 18 and
            canonical_sha([{key: case[key] for key in ('id', 'kind', 'zone', 'input', 'expected')} for case in cases]) == TEMPORAL_CASES_SHA256,
            'Independent fixed temporal expectations changed')
    require(all(case['javaObserved'] == case['pythonObserved'] == case['expected']
                and all(case[key] is True for key in ('javaPassed', 'pythonPassed', 'passed')) for case in cases),
            'A fixed temporal boundary differs from its independent expected value')
    java = proof['java']
    require(java['javaFeature'] == 21 and re.fullmatch(r'21\.0\.(?:1[2-9]|[2-9][0-9])(?:\..+)?', java['javaVersion'])
            and java['javaRuntimeVersion'] and java['javaVendor'] and digest(java['executableSha256'])
            and digest(java['tzdbFile']['sha256']) and integer(java['tzdbFile']['bytes'], 1),
            'An observed supported Java 21 patch and timezone database are required')
    require(proof['qualificationSourceSha256'] == read(release / 'tool-sources.json')['growth_timezones.py'],
            'Timezone proof was produced by different preparation tools')
    source = read(release / 'source-provenance.json')
    coordinate = source['coordinateTimeZones']
    total = source['validUniqueListings']
    require(integer(total, 1) and all(coordinate[key] == total for key in
            ('eligibleUniqueListings', 'queriedListings', 'resolvedListings', 'assignedZoneMatchesCoordinates'))
            and coordinate['unresolvedCoordinates'] == 0 and integer(coordinate['correctedZoneIds'])
            and coordinate['sourceMarketZoneMatchesCoordinates'] + coordinate['correctedZoneIds'] == total
            and len(coordinate['corrections']) == coordinate['correctedZoneIds'],
            'All eligible source coordinates must have a verified assigned timezone')
    zones = coordinate['resolvedZoneIds']
    require(zones and zones == sorted(set(zones)) and proof['scope']['selectedZones'] == zones,
            'Timezone rule comparison does not cover the actual source coordinate zones')
    require(proof['scope']['startInclusive'] == '2016-01-01T00:00:00Z'
            and proof['scope']['endExclusive'] == '2028-01-01T00:00:00Z' and proof['scope']['intervalSeconds'] == 21600
            and proof['scope']['globalTzdbEquivalenceClaimed'] is False, 'Temporal comparison scope differs')
    compared = proof['selectedZoneComparisons']
    require(len(compared) == len(zones) and {item['zone'] for item in compared} == set(zones)
            and all(item['passed'] is True and item['java'] == item['python']
                    and item['java']['samples'] == 17532 and digest(item['java']['sha256']) for item in compared),
            'Java/Python offsets differ or a source zone was skipped')
    require(all(proof['python']['zoneData'][zone]['version']
                and digest(proof['python']['zoneData'][zone]['sha256']) for zone in zones),
            'Actual Python timezone data identity is required')
    runtimes = [coordinate['runtime']]
    for name in ('validation.json', 'restore-validation.json'):
        observed = read(release / name)['coordinateTimeZones']
        require(observed['queriedAccommodations'] == observed['matchedAccommodations'] == fingerprint['tables']['accommodation']['rows']
                and observed['mismatched'] == observed['unresolved'] == observed['violations'] == 0,
                'Every restored accommodation must match its coordinate timezone')
        runtimes.append(observed['runtime'])
    require(all(item['runtimeRulesPassed'] is True and item['timeShapeVersion'] == '2026b.29'
                and item['allBoundaryZoneIdsSupported'] is True and item['allBoundaryZoneIdsLoaded'] is True
                and item['unsupportedBoundaryZoneIds'] == item['excludedBoundaryZoneIds'] == 0
                and item['boundaryZoneIds'] == item['engineKnownZoneIds'] == 444
                and digest(item['boundaryZoneIdsSha256']) for item in runtimes),
            'TimeShape excluded unsupported or unloaded timezone regions')
    temporal = read(release / 'temporal-data-validation.json')
    expected = {'dumpSha256': read(release / 'dump-integrity.json')['compressedSha256'],
                'reservations': fingerprint['tables']['reservation']['rows'],
                'accommodations': fingerprint['tables']['accommodation']['rows']}
    require(temporal['schemaVersion'] == 1 and temporal['kind'] == 'airbob-growth-temporal-data-validation'
            and temporal['state'] == 'TEMPORAL_DATA_QUALIFIED' and temporal['passed'] is True
            and temporal['expected'] == expected and temporal['dump']['sha256'] == expected['dumpSha256']
            and temporal['dump']['gzipIntegrityPassed'] is True and temporal['dump']['plaintextTemporaryBytes'] == 0
            and temporal['runtimeQualificationCanonicalSha256'] == canonical_sha(proof)
            and temporal['fixedCasesSha256'] == TEMPORAL_CASES_SHA256
            and all(temporal['counts'][key] == expected[key] for key in ('reservations', 'accommodations'))
            and temporal['counts']['impactedReservations'] == 0 and temporal['mismatchCounts']
            and all(type(value) is int and value == 0 for value in temporal['mismatchCounts'].values()),
            'Independent complete-dump temporal validation failed')


def validate_evidence(release, manifest, fingerprint):
    require(fingerprint == read(release / 'final-reset-fingerprint.json'), 'Final reset rows/DDL differ')
    scenario = read(release / 'scenario-qualification.json')
    require(scenario['state'] == 'SMALL_SCENARIOS_QUALIFIED' and scenario['resetVerified'] is True
            and scenario['appJarSha256'] == manifest['appJarSha256'], 'Integrated application identity/evidence differs')
    repeats = scenario['repeats']
    require(len(repeats) == 2 and all(item['runtime']['passed'] is True and item['readModel']['passed'] is True
            and item['reset']['allRowsAndDdlEqual'] is True for item in repeats), 'Two real executions and exact resets required')
    require(repeats[0]['bootstrap']['passed'] is True, 'Inventory bootstrap proof is missing')
    require(all(item['runtime']['currentBooking']['agedQuoteAccepted'] is True and
            item['runtime']['currentBooking']['stalePriceRejected'] is True for item in repeats), 'V28 checkout proof is missing')
    for name in ('cache-qualification.json', 'search-qualification.json'):
        require(read(release / name)['state'] == 'PASSED', 'Cache/search qualification failed')
    for name in ('http-coverage.json', 'image-qualification.json', 'account-login-qualification.json'):
        require(read(release / name)['passed'] is True, 'HTTP/image/login qualification failed')
    validate_accounts(release, scenario)
    disk = read(release / 'measurements.json')['disk']
    require(disk['schemaVersion'] == 2 and disk['samplingComplete'] is True and not disk['errors']
            and disk['maximumObservedDatasetSchemas'] == disk['maximumObservedMysqlContainers'] == 1,
            'Complete single-database disk evidence is required')
    metrics = ('mysqlAllocatedBytes', 'outputAllocatedBytes', 'runOwnedBytes', 'hostFreeBytes',
               'rawTemporaryDumpBytes', 'mysqlCgroupMemoryBytes', 'runnerProcessTreeResidentBytes',
               'auxiliaryAllocatedTablespaceBytes', 'datasetAllocatedTablespaceBytes')
    ready = set()
    for sample in disk['samples']:
        require(sample['valid'] is True and all(integer(sample.get(key)) for key in metrics)
                and sample['rawTemporaryDumpBytes'] == 0, 'Invalid disk sample or plaintext spool')
        if sample['expectedMysqlContainers'] == 1:
            require(sample['mysqlContainers'] == sample['datasetSchemas'] == 1, 'Missing ready database measurement')
            ready.add(sample['stage'])
    require(REQUIRED_STAGES <= ready, 'A required ready stage was not measured')


def validate(release, dataset_id, migration_dir, *, allow_small=False, expected_app_sha=None, verify_gzip=True):
    """Return (consumer manifest, file checksums, complete baseline fingerprint).

    Callers must additionally authenticate consumer/SHA256SUMS bytes through their
    reviewed local plan or immutable publication receipt; internal checks are not a signature.
    """
    release, migration_dir = Path(release), Path(migration_dir)
    require(release.is_dir() and not release.is_symlink(), 'A regular release directory is required')
    checks = read(release / 'SHA256SUMS.json')
    require(22 <= len(checks) <= 128 and set(checks) == {path.name for path in release.iterdir()} - {'SHA256SUMS.json'},
            'Exact release file inventory differs')
    for name, checksum in checks.items():
        require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]+', name) and digest(checksum)
                and sha(release / name) == checksum, 'Release bytes differ: ' + name)
        require('.private.' not in name and not name.endswith('.env'), 'Private artifacts cannot be published in a release')
    manifest = read(release / 'consumer-manifest.json')
    require(manifest['schemaVersion'] == 4 and manifest['datasetVersion'] == 'benchmark-dataset-v4', 'V4 required')
    require(set(manifest) == {'schemaVersion', 'datasetVersion', 'datasetId', 'qualification', 'mysql', 'time',
            'artifacts', 'appJarSha256', 'capabilities', 'finalScaleSelected', 'datasetScale', 'integratedEvidence'}
            and manifest['qualification'] == 'SMALL_LOCAL_SCENARIOS' and manifest['integratedEvidence'] == {
                'validated': True, 'repeatCount': 2, 'quoteContract': 'V28 revalidation without quote expiry'},
            'Unrecognized integrated B consumer contract')
    require(re.fullmatch(DATASET_RE, dataset_id) and manifest['datasetId'] == dataset_id ==
            'global-growth-b-' + checks['airbob-growth.sql.gz'][:16], 'B identity is not bound to its dump')
    require(manifest['mysql'] == {'version': '8.4.11', 'flywayVersion': 28}, 'MySQL 8.4.11 / V28 required')
    require(digest(manifest['appJarSha256']) and (expected_app_sha is None or manifest['appJarSha256'] == expected_app_sha),
            'Qualified application JAR differs')
    require(set(manifest['artifacts']) == set(ARTIFACTS), 'The complete B consumer artifact contract is required')
    for key, name in ARTIFACTS.items():
        require(manifest['artifacts'][key] == {'file': name, 'sha256': checks[name]}, 'Consumer artifact binding differs')
    require(manifest['capabilities'] == {'immutableReads': True, 'runtimePreparation': True, 'searchSnapshot': False,
            'searchRebuildValidated': True, 'cacheHandlerValidated': True,
            'kafkaCdcEndToEndValidated': False, 'awsExecutionValidated': False}, 'Unsupported capability claims')
    root, qualification = read(release / 'manifest.json'), read(release / 'qualification.json')
    require(root['datasetId'] == dataset_id and root['datasetProfile'] == 'global-growth-b'
            and root['schemaVersion'] == 'benchmark-dataset-v4' and root['mysqlVersion'] == '8.4.11'
            # profileSha256 identifies the input serialization; Java serializes profile.json again.
            # Semantic equality to generation-plan is checked below; SHA256SUMS binds its published bytes.
            and root['time'] == manifest['time'] and digest(qualification['profileSha256'])
            and qualification['dumpSha256'] == checks['airbob-growth.sql.gz']
            and all(item['datasetScale'] == manifest['datasetScale'] and item['finalScaleSelected'] is manifest['finalScaleSelected']
                    for item in (root, qualification)), 'Producer profile/release identity differs')
    migrations = read(release / 'migration-files.json')
    require({path.name: sha(path) for path in migration_dir.glob('V*__*.sql')} == migrations
            and sorted(int(re.fullmatch(r'V(\d+)__.+\.sql', name)[1]) for name in migrations) == list(range(1, 29)),
            'Application migration bytes differ from V28')
    fingerprint = read(release / 'before-fingerprint.json')
    validate_fingerprint(fingerprint)
    validate_population(release, manifest, fingerprint, allow_small)
    validate_evidence(release, manifest, fingerprint)
    validate_timezones(release, fingerprint)
    for name in ('validation.json', 'restore-validation.json'):
        evidence = read(release / name)
        require(evidence.get('passed') is True and evidence.get('checkedRows') == 'full-dataset'
                and isinstance(evidence.get('violations'), dict) and evidence['violations']
                and all(type(count) is int and count == 0 for count in evidence['violations'].values()),
                'Full dataset integrity validation failed')
    integrity = read(release / 'dump-integrity.json')
    require(integrity['gzipIntegrityPassed'] is True and integrity['plaintextTemporaryBytes'] == 0
            and integrity['compressedSha256'] == checks['airbob-growth.sql.gz'], 'Dump preservation evidence differs')
    if verify_gzip:
        observed = inspect_dump(release / 'airbob-growth.sql.gz')
        require(all(integrity[key] == value for key, value in observed.items()), 'Dump CRC/content differs from sealed evidence')
    return manifest, checks, fingerprint


def extract_runtime(release, destination):
    """Extract the checksum-verified bundle with bounded, regular members only."""
    release, destination = Path(release), Path(destination)
    require(not destination.exists(), 'Runtime extraction target already exists')
    require(sha(release / 'preparation-tools.tar.gz') == read(release / 'consumer-manifest.json')['artifacts']['tools']['sha256'],
            'Runtime bundle identity differs')
    with tarfile.open(release / 'preparation-tools.tar.gz') as archive:
        entries = archive.getmembers()
        require(0 < len(entries) <= 512 and sum(entry.size for entry in entries) <= 256 * 1024**2, 'Runtime archive exceeds bounds')
        names = set()
        for entry in entries:
            path = PurePosixPath(entry.name)
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in entry.name and
                    str(path) == entry.name.rstrip('/') and entry.name not in names and
                    (entry.isdir() or entry.isfile()), 'Unsafe runtime archive member')
            names.add(entry.name)
        destination.mkdir(mode=0o700)
        for entry in entries:
            target = destination / entry.name
            if entry.isdir():
                target.mkdir(mode=0o700, parents=True, exist_ok=True)
            else:
                target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                with archive.extractfile(entry) as source, target.open('xb') as output:
                    for chunk in iter(lambda: source.read(1024 * 1024), b''):
                        output.write(chunk)
                target.chmod(0o700 if entry.mode & 0o111 else 0o600)
    require({path.name: sha(path) for path in (destination / 'runtime/lib').glob('*.jar')} == read(release / 'etl-binaries.json'),
            'Bundled verifier binary differs')
    sources = read(release / 'tool-sources.json')
    for path in (destination / 'tools').iterdir():
        if path.is_file():
            require(sources.get(path.name) == sha(path), 'Bundled Python source differs: ' + path.name)
    return destination
