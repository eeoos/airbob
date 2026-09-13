#!/usr/bin/env python3
"""Warm the sealed 15-target B read workload after full fingerprint verification.

The operator establishes a loopback tunnel and supplies target-bound credentials.
Only normal login/logout and the five sealed GET kinds are available. No dump,
database, Docker, Redis administration, or cloud API is opened by this tool.
"""
import argparse
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('_growth_b_warmup_http', ROOT / 'scripts/verify-growth-b-dev.py')
http = importlib.util.module_from_spec(spec); spec.loader.exec_module(http)
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
from growth_b_contract import public_account_union

need, CheckFailed = http.need, http.CheckFailed
KINDS = ('publishedListingReviews', 'wishlistItems', 'memberReservations', 'memberWishlists', 'hostListings')
BANDS = ('popular', 'middle', 'tail')
ROUTES = {
    'publishedListingReviews': ('/api/v1/accommodations/{resourceId}/reviews?size=20', 'reviews'),
    'wishlistItems': ('/api/v1/members/wishlists/accommodations/{resourceId}?size=20', 'wishlist_accommodations'),
    'memberReservations': ('/api/v1/profile/guest/reservations?filterType=PAST&size=20', 'reservations'),
    'memberWishlists': ('/api/v1/members/wishlists?size=20', 'wishlists'),
    'hostListings': ('/api/v1/profile/host/accommodations?size=20', 'accommodations'),
}
TARGETS, REPETITIONS, MAX_SECONDS = 15, 20, 900
CLEANUP_SECONDS, CLEANUP_REQUEST_SECONDS = 60, 2
MAX_HTTP_REQUESTS = TARGETS * REPETITIONS + TARGETS * 4
FINGERPRINT_STATES = {'BASELINE_ALL_ROWS_AND_DDL_VERIFIED', 'PREPARED_ALL_ROWS_AND_DDL_VERIFIED',
                      'SERVICE_MUTATIONS_RESET_AND_VERIFIED'}
APP_STATES = {'READINESS_VERIFIED_EXISTING_PROCESS', 'READINESS_VERIFIED_AFTER_RESTART'}
CACHE_STATES = {'DISABLED', 'ENABLED_EXISTING_CONTENTS', 'ENABLED_EMPTY_CONFIRMED_EXTERNALLY'}


def positive_id(value): return http.integer(value, 1) and value <= 2**63 - 1


def target_path(target):
    kind = target.get('kind')
    need(kind in KINDS and target.get('method') == 'GET' and target.get('band') in BANDS
         and positive_id(target.get('ownerMemberId')) and http.integer(target.get('fanout')), 'WORKLOAD_TARGET_INVALID')
    resource = kind in KINDS[:2]
    need(set(target) == {'kind', 'band', 'ownerMemberId', 'fanout', 'method', 'path'} | ({'resourceId'} if resource else set()),
         'WORKLOAD_TARGET_INVALID')
    need(not resource or positive_id(target.get('resourceId')), 'WORKLOAD_RESOURCE_INVALID')
    expected = ROUTES[kind][0].format(resourceId=target.get('resourceId'))
    need(target.get('path') == expected, 'WORKLOAD_ROUTE_MISMATCH')
    return expected


def permitted_request(method, path, body=None):
    if method == 'POST' and path in {http.AUTH + 'login', http.AUTH + 'logout'}:
        http.request_template(method, path, body=body)
        return
    need(method == 'GET' and body is None, 'REQUEST_NOT_ALLOWED')
    if path == http.AUTH + 'me': return
    for template, _ in ROUTES.values():
        pattern = re.escape(template).replace(re.escape('{resourceId}'), r'[1-9][0-9]{0,18}')
        if re.fullmatch(pattern, path): return
    raise CheckFailed('REQUEST_NOT_ALLOWED')


