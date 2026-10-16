"""Offline AWS experiment contract and bounded capacity search. No AWS calls on import."""
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ACCOUNT = '942632789808'
REGION = 'ap-northeast-2'
BUCKET = 'airbob-performance-lab-evidence-' + ACCOUNT
SPEC = importlib.util.spec_from_file_location('local_cache_experiments', HERE / 'run-local-experiments.py')
local = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(local)
need = local.require


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def validate(c, *, executing=False):
    fields = {'schemaVersion', 'example', 'runId', 'experimentId', 'datasetId', 'datasetSha256', 'appImage',
              'appCommit', 'apps', 'redisInstanceId', 'loadGeneratorInstanceId', 'asgName', 'alb',
              'tokenParameter', 'accommodationIds', 'scenarios', 'rounds', 'durationSeconds',
              'warmupSeconds', 'rate', 'outageRate', 'burst', 'p95LimitMs', 'errorRateLimit',
              'clientVUs', 'deadlineSeconds', 'capacity'}
    need(isinstance(c, dict) and fields <= set(c) <= fields | {
             'originalApplication', 'responseCheck', 'capacityVariants', 'capacityCriterion', 'allowInitializingOriginal',
             'hikariMaximumPoolSize', 'capacityProbeRates', 'burstConnectionWarmup'}
         and c['schemaVersion'] == 1, 'Invalid AWS cache contract fields')
    need(type(c['example']) is bool and (not executing or not c['example']), 'Replace the example contract before execution')
    need(re.fullmatch(r'lab-[a-z0-9][a-z0-9-]{0,27}', c['runId']) is not None, 'Invalid lab run ID')
    need(re.fullmatch(r'cache-[a-z0-9][a-z0-9-]{0,35}', c['experimentId']) is not None, 'Invalid experiment ID')
    need(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9._-]{0,100}', c['datasetId']) is not None, 'Invalid dataset ID')
    need(re.fullmatch(r'[0-9a-f]{64}', c['datasetSha256']) is not None, 'Dataset manifest SHA required')
    need(re.fullmatch(r'[0-9a-f]{40}', c['appCommit']) is not None, 'Application commit required')
    need(re.fullmatch(ACCOUNT + r'\.dkr\.ecr\.' + REGION
                     + r'\.amazonaws\.com/airbob-repo@sha256:[0-9a-f]{64}', c['appImage']) is not None,
         'Digest-pinned application image required')
    original = c.get('originalApplication', {'appImage': c['appImage'], 'appCommit': c['appCommit']})
    need(isinstance(original, dict) and set(original) == {'appImage', 'appCommit'}
         and re.fullmatch(ACCOUNT + r'\.dkr\.ecr\.' + REGION
                          + r'\.amazonaws\.com/airbob-repo@sha256:[0-9a-f]{64}', original['appImage'])
         and re.fullmatch(r'[0-9a-f]{40}', original['appCommit']), 'Exact original application identity required')
    need(c.get('responseCheck', 'canonical') in ('canonical', 'body'), 'Invalid responseCheck')
    need(type(c.get('burstConnectionWarmup', False)) is bool, 'Invalid burstConnectionWarmup')
    need(type(c.get('allowInitializingOriginal', False)) is bool
         and (not c.get('allowInitializingOriginal') or 'originalApplication' in c),
         'Initializing handoff requires an explicit original application')
    need(c.get('capacityCriterion', 'latency-slo') in ('latency-slo', 'completed-load'),
         'Invalid capacityCriterion')
    if 'hikariMaximumPoolSize' in c:
        need(type(c['hikariMaximumPoolSize']) is int and 10 <= c['hikariMaximumPoolSize'] <= 40,
             'Diagnostic Hikari maximum pool size must be 10-40')
    variants = c.get('capacityVariants', ['cache-off', 'cache-on'])
    need(isinstance(variants, list) and 0 < len(variants) == len(set(variants))
         and set(variants) <= {'cache-off', 'cache-on'}, 'Invalid capacityVariants')
    ids = c['apps'] + [c['redisInstanceId'], c['loadGeneratorInstanceId']]
    need(1 <= len(c['apps']) <= 4 and len(set(ids)) == len(ids)
         and all(re.fullmatch(r'i-[0-9a-f]{17}', i) for i in ids), 'Distinct app, Redis and load-generator instance IDs required')
    need(c['asgName'] == 'airbob-' + c['runId'] + '-app', 'Wrong ASG')
    alb = c['alb']
    need(set(alb) == {'arn', 'targetGroupArn', 'dnsName', 'origin'}, 'Invalid ALB fields')
    prefix = 'arn:aws:elasticloadbalancing:' + REGION + ':' + ACCOUNT + ':'
    need(alb['arn'].startswith(prefix + 'loadbalancer/app/airbob-')
         and alb['targetGroupArn'].startswith(prefix + 'targetgroup/airbob-')
         and re.fullmatch(r'[a-z0-9-]+\.ap-northeast-2\.elb\.amazonaws\.com', alb['dnsName'])
         and alb['origin'] == 'https://api.airbob.cloud', 'Exact regional ALB and TLS origin required')
    need(c['tokenParameter'] == '/airbob/performance-lab/cache-benchmark/' + c['runId'] + '/token',
         'Token must be an external SecureString under the exact run path')
    keys = c['accommodationIds']
    need(isinstance(keys, list) and 5 <= len(keys) <= 200 and len(keys) == len(set(keys))
         and all(type(i) is int and 0 < i < 2**53 for i in keys), 'Published accommodation IDs required')
    need(isinstance(c['scenarios'], list) and 0 < len(c['scenarios']) == len(set(c['scenarios']))
         and set(c['scenarios']) <= {'latency', 'miss', 'stampede', 'outage', 'capacity'}, 'Invalid scenarios')
    bounds = {'rounds': (1, 3), 'durationSeconds': (5, 300), 'warmupSeconds': (5, 60),
              'rate': (1, 20000), 'outageRate': (1, 1000), 'burst': (2, 1000),
              'clientVUs': (80, 20000), 'deadlineSeconds': (1200, 5400)}
    for key, (minimum, maximum) in bounds.items():
        need(type(c[key]) is int and minimum <= c[key] <= maximum, 'Invalid ' + key)
    need(isinstance(c['p95LimitMs'], (int, float)) and math.isfinite(c['p95LimitMs'])
         and 1 <= c['p95LimitMs'] <= 10000 and c['errorRateLimit'] == 0, 'Invalid experiment SLO')
    cap = c['capacity']
    required_capacity = {'startRps', 'ceilingRps', 'resolutionRps', 'probeSeconds', 'confirmSeconds', 'repeats'}
    need(required_capacity <= set(cap) <= required_capacity | {'warmupRps', 'conditioningStages', 'rampSeconds'},
         'Invalid capacity contract')
    need(type(cap.get('warmupRps', 40)) is int and 1 <= cap.get('warmupRps', 40) <= 20000,
         'Invalid capacity warmupRps')
    need(type(cap.get('rampSeconds', 0)) is int and 0 <= cap.get('rampSeconds', 0) <= 60,
         'Invalid capacity rampSeconds')
    for key, (minimum, maximum) in {'startRps': (1, 20000), 'ceilingRps': (2, 20000),
            'resolutionRps': (1, 1000), 'probeSeconds': (15, 300), 'confirmSeconds': (60, 300),
            'repeats': (2, 3)}.items():
        need(type(cap[key]) is int and minimum <= cap[key] <= maximum, 'Invalid capacity ' + key)
    need(cap['startRps'] < cap['ceilingRps'] and cap['resolutionRps'] <= cap['startRps'], 'Invalid search interval')
    if 'conditioningStages' in cap:
        stages = cap['conditioningStages']
        need('capacity' in c['scenarios'] and isinstance(stages, list) and 1 <= len(stages) <= 3
             and all(isinstance(stage, dict) and set(stage) == {'rps', 'seconds'}
                     and type(stage['rps']) is int and 1 <= stage['rps'] <= cap['ceilingRps']
                     and type(stage['seconds']) is int and 15 <= stage['seconds'] <= 120
                     for stage in stages), 'Invalid capacity conditioningStages')
        need(sum(stage['seconds'] for stage in stages) <= 300
             and all(a['rps'] < b['rps'] for a, b in zip(stages, stages[1:])),
             'Capacity conditioning must increase within 300 seconds')
    if 'capacityProbeRates' in c:
        rates = c['capacityProbeRates']
        need('capacity' in c['scenarios'] and isinstance(rates, list) and 1 <= len(rates) <= 20
             and all(type(rate) is int and cap['startRps'] <= rate <= cap['ceilingRps'] for rate in rates)
             and len(set(rates)) == len(rates), 'Invalid fixed capacity probe rates')
    need(c['durationSeconds'] + c['warmupSeconds'] < 480
         and max(cap['probeSeconds'], cap['confirmSeconds']) + c['warmupSeconds']
             + cap.get('rampSeconds', 0) < 480, 'Warm-cache phases must fit the minimum default TTL')
    return c


