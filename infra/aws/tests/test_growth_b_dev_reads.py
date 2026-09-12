"""Offline HTTP boundary tests. These tests never open a socket or run Docker."""
import argparse
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('growth_b_dev_reads', ROOT / 'scripts/verify-growth-b-dev.py')
dev = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(dev)


def write_json(path, value, mode=0o600):
    path.write_text(json.dumps(value))
    path.chmod(mode)


def fixture(directory):
    release = directory / 'release'
    release.mkdir()
    private = directory / 'private'
    private.mkdir(mode=0o700)
    dump = 'a' * 64
    dataset = 'global-growth-b-' + dump[:16]
    environment = 'local:mysql:' + dataset
    rows = []
    credentials = []
    for index, (key, (email, role, group)) in enumerate(dev.KINDS.items(), 1):
        row = {'key': key, 'memberId': index, 'email': email, 'nickname': 'SECRET-NICKNAME-' + key,
               'role': role, 'status': 'ACTIVE', 'ownership': {}}
        if key != 'admin':
            for metric, ids in {'publishedListings': [index * 100], 'reservations': [index * 10 + 1],
                               'reviews': [index * 10 + 2], 'receivedReservations': [index * 10 + 3]}.items():
                row['ownership'][metric] = {'count': len(ids), 'sampleIds': ids}
        rows.append(row)
        credentials.append({'memberId': index, 'email': email, 'password': 'SECRET-UNIQUE-PASSWORD-' + key,
                            'role': role, 'group': group, 'environment': environment,
                            'usable': False, 'loginState': 'NOT_VERIFIED'})
    selection = {'schemaVersion': 1, 'datasetProfile': 'global-growth-b', 'finalScaleSelected': True,
                 'baseRows': {'members': 3000000, 'accommodations': 730501, 'reservations': 10000000},
                 'selectionScope': 'actual rows of this generated dataset', 'accounts': rows}
    accounts = {'schemaVersion': 2, 'datasetProfile': 'global-growth-b', 'representativeAccountCount': 3,
                'representativeAccounts': rows}
    login = {'passed': True, 'credentialAndSessionValuesRecorded': False,
             'representatives': [{**{field: row[field] for field in ('memberId', 'email', 'role', 'status')},
                                  'loginStatus': 200, 'authenticatedIdentityMatches': True} for row in rows]}
    root = {'state': 'DATASET_DB_AND_HTTP_QUALIFIED', 'datasetId': dataset,
            'datasetProfile': 'global-growth-b', 'finalScaleSelected': True,
            'datasetScale': 'selected-global-b-ten-million'}
    for filename, data in [('manifest.json', root), ('accounts.json', accounts),
                           ('representative-accounts.json', selection), ('account-login-qualification.json', login)]:
        write_json(release / filename, data)
    checks = {name: dev.hash_bytes((release / name).read_bytes()) for name in dev.METADATA.values()}
    checks['airbob-growth.sql.gz'] = dump  # Deliberately no dump file: metadata-only scope.
    consumer = {'schemaVersion': 4, 'datasetVersion': 'benchmark-dataset-v4', 'datasetId': dataset,
                'mysql': {'version': '8.4.11', 'flywayVersion': 28}, 'finalScaleSelected': True,
                'datasetScale': 'selected-global-b-ten-million', 'qualification': 'SMALL_LOCAL_SCENARIOS',
                'appJarSha256': 'b' * 64, 'artifacts': {}}
    for key, artifact in dev.ARTIFACT_KEYS.items():
        name = dev.METADATA[key]
        consumer['artifacts'][artifact] = {'file': name, 'sha256': checks[name]}
    write_json(release / 'consumer-manifest.json', consumer)
    checks['consumer-manifest.json'] = dev.hash_bytes((release / 'consumer-manifest.json').read_bytes())
    write_json(release / 'SHA256SUMS.json', checks)
    private_file = private / 'representative-accounts.private.json'
    write_json(private_file, {'schemaVersion': 2, 'datasetProfile': 'global-growth-b',
                             'environment': environment, 'credentials': credentials})
    args = argparse.Namespace(release=str(release), consumer_manifest_sha256=checks['consumer-manifest.json'],
                              checksums_sha256=dev.hash_bytes((release / 'SHA256SUMS.json').read_bytes()),
                              private_representatives=str(private_file), account_environment=environment,
                              base_url='http://localhost:8080', output=str(directory / 'output'), timeout=30)
    return args