def private_accounts(raw, public, bundle, environment):
    value = http.parse_json(raw)
    need(isinstance(value, dict) and value.get('schemaVersion') == 2 and 'password' not in value
         and value.get('datasetProfile') == 'global-growth-b' and value.get('environment') == environment
         and value.get('loadPool') == {'state': 'NOT_CONFIGURED', 'targetConcurrentMembers': None, 'memberCount': 0},
         'PRIVATE_ENVIRONMENT_OR_PURPOSE_MISMATCH')
    rows = value.get('credentials')
    need(isinstance(rows, list) and len(rows) == len(public), 'PRIVATE_FULL_ACCOUNT_COVERAGE_REQUIRED')
    expected = {row['memberId']: row for row in public}
    representatives = {row['memberId']: row for row in bundle['representativeAccounts']}
    selected, passwords = {}, set()
    for row in rows:
        need(isinstance(row, dict), 'PRIVATE_ACCOUNT_INVALID')
        member, password = row.get('memberId'), row.get('password')
        need(positive_id(member) and member in expected and member not in selected
             and all(row.get(key) == expected[member][key] for key in ('email', 'role')), 'PRIVATE_ACCOUNT_IDENTITY_MISMATCH')
        group = ('administrator' if representatives[member]['key'] == 'admin' else 'representative') if member in representatives else 'qualification'
        need(row.get('environment') == environment and row.get('group') == group
             and isinstance(row.get('purpose'), str) and row['purpose'].strip()
             and isinstance(password, str) and 16 <= len(password.encode()) <= 72 and password not in passwords,
             'PRIVATE_CREDENTIAL_CONTRACT_MISMATCH')
        selected[member] = row; passwords.add(password)
    return selected


