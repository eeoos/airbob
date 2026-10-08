"""Offline experiment contracts; HTTP smoke uses a local fake API, never AWS."""
import copy
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

HERE = Path(__file__).resolve().parent
COUPON = HERE.parent / 'coupon'
ROOT = HERE.parents[2]
sys.path.insert(0, str(COUPON))
import coupon_experiment as contract
import coupon_monitoring as monitoring
import run_experiments as runner


def config():
    return json.loads((COUPON / 'experiment.example.json').read_text())


def evidence(scenario='capacity', variant='db', rate=100):
    c = config()
    n = rate * c['durationSeconds']
    stock = n + 1 if scenario == 'capacity' else n // 10
    issued = n if scenario == 'capacity' else stock
    state = {'stock': stock, 'issued_quantity': issued, 'member_coupon_count': issued,
             'distinct_member_count': issued, 'redis_prepared': variant == 'lua',
             'redis_remaining_stock': stock - issued if variant == 'lua' else None,
             'redis_issued_count': issued if variant == 'lua' else None}
    artifact = {'metadata': {'experiment': scenario, 'rate': rate, 'couponStock': stock, 'variant': variant},
                'performance': {'requestCount': n, 'droppedIterations': 0, 'successRate': issued / c['durationSeconds'],
                                'successDuration': {'p(95)': 20, 'p(99)': 30},
                                'outcomes': {'success': issued, 'soldOut': n - issued, 'duplicate': 0,
                                             'notIssuable': 0, 'unprepared': 0, 'authentication': 0, 'unexpected': 0}}}
    return [artifact, c, scenario, rate, stock, state, {'requests': n, 'queries': n * 3}, {'healthy': True}]