def reseal(args, filename, mutate):
    """Change fixture metadata and both explicit anchors to exercise semantics."""
    release = Path(args.release)
    data = json.loads((release / filename).read_text())
    mutate(data)
    write_json(release / filename, data)
    checks = json.loads((release / 'SHA256SUMS.json').read_text())
    checks[filename] = dev.hash_bytes((release / filename).read_bytes())
    consumer = json.loads((release / 'consumer-manifest.json').read_text())
    for item in consumer['artifacts'].values():
        if item['file'] == filename:
            item['sha256'] = checks[filename]
    if filename != 'consumer-manifest.json':
        write_json(release / 'consumer-manifest.json', consumer)
    args.consumer_manifest_sha256 = dev.hash_bytes((release / 'consumer-manifest.json').read_bytes())
    checks['consumer-manifest.json'] = args.consumer_manifest_sha256
    write_json(release / 'SHA256SUMS.json', checks)
    args.checksums_sha256 = dev.hash_bytes((release / 'SHA256SUMS.json').read_bytes())


def page(field, rows, cursor=None):
    return {field: rows, 'page_info': {'has_next': cursor is not None, 'next_cursor': cursor,
                                      'current_size': len(rows)}}


def uid(number):
    return f'00000000-0000-4000-8000-{number:012d}'


class FakeServer:
    def __init__(self, inputs, mutate=None):
        self.inputs, self.mutate = inputs, mutate
        self.calls, self.sessions = [], []

    def __call__(self, base, timeout):
        session = FakeSession(self)
        self.sessions.append(session)
        return session


class FakeSession:
    def __init__(self, server):
        self.server, self.key, self.logged_in, self.cookie, self.cleared = server, None, False, False, False

    def has_session(self):
        return self.cookie

    def clear(self):
        self.cleared = True
        self.cookie = False

    def request(self, method, path, query=None, body=None):
        dev.request_template(method, path, query, body)
        if method == 'POST' and path.endswith('/login'):
            self.key = next(key for key, row in self.server.inputs['credentials'].items() if row['email'] == body['email'])
            assert body['password'] == self.server.inputs['credentials'][self.key]['password']
            self.logged_in, self.cookie = True, True
            result = (200, {'success': True})
        elif method == 'POST' and path.endswith('/logout'):
            self.logged_in = False
            result = (200, {'success': True})
        elif path == dev.AUTH + 'me':
            result = (200, {'success': True, 'data': {field: self.server.inputs['selected'][self.key][field]
                      for field in ('email', 'nickname')}}) if self.logged_in else (401, {'success': False})
            if self.logged_in:
                result[1]['data']['id'] = self.server.inputs['selected'][self.key]['memberId']
        elif path == dev.ADMIN:
            result = (200, {'success': True, 'data': {'items': [], 'has_more': False}}) if self.logged_in and self.key == 'admin' else (403 if self.logged_in else 401, {'success': False})
        else:
            member = self.server.inputs['selected'][self.key]['memberId']
            listing = member * 100
            if path == dev.SEARCH:
                ident = 901 if query['topLeftLng'] == 124 else 902
                country = 'South Korea' if ident == 901 else 'France'
                data = {'stay_search_result_listing': [{'id': ident, 'address_summary': {'country': country}}]}
            elif path == '/api/v1/profile/guest/reservations':
                data = page('reservations', [{'reservation_id': member * 10 + 1, 'reservation_uid': uid(member),
                                              'accommodation': {'id': listing + 1}}])
            elif path.startswith('/api/v1/profile/guest/reservations/'):
                data = {'reservation_uid': uid(member), 'accommodation': {'id': listing + 1}}
            elif path == '/api/v1/profile/host/reservations':
                data = page('reservations', [{'reservation_uid': uid(member + 10), 'accommodation': {'id': listing}}])
            elif path.startswith('/api/v1/profile/host/reservations/'):
                data = {'reservation_uid': uid(member + 10), 'accommodation': {'id': listing}}
            elif path == '/api/v1/profile/host/accommodations':
                data = page('accommodations', [{'id': listing}])
            elif path.startswith('/api/v1/profile/host/accommodations/'):
                data = {'id': int(path.rsplit('/', 1)[-1])}
            elif path.endswith('/reviews'):
                data = page('reviews', [{'id': member * 10 + 2, 'reviewer': {'id': member},
                                        'content': 'SECRET-REVIEW-CONTENT'}])
            elif path.startswith('/api/v1/accommodations/'):
                ident = int(path.rsplit('/', 1)[-1])
                data = {'id': ident, 'host': {'id': member}, 'description': 'SECRET-DESCRIPTION',
                        'address_summary': {'country': 'South Korea' if ident == 901 else 'France'}}
            else:
                raise AssertionError('unimplemented fake route')
            result = (200, {'success': True, 'data': data})
        self.server.calls.append((self.key, method, path, query))
        if self.server.mutate:
            replacement = self.server.mutate(self, method, path, query, result)
            if replacement is not None:
                result = replacement
        return result


class DevReadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.args = fixture(self.directory)

    def tearDown(self):
        self.temp.cleanup()

    def inputs(self):
        return dev.read_inputs(self.args)

    def execute(self, mutate=None):
        server = FakeServer(self.inputs(), mutate)
        result = dev.run(self.args, session_factory=server)
        return server, result

    def private_change(self, mutate):
        path = Path(self.args.private_representatives)
        data = json.loads(path.read_text())
        mutate(data)
        write_json(path, data)

    def test_three_accounts_success_and_no_private_serialization(self):
        before = Path(self.args.private_representatives).read_bytes()
        server, (evidence, path, checksum) = self.execute()
        self.assertEqual(dev.STATE, evidence['state'])
        self.assertTrue(evidence['passed'])
        self.assertEqual(checksum, dev.hash_bytes(path.read_bytes()))
        self.assertEqual(3, len(server.sessions))
        self.assertTrue(all(session.cleared and not session.logged_in for session in server.sessions))
        self.assertEqual(3, sum(method == 'POST' and route.endswith('/login') for _, method, route, _ in server.calls))
        self.assertEqual(3, sum(method == 'POST' and route.endswith('/logout') for _, method, route, _ in server.calls))
        self.assertEqual(before, Path(self.args.private_representatives).read_bytes())
        self.assertEqual(0o700, stat.S_IMODE(path.parent.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
        text = path.read_text()
        for forbidden in ('SECRET-', '@airbob.test', 'SESSION_ID', uid(1), uid(11), 'reservation_id', 'nickname'):
            self.assertNotIn(forbidden, text)
        self.assertFalse(evidence['scope']['runtimeProfileVerified'])
        self.assertFalse(evidence['scope']['runningAppJarIdentityVerified'])
        self.assertEqual(0, evidence['scope']['businessWriteRequests'])
        self.assertLess(evidence['requestCount'], 70)

    def test_external_anchors_are_required_before_network_or_output(self):
        self.args.checksums_sha256 = 'f' * 64
        factory = mock.Mock()
        with self.assertRaisesRegex(dev.CheckFailed, 'CHECKSUM_ANCHOR_MISMATCH'):
            dev.run(self.args, session_factory=factory)
        factory.assert_not_called()
        self.assertFalse(Path(self.args.output).exists())

    def test_consumed_selection_tamper_rejected(self):
        path = Path(self.args.release) / 'representative-accounts.json'
        path.write_text(path.read_text() + ' ')
        with self.assertRaisesRegex(dev.CheckFailed, 'CONSUMED_ARTIFACT_CHECKSUM_MISMATCH'):
            self.inputs()

    def test_final_scale_required_and_not_small(self):
        reseal(self.args, 'consumer-manifest.json', lambda d: d.update(finalScaleSelected=False))
        with self.assertRaisesRegex(dev.CheckFailed, 'QUALIFIED_FINAL_B_REQUIRED'):
            self.inputs()

    def test_final_root_state_required(self):
        reseal(self.args, 'manifest.json', lambda d: d.update(state='SMALL_DB_AND_HTTP_QUALIFIED'))
        with self.assertRaisesRegex(dev.CheckFailed, 'FINAL_RELEASE_NOT_QUALIFIED'):
            self.inputs()

    def test_no_dump_read_or_full_fingerprint_claim(self):
        self.assertFalse((Path(self.args.release) / 'airbob-growth.sql.gz').exists())
        self.assertEqual(3, len(self.inputs()['selected']))

    def test_private_environment_and_role_are_bound(self):
        for field, value in [('environment', 'oci:anywhere'), ('role', 'ADMIN')]:
            with self.subTest(field=field):
                original = Path(self.args.private_representatives).read_bytes()
                self.private_change(lambda d: d['credentials'][0].update({field: value}))
                with self.assertRaisesRegex(dev.CheckFailed, 'PRIVATE_IDENTITY_MISMATCH'):
                    self.inputs()
                Path(self.args.private_representatives).write_bytes(original)

    def test_three_distinct_passwords_and_no_qualification_union(self):
        self.private_change(lambda d: d['credentials'][1].update(password=d['credentials'][0]['password']))
        with self.assertRaisesRegex(dev.CheckFailed, 'PRIVATE_DISTINCT_PASSWORD_CONTRACT'):
            self.inputs()

    def test_additional_credentials_rejected(self):
        self.private_change(lambda d: d['credentials'].append(copy.deepcopy(d['credentials'][0])))
        with self.assertRaisesRegex(dev.CheckFailed, 'PRIVATE_REPRESENTATIVE_THREE_REQUIRED'):
            self.inputs()

    def test_private_permissions_and_symlink_rejected(self):
        path = Path(self.args.private_representatives)
        path.chmod(0o644)
        with self.assertRaisesRegex(dev.CheckFailed, 'PRIVATE_FILE_PERMISSIONS'):
            self.inputs()
        path.chmod(0o600)
        original = path.with_suffix('.original')
        path.rename(original)
        path.symlink_to(original)
        with self.assertRaises(dev.CheckFailed):
            self.inputs()

    def test_private_parent_permissions_rejected(self):
        Path(self.args.private_representatives).parent.chmod(0o755)
        with self.assertRaisesRegex(dev.CheckFailed, 'PRIVATE_DIRECTORY_PERMISSIONS'):
            self.inputs()

    def test_duplicate_json_and_nonfinite_rejected(self):
        for raw in [b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":Infinity}']:
            with self.subTest(raw=raw), self.assertRaises(dev.CheckFailed):
                dev.parse_json(raw)

    def test_wrong_auth_identity_fails_and_logs_out(self):
        def mutate(session, method, path, query, result):
            if path == dev.AUTH + 'me' and session.logged_in and session.key == 'demo':
                result[1]['data']['id'] = 9999
        server, (evidence, _, _) = self.execute(mutate)
        self.assertFalse(evidence['passed'])
        self.assertEqual('HTTP_IDENTITY_OR_OWNERSHIP_MISMATCH', evidence['accounts'][0]['failureCode'])
        self.assertTrue(evidence['accounts'][0]['logoutInvalidatesSession'])
        self.assertTrue(all(s.cleared for s in server.sessions))

    def test_member_admin_escalation_is_failure(self):
        def mutate(session, method, path, query, result):
            if path == dev.ADMIN and session.logged_in and session.key == 'host':
                return 200, {'success': True, 'data': {'items': []}}
        _, (evidence, _, _) = self.execute(mutate)
        self.assertEqual('UNEXPECTED_HTTP_STATUS', evidence['accounts'][1]['failureCode'])

    def test_admin_denial_is_failure(self):
        def mutate(session, method, path, query, result):
            if path == dev.ADMIN and session.logged_in and session.key == 'admin':
                return 403, {'success': False, 'error': {'message': 'SECRET-ERROR-PII'}}
        _, (evidence, path, _) = self.execute(mutate)
        self.assertFalse(evidence['passed'])
        self.assertNotIn('SECRET-', path.read_text())

    def test_guest_uid_mismatch_rejected(self):
        def mutate(session, method, path, query, result):
            if path.startswith('/api/v1/profile/guest/reservations/'):
                result[1]['data']['reservation_uid'] = uid(999)
        _, (evidence, _, _) = self.execute(mutate)
        self.assertFalse(evidence['passed'])
        self.assertEqual('HTTP_IDENTITY_OR_OWNERSHIP_MISMATCH', evidence['accounts'][0]['failureCode'])

    def test_review_id_alone_without_expected_author_is_not_proof(self):
        def mutate(session, method, path, query, result):
            if path.endswith('/reviews'):
                result[1]['data']['reviews'][0]['reviewer']['id'] = 999
        _, (evidence, _, _) = self.execute(mutate)
        self.assertEqual('HTTP_IDENTITY_OR_OWNERSHIP_MISMATCH', evidence['accounts'][0]['failureCode'])

    def test_missing_selected_review_is_failure_not_skip(self):
        def mutate(session, method, path, query, result):
            if path.endswith('/reviews'):
                result[1]['data']['reviews'][0]['id'] = 999
        _, (evidence, _, _) = self.execute(mutate)
        self.assertEqual('REVIEW_PROOF_NOT_FOUND_WITHIN_BOUNDS', evidence['accounts'][0]['failureCode'])

    def test_sealed_listing_owner_checked(self):
        def mutate(session, method, path, query, result):
            if path == '/api/v1/accommodations/100':
                result[1]['data']['host']['id'] = 999
        _, (evidence, _, _) = self.execute(mutate)
        self.assertEqual('HTTP_IDENTITY_OR_OWNERSHIP_MISMATCH', evidence['accounts'][0]['failureCode'])

    def test_empty_korean_search_not_silently_accepted(self):
        def mutate(session, method, path, query, result):
            if path == dev.SEARCH and query['topLeftLng'] == 124:
                result[1]['data']['stay_search_result_listing'] = []
        _, (evidence, _, _) = self.execute(mutate)
        self.assertEqual('SEARCH_RESULTS_REQUIRED', evidence['accounts'][0]['failureCode'])

    def test_overseas_viewport_covers_verified_lyon_envelope_without_geocoding_or_dates(self):
        # Measured envelope of the sealed 8,748 PUBLISHED Lyon documents. The
        # previous Paris fixture fails this geometry-based fake response.
        def mutate(session, method, path, query, result):
            if path == dev.SEARCH and query['topLeftLng'] not in {-179.99, 124}:
                contains_lyon = (query['topLeftLat'] >= 45.80479 and query['bottomRightLat'] <= 45.72193
                    and query['topLeftLng'] <= 4.77384 and query['bottomRightLng'] >= 4.89753)
                if not contains_lyon:
                    result[1]['data']['stay_search_result_listing'] = []
        server, (evidence, _, _) = self.execute(mutate)
        self.assertTrue(evidence['passed'])
        queries = [query for _, _, path, query in server.calls if path == dev.SEARCH]
        overseas = queries[2]
        self.assertEqual({'topLeftLat', 'topLeftLng', 'bottomRightLat', 'bottomRightLng', 'page', 'adultOccupancy'}, set(overseas))
        self.assertEqual(1, overseas['adultOccupancy'])
        self.assertFalse(overseas['bottomRightLat'] <= 48.8566 <= overseas['topLeftLat']
                         and overseas['topLeftLng'] <= 2.3522 <= overseas['bottomRightLng'])
        self.assertTrue(any(path == '/api/v1/accommodations/902' for _, _, path, _ in server.calls))

    def test_empty_overseas_search_still_fails_and_logs_out(self):
        def mutate(session, method, path, query, result):
            if path == dev.SEARCH and query['topLeftLng'] not in {-179.99, 124}:
                result[1]['data']['stay_search_result_listing'] = []
        _, (evidence, _, _) = self.execute(mutate)
        self.assertEqual('SEARCH_RESULTS_REQUIRED', evidence['accounts'][0]['failureCode'])
        self.assertTrue(evidence['accounts'][0]['logoutInvalidatesSession'])

    def test_overseas_result_cannot_pass_with_korean_country(self):
        def mutate(session, method, path, query, result):
            if path == dev.SEARCH and query['topLeftLng'] not in {-179.99, 124}:
                result[1]['data']['stay_search_result_listing'][0]['address_summary']['country'] = 'South Korea'
        _, (evidence, _, _) = self.execute(mutate)
        self.assertEqual('SEARCH_COUNTRY_CATEGORY_MISMATCH', evidence['accounts'][0]['failureCode'])

    def test_pagination_uses_only_bounded_cursor_and_sample_identity(self):
        def mutate(session, method, path, query, result):
            if path == '/api/v1/profile/guest/reservations' and not query.get('cursor'):
                member = self.inputs()['selected'][session.key]['memberId']
                result[1]['data'] = page('reservations', [{'reservation_id': member * 10 + 9,
                    'reservation_uid': uid(999), 'accommodation': {'id': 999}}], cursor='Y3Vyc29y')
        server, (evidence, _, _) = self.execute(mutate)
        self.assertTrue(evidence['passed'])
        self.assertEqual(2, evidence['accounts'][0]['guestPagesRead'])
        self.assertTrue(any(query and query.get('cursor') == 'Y3Vyc29y' for _, _, _, query in server.calls))

    def test_transport_error_text_is_never_published(self):
        def mutate(session, method, path, query, result):
            if path == dev.SEARCH:
                raise RuntimeError('SECRET-PASSWORD SECRET-COOKIE SECRET-PERSON')
        _, (evidence, path, _) = self.execute(mutate)
        self.assertFalse(evidence['passed'])
        self.assertNotIn('SECRET-', path.read_text())
        self.assertEqual('UNCLASSIFIED_RESPONSE_FAILURE', evidence['accounts'][0]['failureCode'])

    def test_logout_failure_makes_whole_result_fail(self):
        def mutate(session, method, path, query, result):
            if path == dev.AUTH + 'logout':
                return 503, {'success': False}
        _, (evidence, _, _) = self.execute(mutate)
        self.assertFalse(evidence['passed'])
        self.assertEqual('UNEXPECTED_HTTP_STATUS', evidence['accounts'][0]['logoutFailureCode'])

    def test_existing_output_not_reused(self):
        Path(self.args.output).mkdir()
        factory = mock.Mock()
        with self.assertRaisesRegex(dev.CheckFailed, 'OUTPUT_MUST_BE_NEW_DIRECTORY'):
            dev.run(self.args, session_factory=factory)
        factory.assert_not_called()

    def test_output_cannot_mutate_the_sealed_release(self):
        self.args.output = str(Path(self.args.release) / 'new-result')
        factory = mock.Mock()
        with self.assertRaisesRegex(dev.CheckFailed, 'OUTPUT_INSIDE_SEALED_RELEASE_REJECTED'):
            dev.run(self.args, session_factory=factory)
        factory.assert_not_called()
        self.assertFalse(Path(self.args.output).exists())

    def test_read_budget_keeps_room_to_invalidate_own_session(self):
        inputs = self.inputs()
        reader = dev.Reader(inputs)
        server = FakeServer(inputs)
        session = server(inputs['baseUrl'], 30)
        credential = inputs['credentials']['demo']
        session.request('POST', dev.AUTH + 'login', body={'email': credential['email'], 'password': credential['password']})
        reader.requests = dev.MAX_REQUESTS - 6
        with self.assertRaisesRegex(dev.CheckFailed, 'HTTP_REQUEST_BUDGET_EXHAUSTED'):
            reader.call(session, 'demo', 'session-identity', 'GET', dev.AUTH + 'me')
        self.assertEqual(dev.MAX_REQUESTS - 6, reader.requests)
        reader.call(session, 'demo', 'logout-own-session', 'POST', dev.AUTH + 'logout')
        reader.call(session, 'demo', 'logout-invalidates-session', 'GET', dev.AUTH + 'me', status=401)
        self.assertFalse(session.logged_in)

    def test_review_cursor_repetition_is_failure(self):
        def mutate(session, method, path, query, result):
            if path.endswith('/reviews'):
                result[1]['data'] = page('reviews', [{'id': 999, 'reviewer': {'id': 999}}], cursor='cmVwZWF0')
        _, (evidence, _, _) = self.execute(mutate)
        self.assertEqual('REPEATED_REVIEW_CURSOR', evidence['accounts'][0]['failureCode'])

    def test_guest_bound_is_enforced_when_selected_sample_missing(self):
        counter = {}
        def mutate(session, method, path, query, result):
            if path == '/api/v1/profile/guest/reservations':
                counter[session.key] = counter.get(session.key, 0) + 1
                result[1]['data'] = page('reservations', [{'reservation_id': 10000 + counter[session.key],
                    'reservation_uid': uid(999), 'accommodation': {'id': 999}}], cursor='bmV4dA==')
        _, (evidence, _, _) = self.execute(mutate)
        self.assertEqual(dev.MAX_GUEST_PAGES, counter['demo'])
        self.assertEqual('RESERVATION_PROOF_NOT_FOUND_WITHIN_BOUNDS', evidence['accounts'][0]['failureCode'])

    def test_loopback_only_and_normalized_localhost(self):
        self.assertEqual('http://127.0.0.1:8080', dev.base_url('http://localhost:8080/'))
        for value in ['https://localhost:8080', 'http://example.com:8080', 'http://127.0.0.1:8080@evil.test:8080',
                      'http://localhost:8080/path', 'http://localhost:8080?x=1', 'http://localhost:8080#x',
                      'http://localhost:80', 'http://127.0.0.2:8080', 'http://localhost:invalid']:
            with self.subTest(value=value), self.assertRaises(dev.CheckFailed):
                dev.base_url(value)

    def test_business_writes_and_external_queries_rejected(self):
        cases = [('POST', '/api/v1/reservations/checkout', {}, {}),
                 ('POST', '/api/v1/admin/payment-operations/' + uid(1) + '/reconciliation', {}, {}),
                 ('DELETE', '/api/v1/accommodations/1', {}, None),
                 ('GET', 'http://evil.test', {}, None),
                 ('GET', dev.SEARCH, {'destination': 'http://evil.test'}, None),
                 ('GET', '/api/v1/profile/guest/reservations', {'size': 10000}, None),
                 ('GET', '/api/v1/accommodations/1/reviews', {'cursor': '../../secrets'}, None),
                 ('POST', dev.AUTH + 'login', {'redirect': 'http://evil.test'}, {'email': 'a', 'password': 'b'})]
        for method, path, query, body in cases:
            with self.subTest(path=path), self.assertRaises(dev.CheckFailed):
                dev.request_template(method, path, query, body)

    def test_redirect_is_rejected_even_to_same_origin(self):
        with self.assertRaisesRegex(dev.CheckFailed, 'HTTP_REDIRECT_REJECTED'):
            dev.NoRedirect().redirect_request(None, None, 302, '', {}, 'http://127.0.0.1:8080/api/v1/auth/me')

    def test_http_transport_has_no_environment_proxies(self):
        with mock.patch.dict(os.environ, {'HTTP_PROXY': 'http://evil.test', 'HTTPS_PROXY': 'http://evil.test'}):
            session = dev.HttpSession('http://localhost:8080', 30)
        self.assertFalse(any(isinstance(handler, dev.urllib.request.ProxyHandler) and handler.proxies
                             for handler in session.opener.handlers))

    def test_oversized_response_not_parsed_or_exposed(self):
        session = dev.HttpSession('http://localhost:8080', 30)
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.code = 200
        response.headers = {'Content-Type': 'application/json'}
        response.read.return_value = b'x' * (dev.MAX_RESPONSE_BYTES + 1)
        session.opener = mock.Mock()
        session.opener.open.return_value = response
        with self.assertRaisesRegex(dev.CheckFailed, 'HTTP_RESPONSE_TOO_LARGE'):
            session.request('GET', dev.AUTH + 'me')

    def test_main_rejects_without_echoing_exception_contents(self):
        argv = ['--release', self.args.release, '--consumer-manifest-sha256', self.args.consumer_manifest_sha256,
                '--checksums-sha256', self.args.checksums_sha256,
                '--private-representatives', self.args.private_representatives,
                '--account-environment', self.args.account_environment, '--output', self.args.output]
        stdout = io.StringIO()
        with mock.patch.object(dev, 'run', side_effect=RuntimeError('SECRET-PASSWORD')), mock.patch('sys.stdout', stdout):
            self.assertEqual(2, dev.main(argv))
        self.assertNotIn('SECRET-', stdout.getvalue())
        self.assertEqual('UNCLASSIFIED_FAILURE', json.loads(stdout.getvalue())['failureCode'])


if __name__ == '__main__':
    unittest.main()