def read_inputs(config):
    required = {'schemaVersion', 'releaseDirectory', 'datasetId', 'consumerManifestSha256', 'checksumsSha256',
                'workloadSha256', 'privateAccounts', 'baseUrl', 'targetMode', 'accountEnvironment', 'preconditions'}
    need(isinstance(config, dict) and required <= set(config)
         and set(config) <= required | {'allowSmallQualification', 'requestTimeoutSeconds'}
         and config['schemaVersion'] == 1, 'INVALID_WARMUP_CONFIGURATION')
    release = Path(config['releaseDirectory']).absolute()
    need(release.is_dir() and not release.is_symlink(), 'RELEASE_DIRECTORY_REQUIRED')
    for key in ('consumerManifestSha256', 'checksumsSha256', 'workloadSha256'):
        need(http.digest(config[key]), 'EXTERNAL_ANCHORS_REQUIRED')
    raw_checks = http.read_bytes(release / 'SHA256SUMS.json')
    raw_consumer = http.read_bytes(release / 'consumer-manifest.json')
    need(http.hash_bytes(raw_checks) == config['checksumsSha256']
         and http.hash_bytes(raw_consumer) == config['consumerManifestSha256'], 'RELEASE_ANCHOR_MISMATCH')
    checks, consumer = http.parse_json(raw_checks), http.parse_json(raw_consumer)
    need(isinstance(checks, dict) and isinstance(consumer, dict), 'INVALID_RELEASE_METADATA')
    dump_sha = checks.get('airbob-growth.sql.gz')
    need(http.digest(dump_sha) and config['datasetId'] == consumer.get('datasetId') == 'global-growth-b-' + dump_sha[:16]
         and checks.get('consumer-manifest.json') == config['consumerManifestSha256'], 'DATASET_IDENTITY_MISMATCH')
    small = consumer.get('datasetScale') == 'small-qualification'
    need(consumer.get('schemaVersion') == 4 and consumer.get('datasetVersion') == 'benchmark-dataset-v4'
         and consumer.get('mysql') == {'version': '8.4.11', 'flywayVersion': 28}
         and consumer.get('qualification') == 'SMALL_LOCAL_SCENARIOS' and http.digest(consumer.get('appJarSha256'))
         and ((small and config.get('allowSmallQualification') is True and consumer.get('finalScaleSelected') is False)
              or (not small and consumer.get('datasetScale') == 'selected-global-b-ten-million'
                  and consumer.get('finalScaleSelected') is True)), 'QUALIFIED_B_SCALE_REQUIRED')
    values, bindings = {}, {}
    for name, artifact in (('manifest.json', None), ('accounts.json', 'accounts'), ('base-scenario-targets.json', 'baseWorkloads')):
        raw = http.read_bytes(release / name); checksum = http.hash_bytes(raw)
        need(checks.get(name) == checksum, 'CONSUMED_ARTIFACT_CHECKSUM_MISMATCH')
        if artifact:
            need(consumer.get('artifacts', {}).get(artifact) == {'file': name, 'sha256': checksum}, 'CONSUMED_ARTIFACT_BINDING_MISMATCH')
        values[name] = http.parse_json(raw); bindings[name] = checksum
    root, bundle, workload = (values[name] for name in ('manifest.json', 'accounts.json', 'base-scenario-targets.json'))
    need(isinstance(root, dict) and root.get('datasetId') == config['datasetId'] and root.get('datasetProfile') == 'global-growth-b'
         and root.get('datasetScale') == consumer['datasetScale'] and root.get('finalScaleSelected') is (not small)
         and root.get('state') == ('SMALL_DB_AND_HTTP_QUALIFIED' if small else 'DATASET_DB_AND_HTTP_QUALIFIED'),
         'QUALIFIED_RELEASE_REQUIRED')
    need(isinstance(workload, dict) and workload.get('schemaVersion') == 1
         and bindings['base-scenario-targets.json'] == config['workloadSha256'], 'WORKLOAD_ANCHOR_MISMATCH')
    targets = workload.get('targets')
    need(isinstance(targets, list) and len(targets) == TARGETS and all(isinstance(row, dict) for row in targets), 'EXACT_FIFTEEN_TARGETS_REQUIRED')
    need([(row.get('kind'), row.get('band')) for row in targets] == [(kind, band) for kind in KINDS for band in BANDS],
         'SEALED_TARGET_ORDER_REQUIRED')
    for target in targets: target_path(target)
    need(isinstance(bundle, dict), 'PUBLIC_ACCOUNT_CONTRACT_MISMATCH')
    try: public = public_account_union(bundle)
    except (ValueError, KeyError, TypeError): raise CheckFailed('PUBLIC_ACCOUNT_CONTRACT_MISMATCH') from None
    need(len(public) == (113 if small else 124) and bundle.get('baseScenarioTargets') == targets
         and all(isinstance(row.get('nickname'), str) for row in public), 'FULL_B_ACCOUNT_OR_TARGET_CONTRACT_MISMATCH')
    public_by_id = {row['memberId']: row for row in public}
    need(all(row['ownerMemberId'] in public_by_id for row in targets), 'WORKLOAD_OWNER_NOT_IN_ACCOUNT_BUNDLE')
    mode, environment = config['targetMode'], config['accountEnvironment']
    need(mode in {'oci', 'aws'}, 'UNSUPPORTED_TARGET_MODE')
    if mode == 'oci': need(environment == 'oci:mysql:' + config['datasetId'], 'TARGET_ACCOUNT_ENVIRONMENT_MISMATCH')
    else:
        need(isinstance(environment, str) and re.fullmatch(r'aws:db-[A-Z0-9]{1,64}:[0-9a-f-]{36}', environment), 'TARGET_ACCOUNT_ENVIRONMENT_MISMATCH')
        try: need(str(uuid.UUID(environment.rsplit(':', 1)[1])) == environment.rsplit(':', 1)[1], 'TARGET_ACCOUNT_ENVIRONMENT_MISMATCH')
        except ValueError: raise CheckFailed('TARGET_ACCOUNT_ENVIRONMENT_MISMATCH') from None
    credentials = private_accounts(http.read_bytes(config['privateAccounts'], private=True), public, bundle, environment)
    preconditions = config['preconditions']
    need(isinstance(preconditions, dict) and set(preconditions) == {'fingerprintState', 'fingerprintReceiptSha256', 'appState', 'appImage', 'detailCacheState'}
         and preconditions.get('fingerprintState') in FINGERPRINT_STATES
         and http.digest(preconditions.get('fingerprintReceiptSha256')) and preconditions.get('appState') in APP_STATES
         and preconditions.get('detailCacheState') in CACHE_STATES
         and isinstance(preconditions.get('appImage'), str)
         and re.fullmatch(r'(?:[a-zA-Z0-9./:_-]+@)?sha256:[0-9a-f]{64}', preconditions['appImage']), 'AFTER_VERIFICATION_PRECONDITIONS_REQUIRED')
    timeout = config.get('requestTimeoutSeconds', 15)
    need(http.integer(timeout, 1) and timeout <= 30, 'INVALID_REQUEST_TIMEOUT')
    return {'datasetId': config['datasetId'], 'datasetScale': consumer['datasetScale'], 'baseUrl': http.base_url(config['baseUrl']),
            'targetMode': mode, 'targets': targets, 'credentials': credentials, 'public': public_by_id, 'timeout': timeout,
            'preconditions': dict(preconditions), 'binding': {
                'consumerManifestSha256': config['consumerManifestSha256'], 'checksumsSha256': config['checksumsSha256'],
                'workloadSha256': config['workloadSha256'], 'consumedMetadata': bindings,
                'qualifiedAppJarSha256': consumer['appJarSha256'], 'accountEnvironment': environment, 'fullPrivateAccountCount': len(public)}}


