#!/usr/bin/env python3
"""Bounded representative HTTP reads of an already running Airbob service.

Only this client's login/logout sessions are written. No database, Docker, external
integration, account preparation, or business-write API is available here. The
release anchors bind the small metadata subset consumed below; this does not repeat
the full dump/roundtrip qualification or identify the running Spring profile.
OCI/AWS services must be reached through an operator-established loopback tunnel;
their process/database identity evidence is recorded separately.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


STATE = 'LOCAL_B_REPRESENTATIVE_HTTP_READS_VERIFIED'
KINDS = {'demo': ('demo@airbob.test', 'MEMBER', 'representative'),
         'host': ('host@airbob.test', 'MEMBER', 'representative'),
         'admin': ('admin@airbob.test', 'ADMIN', 'administrator')}
METADATA = {'root': 'manifest.json', 'accounts': 'accounts.json',
            'selection': 'representative-accounts.json',
            'loginProof': 'account-login-qualification.json'}
ARTIFACT_KEYS = {'accounts': 'accounts', 'selection': 'representativeAccounts',
                 'loginProof': 'accountLogins'}
MAX_METADATA_BYTES = 2 * 1024 * 1024
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_REQUESTS = 240  # Includes failure cleanup for all three independent sessions.
MAX_GUEST_PAGES = 20
MAX_REVIEW_PAGES = 4
PAGE_SIZE = 50  # The production cursor argument resolver's maximum.
ADMIN = '/api/v1/admin/payment-operations/manual-review'
AUTH = '/api/v1/auth/'
SEARCH = '/api/v1/search/accommodations'
SEARCH_BOUNDS = {
    'listing': (80, -179.99, -60, 179.99),
    'korea': (39.5, 124, 32, 132),
    'overseas': (45.85, 4.70, 45.65, 5.0),  # Verified Lyon viewport, no geocoding request.
}


class CheckFailed(Exception):
    """Only a closed local code may cross the public evidence boundary."""

    def __init__(self, code):
        if not re.fullmatch(r'[A-Z][A-Z0-9_]{0,90}', code):
            code = 'UNCLASSIFIED_FAILURE'
        self.code = code
        super().__init__(code)


def need(condition, code):
    if not condition:
        raise CheckFailed(code)


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def digest(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value) is not None


def unique_json(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result, 'DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def parse_json(raw):
    try:
        return json.loads(raw, object_pairs_hook=unique_json,
                          parse_constant=lambda _: (_ for _ in ()).throw(CheckFailed('INVALID_JSON_NUMBER')))
    except (ValueError, UnicodeError, RecursionError):
        raise CheckFailed('INVALID_JSON') from None


def read_bytes(path, *, private=False):
    """Read once through a non-following descriptor, then hash those exact bytes."""
    path = Path(path)
    try:
        parent = path.parent.lstat()
        need(stat.S_ISDIR(parent.st_mode), 'INPUT_PARENT_NOT_DIRECTORY')
        if private:
            need(stat.S_IMODE(parent.st_mode) == 0o700, 'PRIVATE_DIRECTORY_PERMISSIONS')
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as stream:
            info = os.fstat(stream.fileno())
            need(stat.S_ISREG(info.st_mode), 'INPUT_NOT_REGULAR_FILE')
            if private:
                need(stat.S_IMODE(info.st_mode) == 0o600, 'PRIVATE_FILE_PERMISSIONS')
                need(info.st_uid == os.getuid(), 'PRIVATE_FILE_OWNER')
            need(info.st_size <= MAX_METADATA_BYTES, 'INPUT_TOO_LARGE')
            result = stream.read(MAX_METADATA_BYTES + 1)
            need(len(result) <= MAX_METADATA_BYTES, 'INPUT_TOO_LARGE')
            return result
    except OSError:
        raise CheckFailed('INPUT_FILE_UNAVAILABLE') from None


def hash_bytes(raw):
    return hashlib.sha256(raw).hexdigest()


def base_url(value):
    try:
        part = urllib.parse.urlsplit(value)
        need(part.scheme == 'http' and part.hostname in {'127.0.0.1', 'localhost', '::1'}
             and part.username is None and part.password is None
             and part.path in {'', '/'} and not part.query and not part.fragment
             and part.port is not None and 1024 <= part.port <= 65535,
             'LOOPBACK_HTTP_BASE_REQUIRED')
        # Resolve localhost to a literal, bypassing DNS and system proxy settings.
        host = '[::1]' if part.hostname == '::1' else '127.0.0.1'
        return f'http://{host}:{part.port}'
    except ValueError:
        raise CheckFailed('LOOPBACK_HTTP_BASE_REQUIRED') from None


def read_inputs(args):
    release = Path(args.release).absolute()
    need(not release.is_symlink() and release.is_dir(), 'RELEASE_DIRECTORY_REQUIRED')
    need(digest(args.consumer_manifest_sha256) and digest(args.checksums_sha256), 'EXTERNAL_ANCHORS_REQUIRED')
    checks_raw = read_bytes(release / 'SHA256SUMS.json')
    consumer_raw = read_bytes(release / 'consumer-manifest.json')
    need(hash_bytes(checks_raw) == args.checksums_sha256, 'CHECKSUM_ANCHOR_MISMATCH')
    need(hash_bytes(consumer_raw) == args.consumer_manifest_sha256, 'CONSUMER_ANCHOR_MISMATCH')
    checks, consumer = parse_json(checks_raw), parse_json(consumer_raw)
    need(isinstance(checks, dict) and isinstance(consumer, dict), 'INVALID_RELEASE_METADATA')
    need(checks.get('consumer-manifest.json') == args.consumer_manifest_sha256,
         'CONSUMER_CHECKSUM_MISMATCH')
    dump_sha = checks.get('airbob-growth.sql.gz')
    need(digest(dump_sha) and consumer.get('datasetId') == 'global-growth-b-' + dump_sha[:16],
         'DATASET_IDENTITY_MISMATCH')
    dataset = consumer['datasetId']
    need(consumer.get('schemaVersion') == 4 and consumer.get('datasetVersion') == 'benchmark-dataset-v4'
         and consumer.get('mysql') == {'version': '8.4.11', 'flywayVersion': 28}
         and consumer.get('finalScaleSelected') is True
         and consumer.get('datasetScale') == 'selected-global-b-ten-million'
         and consumer.get('qualification') == 'SMALL_LOCAL_SCENARIOS'
         and digest(consumer.get('appJarSha256')), 'QUALIFIED_FINAL_B_REQUIRED')
    values, bindings = {}, {}
    for key, name in METADATA.items():
        raw = read_bytes(release / name)
        checksum = hash_bytes(raw)
        need(checks.get(name) == checksum, 'CONSUMED_ARTIFACT_CHECKSUM_MISMATCH')
        if key in ARTIFACT_KEYS:
            need(consumer.get('artifacts', {}).get(ARTIFACT_KEYS[key]) == {'file': name, 'sha256': checksum},
                 'CONSUMED_ARTIFACT_BINDING_MISMATCH')
        values[key] = parse_json(raw)
        need(isinstance(values[key], dict), 'INVALID_RELEASE_METADATA')
        bindings[name] = checksum
    root, selection, accounts, proof = (values[key] for key in ('root', 'selection', 'accounts', 'loginProof'))
    need(root.get('datasetId') == dataset and root.get('datasetProfile') == 'global-growth-b'
         and root.get('state') == 'DATASET_DB_AND_HTTP_QUALIFIED'
         and root.get('finalScaleSelected') is True
         and root.get('datasetScale') == consumer['datasetScale'], 'FINAL_RELEASE_NOT_QUALIFIED')
    need(selection.get('schemaVersion') == 1 and selection.get('datasetProfile') == 'global-growth-b'
         and selection.get('finalScaleSelected') is True
         and selection.get('selectionScope') == 'actual rows of this generated dataset'
         and selection.get('baseRows') == {'members': 3000000, 'accommodations': 730501, 'reservations': 10000000},
         'FINAL_REPRESENTATIVE_SELECTION_REQUIRED')
    rows = selection.get('accounts')
    need(isinstance(rows, list) and len(rows) == 3 and all(isinstance(row, dict) for row in rows),
         'EXACTLY_THREE_REPRESENTATIVES_REQUIRED')
    selected = {}
    for row in rows:
        key = row.get('key')
        need(key in KINDS and key not in selected and integer(row.get('memberId'), 1)
             and (row.get('email'), row.get('role')) == KINDS[key][:2]
             and row.get('status') == 'ACTIVE' and isinstance(row.get('nickname'), str),
             'REPRESENTATIVE_IDENTITY_MISMATCH')
        selected[key] = row
        for metric in (() if key == 'admin' else ('publishedListings', 'reservations', 'reviews', 'receivedReservations')):
            owned = row.get('ownership', {}).get(metric, {})
            sample = owned.get('sampleIds')
            need(integer(owned.get('count'), 1) and isinstance(sample, list) and 0 < len(sample) <= 16
                 and all(integer(x, 1) for x in sample) and len(set(sample)) == len(sample)
                 and len(sample) <= owned['count'], 'OWNERSHIP_SELECTION_PROOF_REQUIRED')
    need(len({row['memberId'] for row in rows}) == 3, 'REPRESENTATIVE_IDENTITIES_NOT_DISTINCT')
    need(accounts.get('schemaVersion') == 2 and accounts.get('datasetProfile') == 'global-growth-b'
         and accounts.get('representativeAccountCount') == 3 and accounts.get('representativeAccounts') == rows,
         'ACCOUNT_BUNDLE_SELECTION_MISMATCH')
    representatives = proof.get('representatives')
    need(proof.get('passed') is True and proof.get('credentialAndSessionValuesRecorded') is False
         and isinstance(representatives, list) and len(representatives) == 3,
         'SEALED_LOGIN_PROOF_REQUIRED')
    expected = {row['memberId']: row for row in rows}
    observed = set()
    for row in representatives:
        member = row.get('memberId')
        need(member in expected and member not in observed and row.get('loginStatus') == 200
             and row.get('authenticatedIdentityMatches') is True
             and all(row.get(field) == expected[member][field] for field in ('email', 'role', 'status')),
             'SEALED_LOGIN_IDENTITY_MISMATCH')
        observed.add(member)
    environment = args.account_environment
    target_mode = getattr(args, 'target_mode', 'local')
    need(target_mode in {'local', 'oci', 'aws'}, 'UNSUPPORTED_TARGET_MODE')
    pattern = (r'aws:db-[A-Z0-9]{1,64}:[0-9a-f-]{36}' if target_mode == 'aws' else
               re.escape(target_mode) + r':[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}:' + dataset)
    need(isinstance(environment, str) and re.fullmatch(pattern, environment),
         'EXACT_LOCAL_ACCOUNT_ENVIRONMENT_REQUIRED' if target_mode == 'local' else 'EXACT_CLOUD_ACCOUNT_ENVIRONMENT_REQUIRED')
    if target_mode == 'aws':
        need(str(uuid.UUID(environment.rsplit(':', 1)[1])) == environment.rsplit(':', 1)[1],
             'EXACT_CLOUD_ACCOUNT_ENVIRONMENT_REQUIRED')
    private = parse_json(read_bytes(args.private_representatives, private=True))
    need(isinstance(private, dict) and private.get('schemaVersion') == 2 and 'password' not in private
         and private.get('datasetProfile') == 'global-growth-b' and private.get('environment') == environment,
         'PRIVATE_V2_ENVIRONMENT_MISMATCH')
    credentials = private.get('credentials')
    need(isinstance(credentials, list) and len(credentials) == 3, 'PRIVATE_REPRESENTATIVE_THREE_REQUIRED')
    by_id, passwords = {}, set()
    for row in credentials:
        need(isinstance(row, dict), 'PRIVATE_IDENTITY_MISMATCH')
        member, password = row.get('memberId'), row.get('password')
        need(integer(member, 1) and member in expected and member not in by_id
             and all(row.get(field) == expected[member][field] for field in ('email', 'role'))
             and row.get('environment') == environment
             and row.get('group') == KINDS[expected[member]['key']][2], 'PRIVATE_IDENTITY_MISMATCH')
        need(isinstance(password, str) and 16 <= len(password.encode('utf-8')) <= 72
             and password not in passwords, 'PRIVATE_DISTINCT_PASSWORD_CONTRACT')
        passwords.add(password)
        by_id[member] = row
    return {'datasetId': dataset, 'baseUrl': base_url(args.base_url), 'selected': selected, 'targetMode': target_mode,
            'credentials': {key: by_id[row['memberId']] for key, row in selected.items()},
            'binding': {'consumerManifestSha256': args.consumer_manifest_sha256,
                        'checksumsSha256': args.checksums_sha256, 'consumedMetadata': bindings,
                        'qualifiedAppJarSha256': consumer['appJarSha256'], 'accountEnvironment': environment}}


def request_template(method, path, query=None, body=None):
    """Closed route and query vocabulary, independent of caller-supplied metadata."""
    query = query or {}
    need(isinstance(query, dict), 'REQUEST_NOT_ALLOWED')
    if method == 'POST' and path in {AUTH + 'login', AUTH + 'logout'}:
        need(not query and (body is None if path.endswith('logout') else
                           isinstance(body, dict) and set(body) == {'email', 'password'}), 'REQUEST_NOT_ALLOWED')
        return path
    need(method == 'GET' and body is None, 'REQUEST_NOT_ALLOWED')
    fixed = {AUTH + 'me': set(), ADMIN: {'limit'}, SEARCH: {'topLeftLat', 'topLeftLng', 'bottomRightLat',
             'bottomRightLng', 'page', 'adultOccupancy'},
             '/api/v1/profile/guest/reservations': {'size', 'cursor'},
             '/api/v1/profile/host/reservations': {'size', 'cursor'},
             '/api/v1/profile/host/accommodations': {'size', 'cursor', 'status'}}
    template = path
    if path in fixed:
        allowed = fixed[path]
    elif re.fullmatch(r'/api/v1/accommodations/[1-9][0-9]{0,18}(?:/reviews(?:/summary)?)?', path):
        allowed = {'size', 'cursor', 'sortType'} if path.endswith('/reviews') else set()
        template = re.sub(r'/[0-9]+', '/{id}', path)
    elif re.fullmatch(r'/api/v1/profile/host/accommodations/[1-9][0-9]{0,18}', path):
        allowed = set()
        template = '/api/v1/profile/host/accommodations/{id}'
    elif re.fullmatch(r'/api/v1/profile/(guest|host)/reservations/[0-9a-f-]{36}', path):
        token = path.rsplit('/', 1)[-1]
        try:
            need(str(uuid.UUID(token)) == token, 'REQUEST_NOT_ALLOWED')
        except ValueError:
            raise CheckFailed('REQUEST_NOT_ALLOWED') from None
        allowed = set()
        template = path.rsplit('/', 1)[0] + '/{uid}'
    else:
        raise CheckFailed('REQUEST_NOT_ALLOWED')
    need(set(query) <= allowed, 'REQUEST_NOT_ALLOWED')
    for key, value in query.items():
        if key in {'size', 'limit'}:
            need(integer(value, 1) and value <= PAGE_SIZE, 'REQUEST_NOT_ALLOWED')
        elif key == 'cursor':
            need(isinstance(value, str) and 0 < len(value) <= 2048
                 and re.fullmatch(r'[A-Za-z0-9+/=_-]+', value), 'REQUEST_NOT_ALLOWED')
        elif key == 'status':
            need(value == 'PUBLISHED', 'REQUEST_NOT_ALLOWED')
        elif key == 'sortType':
            need(value == 'LATEST', 'REQUEST_NOT_ALLOWED')
        elif key == 'page':
            need(value == 0, 'REQUEST_NOT_ALLOWED')
        elif key == 'adultOccupancy':
            need(value == 1, 'REQUEST_NOT_ALLOWED')
        else:
            need(type(value) in {int, float} and -180 <= value <= 180, 'REQUEST_NOT_ALLOWED')
    return template


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise CheckFailed('HTTP_REDIRECT_REJECTED')


class HttpSession:
    def __init__(self, base, timeout):
        self.base, self.timeout = base_url(base), timeout
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                                 urllib.request.HTTPCookieProcessor(self.cookies))

    def request(self, method, path, query=None, body=None):
        request_template(method, path, query, body)
        url = self.base + path + ('?' + urllib.parse.urlencode(query) if query else '')
        raw = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(url, data=raw, method=method,
                  headers={'Accept': 'application/json', 'Accept-Encoding': 'identity',
                           'Content-Type': 'application/json'})
        try:
            try:
                response = self.opener.open(request, timeout=self.timeout)
            except urllib.error.HTTPError as error:
                response = error  # Never record the reason/body/header text.
            with response:
                status = response.code
                need(response.headers.get('Content-Encoding', 'identity') == 'identity', 'HTTP_ENCODING_REJECTED')
                raw = response.read(MAX_RESPONSE_BYTES + 1)
                need(len(raw) <= MAX_RESPONSE_BYTES, 'HTTP_RESPONSE_TOO_LARGE')
                need('application/json' in response.headers.get('Content-Type', ''), 'HTTP_JSON_REQUIRED')
                return status, parse_json(raw)
        except (OSError, urllib.error.URLError, TimeoutError):
            raise CheckFailed('HTTP_TRANSPORT_FAILED') from None

    def has_session(self):
        return any(cookie.name == 'SESSION_ID' and bool(cookie.value) for cookie in self.cookies)

    def clear(self):
        self.cookies.clear()


class Reader:
    def __init__(self, inputs, timeout=30, session_factory=HttpSession):
        need(integer(timeout, 1) and timeout <= 120, 'INVALID_TIMEOUT')
        self.inputs, self.timeout, self.factory = inputs, timeout, session_factory
        self.observations, self.account_results = [], []
        self.requests = 0

    def call(self, session, account, purpose, method, path, *, query=None, body=None, status=200):
        template = request_template(method, path, query, body)
        limit = MAX_REQUESTS if purpose in {'logout-own-session', 'logout-invalidates-session'} else MAX_REQUESTS - 6
        need(self.requests < limit, 'HTTP_REQUEST_BUDGET_EXHAUSTED')
        self.requests += 1
        item = {'account': account, 'check': purpose, 'method': method, 'endpoint': template,
                'expectedStatus': status, 'passed': False}
        self.observations.append(item)
        started = time.monotonic()
        try:
            actual, response = session.request(method, path, query, body)
            item['status'] = actual
            need(actual == status, 'UNEXPECTED_HTTP_STATUS')
            if status == 200:
                need(isinstance(response, dict) and response.get('success') is True, 'API_SUCCESS_REQUIRED')
                result = response.get('data')
            else:
                result = None
            item['passed'] = True
            return result, item
        finally:
            item['elapsedSeconds'] = round(time.monotonic() - started, 3)

    @staticmethod
    def checked(item, **checks):
        item.update(checks)
        item['passed'] = all(value is True for value in checks.values())
        need(item['passed'], 'HTTP_IDENTITY_OR_OWNERSHIP_MISMATCH')

    def page(self, session, key, purpose, path, field, query):
        data, item = self.call(session, key, purpose, 'GET', path, query=query)
        need(isinstance(data, dict) and isinstance(data.get(field), list), 'API_LIST_REQUIRED')
        rows = data[field]
        need(len(rows) <= query.get('size', 18) and all(isinstance(row, dict) for row in rows), 'API_LIST_BOUND_EXCEEDED')
        item['count'] = len(rows)
        info = data.get('page_info')
        need(isinstance(info, dict) and type(info.get('has_next')) is bool, 'CURSOR_PAGE_INFO_REQUIRED')
        cursor = info.get('next_cursor') if info['has_next'] else None
        if cursor is not None:
            need(isinstance(cursor, str) and 0 < len(cursor) <= 2048, 'CURSOR_PAGE_INFO_REQUIRED')
        need(not info['has_next'] or cursor is not None, 'CURSOR_PAGE_INFO_REQUIRED')
        return rows, cursor

    def listing_detail(self, session, key, listing, *, expected_host=None, category=None):
        need(integer(listing, 1), 'INVALID_RESPONSE_ID')
        data, item = self.call(session, key, 'accommodation-detail', 'GET', f'/api/v1/accommodations/{listing}')
        need(isinstance(data, dict), 'API_OBJECT_REQUIRED')
        checks = {'expectedIdMatches': data.get('id') == listing}
        if expected_host is not None:
            checks['expectedHostMatches'] = isinstance(data.get('host'), dict) and data['host'].get('id') == expected_host
        if category:
            country = data.get('address_summary', {}).get('country')
            checks['expectedCountryCategoryMatches'] = (country == 'South Korea' if category == 'korea' else
                                                        isinstance(country, str) and bool(country) and country != 'South Korea')
        self.checked(item, **checks)
        return data

    def searches(self, session):
        for scope, bounds in SEARCH_BOUNDS.items():
            query = dict(zip(('topLeftLat', 'topLeftLng', 'bottomRightLat', 'bottomRightLng'), bounds))
            query.update(page=0, adultOccupancy=1)
            data, item = self.call(session, 'demo', 'search-' + scope, 'GET', SEARCH, query=query)
            need(isinstance(data, dict) and isinstance(data.get('stay_search_result_listing'), list), 'API_LIST_REQUIRED')
            rows = data['stay_search_result_listing']
            need(0 < len(rows) <= 18 and all(isinstance(row, dict) for row in rows), 'SEARCH_RESULTS_REQUIRED')
            item['count'] = len(rows)
            if scope != 'listing':
                matches = [row for row in rows if (row.get('address_summary', {}).get('country') == 'South Korea') == (scope == 'korea')]
                need(bool(matches), 'SEARCH_COUNTRY_CATEGORY_MISMATCH')
                item['countryCategoryMatches'] = True
                self.listing_detail(session, 'demo', matches[0].get('id'), category=scope)

    def guest_and_review(self, session, key, expected):
        samples = set(expected['ownership']['reservations']['sampleIds'])
        review_samples = set(expected['ownership']['reviews']['sampleIds'])
        matches, seen, cursor = {}, set(), None
        for page in range(MAX_GUEST_PAGES):
            query = {'size': PAGE_SIZE}
            if cursor:
                query['cursor'] = cursor
            rows, cursor = self.page(session, key, 'own-guest-reservations',
                                    '/api/v1/profile/guest/reservations', 'reservations', query)
            for row in rows:
                rid = row.get('reservation_id')
                need(integer(rid, 1) and rid not in seen, 'DUPLICATE_OR_INVALID_RESERVATION_ID')
                seen.add(rid)
                if rid in samples:
                    matches[rid] = row
            if not cursor or samples <= matches.keys():
                break
        need(bool(matches), 'RESERVATION_PROOF_NOT_FOUND_WITHIN_BOUNDS')
        first = matches[min(matches)]
        token = self.valid_uid(first.get('reservation_uid'))
        detail, item = self.call(session, key, 'own-guest-reservation-detail', 'GET',
                                 '/api/v1/profile/guest/reservations/' + token)
        need(isinstance(detail, dict), 'API_OBJECT_REQUIRED')
        self.checked(item, expectedUidMatches=detail.get('reservation_uid') == token,
                     expectedAccommodationMatches=detail.get('accommodation', {}).get('id') == first.get('accommodation', {}).get('id'),
                     sealedReservationSampleMatches=True)
        # There is no production 'my reviews' read endpoint. Use the selected
        # member's existing guest bookings to locate their sealed published reviews.
        found, visited = False, set()
        for reservation in matches.values():
            listing = reservation.get('accommodation', {}).get('id')
            need(integer(listing, 1), 'INVALID_RESPONSE_ID')
            if listing in visited:
                continue
            visited.add(listing)
            cursor, cursors = None, set()
            for _ in range(MAX_REVIEW_PAGES):
                query = {'size': PAGE_SIZE, 'sortType': 'LATEST'}
                if cursor:
                    need(cursor not in cursors, 'REPEATED_REVIEW_CURSOR')
                    cursors.add(cursor)
                    query['cursor'] = cursor
                rows, cursor = self.page(session, key, 'own-authored-review',
                                        f'/api/v1/accommodations/{listing}/reviews', 'reviews', query)
                for row in rows:
                    if row.get('id') in review_samples:
                        self.checked(self.observations[-1], sealedReviewSampleMatches=True,
                                     expectedReviewerMatches=row.get('reviewer', {}).get('id') == expected['memberId'])
                        found = True
                        break
                if found or not cursor:
                    break
            if found:
                break
        need(found, 'REVIEW_PROOF_NOT_FOUND_WITHIN_BOUNDS')
        return {'sealedReservationSampleMatches': True, 'reservationSamplesMatched': len(matches),
                'guestPagesRead': page + 1, 'sealedAuthoredReviewMatches': True}

    @staticmethod
    def valid_uid(value):
        try:
            need(isinstance(value, str) and str(uuid.UUID(value)) == value, 'INVALID_RESPONSE_UID')
        except (ValueError, AttributeError):
            raise CheckFailed('INVALID_RESPONSE_UID') from None
        return value

    def host_reads(self, session, key, expected):
        rows, _ = self.page(session, key, 'own-host-accommodations', '/api/v1/profile/host/accommodations',
                            'accommodations', {'size': 5, 'status': 'PUBLISHED'})
        need(bool(rows), 'OWN_HOST_LISTINGS_REQUIRED')
        listing = expected['ownership']['publishedListings']['sampleIds'][0]
        self.listing_detail(session, key, listing, expected_host=expected['memberId'])
        data, item = self.call(session, key, 'sealed-host-accommodation-detail', 'GET',
                               f'/api/v1/profile/host/accommodations/{listing}')
        self.checked(item, expectedIdMatches=isinstance(data, dict) and data.get('id') == listing,
                     sealedListingSampleMatches=True)
        reservations, _ = self.page(session, key, 'own-received-reservations',
                                    '/api/v1/profile/host/reservations', 'reservations', {'size': 5})
        need(bool(reservations), 'OWN_RECEIVED_RESERVATIONS_REQUIRED')
        first = reservations[0]
        token = self.valid_uid(first.get('reservation_uid'))
        data, item = self.call(session, key, 'own-received-reservation-detail', 'GET',
                               '/api/v1/profile/host/reservations/' + token)
        self.checked(item, expectedUidMatches=isinstance(data, dict) and data.get('reservation_uid') == token,
                     expectedAccommodationMatches=isinstance(data, dict) and data.get('accommodation', {}).get('id') == first.get('accommodation', {}).get('id'))
        received_listing = first.get('accommodation', {}).get('id')
        need(integer(received_listing, 1), 'INVALID_RESPONSE_ID')
        data, item = self.call(session, key, 'received-reservation-owned-listing', 'GET',
                               f'/api/v1/profile/host/accommodations/{received_listing}')
        self.checked(item, expectedIdMatches=isinstance(data, dict) and data.get('id') == received_listing)
        reviews, _ = self.page(session, key, 'received-reviews-on-owned-listing',
                               f'/api/v1/accommodations/{listing}/reviews', 'reviews', {'size': 5, 'sortType': 'LATEST'})
        # A selected published listing need not itself have a review. Authored
        # review proof above is mandatory; this received-review page may be empty.
        return {'sealedListingSampleMatches': True, 'receivedReservationOwnershipMatches': True,
                'receivedReviewsOnSelectedListing': len(reviews)}

    def account(self, key):
        selected, credential = self.inputs['selected'][key], self.inputs['credentials'][key]
        session = self.factory(self.inputs['baseUrl'], self.timeout)
        result = {'account': key, 'passed': False}
        self.account_results.append(result)
        failure = None
        login_attempted = False
        try:
            self.call(session, key, 'anonymous-auth-rejected', 'GET', AUTH + 'me', status=401)
            if key == 'admin':
                self.call(session, key, 'anonymous-admin-rejected', 'GET', ADMIN, query={'limit': 5}, status=401)
            login_attempted = True
            self.call(session, key, 'normal-login', 'POST', AUTH + 'login',
                       body={'email': credential['email'], 'password': credential['password']})
            need(session.has_session(), 'SESSION_COOKIE_REQUIRED')
            identity, item = self.call(session, key, 'session-identity', 'GET', AUTH + 'me')
            self.checked(item, expectedMemberIdMatches=isinstance(identity, dict) and identity.get('id') == selected['memberId'],
                         expectedIdentityMatches=isinstance(identity, dict) and all(identity.get(field) == selected[field] for field in ('email', 'nickname')))
            data, item = self.call(session, key, 'admin-read-access', 'GET', ADMIN,
                                   query={'limit': 5}, status=200 if key == 'admin' else 403)
            if key == 'admin':
                need(isinstance(data, dict) and isinstance(data.get('items'), list) and len(data['items']) <= 5,
                     'ADMIN_READ_LIST_REQUIRED')
                item['count'] = len(data['items'])
            else:
                if key == 'demo':
                    self.searches(session)
                result.update(self.guest_and_review(session, key, selected))
                result.update(self.host_reads(session, key, selected))
            result['normalLoginAndIdentityVerified'] = True
            result['adminAuthorizationVerified'] = True
        except CheckFailed as error:
            failure = error.code
        except Exception:
            failure = 'UNCLASSIFIED_RESPONSE_FAILURE'
        finally:
            try:
                if login_attempted:
                    # Keep capacity for all cleanup requests even if reads exhausted
                    # the overall budget. Never create an additional login session.
                    self.call(session, key, 'logout-own-session', 'POST', AUTH + 'logout')
                    self.call(session, key, 'logout-invalidates-session', 'GET', AUTH + 'me', status=401)
                    result['logoutInvalidatesSession'] = True
            except CheckFailed as error:
                result['logoutFailureCode'] = error.code
                failure = failure or error.code
            except Exception:
                result['logoutFailureCode'] = 'SESSION_CLEANUP_FAILED'
                failure = failure or 'SESSION_CLEANUP_FAILED'
            finally:
                session.clear()
        if failure:
            result['failureCode'] = failure
        else:
            result['passed'] = True

    def run(self):
        for key in KINDS:
            self.account(key)
        return all(row['passed'] for row in self.account_results)


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def run(args, *, session_factory=HttpSession):
    inputs = read_inputs(args)  # No HTTP or output mutation before all bindings pass.
    need(integer(args.timeout, 1) and args.timeout <= 120, 'INVALID_TIMEOUT')
    output = Path(args.output).absolute()
    need(not output.resolve().is_relative_to(Path(args.release).resolve()), 'OUTPUT_INSIDE_SEALED_RELEASE_REJECTED')
    try:
        need(output.parent.is_dir() and not output.parent.is_symlink(), 'OUTPUT_PARENT_REQUIRED')
        output.mkdir(mode=0o700, exist_ok=False)
    except OSError:
        raise CheckFailed('OUTPUT_MUST_BE_NEW_DIRECTORY') from None
    started = utc_now()
    reader = Reader(inputs, args.timeout, session_factory)
    passed = reader.run()
    prefix = inputs['targetMode'].upper() + '_B_REPRESENTATIVE_HTTP_READS_'
    evidence = {'schemaVersion': 1, 'kind': 'global-b-dev-representative-http-reads',
                'state': prefix + ('VERIFIED' if passed else 'FAILED'), 'passed': passed,
                'targetMode': inputs['targetMode'],
                'datasetId': inputs['datasetId'], 'baseUrl': inputs['baseUrl'], 'binding': inputs['binding'],
                'startedAt': started, 'completedAt': utc_now(),
                'scope': {'representativeAccounts': 3, 'qualificationAccountsRetested': False,
                          'dumpOrDatabaseFingerprintRevalidated': False, 'runningAppJarIdentityVerified': False,
                          'runtimeProfileVerified': False, 'normalDevProcessEvidenceRequiredSeparately': True,
                          'businessWriteRequests': 0, 'externalIntegrationRequestsFromThisTool': 0,
                          'credentialInputModified': False, 'credentialsCookiesAndResponsePersonalDataRecorded': False,
                          'sessionAndReadSideEffects': 'Only owned login/logout sessions and normal GET cache/recent-view effects'},
                'bounds': {'requests': MAX_REQUESTS, 'guestPagesPerAccount': MAX_GUEST_PAGES,
                           'reviewPagesPerCandidate': MAX_REVIEW_PAGES, 'pageSize': PAGE_SIZE,
                           'responseBytes': MAX_RESPONSE_BYTES, 'requestTimeoutSeconds': args.timeout},
                'requestCount': reader.requests, 'accounts': reader.account_results,
                'observations': reader.observations}
    raw = (json.dumps(evidence, indent=2, sort_keys=True) + '\n').encode()
    target = output / 'representative-http-reads.json'
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(raw)
    return evidence, target, hash_bytes(raw)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', required=True)
    parser.add_argument('--consumer-manifest-sha256', required=True)
    parser.add_argument('--checksums-sha256', required=True)
    parser.add_argument('--private-representatives', required=True)
    parser.add_argument('--account-environment', required=True)
    parser.add_argument('--target-mode', choices=('local', 'oci', 'aws'), default='local',
                        help='Declared account target; cloud runtime identity must be verified separately.')
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--output', required=True)
    parser.add_argument('--timeout', type=int, default=30)
    args = parser.parse_args(argv)
    try:
        evidence, target, checksum = run(args)
        print(json.dumps({'state': evidence['state'], 'passed': evidence['passed'],
                          'report': str(target), 'sha256': checksum}))
        return 0 if evidence['passed'] else 1
    except CheckFailed as error:
        print(json.dumps({'state': args.target_mode.upper() + '_B_REPRESENTATIVE_HTTP_READS_REJECTED', 'failureCode': error.code}))
        return 2
    except Exception:
        print(json.dumps({'state': args.target_mode.upper() + '_B_REPRESENTATIVE_HTTP_READS_REJECTED', 'failureCode': 'UNCLASSIFIED_FAILURE'}))
        return 2


if __name__ == '__main__':
    sys.exit(main())