def capacity_search(config, measure):
    """measure(rate, duration, phase, repetition) returns evaluated evidence.

    Probe geometrically, bisect, then repeat BOTH sides of the boundary. A generator
    bottleneck, ceiling, or unstable boundary must never become a maximum-RPS claim.
    """
    c = config['capacity']
    rows, low, high = [], 0, None

    def sample(rate, seconds, phase, repetition=1):
        row = measure(rate, seconds, phase, repetition)
        rows.append(row)
        return row

    def invalid(row):
        return bool(row['evidenceReasons'] or row['dropped'] or not row['generatorHealthy'])

    def passed(row):
        return row.get('capacityPassed', row['sloPassed'])

    def finish(status):
        return {'status': status, 'criterion': config.get('capacityCriterion', 'latency-slo'),
                'highestPassingProbeRps': low or None,
                'highestConfirmedRps': low if status in {'confirmed-boundary', 'lower-bound-only'} else None,
                'firstFailingRps': high,
                'resolutionRps': c['resolutionRps'], 'observations': len(rows)}

    rate = c['startRps']
    while True:
        row = sample(rate, c['probeSeconds'], 'probe')
        if invalid(row):
            return finish('inconclusive-generator-or-evidence')
        if not passed(row):
            high = rate
            break
        low = rate
        if rate == c['ceilingRps']:
            for repeat in range(1, c['repeats'] + 1):
                row = sample(low, c['confirmSeconds'], 'confirm-lower-bound', repeat)
                if invalid(row) or not passed(row):
                    return finish('unstable-or-inconclusive')
            return finish('lower-bound-only')
        rate = min(rate * 2, c['ceilingRps'])
    while high - low > c['resolutionRps']:
        rate = (low + high) // 2
        row = sample(rate, c['probeSeconds'], 'refine')
        if invalid(row):
            return finish('inconclusive-generator-or-evidence')
        if passed(row):
            low = rate
        else:
            high = rate
    if low == 0:
        return finish('no-passing-rate')
    for repeat in range(1, c['repeats'] + 1):
        # Alternate near-boundary loads as well as OFF/ON experiment order.
        for rate, should_pass in ([(low, True), (high, False)] if repeat % 2
                                   else [(high, False), (low, True)]):
            row = sample(rate, c['confirmSeconds'], 'confirm-boundary', repeat)
            if invalid(row) or passed(row) != should_pass:
                return finish('unstable-or-inconclusive')
    return finish('confirmed-boundary')