class WarmupSession(http.HttpSession):
    def request(self, method, path, body=None):
        permitted_request(method, path, body)
        raw = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=raw, method=method,
            headers={'Accept': 'application/json', 'Accept-Encoding': 'identity', 'Content-Type': 'application/json'})
        try:
            try: response = self.opener.open(request, timeout=self.timeout)
            except urllib.error.HTTPError as error: response = error
            with response:
                need(response.headers.get('Content-Encoding', 'identity') == 'identity', 'HTTP_ENCODING_REJECTED')
                raw = response.read(http.MAX_RESPONSE_BYTES + 1)
                need(len(raw) <= http.MAX_RESPONSE_BYTES, 'HTTP_RESPONSE_TOO_LARGE')
                need('application/json' in response.headers.get('Content-Type', ''), 'HTTP_JSON_REQUIRED')
                return response.code, http.parse_json(raw)
        except (OSError, urllib.error.URLError, TimeoutError): raise CheckFailed('HTTP_TRANSPORT_FAILED') from None


@contextlib.contextmanager
def wall_clock_limit(seconds):
    """Socket read timeouts alone do not bound a slow, continuously streaming body."""
    need(hasattr(signal, 'setitimer') and signal.getitimer(signal.ITIMER_REAL) == (0.0, 0.0), 'REQUEST_DEADLINE_UNAVAILABLE')
    previous = signal.getsignal(signal.SIGALRM)
    def expired(*_): raise CheckFailed('REQUEST_DEADLINE_EXCEEDED')
    try:
        signal.signal(signal.SIGALRM, expired)
        signal.setitimer(signal.ITIMER_REAL, seconds)
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


