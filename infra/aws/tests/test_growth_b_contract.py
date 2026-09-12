"""Contract rejection tests, independent of Docker and downloaded dataset files."""
import copy
import gzip
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_contract as contract


def timezone_fixture():
    """Synthetic receipt shape; literal expectations are separately versioned test data."""
    cases = json.loads((ROOT/'infra/aws/tests/fixtures/global-temporal-v1-cases.json').read_text())
    for case in cases:
        case.update(javaObserved=copy.deepcopy(case['expected']), pythonObserved=copy.deepcopy(case['expected']),
                    javaPassed=True, pythonPassed=True, passed=True)
    return {'schemaVersion': 1, 'kind': 'airbob-growth-timezone-qualification', 'state': 'TIMEZONE_RUNTIME_QUALIFIED',
        'passed': True, 'fixedCaseVersion': 'global-temporal-v1', 'fixedCasesSha256': contract.TEMPORAL_CASES_SHA256,
        'caseCount': 18, 'cases': cases, 'allFixedCasesPassed': True, 'runtimeIdentityPassed': True, 'selectedZonesPassed': True,
        'qualificationSourceSha256': 'd'*64,
        'java': {'javaFeature': 21, 'javaVersion': '21.0.12.1', 'javaRuntimeVersion': '21.0.12.1+1-LTS',
                 'javaVendor': 'synthetic contract fixture', 'executableSha256': 'e'*64, 'tzdbFile': {'sha256': 'f'*64, 'bytes': 1}},
        'python': {'zoneData': {'Asia/Seoul': {'version': '2026c', 'sha256': 'a'*64}}},
        'scope': {'selectedZones': ['Asia/Seoul'], 'startInclusive': '2016-01-01T00:00:00Z',
                  'endExclusive': '2028-01-01T00:00:00Z', 'intervalSeconds': 21600, 'globalTzdbEquivalenceClaimed': False},
        'selectedZoneComparisons': [{'zone': 'Asia/Seoul', 'passed': True,
            'java': {'samples': 17532, 'sha256': 'a'*64}, 'python': {'samples': 17532, 'sha256': 'a'*64}}]}


def coordinate_runtime_fixture():
    return {'runtimeRulesPassed': True, 'timeShapeVersion': '2026b.29',
        'allBoundaryZoneIdsSupported': True, 'allBoundaryZoneIdsLoaded': True,
        'unsupportedBoundaryZoneIds': 0, 'excludedBoundaryZoneIds': 0,
        'boundaryZoneIds': 444, 'engineKnownZoneIds': 444, 'boundaryZoneIdsSha256': 'b'*64}


def account_fixture():
    sample = {'memberId': 1, 'email': 'test@example.test', 'nickname': 'Fixture sample', 'role': 'MEMBER', 'status': 'ACTIVE'}
    representatives = []
    for member, key in enumerate(('demo', 'host', 'admin'), 2):
        metrics = {'demo': ('listings', 'publishedListings', 'reservations', 'reviews', 'wishlists'),
                   'host': ('listings', 'publishedListings', 'receivedReservations', 'receivedReviews', 'settlements'), 'admin': ()}[key]
        email, role = contract.REPRESENTATIVES[key]
        representatives.append({'memberId': member, 'key': key, 'email': email, 'role': role, 'status': 'ACTIVE',
            'nickname': 'Fixture ' + key, 'originalEmail': key + '@example.test', 'purpose': 'Synthetic contract fixture ' + key,
            'ownership': {metric: {'count': 1} for metric in metrics}})
    manifest = {'schemaVersion': 1, 'datasetProfile': 'global-growth-b',
                'selectionScope': 'actual rows of this generated dataset', 'accounts': representatives,
                'baseRows': {'members': 2, 'accommodations': 1, 'reservations': 2}, 'finalScaleSelected': False}
    bundle = {'schemaVersion': 2, 'datasetProfile': 'global-growth-b', 'accountCount': 1, 'accounts': [sample],
              'representativeAccountCount': 3, 'representativeAccounts': representatives,
              'loadPoolPolicy': {'state': 'NOT_CONFIGURED', 'targetConcurrentMembers': None,
                                'qualificationAccountsAreLoadPool': False, 'oneMemberPerConcurrentWriter': True}}
    environment = 'contract-test:synthetic-only'
    results = [row | {'group': ('administrator' if row['key'] == 'admin' else 'representative') if 'key' in row else 'qualification',
                     'loginStatus': 200, 'authenticatedIdentityMatches': True, 'environment': environment}
               for row in [sample] + representatives]
    mapping = {'passed': True, 'accountCount': 3, 'identityRoleAndOwnershipVerified': True, 'emailMutationAfterRestore': False,
               'accounts': [{key: row[key] for key in ('key', 'memberId', 'email', 'role', 'status')} for row in representatives]}
    report = {'passed': True, 'accounts': results, 'representatives': results[1:], 'representativeMapping': mapping,
              'environment': environment, 'unauthenticatedRejected': True, 'invalidPasswordRejected': True,
              'logoutInvalidatesSession': True, 'credentialAndSessionValuesRecorded': False,
              'crossCredentialRejected': [{'memberId': row['memberId'], 'group': row['group'], 'demoPasswordRejected': True}
                                          for row in results if row['memberId'] != 2]}
    summary = {'passed': True, 'accounts': 4, 'qualificationAccounts': 1, 'representativeAccounts': 3,
               'identityAndLogoutVerified': True, 'crossCredentialRejected': True, 'environment': environment, 'representatives': mapping}
    return bundle, manifest, report, summary