class Contracts(unittest.TestCase):
    def test_plan_is_offline_and_accounts_for_unique_members(self):
        c = contract.validate(config())
        self.assertEqual(contract.plan(c)['requiredUniqueSessions'], 180001)
        with self.assertRaisesRegex(ValueError, 'example'):
            contract.validate(c, executing=True)
        completed = subprocess.run([sys.executable, str(COUPON / 'run_experiments.py'), '--config',
                                    str(COUPON / 'experiment.example.json')], capture_output=True, text=True)
        self.assertEqual(completed.returncode, 0, completed.stdout)
        self.assertFalse(json.loads(completed.stdout)['createsAwsResources'])

    def test_rejects_uncomparable_configuration(self):
        for key, value in [('capacityRates', [200, 100]), ('rounds', 1), ('durationSeconds', 60),
                           ('appMetricsUrls', ['http://alb/actuator/prometheus']), ('stockRatio', 1),
                           ('appVersion', 'latest'), ('baseUrl', 'https://token@lab.example.com')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                c = config()
                c[key] = value
                contract.validate(c)

    def test_accepts_both_valid_scenarios(self):
        for scenario in ('capacity', 'scarcity'):
            for variant in ('db', 'lua'):
                self.assertTrue(contract.evaluate(*evidence(scenario, variant))['sloPassed'])

    def test_sold_out_is_not_successful_capacity(self):
        data = evidence()
        data[0]['performance']['outcomes'].update(success=17999, soldOut=1)
        result = contract.evaluate(*data)
        self.assertFalse(result['valid'])
        self.assertIn('capacity-stock-exhausted', result['invalidReasons'])

    def test_dropped_or_undelivered_load_is_inconclusive(self):
        for key, value in [('droppedIterations', 1), ('requestCount', 17980)]:
            data = evidence()
            data[0]['performance'][key] = value
            self.assertIn('incomplete-offered-load', contract.evaluate(*data)['invalidReasons'])

    def test_authentication_and_missing_metrics_are_invalid(self):
        data = evidence()
        data[0]['performance']['outcomes']['authentication'] = 1
        self.assertIn('fixture-or-authentication-error', contract.evaluate(*data)['invalidReasons'])
        data = evidence()
        data[6]['requests'] -= 1
        self.assertIn('server-client-count-mismatch', contract.evaluate(*data)['invalidReasons'])
        data = evidence()
        data[7]['healthy'] = False
        self.assertFalse(contract.evaluate(*data)['valid'])

    def test_db_and_redis_invariants_catch_lost_or_duplicate_issuance(self):
        for key in ('issued_quantity', 'member_coupon_count', 'distinct_member_count'):
            data = evidence()
            data[5][key] -= 1
            self.assertIn('database-issuance-invariant', contract.evaluate(*data)['invalidReasons'])
        data = evidence(variant='lua')
        data[5]['redis_remaining_stock'] = None
        self.assertIn('redis-issuance-invariant', contract.evaluate(*data)['invalidReasons'])
        data = evidence(variant='lua')
        data[5]['redis_issued_count'] -= 1
        self.assertFalse(contract.evaluate(*data)['valid'])

    def test_latency_and_completed_throughput_fail_slo_without_faking_invalid_load(self):
        data = evidence()
        data[0]['performance']['successDuration']['p(95)'] = 9999
        data[0]['performance']['successRate'] = 80
        result = contract.evaluate(*data)
        self.assertTrue(result['valid'])
        self.assertFalse(result['sloPassed'])
        self.assertEqual(len(result['sloReasons']), 2)

    def test_capacity_requires_repeated_passes_and_distinguishes_ceiling(self):
        c = config()
        c['capacityRates'] = [100, 200]
        def rows(outcomes):
            return [{'scenario': 'capacity', 'variant': 'db', 'rate': rate,
                     'evaluation': {'valid': True, 'sloPassed': passed}}
                    for rate, passes in outcomes for passed in passes]
        self.assertEqual(contract.capacity_summary(rows([(100, [True, True]), (200, [True, True])]), 'db', c)['status'], 'lower-bound-only')
        summary = contract.capacity_summary(rows([(100, [True, True]), (200, [False, False])]), 'db', c)
        self.assertEqual(summary, {'status': 'bracketed', 'highestConfirmedTargetRps': 100, 'firstFailingTargetRps': 200})
        self.assertEqual(contract.capacity_summary(rows([(100, [True, True]), (200, [False, True])]), 'db', c)['status'], 'unstable')
        self.assertEqual(contract.capacity_summary(rows([(100, [True])]), 'db', c)['status'], 'inconclusive')
        self.assertEqual(contract.capacity_summary(rows([(100, [False, False])]), 'db', c)['status'], 'no-passing-rate')


class Monitoring(unittest.TestCase):
    def test_route_scoped_statement_count_includes_zero_query_requests(self):
        raw = '''# HELP ignored irrelevant
process_start_time_seconds 1000
app_query_per_request_queries_sum{path="/api/v1/coupons/{couponId}/issue",http_method="POST",query_type="TOTAL"} 30
app_query_per_request_queries_count{path="/api/v1/coupons/{couponId}/issue",http_method="POST",query_type="TOTAL"} 100
app_query_per_request_queries_sum{path="/api/v1/coupons/{couponId}/issue",http_method="POST",query_type="SELECT"} 10
app_query_per_request_queries_sum{path="/api/v1/admin/coupons/benchmark/fixtures",http_method="POST",query_type="TOTAL"} 99
'''
        with patch.object(monitoring, 'fetch', return_value=raw):
            after = monitoring.snapshot(['http://app/metrics'], '/api/v1/coupons/{couponId}/issue')
        before = copy.deepcopy(after)
        before[0].update(requests=0, queries=0)
        result = monitoring.delta(before, after)
        self.assertEqual(result['requests'], 100)
        self.assertEqual(result['queries'], 30)

    def test_missing_identity_and_counter_reset_are_not_zero(self):
        with patch.object(monitoring, 'fetch', return_value=''), self.assertRaises(ValueError):
            monitoring.snapshot(['http://app/metrics'], '/issue')
        base = {'endpoint': 'app', 'processStartTime': 1, 'requests': 100, 'queries': 300}
        for change in ({'processStartTime': 2}, {'queries': 299}, {'requests': 99}):
            with self.assertRaises(ValueError):
                monitoring.delta([base], [{**base, **change}])

    def test_cpu_excludes_partial_minutes_and_requires_complete_evidence(self):
        self.assertEqual(monitoring.cpu_window(1000, 181000), (60, 180))
        points = {'Datapoints': [{'Timestamp': '1970-01-01T00:01:00Z', 'Average': 10},
                                {'Timestamp': '1970-01-01T00:02:00+00:00', 'Average': 30},
                                {'Timestamp': '1970-01-01T00:00:00Z', 'Average': 99}]}
        self.assertEqual(monitoring.parse_cpu(points, 60, 180)['averagePercent'], 20)
        with self.assertRaisesRegex(ValueError, 'Missing'):
            monitoring.parse_cpu({'Datapoints': points['Datapoints'][:1]}, 60, 180)
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            monitoring.parse_cpu({'Datapoints': points['Datapoints'] * 2}, 60, 180)
        with self.assertRaises(ValueError):
            monitoring.cpu_window(1000, 61000)

    def test_generator_missing_or_pressured_data_invalidates_capacity(self):
        monitor = monitoring.GeneratorMonitor()
        self.assertFalse(monitor.result()['healthy'])
        monitor.samples = [{'cpuPercent': 95, 'availableMemoryPercent': 40}]
        self.assertFalse(monitor.result()['healthy'])
        monitor.samples = [{'cpuPercent': 50, 'availableMemoryPercent': 40}]
        self.assertTrue(monitor.result()['healthy'])


class Orchestration(unittest.TestCase):
    def test_complete_measurement_keeps_evidence_before_closing_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            subject = runner.Runner(config(), Path(directory))
            fixture = {'couponId': 12, 'variant': 'db'}
            data = evidence()
            data[0]['performance'].update(measurementStartEpochMs=1000, measurementEndEpochMs=181000)
            before = [{'endpoint': 'app', 'processStartTime': 1, 'requests': 0, 'queries': 0}]
            after = [{**before[0], 'requests': 18000, 'queries': 54000}]
            with patch.object(subject, 'create', return_value=fixture), patch.object(subject, 'prepare'), \
                    patch.object(subject, 'k6', return_value=(data[0], {'healthy': True}, 0)), \
                    patch.object(subject, 'state', return_value=data[5]), patch.object(subject, 'close') as close, \
                    patch.object(runner, 'snapshot', side_effect=[before, after]), patch.object(runner.time, 'sleep'), \
                    patch.object(runner, 'cloudwatch_cpu', return_value={'averagePercent': 25}):
                subject.case('db', 'capacity', 100, 1, '/sessions')
                close.assert_called_once_with(fixture)
            report = json.loads((Path(directory) / 'report.json').read_text())
            self.assertTrue(report['runs'][0]['evaluation']['sloPassed'])
            self.assertEqual(report['aggregates'][0]['queries']['median'], 54000)
            self.assertTrue(list(Path(directory).glob('*/db-cpu.json')))
            invalid = copy.deepcopy(subject.rows[0])
            invalid['evaluation']['valid'] = False
            self.assertNotIn('queries', runner.aggregates(subject.rows + [invalid])[0])

    def test_ab_ba_repeats_and_stops_only_failed_variant_capacity(self):
        c = config()
        c.update(capacityRates=[100, 200, 300], comparisonRates=[200], cooldownSeconds=0)
        subject = runner.Runner(c, Path('/unused'))
        calls = []
        def case(variant, scenario, rate, round_number, sessions, phase='measure'):
            if phase == 'warmup':
                return
            calls.append((scenario, rate, round_number, variant))
            subject.rows.append({'scenario': scenario, 'variant': variant, 'rate': rate, 'round': round_number,
                                 'evaluation': {'valid': True, 'sloPassed': not (variant == 'db' and rate >= 200)}})
        with patch.object(subject, 'case', side_effect=case), patch.object(runner.time, 'sleep'):
            subject.workloads(Mock())
        self.assertEqual(calls[:4], [('capacity', 100, 1, 'db'), ('capacity', 100, 1, 'lua'),
                                     ('capacity', 100, 2, 'lua'), ('capacity', 100, 2, 'db')])
        self.assertNotIn(('capacity', 300, 1, 'db'), calls)
        self.assertIn(('capacity', 300, 1, 'lua'), calls)
        self.assertIn(('scarcity', 200, 1, 'db'), calls)
        self.assertEqual(len(calls), 14)

    def test_db_fixture_is_closed_even_when_prepare_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            subject = runner.Runner(config(), Path(directory))
            fixture = {'couponId': 12, 'variant': 'db'}
            with patch.object(subject, 'create', return_value=fixture), \
                    patch.object(subject, 'prepare', side_effect=ValueError('prepare failed')), \
                    patch.object(subject, 'close') as close:
                with self.assertRaisesRegex(ValueError, 'prepare failed'):
                    subject.case('db', 'capacity', 100, 1, '/sessions')
                close.assert_called_once_with(fixture)

    def test_cpu_failure_still_preserves_client_and_closes_db_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            subject = runner.Runner(config(), Path(directory))
            fixture = {'couponId': 12, 'variant': 'db'}
            data = evidence()
            data[0]['performance'].update(measurementStartEpochMs=1000, measurementEndEpochMs=181000)
            snapshots = [{'endpoint': 'app', 'processStartTime': 1, 'requests': 1, 'queries': 1}]
            with patch.object(subject, 'create', return_value=fixture), patch.object(subject, 'prepare'), \
                    patch.object(subject, 'k6', return_value=(data[0], {'healthy': True}, 0)), \
                    patch.object(subject, 'state', return_value=data[5]), patch.object(subject, 'close') as close, \
                    patch.object(runner, 'snapshot', return_value=snapshots), patch.object(runner.time, 'sleep'), \
                    patch.object(runner, 'cloudwatch_cpu', side_effect=ValueError('missing cpu')):
                with self.assertRaisesRegex(ValueError, 'missing cpu'):
                    subject.case('db', 'capacity', 100, 1, '/sessions')
                close.assert_called_once_with(fixture)
                self.assertTrue(list(Path(directory).glob('*/sql-after.json')))

    def test_cleanup_does_not_reset_lua_stock(self):
        with tempfile.TemporaryDirectory() as directory:
            subject = runner.Runner(config(), Path(directory))
            fixture = {'couponId': 12, 'variant': 'lua'}
            with patch.object(subject, 'api') as api:
                subject.close(fixture)
                api.assert_not_called()
            self.assertEqual(fixture['status'], 'retained-until-expiry')

    def test_create_journal_preserves_id_before_lua_preparation(self):
        with tempfile.TemporaryDirectory() as directory:
            subject = runner.Runner(config(), Path(directory))
            with patch.object(subject, 'api', return_value={'coupon_id': 12, 'issue_end_at': '2026-09-25T10:00:00'}) as api:
                subject.create('lua', 'case-1', 100, 180)
            self.assertEqual(api.call_count, 1)
            value = json.loads((Path(directory) / 'report.json').read_text())
            self.assertEqual(value['fixtures'][0]['couponId'], 12)

    def test_private_secrets_and_expired_sessions_fail_before_traffic(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'secret'
            path.write_text('session-secret')
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                runner.credential(path)
            path.chmod(0o600)
            self.assertEqual(runner.credential(path), 'session-secret')
            link = Path(directory) / 'symlink'
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                runner.credential(link)
            c = config()
            c.update(accountPasswordFile=None, sessionFixture=str(path))
            os.utime(path, (time.time() - 3600, time.time() - 3600))
            with self.assertRaisesRegex(ValueError, 'missing/old'):
                runner.Sessions(c, directory, 'a' * 64, 1).ensure()

    def test_session_preflight_rejects_manifest_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'sessions.json'
            path.write_text(json.dumps({'datasetVersion': 'coupon-issuance-v2',
                                        'benchmarkDatasetManifestSha256': 'b' * 64, 'sessions': ['session-01234567890']}))
            path.chmod(0o600)
            c = config()
            c.update(accountPasswordFile=None, sessionFixture=str(path))
            with self.assertRaisesRegex(ValueError, 'dataset manifest'):
                runner.Sessions(c, directory, 'a' * 64, 1).ensure()


@unittest.skipUnless(shutil.which('k6') and shutil.which('node'), 'k6 and Node required for local HTTP smoke')
class K6HttpSmoke(unittest.TestCase):
    def test_real_k6_capacity_scarcity_and_invalid_responses(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            c = config()
            c.update(appInstanceCount=1, clientVUs=4, p95LimitMs=5000, p99LimitMs=5000)
            manifest = directory / 'manifest.json'
            raw = json.loads((ROOT / 'infra/aws/tests/fixtures/benchmark-dataset-v2.json').read_text())
            capsule = next(cap for cap in raw['capsules'] if cap['capsuleId'] == 'coupon-accounts-v1')
            capsule['accountPool'] = {'capacity': 5, 'emails': [f'coupon-{i}@airbob.cloud' for i in range(5)]}
            manifest.write_text(json.dumps(raw))
            manifest_hash = hashlib.sha256(manifest.read_bytes()).hexdigest()
            sessions = directory / 'sessions.json'
            sessions.write_text(json.dumps({'datasetVersion': 'coupon-issuance-v2',
                                            'benchmarkDatasetManifestSha256': manifest_hash,
                                            'sessions': [f'fake-session-{i:016d}' for i in range(5)]}))
            token = directory / 'token'
            token.write_text('test-benchmark-token')
            token.chmod(0o600)
            c.update(benchmarkDatasetManifest=str(manifest), benchmarkTokenFile=str(token))
            self.assertEqual(runner.manifest_contract(c, 5), manifest_hash)
            for scenario, server_stock, status, manifest_kind in [('capacity', 5, 201, 'legacy'), ('scarcity', 1, 201, 'legacy'),
                    ('capacity', 1, 201, 'legacy'), ('capacity', 5, 401, 'legacy'), ('capacity', 5, 201, 'v28')]:
                with self.subTest(scenario=scenario, server_stock=server_stock, status=status, manifest=manifest_kind):
                    if manifest_kind == 'v28':
                        manifest.write_text(json.dumps({'schemaVersion': 1, 'datasetVersion': 'coupon-accounts-v1',
                            'sourceDataset': {'id': 'global-b-test', 'manifestSha256': 'c' * 64, 'flywayVersion': 28},
                            'accountPool': capsule['accountPool']}))
                        manifest_hash = hashlib.sha256(manifest.read_bytes()).hexdigest()
                        fixture_json = json.loads(sessions.read_text())
                        fixture_json['benchmarkDatasetManifestSha256'] = manifest_hash
                        sessions.write_text(json.dumps(fixture_json))
                        self.assertEqual(runner.manifest_contract(c, 5), manifest_hash)
                    requests = []
                    class Handler(BaseHTTPRequestHandler):
                        def do_POST(self):
                            requests.append((self.path, self.headers.get('Cookie'), self.headers.get('X-Benchmark-Token')))
                            code = 401 if status == 401 else (201 if len(requests) <= server_stock else 409)
                            response = json.dumps({'success': code == 201, 'error': {'code': 'M004' if code == 401 else 'CP002'}}).encode()
                            self.send_response(code)
                            self.send_header('Content-Type', 'application/json')
                            self.send_header('Content-Length', str(len(response)))
                            self.end_headers()
                            self.wfile.write(response)
                        def log_message(self, *_):
                            pass
                    with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
                        thread = threading.Thread(target=server.serve_forever, daemon=True)
                        thread.start()
                        c['baseUrl'] = f'http://127.0.0.1:{server.server_port}'
                        subject = runner.Runner(c, directory)
                        subject.manifest_hash, subject.sequence = manifest_hash, 1
                        target = directory / f'{scenario}-{server_stock}-{status}-{manifest_kind}'
                        target.mkdir()
                        fixture = {'couponId': 1, 'stock': 5 if scenario == 'capacity' else 1,
                                   'variant': 'db', 'label': target.name}
                        try:
                            artifact, _, code = subject.k6(fixture, sessions, scenario, 2, 2, 1, 'measure', target)
                        finally:
                            server.shutdown()
                            thread.join()
                    p = artifact['performance']
                    self.assertGreaterEqual(p['requestCount'], 3)
                    self.assertEqual(sum(p['outcomes'].values()), p['requestCount'])
                    self.assertEqual(len({r[1] for r in requests}), len(requests))
                    self.assertTrue(all(r[0] == '/api/v2/coupons/1/issue' and r[2] == 'test-benchmark-token' for r in requests))
                    self.assertGreater(p['measurementEndEpochMs'], p['measurementStartEpochMs'])
                    self.assertNotIn('fake-session', (target / 'client.json').read_text())
                    self.assertNotIn('test-benchmark-token', (target / 'k6.log').read_text())
                    if manifest_kind == 'v28':
                        self.assertEqual(artifact['metadata']['sourceDataset']['flywayVersion'], 28)
                    self.assertEqual(code, 99 if status == 401 or scenario == 'capacity' and server_stock == 1 else 0)
                    if status == 401:
                        self.assertEqual(p['outcomes']['authentication'], p['requestCount'])
                    if scenario == 'scarcity':
                        self.assertEqual(p['outcomes']['success'], 1)
                        self.assertGreater(p['soldOutDuration']['count'], 0)


if __name__ == '__main__':
    unittest.main()