class Warmer:
    def __init__(self, inputs, *, session_factory=WarmupSession, clock=time.monotonic_ns):
        self.inputs, self.factory, self.clock = inputs, session_factory, clock
        self.started = clock(); self.deadline = self.started + MAX_SECONDS * 1_000_000_000
        self.work_deadline = self.deadline - CLEANUP_SECONDS * 1_000_000_000
        self.events = {'startedAt': http.utc_now()}; self.event_clock = {'started': self.started}
        self.sessions = {}; self.auth = []; self.accounts = []
        self.requests = 0; self.failure = None; self.cleanup_failures = []
        slots = {owner: i + 1 for i, owner in enumerate(dict.fromkeys(row['ownerMemberId'] for row in inputs['targets']))}
        self.targets = [{'targetIndex': i + 1, 'kind': row['kind'], 'band': row['band'], 'endpointTemplate': ROUTES[row['kind']][0],
                         'ownerSlot': slots[row['ownerMemberId']], 'samples': []} for i, row in enumerate(inputs['targets'])]

    def mark(self, stage):
        self.events[stage + 'At'] = http.utc_now(); self.event_clock[stage] = self.clock()

    def elapsed(self, start, end):
        if start not in self.event_clock: return None
        return round((self.event_clock[end] - self.event_clock[start]) / 1_000_000_000, 6)

    def request(self, session, purpose, method, path, records, record, *, body=None, expected=(200,), cleanup=False):
        permitted_request(method, path, body)
        deadline = self.deadline if cleanup else self.work_deadline
        remaining = (deadline - self.clock()) / 1_000_000_000
        need(remaining > 0, 'CLEANUP_DEADLINE_REACHED' if cleanup else 'WARMUP_DEADLINE_REACHED')
        need(self.requests < MAX_HTTP_REQUESTS, 'REQUEST_BUDGET_EXHAUSTED')
        timeout = min(CLEANUP_REQUEST_SECONDS if cleanup else self.inputs['timeout'], remaining)
        session.timeout = timeout; self.requests += 1
        item = record | {'purpose': purpose, 'status': None, 'passed': False, 'startedAt': http.utc_now()}
        records.append(item); started = self.clock()
        try:
            with wall_clock_limit(timeout): actual, response = session.request(method, path, body)
            item['status'] = actual if type(actual) is int and 100 <= actual <= 599 else None
            need(self.clock() <= deadline, 'CLEANUP_DEADLINE_REACHED' if cleanup else 'WARMUP_DEADLINE_REACHED')
            need(type(actual) is int and actual in expected, 'UNEXPECTED_HTTP_STATUS')
            if actual == 200:
                need(isinstance(response, dict) and response.get('success') is True, 'API_SUCCESS_REQUIRED')
                result = response.get('data')
            else: result = None
            item['passed'] = True
            return result, item
        except CheckFailed as error:
            item['failureCode'] = error.code; raise
        except Exception:
            item['failureCode'] = 'HTTP_REQUEST_FAILED'; raise CheckFailed('HTTP_REQUEST_FAILED') from None
        finally:
            item['elapsedSeconds'] = round((self.clock() - started) / 1_000_000_000, 6)
            item['completedAt'] = http.utc_now()

    def login(self):
        self.mark('loginPreparationStarted')
        owners = dict.fromkeys(row['ownerMemberId'] for row in self.inputs['targets'])
        for slot, owner in enumerate(owners, 1):
            session = self.factory(self.inputs['baseUrl'], self.inputs['timeout'])
            self.sessions[owner] = session
            account = {'ownerSlot': slot, 'normalLoginAndIdentityVerified': False, 'logoutInvalidatesSession': False}
            self.accounts.append(account)
            credential, expected = self.inputs['credentials'][owner], self.inputs['public'][owner]
            self.request(session, 'normal-login', 'POST', http.AUTH + 'login', self.auth, {'ownerSlot': slot},
                         body={key: credential[key] for key in ('email', 'password')})
            need(session.has_session(), 'SESSION_COOKIE_REQUIRED')
            identity, observation = self.request(session, 'verify-owner', 'GET', http.AUTH + 'me', self.auth, {'ownerSlot': slot})
            matches = isinstance(identity, dict) and positive_id(identity.get('id')) and identity['id'] == owner
            matches = matches and all(identity.get(key) == expected.get(key) for key in ('email', 'nickname'))
            observation['passed'] = matches
            if not matches: observation['failureCode'] = 'AUTHENTICATED_OWNER_MISMATCH'
            need(matches, 'AUTHENTICATED_OWNER_MISMATCH')
            account['normalLoginAndIdentityVerified'] = True
        self.mark('loginPreparationCompleted')

    def workload(self):
        self.mark('workloadStarted')
        for target, report in zip(self.inputs['targets'], self.targets):
            for sample in range(1, REPETITIONS + 1):
                data, observation = self.request(self.sessions[target['ownerMemberId']], 'sealed-workload-read', 'GET', target_path(target),
                                                report['samples'], {'sample': sample})
                rows = data.get(ROUTES[target['kind']][1]) if isinstance(data, dict) else None
                valid = isinstance(rows, list) and len(rows) <= 20 and all(isinstance(row, dict) for row in rows)
                observation['passed'] = valid
                if not valid: observation['failureCode'] = 'WORKLOAD_RESPONSE_SHAPE_MISMATCH'
                need(valid, 'WORKLOAD_RESPONSE_SHAPE_MISMATCH')
        self.mark('workloadCompleted')

    def cleanup(self):
        self.mark('cleanupStarted')
        for (owner, session), account in zip(self.sessions.items(), self.accounts):
            slot = account['ownerSlot']; failures = []
            for purpose, method, path, expected in (
                ('logout-own-session', 'POST', http.AUTH + 'logout', (200, 401)),
                ('verify-logout', 'GET', http.AUTH + 'me', (401,))):
                try: self.request(session, purpose, method, path, self.auth, {'ownerSlot': slot}, expected=expected, cleanup=True)
                except CheckFailed as error: failures.append({'ownerSlot': slot, 'purpose': purpose, 'failureCode': error.code})
                except Exception: failures.append({'ownerSlot': slot, 'purpose': purpose, 'failureCode': 'SESSION_CLEANUP_FAILED'})
            try: session.clear()
            except Exception: failures.append({'ownerSlot': slot, 'purpose': 'clear-local-cookies', 'failureCode': 'SESSION_CLEANUP_FAILED'})
            account['logoutInvalidatesSession'] = not failures
            self.cleanup_failures.extend(failures)
        self.mark('cleanupCompleted')

    def run(self):
        try:
            self.login(); self.workload()
        except CheckFailed as error: self.failure = error.code
        except KeyboardInterrupt: self.failure = 'INTERRUPTED'
        except Exception: self.failure = 'UNCLASSIFIED_FAILURE'
        finally: self.cleanup()
        self.mark('completed'); elapsed = self.elapsed('started', 'completed')
        if elapsed > MAX_SECONDS: self.failure = self.failure or 'TOTAL_DEADLINE_EXCEEDED'
        if self.cleanup_failures: self.failure = self.failure or 'SESSION_CLEANUP_FAILED'
        unfinished = []
        for report in self.targets:
            report['successfulSamples'] = sum(row['passed'] for row in report['samples'])
            if report['successfulSamples'] < REPETITIONS:
                unfinished.append({key: report[key] for key in ('targetIndex', 'kind', 'band', 'successfulSamples')}
                                  | {'remainingSamples': REPETITIONS - report['successfulSamples']})
        passed = self.failure is None and not unfinished
        return {'schemaVersion': 1, 'kind': 'global-b-after-verification-service-warmup',
                'state': 'AFTER_VERIFICATION_WARMUP_' + ('COMPLETED' if passed else 'FAILED'), 'passed': passed,
                'datasetId': self.inputs['datasetId'], 'datasetScale': self.inputs['datasetScale'], 'targetMode': self.inputs['targetMode'],
                'baseUrl': self.inputs['baseUrl'], 'binding': self.inputs['binding'], 'preconditions': self.inputs['preconditions'],
                'preconditionsVerifiedByThisTool': False, 'accessState': 'AFTER_FULL_FINGERPRINT_VERIFICATION',
                'timing': self.events | {'elapsedClock': 'monotonic', 'totalElapsedSeconds': elapsed,
                    'loginPreparationElapsedSeconds': self.elapsed('loginPreparationStarted', 'loginPreparationCompleted' if 'loginPreparationCompleted' in self.event_clock else 'cleanupStarted'),
                    'workloadElapsedSeconds': self.elapsed('workloadStarted', 'workloadCompleted' if 'workloadCompleted' in self.event_clock else 'cleanupStarted'),
                    'cleanupElapsedSeconds': self.elapsed('cleanupStarted', 'cleanupCompleted')},
                'bounds': {'targets': TARGETS, 'getRequestsPerTarget': REPETITIONS, 'workloadGetRequests': TARGETS * REPETITIONS,
                           'concurrency': 1, 'maximumSecondsIncludingLoginAndCleanup': MAX_SECONDS,
                           'reservedCleanupSeconds': CLEANUP_SECONDS, 'maximumHttpRequests': MAX_HTTP_REQUESTS,
                           'requestTimeoutSeconds': self.inputs['timeout']},
                'requestCount': self.requests, 'workloadGetCount': sum(len(row['samples']) for row in self.targets),
                'successfulWorkloadGetCount': sum(row['successfulSamples'] for row in self.targets),
                'accounts': self.accounts, 'authenticationAndCleanup': self.auth, 'targets': self.targets,
                'unfinishedTargets': unfinished, 'failureCode': self.failure, 'cleanupFailures': self.cleanup_failures,
                'scope': {'businessWriteRequests': 0, 'redisFlushes': 0, 'cloudApiRequests': 0,
                          'privateCredentialFileModified': False, 'credentialsCookiesAndPersonalDataRecorded': False,
                          'dumpOrDatabaseFingerprintRevalidated': False,
                          'workloadMeaning': 'Fixed after-verification reads; no whole-database warming or load-test claim'}}