def evaluate(result, before, after, variant, scenario, config):
    instances = [local.server_observation(a, b) for a, b in zip(before, after)]
    observed = local.aggregate_observations(instances)
    result.update(server=observed, instances=instances, variant=variant, scenario=scenario)
    evidence_result, ramp_reasons = result, []
    ramp_seconds = config['capacity'].get('rampSeconds', 0) if scenario == 'capacity' else 0
    if ramp_seconds:
        ramp = result.get('ramp', {})
        if (not isinstance(ramp, dict) or ramp.get('seconds') != ramp_seconds
                or ramp.get('startRate') != 1
                or not isinstance(ramp.get('completed'), (int, float)) or ramp['completed'] <= 0
                or not isinstance(ramp.get('errors'), (int, float)) or not 0 <= ramp['errors'] <= ramp['completed']
                or result.get('totalCompleted') != result['completed'] + ramp['completed']
                or not isinstance(result.get('totalErrorRate'), (int, float))
                or not 0 <= result['totalErrorRate'] <= 1):
            ramp_reasons.append('missing-or-inconsistent-ramp-evidence')
        else:
            evidence_result = result | {'completed': result['totalCompleted'], 'errorRate': result['totalErrorRate']}
            if ramp['errors'] or result['totalErrorRate'] > 0:
                ramp_reasons.append('ramp-or-measurement-response-error')
    elif result.get('ramp'):
        ramp_reasons.append('unexpected-ramp-evidence')
    result['evidenceReasons'] = local.evidence_reasons(evidence_result, observed, variant, scenario) + ramp_reasons
    if result.get('kind') == 'burst' and config.get('burstConnectionWarmup', False):
        warmup = result.get('burstConnectionWarmup', {})
        if (not isinstance(warmup, dict) or warmup.get('requests') != config['burst']
                or warmup.get('errors') != 0 or warmup.get('late') != 0):
            result['evidenceReasons'].append('burst-connection-warmup-incomplete')
        if not isinstance(warmup, dict) or warmup.get('reused') != result['completed']:
            result['evidenceReasons'].append('burst-connection-not-reused')
    elif result.get('burstConnectionWarmup'):
        result['evidenceReasons'].append('unexpected-burst-connection-warmup')
    result['generatorHealthy'] = (result['generator']['maxCpuPercent'] < 90
                                  and result['generator']['minAvailableMemoryPercent'] >= 15)
    if scenario == 'capacity' and not result['generatorHealthy']:
        result['evidenceReasons'].append('load-generator-resource-pressure')
    if scenario == 'capacity':
        result.update(local.offered_load_observation(result))
    result['sloPassed'] = (local.qualifies(result, config['p95LimitMs'], config['errorRateLimit'])
                           if scenario == 'capacity' else None)
    # The overload investigation does not equate a provisional latency target
    # with server saturation. Keep the latency-SLO result as separate evidence.
    result['capacityPassed'] = (local.qualifies(result, float('inf'), config['errorRateLimit'])
        if scenario == 'capacity' and config.get('capacityCriterion') == 'completed-load'
        else result['sloPassed'])
    return result
