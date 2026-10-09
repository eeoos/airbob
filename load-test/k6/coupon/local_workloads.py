"""Bounded local workload plans; no infrastructure or business requests on import."""
import math
import re

from coupon_experiment import require


def validate_plan(value):
    require(isinstance(value, dict) and value.get('schemaVersion') == 1, 'Invalid local workload plan version')
    cases = value.get('cases')
    require(isinstance(cases, list) and 1 <= len(cases) <= 10, 'A local plan needs 1..10 cases')
    names = set()
    for case in cases:
        name = case.get('name', '')
        require(re.fullmatch(r'[a-z][a-z0-9-]{0,39}', name) and name not in names, 'Invalid/duplicate case name')
        names.add(name)
        require(case.get('scenario') in ('capacity', 'scarcity', 'duplicate'), 'Invalid local scenario')
        segments = case.get('segments')
        require(isinstance(segments, list) and 1 <= len(segments) <= 6, 'A case needs 1..6 segments')
        seen, offset, requests = set(), 0, 0
        for segment in segments:
            require(re.fullmatch(r'[a-z][a-z0-9-]{0,29}', segment.get('name', ''))
                    and segment['name'] not in seen, 'Invalid/duplicate segment name')
            seen.add(segment['name'])
            rate, seconds = segment.get('rate'), segment.get('seconds')
            require(type(rate) is int and 1 <= rate <= 2000
                    and type(seconds) is int and 5 <= seconds <= 180, 'Invalid segment rate or duration')
            segment.update(startSeconds=offset, sessionOffset=requests, plannedRequests=rate * seconds)
            offset += seconds
            requests += rate * seconds
        require(10 <= offset <= 180 and requests <= 60000, 'Local case exceeds duration/session budget')
        stock = case.get('stock')
        require(type(stock) is int and 0 < stock <= 1_000_000, 'Invalid case stock')
        if case['scenario'] == 'capacity':
            require(stock > requests, 'Capacity stock must exceed all segment requests')
        elif case['scenario'] == 'scarcity':
            require(stock < requests and len(segments) == 1, 'Scarcity needs one segment and stock below requests')
        else:
            users, burst = case.get('uniqueMembers'), case.get('duplicateBurst')
            require(type(users) is int and 0 < users < stock and type(burst) is int
                    and 2 <= burst <= 100 and users * burst <= requests and len(segments) == 1,
                    'Duplicate workload requires enough stock, members and repeated bursts')
        preparation = case.get('otherPreparation')
        if preparation is not None:
            require(case['scenario'] in ('capacity', 'scarcity') and len(segments) == 1,
                    'Other-campaign preparation requires a steady capacity/scarcity case')
            require(type(preparation.get('stock')) is int and 1 <= preparation['stock'] <= 100000
                    and type(preparation.get('count')) is int and 1 <= preparation['count'] <= 5
                    and type(preparation.get('intervalSeconds')) is int and preparation['intervalSeconds'] >= 2
                    and preparation['count'] * preparation['intervalSeconds'] < offset,
                    'Invalid bounded campaign preparation')
        case.update(durationSeconds=offset, plannedRequests=requests,
                    requiredSessions=(case['uniqueMembers'] if case['scenario'] == 'duplicate' else requests) + 1)
    value['requiredSessions'] = max(1001, *(case['requiredSessions'] for case in cases))
    return value


def segment_evaluations(performance, workload, p95_limit, p99_limit):
    results = []
    for segment in workload['segments']:
        measured = performance.get('segments', {}).get(segment['name'])
        require(measured is not None, 'Missing measured workload segment')
        reasons = []
        if abs(measured['requestCount'] - segment['plannedRequests']) > 1 or measured['droppedIterations']:
            reasons.append('incomplete-offered-load')
        for percentile, limit in (('p(95)', p95_limit), ('p(99)', p99_limit)):
            latency = measured['duration'].get(percentile)
            if latency is None or not math.isfinite(latency) or latency > limit:
                reasons.append('response-' + percentile)
        outcomes = measured['outcomes']
        if any(outcomes.get(k, 0) for k in ('unexpected', 'authentication', 'notIssuable', 'unprepared', 'contended')):
            reasons.append('failed-outcomes')
        if workload['scenario'] == 'capacity' and outcomes['success'] != measured['requestCount']:
            reasons.append('capacity-not-all-success')
        if measured['requestRate'] < segment['rate'] * 0.99:
            reasons.append('throughput-below-target')
        results.append({'name': segment['name'], 'rate': segment['rate'], 'seconds': segment['seconds'],
                        'passed': not reasons, 'reasons': reasons, 'performance': measured})
    return results