def run(config, output, *, session_factory=WarmupSession, clock=time.monotonic_ns):
    inputs = read_inputs(config)
    output = Path(output).absolute()
    need(not output.resolve().is_relative_to(Path(config['releaseDirectory']).resolve()), 'OUTPUT_INSIDE_SEALED_RELEASE_REJECTED')
    need(output.parent.is_dir() and not output.parent.is_symlink(), 'OUTPUT_PARENT_REQUIRED')
    try: output.mkdir(mode=0o700, exist_ok=False)
    except OSError: raise CheckFailed('OUTPUT_MUST_BE_NEW_DIRECTORY') from None
    evidence = Warmer(inputs, session_factory=session_factory, clock=clock).run()
    raw = (json.dumps(evidence, sort_keys=True, indent=2) + '\n').encode()
    target = output / 'warmup-receipt.json'
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream: stream.write(raw)
    return evidence, target, http.hash_bytes(raw)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True); parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        evidence, target, checksum = run(http.parse_json(http.read_bytes(args.config)), args.output)
        print(json.dumps({'state': evidence['state'], 'passed': evidence['passed'], 'report': str(target), 'sha256': checksum}))
        return 0 if evidence['passed'] else 1
    except CheckFailed as error:
        print(json.dumps({'state': 'WARMUP_REJECTED', 'failureCode': error.code})); return 2
    except Exception:
        print(json.dumps({'state': 'WARMUP_REJECTED', 'failureCode': 'UNCLASSIFIED_FAILURE'})); return 2


if __name__ == '__main__': sys.exit(main())
