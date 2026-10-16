"""Pure contracts and report calculations for isolated coupon experiments."""
import hashlib
import json
import math
import re
from urllib.parse import urlsplit

VARIANTS = ('db', 'skip_surrogate_rr', 'skip_surrogate_rc', 'skip_composite_rr', 'skip_composite_rc')
DEFAULT_VARIANTS = VARIANTS[:3]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def issue_route(variant):
    require(variant in VARIANTS, 'Invalid coupon issue variant')
    return '/api/v2/coupons/{couponId}/issue/' + variant.replace('_', '-')


def positive(value, name, maximum):
    require(type(value) is int and 0 < value <= maximum, 'Invalid ' + name)


def validate(config, executing=False):
    c = dict(config)
    required = {'schemaVersion', 'example', 'runId', 'baseUrl', 'appVersion', 'appInstanceCount',
                'benchmarkDatasetManifest', 'sessionFixture', 'accountPasswordFile', 'adminSessionFile',
                'benchmarkTokenFile', 'appMetricsUrls', 'rounds', 'warmupSeconds', 'durationSeconds',
                'cooldownSeconds', 'comparisonRates', 'stockRatio', 'capacityRates', 'p95LimitMs',
                'p99LimitMs', 'clientVUs', 'cpu', 'variants'}
    require(set(c) == required and c['schemaVersion'] == 1, 'Invalid configuration fields')
    require(type(c['example']) is bool and (not executing or not c['example']), 'Replace the example configuration')
    require(re.fullmatch(r'[a-z0-9][a-z0-9-]{0,47}', c['runId']) is not None, 'Invalid runId')
    for url in [c['baseUrl'], *c['appMetricsUrls']]:
        parts = urlsplit(url)
        require(parts.scheme in ('https', 'http') and parts.hostname and not parts.username
                and not parts.password and not parts.query and not parts.fragment, 'Invalid endpoint URL')
    require(urlsplit(c['baseUrl']).path in ('', '/'), 'baseUrl must be an origin')
    require(not executing or urlsplit(c['baseUrl']).scheme == 'https', 'AWS execution requires a lab-only HTTPS origin')
    require(re.fullmatch(r'[0-9a-f]{40}|[^\s]+@sha256:[0-9a-f]{64}', c['appVersion']) is not None,
            'appVersion must be an exact commit or image digest')
    require(isinstance(c['variants'], list) and 2 <= len(c['variants']) <= len(VARIANTS)
            and len(set(c['variants'])) == len(c['variants'])
            and all(v in VARIANTS for v in c['variants']), 'Invalid variants')
    for key, maximum in {'appInstanceCount': 8, 'rounds': 12, 'warmupSeconds': 60,
                         'durationSeconds': 900, 'clientVUs': 20000,
                         'p95LimitMs': 30000, 'p99LimitMs': 30000}.items():
        positive(c[key], key, maximum)
    require(c['rounds'] >= 2 and c['durationSeconds'] >= 120, 'Use at least two rounds and 120-second measurements')
    require(c['p95LimitMs'] <= c['p99LimitMs'], 'p95 limit exceeds p99 limit')
    require(type(c['cooldownSeconds']) is int and 0 <= c['cooldownSeconds'] <= 300, 'Invalid cooldown')
    require(len(c['appMetricsUrls']) == c['appInstanceCount']
            and len(set(c['appMetricsUrls'])) == c['appInstanceCount'], 'One distinct metrics endpoint per app is required')
    for key in ('comparisonRates', 'capacityRates'):
        rates = c[key]
        require(isinstance(rates, list) and 1 <= len(rates) <= 20, 'Invalid ' + key)
        for rate in rates:
            positive(rate, key, 20000)
        require(rates == sorted(set(rates)), key + ' must be strictly increasing')
    require(type(c['stockRatio']) in (float, int) and 0 < c['stockRatio'] < 1, 'Invalid stock ratio')
    require(min(c['comparisonRates']) * c['durationSeconds'] * c['stockRatio'] >= 1,
            'Scarcity stock must be positive')
    cpu = c['cpu']
    require(isinstance(cpu, dict) and set(cpu) == {'region', 'dbInstanceIdentifier', 'waitSeconds'}, 'Invalid CPU configuration')
    require(re.fullmatch(r'[a-z]{2}(?:-[a-z]+)+-\d', cpu['region']) is not None, 'Invalid AWS region')
    require(re.fullmatch(r'[a-zA-Z][a-zA-Z0-9-]{0,62}', cpu['dbInstanceIdentifier']) is not None, 'Invalid DB instance identifier')
    positive(cpu['waitSeconds'], 'CPU waitSeconds', 600)
    for key in ('benchmarkDatasetManifest', 'sessionFixture', 'accountPasswordFile', 'adminSessionFile', 'benchmarkTokenFile'):
        require(c[key] is None and key == 'accountPasswordFile'
                or isinstance(c[key], str) and c[key].startswith('/'), 'Use an absolute file path for ' + key)
    return c


