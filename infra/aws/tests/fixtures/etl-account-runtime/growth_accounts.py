"""Shareable experiment accounts, private credentials and real login qualification."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import tempfile


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def write_private(path, value):
    """Create privately from the first byte and refuse symlinks or shared directories."""
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.parent.is_symlink() or stat.S_IMODE(path.parent.stat().st_mode) & 0o077:
        raise ValueError('Private account directory must be a real directory with mode 0700')
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, 'O_NOFOLLOW', 0)
    fd = os.open(path, flags, 0o600)
    with os.fdopen(fd, 'w') as destination:
        os.fchmod(destination.fileno(), 0o600)
        json.dump(value, destination, ensure_ascii=False, indent=2)
        destination.write('\n')


def read_private(path):
    path = Path(path)
    if (path.is_symlink() or not path.is_file() or stat.S_IMODE(path.stat().st_mode) != 0o600
            or path.parent.is_symlink() or stat.S_IMODE(path.parent.stat().st_mode) != 0o700):
        raise ValueError('Private account file must have mode 0600 in a real 0700 directory')
    try:
        value = json.loads(path.read_text())
    except (ValueError, UnicodeError):
        raise ValueError('Malformed private account file') from None
    return validate_private(value)


REPRESENTATIVES = {'demo': ('demo@airbob.test', 'MEMBER'),
                   'host': ('host@airbob.test', 'MEMBER'), 'admin': ('admin@airbob.test', 'ADMIN')}


def validate_private(value):
    if (not isinstance(value, dict) or value.get('schemaVersion') != 2 or 'password' in value
            or not isinstance(value.get('environment'), str) or not value['environment'].strip()
            or not isinstance(value.get('datasetProfile'), str) or not value['datasetProfile']
            or not isinstance(value.get('credentials'), list) or not value['credentials']):
        raise ValueError('Expected private schema v2 with per-account credentials and an explicit environment')
    ids, emails, passwords = set(), set(), set()
    for item in value['credentials']:
        if not isinstance(item, dict):
            raise ValueError('Invalid private credential record')
        member, email, role, group, password = (item.get(k) for k in ('memberId', 'email', 'role', 'group', 'password'))
        if (type(member) is not int or member <= 0 or member in ids or not isinstance(email, str)
                or '@' not in email or email in emails or role not in {'MEMBER', 'ADMIN'}
                or group not in {'representative', 'administrator', 'qualification', 'load'}
                or not isinstance(password, str) or len(password) < 16 or len(password.encode()) > 72
                or password in passwords):
            raise ValueError('Private credentials need unique valid identities and distinct 16..72-byte passwords')
        if ((role == 'ADMIN') != (group == 'administrator')
                or group == 'administrator' and email != 'admin@airbob.test'
                or group == 'representative' and email not in {'demo@airbob.test', 'host@airbob.test'}):
            raise ValueError('Administrator and demonstration credential groups must match existing roles and aliases')
        ids.add(member); emails.add(email); passwords.add(password)
    return value


def credential_for_email(private_or_path, email):
    private = read_private(private_or_path) if isinstance(private_or_path, (str, Path)) else validate_private(private_or_path)
    matches = [row for row in private['credentials'] if row['email'] == email]
    if len(matches) != 1:
        raise ValueError('No unique prepared credential for the requested email')
    return matches[0]


def credential_for_member(private_or_path, member_id):
    private = read_private(private_or_path) if isinstance(private_or_path, (str, Path)) else validate_private(private_or_path)
    matches = [row for row in private['credentials'] if row['memberId'] == int(member_id)]
    if len(matches) != 1:
        raise ValueError('No unique prepared credential for the requested member')
    return matches[0]


def representative_rows(representatives):
    rows = representatives['accounts'] if isinstance(representatives, dict) else representatives
    if not isinstance(rows, list) or len(rows) != 3:
        raise ValueError('Exactly three sealed representative accounts are required')
    ids, keys = set(), set()
    for row in rows:
        member, key = row.get('memberId'), row.get('key')
        if (type(member) is not int or member <= 0 or member in ids or key in keys
                or key not in REPRESENTATIVES or (row.get('email'), row.get('role')) != REPRESENTATIVES[key]
                or row.get('status') != 'ACTIVE'):
            raise ValueError('Invalid sealed representative identity, alias, or existing role')
        ids.add(member); keys.add(key)
    return rows


def verify_representative_accounts(db, representatives):
    rows = representative_rows(representatives)
    counts = {
        'listings': "SELECT COUNT(*) FROM accommodation WHERE member_id={member} AND status<>'DELETED'",
        'publishedListings': "SELECT COUNT(*) FROM accommodation WHERE member_id={member} AND status='PUBLISHED'",
        'reservations': 'SELECT COUNT(*) FROM reservation WHERE guest_id={member}',
        'reviews': "SELECT COUNT(*) FROM review WHERE member_id={member} AND status='PUBLISHED'",
        'wishlists': "SELECT COUNT(*) FROM wishlist WHERE member_id={member} AND status='ACTIVE'",
        'receivedReservations': 'SELECT COUNT(*) FROM reservation r JOIN accommodation a ON a.id=r.accommodation_id WHERE a.member_id={member}',
        'receivedReviews': "SELECT COUNT(*) FROM review v JOIN accommodation a ON a.id=v.accommodation_id WHERE a.member_id={member} AND v.status='PUBLISHED'",
        'settlements': 'SELECT COUNT(*) FROM settlement WHERE host_id={member}',
    }
    for expected in rows:
        member = expected['memberId']
        actual = json.loads(db.scalar("SELECT JSON_OBJECT('memberId',id,'email',email,'role',role,'status',status) "
                                      f'FROM member WHERE id={member}'))
        if any(actual.get(key) != expected[key] for key in ('memberId', 'email', 'role', 'status')):
            raise AssertionError('Restored representative identity differs from the sealed baseline')
        required = {'demo': ('listings', 'publishedListings', 'reservations', 'reviews', 'wishlists'),
                    'host': ('listings', 'publishedListings', 'receivedReservations', 'receivedReviews', 'settlements'), 'admin': ()}[expected['key']]
        for metric in required:
            recorded = expected.get('ownership', {}).get(metric, {}).get('count')
            if not isinstance(recorded, int) or recorded <= 0 or int(db.scalar(counts[metric].format(member=member))) != recorded:
                raise AssertionError('Restored representative ownership differs from its sealed evidence')
    return {'passed': True, 'accountCount': 3, 'identityRoleAndOwnershipVerified': True,
            'emailMutationAfterRestore': False,
            'accounts': [{key: row[key] for key in ('key', 'memberId', 'email', 'role', 'status')} for row in rows]}


def public_account_union(bundle):
    selected = {int(row['memberId']): row for row in bundle['accounts']}
    for row in representative_rows(bundle['representativeAccounts']):
        previous = selected.get(row['memberId'])
        if previous and any(previous[k] != row[k] for k in ('email', 'role')):
            raise ValueError('Qualification and representative identities disagree')
        selected[row['memberId']] = row
    return [selected[key] for key in sorted(selected)]


def create_private_credentials(bundle, private_output, environment, *, existing=None,
                               target_concurrent_members=None, load_members=None):
    if not isinstance(environment, str) or not environment.strip():
        raise ValueError('The actual preparation environment must be explicit')
    private_output = Path(private_output)
    if existing is None and (private_output / 'accounts.private.json').exists():
        existing = read_private(private_output / 'accounts.private.json')
    accounts = public_account_union(bundle)
    representative_ids = {row['memberId']: row for row in bundle['representativeAccounts']}
    old = validate_private(existing) if existing else None
    if old and (old['datasetProfile'] != bundle['datasetProfile'] or old['environment'] != environment):
        raise ValueError('Saved credentials belong to another dataset or environment')
    previous = {row['memberId']: row for row in old['credentials']} if old else {}
    load_members = [] if load_members is None else load_members
    if target_concurrent_members is None:
        if load_members:
            raise ValueError('A load pool requires an explicit target concurrent member count')
        load_pool = {'state': 'NOT_CONFIGURED', 'targetConcurrentMembers': None, 'memberCount': 0}
    else:
        if type(target_concurrent_members) is not int or target_concurrent_members <= 0 or len(load_members) != target_concurrent_members:
            raise ValueError('The selected load pool must match the explicit concurrent member count')
        used = {row['memberId'] for row in accounts}
        if any(row['memberId'] in used or row.get('role') != 'MEMBER' or row.get('status') != 'ACTIVE' for row in load_members):
            raise ValueError('Load members must be separate existing active MEMBER accounts')
        load_pool = {'state': 'SELECTED_REQUIRES_PREPARATION_AND_LOGIN',
                     'targetConcurrentMembers': target_concurrent_members, 'memberCount': len(load_members)}
    credentials = []
    load_ids = {row['memberId'] for row in load_members}
    for row in accounts + load_members:
        representative = representative_ids.get(row['memberId'])
        group = ('administrator' if representative['key'] == 'admin' else 'representative') if representative else (
            'load' if row['memberId'] in load_ids else 'qualification')
        saved = previous.get(row['memberId'])
        if saved and any(saved[k] != value for k, value in [('email', row['email']), ('role', row['role']), ('group', group)]):
            raise ValueError('Saved credential identity or purpose changed')
        credentials.append({'memberId': row['memberId'], 'email': row['email'], 'role': row['role'], 'group': group,
            'purpose': row.get('purpose') or ', '.join(row.get('labels', [])) or 'Explicit synthetic load member',
            'password': saved['password'] if saved else secrets.token_urlsafe(36),
            'environment': environment, 'loginState': 'NOT_VERIFIED', 'usable': False})
    private = validate_private({'schemaVersion': 2, 'datasetProfile': bundle['datasetProfile'],
        'environment': environment, 'audience': 'machine only', 'credentials': credentials, 'loadPool': load_pool})
    write_private(private_output / 'accounts.private.json', private)
    write_account_handoffs(private_output, private)
    return private


def write_account_handoffs(private_output, private):
    for filename, groups, audience in [
            ('representative-accounts.private.json', {'representative', 'administrator'}, 'operator: representative three only'),
            ('demo-accounts.private.json', {'representative'}, 'interviewer: demo and host only'),
            ('administrator.private.json', {'administrator'}, 'administrator only')]:
        selected = [row for row in private['credentials'] if row['group'] in groups]
        write_private(Path(private_output) / filename, {'schemaVersion': 2, 'datasetProfile': private['datasetProfile'],
            'environment': private['environment'], 'audience': audience, 'credentials': selected,
            'usage': 'Usable is true only after normal login and identity verification in the recorded environment.'})


def update_login_state(private_input, member_ids, environment, *, usable, reason=None):
    private = read_private(private_input)
    if private['environment'] != environment:
        raise ValueError('Login verification belongs to another environment')
    selected = set(map(int, member_ids))
    if not selected.issubset({row['memberId'] for row in private['credentials']}):
        raise ValueError('Login result refers to an unprepared member')
    for row in private['credentials']:
        if row['memberId'] in selected:
            row.update(loginState='VERIFIED' if usable else 'NOT_AVAILABLE', usable=usable)
            if usable:
                row.update(verifiedAt=dt.datetime.now(dt.timezone.utc).isoformat(), verifiedEnvironment=environment)
                row.pop('unavailableReason', None)
            else:
                row['unavailableReason'] = reason or 'Preparation or qualification is not complete'
    write_private(private_input, private)
    write_account_handoffs(Path(private_input).parent, private)
    return private


def select_load_members(db, target_concurrent_members, *, excluded_member_ids):
    if type(target_concurrent_members) is not int or target_concurrent_members <= 0:
        raise ValueError('Target concurrent members must be explicitly provided as a positive integer')
    excluded = sorted(set(map(int, excluded_member_ids)))
    exclusion = ' AND id NOT IN (' + ','.join(map(str, excluded)) + ')' if excluded else ''
    rows = db.rows("SELECT id memberId,email,role,status FROM member WHERE status='ACTIVE' AND role='MEMBER'"
                   + exclusion + ' ORDER BY id LIMIT ' + str(target_concurrent_members))
    if len(rows) != target_concurrent_members:
        raise ValueError('The existing member population cannot supply the requested separate load pool')
    return rows


def required_runtime_member_ids(db, fixtures, query_targets, expiration_targets):
    """Resolve the owners used by existing qualification routes before preparing any passwords."""
    selected = {int(query_targets['guestFanouts'][0]['guest_id']),
                int(query_targets['wishlistFanouts'][0]['member_id'])}
    for status in ('CONFIRMED', 'CANCELLED'):
        rows = db.rows("SELECT r.guest_id memberId FROM payment p JOIN reservation r ON r.id=p.reservation_id "
                       "WHERE r.status='" + status + "' ORDER BY r.id LIMIT 1")
        selected.update(int(row['memberId']) for row in rows)
    selected.update(int(row['memberId']) for row in db.rows(
        'SELECT requester_member_id memberId FROM payment_operation ORDER BY id LIMIT 2'))
    rows = db.rows('SELECT member_id memberId FROM member_coupon GROUP BY member_id ORDER BY COUNT(*) DESC,member_id LIMIT 1')
    selected.update(int(row['memberId']) for row in rows)
    if expiration_targets != 8:
        supply = db.rows("SELECT member_id memberId FROM accommodation WHERE status='PUBLISHED' AND base_price>1000 ORDER BY id LIMIT "
                         + str(int(expiration_targets) + 1))
        if len(supply) != expiration_targets + 1:
            raise AssertionError('Expiration owner selection has insufficient existing supply')
        selected.add(int(supply[-1]['memberId']))
    else:
        selected.add(int(fixtures['hostId']))
    return sorted(selected)


def _points(targets, name):
    measured = targets['measured'][name]
    return [(band, row) for band, label in [('popular', 'max'), ('middle', 'p50'), ('tail', 'min')]
            if (row := measured['targets'].get(label)) is not None]


def prepare_account_bundle(db, fixtures, measured_targets, output, private_output, profile=None, *,
                           representatives, environment, private_credentials=None, extra_member_ids=()):
    """Call after exact restored fingerprint comparison, before credential mutation.

    Writes no passwords or sessions to output. private_output must outlive temporary DB
    credentials so an operator can prepare the same accounts after an independent restore.
    """
    output, private_output = Path(output), Path(private_output)
    output.mkdir(parents=True, exist_ok=True)
    verify_representative_accounts(db, representatives)
    representatives = representative_rows(representatives)
    selected = {}
    def select(member, label):
        selected.setdefault(int(member), set()).add(label)
    for key in ('hostId', 'guestId', 'adminId'):
        select(fixtures[key], 'boundary-' + key.removesuffix('Id'))
    for member in fixtures['reviewWriterIds']:
        select(member, 'boundary-review-writer-and-coupon-contender')
    for member in extra_member_ids:
        select(member, 'http-smoke-owner')
    for name in ('hostListings', 'memberReservations', 'memberWishlists', 'memberCoupons', 'hostSettlements'):
        for band, pool in measured_targets['measured'][name]['requestPools'].items():
            for row in pool[:8]:
                select(row['key_id'], name + '-' + band)
    # Include the owner of every advertised wishlist and published listing target.
    scenarios = []
    for name in ('publishedListingReviews', 'wishlistItems'):
        for band, point in _points(measured_targets, name):
            key = int(point['key_id'])
            table = 'accommodation' if name == 'publishedListingReviews' else 'wishlist'
            owner = int(db.scalar(f'SELECT member_id FROM {table} WHERE id={key}'))
            select(owner, name + '-' + band + '-owner')
            scenarios.append({'kind': name, 'band': band, 'resourceId': key, 'ownerMemberId': owner,
                'fanout': point['n'], 'method': 'GET',
                'path': f'/api/v1/accommodations/{key}/reviews?size=20' if table == 'accommodation'
                    else f'/api/v1/members/wishlists/accommodations/{key}?size=20'})
    for name, path in [('memberReservations', '/api/v1/profile/guest/reservations?filterType=PAST&size=20'),
                       ('memberWishlists', '/api/v1/members/wishlists?size=20'),
                       ('hostListings', '/api/v1/profile/host/accommodations?size=20')]:
        for band, point in _points(measured_targets, name):
            member = int(point['key_id']); select(member, name + '-' + band)
            scenarios.append({'kind': name, 'band': band, 'ownerMemberId': member,
                              'fanout': point['n'], 'method': 'GET', 'path': path})
    accounts = []
    for member, labels in sorted(selected.items()):
        row = json.loads(db.scalar("SELECT JSON_OBJECT('memberId',id,'email',email,'nickname',nickname," +
                                   f"'role',role,'status',status) FROM member WHERE id={member}"))
        if row['status'] != 'ACTIVE':
            raise AssertionError('Selected experiment account is not active')
        owned = {}
        for label, table, owner in [('listings', 'accommodation', 'member_id'),
                                    ('wishlists', 'wishlist', 'member_id'),
                                    ('reservations', 'reservation', 'guest_id'),
                                    ('reviews', 'review', 'member_id'),
                                    ('coupons', 'member_coupon', 'member_id')]:
            owned[label] = {'count': int(db.scalar(f'SELECT COUNT(*) FROM {table} WHERE {owner}={member}')),
                            'sampleIds': [r['id'] for r in db.rows(f'SELECT id FROM {table} WHERE {owner}={member} ORDER BY id LIMIT 32')],
                            'completeSelectorSql': f'SELECT id FROM {table} WHERE {owner}={member} ORDER BY id'}
        accounts.append(row | {'labels': sorted(labels), 'ownership': owned,
                              'credentialRef': {'file': 'accounts.private.json', 'memberId': member}})
    reset = {'databasePolicy': 'one business database at a time',
             'steps': ['Stop the application and all scenario writers.',
                       'Remove only the run business database, restore the sealed compressed dump into an empty database.',
                       'Compare every table row hash and DDL hash with before-fingerprint.json before mutations.',
                       'Apply saved credentials with growth_accounts.py apply; it rechecks the full fingerprint first.',
                       'Seed only missing current inventory; preserve HOLD/OCCUPIED owners and wait for readiness.',
                       'Start isolated Redis dependencies; create fresh coupon campaigns, quotes and expiry fixtures immediately before writes.'],
             'liveState': 'Coupon stock, benchmark tokens, sessions, payment-attempt tokens and quotes are never reusable dump credentials.'}
    bundle = {'schemaVersion': 2, 'datasetProfile': (profile or {}).get('version'),
              'credentialState': 'requires saved credential preparation after every sealed restore',
              'accounts': accounts, 'accountCount': len(accounts), 'accountPurpose': 'qualification sample only',
              'representativeAccounts': representatives, 'representativeAccountCount': 3, 'baseScenarioTargets': scenarios,
              'boundaryWriteTargets': fixtures, 'reset': reset,
              'loadPoolPolicy': {'state': 'NOT_CONFIGURED', 'targetConcurrentMembers': None,
                                 'qualificationAccountsAreLoadPool': False,
                                 'oneMemberPerConcurrentWriter': True},
              'scenarioPreparation': {
                  'indexes': 'Use baseScenarioTargets with query catalogue; keep SQL result hashes equal.',
                  'reviewStatistics': 'Use boundary reviewWriterIds on hotListingId for create/update/delete concurrency; use base popular/middle/tail for read cost.',
                  'recentlyViewedNPlusOne': 'Fill 0, 1, 20 and 100 existing published listing IDs on the logged-in account.',
                  'wishlistBulk': 'Create only account-owned experiment lists; compare representative and link counts after deletion.',
                  'amenityBulk': 'Use guarded benchmark endpoint and restore afterward.',
                  'expirationBulk': 'Create current owned HOLDs, one reclaimed HOLD control and one OCCUPIED control.',
                  'couponConcurrency': 'Create a fresh campaign for each repeat; Lua requires stock preparation, Redisson does not.',
                  'detailCache': 'Flush only the dedicated accommodation-cache Redis; authenticate sessions on the general Redis.',
                  'asgAlb': 'Use published base target pools with one logged-in member per writer; record readiness before ALB membership.'}}
    # Private credentials are independent of a temporary root-password/Redis directory.
    create_private_credentials(bundle, private_output, environment, existing=private_credentials)
    write_json(output / 'accounts.json', bundle)
    write_json(output / 'base-scenario-targets.json', {'schemaVersion': 1, 'targets': scenarios, 'reset': reset})
    return bundle


def qualify_account_logins(app, db, bundle, output, *, private_input=None, environment=None):
    """Authenticate every advertised account using BCrypt + session issuance + /auth/me."""
    private_input = private_input or getattr(app, 'env', {}).get('AIRBOB_GROWTH_CREDENTIALS_FILE')
    if not private_input:
        raise ValueError('Normal-login qualification requires its explicit private credential file')
    private = read_private(private_input)
    environment = environment or private['environment']
    accounts = public_account_union(bundle)
    update_login_state(private_input, [row['memberId'] for row in accounts], environment, usable=False,
                       reason='Normal login and identity verification are in progress')
    representative_check = verify_representative_accounts(db, bundle['representativeAccounts'])
    results, clients = [], {}
    app.request(app.client(), '/api/v1/auth/me', expected=(401,), capture=False)
    for account in accounts:
        credential = credential_for_member(private, account['memberId'])
        if any(credential[key] != account[key] for key in ('email', 'role')):
            raise AssertionError('Prepared private credential differs from the advertised identity')
        client = app.client()
        app.login(client, account['email'])
        me = app.request(client, '/api/v1/auth/me', capture=False)['response']['data']
        if int(me['id']) != account['memberId'] or me['email'] != account['email'] or me['nickname'] != account['nickname']:
            raise AssertionError('Login returned a different member identity')
        clients[account['memberId']] = client
        results.append({'memberId': account['memberId'], 'email': account['email'], 'role': account['role'],
                        'group': credential['group'], 'loginStatus': 200, 'authenticatedIdentityMatches': True,
                        'environment': environment, 'status': account['status'],
                        **({'key': account['key']} if 'key' in account else {})})
    if not results:
        raise AssertionError('No usable experiment accounts')
    first = accounts[0]
    app.request(app.client(), '/api/v1/auth/login', method='POST',
                body={'email': first['email'], 'password': 'invalid-' + secrets.token_hex(32)},
                expected=(400, 401), capture=False)
    demo = credential_for_email(private, 'demo@airbob.test')
    cross_credentials = []
    targets = [credential_for_email(private, email) for email in ('host@airbob.test', 'admin@airbob.test')]
    for group in ('qualification', 'load'):
        selected = next((row for row in private['credentials'] if row['group'] == group), None)
        if selected:
            targets.append(selected)
    for target in targets:
        app.request(app.client(), '/api/v1/auth/login', method='POST',
                    body={'email': target['email'], 'password': demo['password']}, expected=(400, 401), capture=False)
        cross_credentials.append({'memberId': target['memberId'], 'group': target['group'],
                                  'demoPasswordRejected': True})
    observations = []
    for target in bundle['baseScenarioTargets']:
        client = clients[target['ownerMemberId']]
        one = app.request(client, target['path'], capture=False)
        two = app.request(client, target['path'], capture=False)
        if one['response'] != two['response'] or one['response'].get('data') is None:
            raise AssertionError('Base workload repeated read differs')
        owner = int(target['ownerMemberId'])
        resource = int(target.get('resourceId', 0))
        queries = {
            'publishedListingReviews': ('reviews', 'id', f"SELECT id FROM review WHERE accommodation_id={resource} AND status='PUBLISHED' ORDER BY created_at DESC,id DESC LIMIT 20"),
            'wishlistItems': ('wishlist_accommodations', 'wishlist_accommodation_id', f"SELECT wa.id FROM wishlist_accommodation wa JOIN accommodation a ON a.id=wa.accommodation_id WHERE wa.wishlist_id={resource} AND a.status='PUBLISHED' ORDER BY wa.created_at DESC,wa.id DESC LIMIT 20"),
            'memberReservations': ('reservations', 'reservation_id', f"SELECT id FROM reservation WHERE guest_id={owner} AND status='CONFIRMED' AND check_out_at<=UTC_TIMESTAMP() ORDER BY created_at DESC,id DESC LIMIT 20"),
            'memberWishlists': ('wishlists', 'id', f"SELECT id FROM wishlist WHERE member_id={owner} AND status='ACTIVE' ORDER BY created_at DESC,id DESC LIMIT 20"),
            'hostListings': ('accommodations', 'id', f"SELECT id FROM accommodation WHERE member_id={owner} AND status<>'DELETED' ORDER BY created_at DESC,id DESC LIMIT 20"),
        }
        field, id_field, sql = queries[target['kind']]
        expected = [int(row['id']) for row in db.rows(sql)]
        actual = [int(row[id_field]) for row in one['response']['data'][field]]
        if actual != expected:
            raise AssertionError('Base workload first page differs from its owner-scoped SQL')
        observations.append(target | {'status': one['status'], 'repeatEqual': True,
                                     'responseSha256': one['responseSha256'],
                                     'ownerScopedSqlMatches': True, 'firstPageRows': len(actual)})
    client = clients[first['memberId']]
    app.request(client, '/api/v1/auth/logout', method='POST', capture=False)
    app.request(client, '/api/v1/auth/me', expected=(401,), capture=False)
    report = {'passed': True, 'method': 'normal login API, server-issued session cookie and /api/v1/auth/me',
              'accounts': results, 'unauthenticatedRejected': True, 'invalidPasswordRejected': True,
              'logoutInvalidatesSession': True, 'baseWorkloads': observations,
              'representativeMapping': representative_check,
              'representatives': [row for row in results if row['group'] in {'representative', 'administrator'}],
              'environment': environment, 'crossCredentialRejected': cross_credentials,
              'privateHandoffs': {'operator': 'representative-accounts.private.json',
                                 'interviewer': 'demo-accounts.private.json', 'administrator': 'administrator.private.json'},
              'credentialAndSessionValuesRecorded': False}
    update_login_state(private_input, [row['memberId'] for row in accounts], environment, usable=True)
    write_json(Path(output) / 'account-login-qualification.json', report)
    return {'passed': True, 'accounts': len(results), 'qualificationAccounts': len(bundle['accounts']),
            'representativeAccounts': 3, 'baseWorkloads': len(observations),
            'identityAndLogoutVerified': True, 'environment': environment, 'crossCredentialRejected': True,
            'representatives': representative_check}


def apply_saved_credentials(etl, profile, baseline, private_input, output, environment, schema='airbobdb'):
    """Restore verification remains exact before changing credential hashes."""
    if schema not in {'airbobdb', 'airbob_growth_bulk_write_benchmark'}:
        raise ValueError('Unexpected growth schema')
    private = read_private(private_input)
    env = dict(environment) | {'AIRBOB_GROWTH_CREDENTIALS_FILE': str(Path(private_input).resolve())}
    command = [str(Path(etl).resolve()), '--growth-profile=' + str(Path(profile).resolve()),
               '--service-schema=' + schema]
    with tempfile.TemporaryDirectory(prefix='airbob-account-verify-') as directory:
        fingerprint = Path(directory) / 'fingerprint.json'
        subprocess.run(command + ['--growth-command=fingerprint', '--growth-output=' + str(fingerprint)],
                       env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        if json.loads(fingerprint.read_text()) != json.loads(Path(baseline).read_text()):
            raise AssertionError('Rows or DDL differ from the sealed baseline; credentials were not changed')
        prepared = Path(directory) / 'prepared.json'
        subprocess.run(command + ['--growth-command=prepare-credentials', '--growth-output=' + str(prepared)],
                       env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        report = json.loads(prepared.read_text())
    write_json(Path(output), report | {'sealedBaselineVerifiedBeforeCredentials': True,
                                      'privateAccountCount': len(private['credentials'])})
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['apply'])
    for option in ['etl', 'profile', 'baseline-fingerprint', 'private-input', 'output']:
        parser.add_argument('--' + option, required=True)
    parser.add_argument('--schema', default='airbobdb')
    args = parser.parse_args()
    apply_saved_credentials(args.etl, args.profile, args.baseline_fingerprint, args.private_input,
                            args.output, os.environ, args.schema)