def private_account_fixture(bundle, environment):
    representatives = {row['memberId']: row for row in bundle['representativeAccounts']}
    credentials = []
    for row in contract.public_account_union(bundle):
        representative = representatives.get(row['memberId'])
        group = ('administrator' if representative['key'] == 'admin' else 'representative') if representative else 'qualification'
        credentials.append({key: row[key] for key in ('memberId', 'email', 'role')} | {
            'group': group, 'purpose': 'Synthetic private contract fixture', 'environment': environment,
            'password': 'unit-fixture-value-only-' + str(row['memberId']), 'loginState': 'NOT_VERIFIED', 'usable': False})
    return {'schemaVersion': 2, 'datasetProfile': bundle['datasetProfile'], 'environment': environment,
            'credentials': credentials, 'loadPool': {'state': 'NOT_CONFIGURED', 'targetConcurrentMembers': None, 'memberCount': 0}}


class ReleaseFixture:
    def __init__(self, directory):
        self.path = Path(directory)
        with gzip.open(self.path / 'airbob-growth.sql.gz', 'wb') as stream:
            stream.write(b'-- synthetic contract-only SQL\n')
        profile = {'version': 'global-growth-b', 'queryBoundaries': True, 'listingLimit': 1,
                   'members': 2, 'reservations': 2, 'wishlists': 1, 'snapshotAsOf': '2026-08-01T00:00:00+09:00',
                   'activityCutoffExclusive': '2026-04-01T00:00:00+09:00'}
        tables = {name: dict(rows=0, ddlSha256='a'*64, rowsSha256='b'*64, domainRowsSha256='c'*64) for name in contract.TABLES}
        for name, base in [('accommodation', 1), ('member', 2), ('reservation', 2), ('wishlist', 1), ('review', 1)]:
            tables[name]['rows'] = base + contract.BOUNDARY_ROWS[name]
        for name in ('address', 'occupancy_policy'):
            tables[name]['rows'] = tables['accommodation']['rows']
        tables['flyway_schema_history']['rows'] = 28
        fp = {'mysqlVersion': '8.4.11', 'tables': tables}
        boundary = {'version': 'query-boundaries-v1', 'rowCounts': contract.BOUNDARY_ROWS}
        repeat = {'runtime': {'passed': True, 'currentBooking': {'agedQuoteAccepted': True, 'stalePriceRejected': True}},
                  'readModel': {'passed': True}, 'reset': {'allRowsAndDdlEqual': True}, 'bootstrap': {'passed': True}}
        bundle, representatives, logins, login_summary = account_fixture()
        samples = [dict(stage=stage, valid=True, expectedMysqlContainers=1, mysqlContainers=1, datasetSchemas=1,
                    mysqlAllocatedBytes=1, outputAllocatedBytes=1, runOwnedBytes=2, hostFreeBytes=100,
                    rawTemporaryDumpBytes=0, mysqlCgroupMemoryBytes=1, runnerProcessTreeResidentBytes=1,
                    auxiliaryAllocatedTablespaceBytes=0, datasetAllocatedTablespaceBytes=1)
                   for stage in contract.REQUIRED_STAGES]
        self.values = {name: {} for name in contract.ARTIFACTS.values() if name not in ('airbob-growth.sql.gz', 'preparation-tools.tar.gz')}
        self.values.update({'profile.json': profile, 'generation-plan.json': {'profile': profile, 'feasibilityPassed': True,
                'accommodations': 1, 'members': 2, 'counts': {'CONFIRMED': 2, 'CANCELLED': 0, 'EXPIRED': 0, 'reviews': 1}},
            'boundary-fixtures.json': boundary, 'runtime-plan.json': {'baseIdentities': boundary},
            'source-provenance.json': {'selectedListings': 1, 'validUniqueListings': 1, 'coordinateTimeZones': {
                'eligibleUniqueListings': 1, 'queriedListings': 1, 'resolvedListings': 1, 'assignedZoneMatchesCoordinates': 1,
                'unresolvedCoordinates': 0, 'correctedZoneIds': 0, 'sourceMarketZoneMatchesCoordinates': 1,
                'corrections': [], 'resolvedZoneIds': ['Asia/Seoul'], 'runtime': coordinate_runtime_fixture()}},
            'integrated-data.json': {'rows': {'member_history': 0}},
            'before-fingerprint.json': fp, 'final-reset-fingerprint.json': copy.deepcopy(fp),
            'scenario-qualification.json': {'state': 'SMALL_SCENARIOS_QUALIFIED', 'resetVerified': True,
                    'appJarSha256': 'e'*64, 'repeats': [copy.deepcopy(repeat), copy.deepcopy(repeat)], 'accountLogins': login_summary},
            'accounts.json': bundle, 'representative-accounts.json': representatives,
            'account-login-qualification.json': logins,
            'cache-qualification.json': {'state': 'PASSED'}, 'search-qualification.json': {'state': 'PASSED'},
            'http-coverage.json': {'passed': True}, 'image-qualification.json': {'passed': True},
            'dump-integrity.json': contract.inspect_dump(self.path / 'airbob-growth.sql.gz'),
            'measurements.json': {'disk': {'schemaVersion': 2, 'samplingComplete': True, 'errors': [],
                    'maximumObservedDatasetSchemas': 1, 'maximumObservedMysqlContainers': 1, 'samples': samples}},
            'migration-files.json': {p.name: contract.sha(p) for p in (ROOT / 'src/main/resources/db/migration').glob('V*__*.sql')},
            'validation.json': {'passed': True, 'checkedRows': 'full-dataset', 'violations': {'ownership': 0}},
            'restore-validation.json': {'passed': True, 'checkedRows': 'full-dataset', 'violations': {'ownership': 0}}})
        self.values['timezone-qualification.json'] = timezone_fixture()
        self.values['tool-sources.json'] = {'growth_timezones.py': 'd'*64}
        for name in ('validation.json', 'restore-validation.json'):
            self.values[name]['coordinateTimeZones'] = {'queriedAccommodations': tables['accommodation']['rows'],
                'matchedAccommodations': tables['accommodation']['rows'], 'mismatched': 0, 'unresolved': 0,
                'violations': 0, 'runtime': coordinate_runtime_fixture()}
        self.values['temporal-data-validation.json'] = {'schemaVersion': 1, 'kind': 'airbob-growth-temporal-data-validation',
            'state': 'TEMPORAL_DATA_QUALIFIED', 'passed': True, 'fixedCasesSha256': contract.TEMPORAL_CASES_SHA256,
            'dump': {'sha256': contract.sha(self.path/'airbob-growth.sql.gz'), 'gzipIntegrityPassed': True, 'plaintextTemporaryBytes': 0},
            'expected': {'dumpSha256': contract.sha(self.path/'airbob-growth.sql.gz'),
                'reservations': tables['reservation']['rows'], 'accommodations': tables['accommodation']['rows']},
            'runtimeQualificationCanonicalSha256': contract.canonical_sha(self.values['timezone-qualification.json']),
            'counts': {'reservations': tables['reservation']['rows'], 'accommodations': tables['accommodation']['rows'], 'impactedReservations': 0},
            'mismatchCounts': {'checkInUtc': 0, 'checkOutUtc': 0}}
        (self.path / 'preparation-tools.tar.gz').write_bytes(b'not extracted in contract validation')
        self.manifest = dict(schemaVersion=4, datasetVersion='benchmark-dataset-v4',
            datasetId='global-growth-b-' + contract.sha(self.path / 'airbob-growth.sql.gz')[:16],
            qualification='SMALL_LOCAL_SCENARIOS', mysql={'version': '8.4.11', 'flywayVersion': 28},
            time={'snapshotAsOf': profile['snapshotAsOf'], 'activityCutoffExclusive': profile['activityCutoffExclusive'],
                  'stayTimeZonePolicy': contract.TIME_ZONE_POLICY, 'cohortReportingTimezone': 'Asia/Seoul'},
            appJarSha256='e'*64, finalScaleSelected=False, datasetScale='small-qualification',
            capabilities={'immutableReads': True, 'runtimePreparation': True, 'searchSnapshot': False,
                'searchRebuildValidated': True, 'cacheHandlerValidated': True, 'kafkaCdcEndToEndValidated': False, 'awsExecutionValidated': False},
            integratedEvidence={'validated': True, 'repeatCount': 2, 'quoteContract': 'V28 revalidation without quote expiry'})
        self.seal()

    def seal(self):
        self.values['manifest.json'] = {key: self.manifest[key] for key in ('datasetId', 'time', 'datasetScale', 'finalScaleSelected')}
        self.values['manifest.json'].update(datasetProfile='global-growth-b', schemaVersion='benchmark-dataset-v4', mysqlVersion='8.4.11')
        for name, value in self.values.items():
            (self.path / name).write_text(json.dumps(value))
        qualification = {'profileSha256': contract.sha(self.path/'profile.json'), 'dumpSha256': contract.sha(self.path/'airbob-growth.sql.gz'),
                         'datasetScale': self.manifest['datasetScale'], 'finalScaleSelected': self.manifest['finalScaleSelected']}
        (self.path/'qualification.json').write_text(json.dumps(qualification))
        self.manifest['artifacts'] = {key: {'file': name, 'sha256': contract.sha(self.path/name)} for key, name in contract.ARTIFACTS.items()}
        (self.path/'consumer-manifest.json').write_text(json.dumps(self.manifest))
        checks = {p.name: contract.sha(p) for p in self.path.iterdir() if p.name != 'SHA256SUMS.json'}
        (self.path/'SHA256SUMS.json').write_text(json.dumps(checks))


class ContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.fixture = ReleaseFixture(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def validate(self, **kwargs):
        return contract.validate(self.fixture.path, self.fixture.manifest['datasetId'], ROOT/'src/main/resources/db/migration', **kwargs)

    def test_bounded_small_requires_explicit_opt_in(self):
        with self.assertRaises(ValueError): self.validate()
        self.assertEqual(self.validate(allow_small=True)[0]['schemaVersion'], 4)

    def test_profile_does_not_authorize_arbitrary_final_rows(self):
        self.fixture.manifest.update(finalScaleSelected=True, datasetScale=contract.FINAL_SCALE)
        self.fixture.seal()
        with self.assertRaisesRegex(ValueError, 'selected B final'): self.validate()

    def test_semantic_corruption_is_rejected_even_after_resealing_files(self):
        cases = [
            ('base row', lambda f: f.values['before-fingerprint.json']['tables']['reservation'].update(rows=271)),
            ('plan population', lambda f: f.values['generation-plan.json']['counts'].update(CONFIRMED=3)),
            ('fixture inflation', lambda f: f.values['boundary-fixtures.json']['rowCounts'].update(member=132)),
            ('reset drift', lambda f: f.values['final-reset-fingerprint.json']['tables']['member'].update(rowsSha256='f'*64)),
            ('no real login', lambda f: f.values['account-login-qualification.json'].update(accounts=[])),
            ('missing representative login', lambda f: f.values['account-login-qualification.json']['accounts'].pop()),
            ('wrong canonical identity', lambda f: f.values['representative-accounts.json']['accounts'][0].update(memberId=50)),
            ('wrong selection dataset', lambda f: f.values['representative-accounts.json']['baseRows'].update(reservations=300000)),
            ('wrong representative role', lambda f: f.values['representative-accounts.json']['accounts'][0].update(role='ADMIN')),
            ('missing ownership', lambda f: f.values['representative-accounts.json']['accounts'][0]['ownership']['reviews'].update(count=0)),
            ('qualification used as load pool', lambda f: f.values['accounts.json']['loadPoolPolicy'].update(targetConcurrentMembers=111)),
            ('missing source mapping', lambda f: f.values['scenario-qualification.json']['accountLogins'].pop('representatives')),
            ('shared password accepted', lambda f: f.values['account-login-qualification.json']['crossCredentialRejected'][0].update(demoPasswordRejected=False)),
            ('invalid image', lambda f: f.values['image-qualification.json'].update(passed=False)),
            ('disk error', lambda f: f.values['measurements.json']['disk']['errors'].append({'errorType': 'TimeoutExpired'})),
            ('missing ready stage', lambda f: f.values['measurements.json']['disk']['samples'].pop()),
            ('unknown table', lambda f: f.values['before-fingerprint.json']['tables'].update(injected={})),
            ('false full validation', lambda f: f.values['validation.json']['violations'].update(ownership=1)),
            ('old timezone proof', lambda f: f.values['timezone-qualification.json'].update(passed=False)),
            ('wrong future expectation', lambda f: f.values['timezone-qualification.json']['cases'][0]['expected'].update(instant='2026-11-15T23:00:00Z')),
            ('coordinate disagreement', lambda f: f.values['source-provenance.json']['coordinateTimeZones'].update(assignedZoneMatchesCoordinates=0)),
            ('excluded region', lambda f: f.values['source-provenance.json']['coordinateTimeZones']['runtime'].update(excludedBoundaryZoneIds=1)),
            ('missing source zone', lambda f: f.values['timezone-qualification.json'].update(selectedZoneComparisons=[])),
            ('wrong independent dump time', lambda f: f.values['temporal-data-validation.json']['counts'].update(impactedReservations=1)),
        ]
        for label, mutate in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                fixture = ReleaseFixture(directory)
                # BOUNDARY_ROWS is immutable protocol data, not an editable fixture alias.
                fixture.values = copy.deepcopy(fixture.values)
                mutate(fixture)
                fixture.seal()
                with self.assertRaises((ValueError, KeyError)):
                    contract.validate(fixture.path, fixture.manifest['datasetId'], ROOT/'src/main/resources/db/migration', allow_small=True)

    def test_truncated_gzip_rejected(self):
        path = self.fixture.path/'airbob-growth.sql.gz'
        path.write_bytes(path.read_bytes()[:-5])
        with self.assertRaises((EOFError, OSError)): contract.inspect_dump(path)

    def test_duplicate_json_and_symlink_rejected(self):
        path = self.fixture.path/'duplicate.json'
        path.write_text('{"x":1,"x":2}')
        with self.assertRaises(ValueError): contract.read(path)
        link = self.fixture.path/'alias.json'
        link.symlink_to(path)
        with self.assertRaises(ValueError): contract.sha(link)

    def test_changed_artifact_and_app_rejected(self):
        with self.assertRaisesRegex(ValueError, 'application JAR'):
            self.validate(allow_small=True, expected_app_sha='0'*64)
        (self.fixture.path/'accounts.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'Release bytes'): self.validate(allow_small=True)


class PrivateAccountTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.release = self.root / 'release'; self.release.mkdir()
        self.bundle = account_fixture()[0]
        (self.release / 'accounts.json').write_text(json.dumps(self.bundle))
        self.environment = 'local:fixture:dataset'
        self.value = private_account_fixture(self.bundle, self.environment)
        self.private = self.root / 'private'; self.private.mkdir(mode=0o700)
        self.path = self.private / 'accounts.private.json'

    def validate(self):
        self.path.write_text(json.dumps(self.value)); self.path.chmod(0o600)
        return contract.validate_private_accounts(self.path, self.release, expected_environment=self.environment)

    def test_distinct_union_and_environment_are_accepted(self):
        self.assertEqual(len(self.validate()['credentials']), 4)

    def test_public_sample_can_overlap_representatives_without_duplicating_login(self):
        self.bundle['accounts'].append(self.bundle['representativeAccounts'][0])
        self.bundle['accountCount'] = 2
        self.assertEqual(len(contract.public_account_union(self.bundle)), 4)
        self.bundle['accounts'].append(self.bundle['accounts'][0]); self.bundle['accountCount'] = 3
        with self.assertRaises(ValueError): contract.public_account_union(self.bundle)

    def test_private_mismatch_rejected_before_db_access(self):
        cases = [lambda v: v.update(password='legacy-shared-value'),
                 lambda v: v['credentials'][1].update(password=v['credentials'][0]['password']),
                 lambda v: v['credentials'][1].update(memberId=90),
                 lambda v: v['credentials'][1].update(role='ADMIN'),
                 lambda v: v['credentials'][1].update(group='qualification'),
                 lambda v: v['credentials'].pop(), lambda v: v.update(environment='another-target'),
                 lambda v: v['credentials'][1].update(environment='another-target'),
                 lambda v: v['loadPool'].update(targetConcurrentMembers=111)]
        initial = copy.deepcopy(self.value)
        for mutate in cases:
            self.value = copy.deepcopy(initial); mutate(self.value)
            with self.subTest(change=cases.index(mutate)), self.assertRaises(ValueError): self.validate()

    def test_shared_file_or_directory_and_symlink_are_rejected(self):
        self.validate()
        self.path.chmod(0o644)
        with self.assertRaises(ValueError): contract.validate_private_accounts(self.path, self.release)
        self.path.chmod(0o600); self.private.chmod(0o755)
        with self.assertRaises(ValueError): contract.validate_private_accounts(self.path, self.release)
        self.private.chmod(0o700)
        alias = self.private / 'alias.json'; alias.symlink_to(self.path)
        with self.assertRaises(ValueError): contract.validate_private_accounts(alias, self.release)


if __name__ == '__main__':
    unittest.main()