def plan(c):
    maximum = max(c['comparisonRates'] + c['capacityRates'])
    capacity = maximum * max(c['durationSeconds'], c['warmupSeconds']) + 1
    return {'schemaVersion': 1, 'runId': c['runId'], 'requiredUniqueSessions': capacity,
            'rounds': c['rounds'], 'measurementSeconds': c['durationSeconds'],
            'maximumMeasuredRuns': c['rounds'] * len(c['variants']) * (len(c['comparisonRates']) + len(c['capacityRates'])),
            'variants': c['variants'],
            'capacityRates': c['capacityRates'], 'comparisonRates': c['comparisonRates'],
            'scenarios': ['capacity', 'scarcity'], 'createsAwsResources': False,
            'capacityInterpretation': 'highest repeatedly passing configured rate; ceiling is a lower bound'}


def order(round_number, variants=DEFAULT_VARIANTS):
    values = list(variants)
    require(values and len(set(values)) == len(values) and all(v in VARIANTS for v in values), 'Invalid variants')
    block, offset = divmod(round_number - 1, len(values))
    if block % 2:
        values = values[:1] + list(reversed(values[1:]))
    return values[offset:] + values[:offset]


def inventory_failures(state, variant, stock, successes):
    issued = state['member_coupon_count']
    failures = []
    if not (issued == state['issued_quantity'] == state['distinct_member_count'] == successes
            and 0 <= issued <= stock and state['stock'] == stock and state['variant'] == variant):
        failures.append('database-issuance-invariant')
    if variant == 'db':
        if state['stock_prepared'] or state['legacy_issued_quantity'] != issued or state['remaining_unit_count'] is not None:
            failures.append('conditional-update-invariant')
    elif not (state['stock_prepared'] and state['legacy_issued_quantity'] is None
              and state['remaining_unit_count'] == stock - issued):
        failures.append('unit-issuance-invariant')
    return failures


def evaluate(artifact, c, scenario, rate, stock, state, server, generator):
    p, m = artifact['performance'], artifact['metadata']
    invalid, failed = [], []
    n = p['requestCount']
    outcomes = p['outcomes']
    expected = rate * c['durationSeconds']
    if m['experiment'] != scenario or m['rate'] != rate or m['couponStock'] != stock:
        invalid.append('client-contract-mismatch')
    if p['droppedIterations'] or n < expected - 1 or n > expected + 1:
        invalid.append('incomplete-offered-load')
    if sum(outcomes.values()) != n:
        invalid.append('incomplete-client-outcomes')
    if any(outcomes.get(k, 0) for k in ('duplicate', 'notIssuable', 'unprepared', 'authentication')):
        invalid.append('fixture-or-authentication-error')
    if server['requests'] != n:
        invalid.append('server-client-count-mismatch')
    if not generator.get('healthy', False):
        invalid.append('load-generator-pressure-or-missing-data')
    issued = state['member_coupon_count']
    invalid.extend(inventory_failures(state, m['variant'], stock, outcomes['success']))
    if scenario == 'capacity' and outcomes['soldOut']:
        invalid.append('capacity-stock-exhausted')
    if scenario == 'capacity' and outcomes['success'] != n:
        failed.append('capacity-requires-all-success')
    if scenario == 'scarcity' and (issued != stock or not outcomes['soldOut']):
        invalid.append('scarcity-was-not-exercised')
    if outcomes['unexpected']:
        failed.append('unexpected-response')
    if outcomes.get('contended', 0):
        failed.append('retryable-contention')
    successful = p['successDuration']
    for percentile, key in (('p(95)', 'p95LimitMs'), ('p(99)', 'p99LimitMs')):
        value = successful.get(percentile)
        if value is None or not math.isfinite(value) or value > c[key]:
            failed.append('success-' + percentile)
    if scenario == 'capacity' and p['successRate'] < rate * 0.99:
        failed.append('successful-throughput-below-target')
    if not outcomes['success']:
        failed.append('no-successful-issuance')
    return {'valid': not invalid, 'sloPassed': not invalid and not failed,
            'invalidReasons': invalid, 'sloReasons': failed}


def capacity_summary(rows, variant, c):
    relevant = [r for r in rows if r['scenario'] == 'capacity' and r['variant'] == variant]
    low = None
    for rate in c['capacityRates']:
        step = [r for r in relevant if r['rate'] == rate]
        if len(step) != c['rounds'] or any(not r['evaluation']['valid'] for r in step):
            return {'status': 'inconclusive', 'highestConfirmedTargetRps': low, 'firstFailingTargetRps': None}
        passes = [r['evaluation']['sloPassed'] for r in step]
        if not all(passes):
            return {'status': 'bracketed' if not any(passes) and low else
                    ('no-passing-rate' if not any(passes) else 'unstable'),
                    'highestConfirmedTargetRps': low, 'firstFailingTargetRps': rate if not any(passes) else None}
        low = rate
    return {'status': 'lower-bound-only', 'highestConfirmedTargetRps': low, 'firstFailingTargetRps': None}


def fingerprint(c):
    return hashlib.sha256(json.dumps(c, sort_keys=True).encode()).hexdigest()
