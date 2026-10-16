"""Exercise phase accounting and duplicate-member scheduling through real k6 HTTP."""
import copy
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

COUPON = Path(__file__).resolve().parents[1] / 'coupon'
sys.path.insert(0, str(COUPON))
from local_workloads import validate_plan, segment_evaluations
from run_experiments import Runner
import run_local_e2e as local


def spike():
    return {'schemaVersion': 1, 'cases': [{'name': 'spike-recovery', 'scenario': 'capacity', 'stock': 41,
        'segments': [{'name': 'baseline', 'rate': 2, 'seconds': 5},
                     {'name': 'peak', 'rate': 4, 'seconds': 5},
                     {'name': 'recovery', 'rate': 2, 'seconds': 5}]}]}


class WorkloadPlan(unittest.TestCase):
    def test_other_campaign_can_be_prepared_while_sold_out_requests_continue(self):
        plan = {'schemaVersion': 1, 'cases': [{'name': 'sold-out-preparation', 'scenario': 'scarcity',
            'stock': 1000, 'segments': [{'name': 'steady', 'rate': 250, 'seconds': 20}],
            'otherPreparation': {'stock': 10000, 'count': 2, 'intervalSeconds': 6}}]}
        result = validate_plan(plan)['cases'][0]
        self.assertEqual(result['plannedRequests'], 5000)
        self.assertEqual(result['otherPreparation']['count'], 2)

    def test_spike_reserves_disjoint_members_across_the_whole_campaign(self):
        plan = validate_plan(spike())
        case = plan['cases'][0]
        self.assertEqual(case['plannedRequests'], 40)
        self.assertEqual(case['requiredSessions'], 41)
        self.assertEqual(plan['requiredSessions'], 1001)  # includes the separate warmup
        self.assertEqual([s['sessionOffset'] for s in case['segments']], [0, 10, 30])
        self.assertEqual([s['startSeconds'] for s in case['segments']], [0, 5, 10])

    def test_inventory_and_resource_budgets_fail_before_infrastructure(self):
        for field, value in [('stock', 40), ('stock', 0), ('scenario', 'unknown')]:
            plan = spike()
            plan['cases'][0][field] = value
            with self.assertRaises(ValueError):
                validate_plan(plan)
        plan = spike()
        plan['cases'][0]['segments'][0].update(rate=2000, seconds=180)
        with self.assertRaises(ValueError):
            validate_plan(plan)

    def test_duplicates_need_more_stock_than_unique_members_and_cover_every_member(self):
        plan = {'schemaVersion': 1, 'cases': [{'name': 'duplicates', 'scenario': 'duplicate', 'stock': 5,
            'uniqueMembers': 4, 'duplicateBurst': 2, 'segments': [{'name': 'steady', 'rate': 4, 'seconds': 10}]}]}
        self.assertEqual(validate_plan(copy.deepcopy(plan))['cases'][0]['requiredSessions'], 5)
        for key, value in [('stock', 4), ('uniqueMembers', 30), ('duplicateBurst', 1)]:
            bad = copy.deepcopy(plan)
            bad['cases'][0][key] = value
            with self.assertRaises(ValueError):
                validate_plan(bad)

    def test_failed_peak_does_not_mask_successful_recovery(self):
        case = validate_plan(spike())['cases'][0]
        metrics = {'segments': {s['name']: {'requestCount': s['plannedRequests'], 'droppedIterations': 0,
            'duration': {'p(95)': 20, 'p(99)': 30}, 'requestRate': s['rate'],
            'outcomes': {'success': s['plannedRequests']}} for s in case['segments']}}
        metrics['segments']['peak']['duration']['p(95)'] = 600
        rows = segment_evaluations(metrics, case, 500, 1000)
        self.assertEqual([r['passed'] for r in rows], [True, False, True])
        metrics['segments'].pop('recovery')
        with self.assertRaisesRegex(ValueError, 'Missing measured'):
            segment_evaluations(metrics, case, 500, 1000)


@unittest.skipUnless(shutil.which('k6'), 'k6 required for local HTTP contract')
class ScenarioHttp(unittest.TestCase):
    def test_real_k6_tracks_members_outcomes_and_executor_drops(self):
        cases = [validate_plan(spike())['cases'][0], validate_plan({'schemaVersion': 1, 'cases': [{
            'name': 'duplicates', 'scenario': 'duplicate', 'stock': 5, 'uniqueMembers': 4, 'duplicateBurst': 2,
            'segments': [{'name': 'steady', 'rate': 4, 'seconds': 10}]}]})['cases'][0],
            validate_plan({'schemaVersion': 1, 'cases': [{'name': 'other-preparation', 'scenario': 'capacity',
                'stock': 41, 'segments': [{'name': 'steady', 'rate': 4, 'seconds': 10}],
                'otherPreparation': {'stock': 100, 'count': 1, 'intervalSeconds': 2}}]})['cases'][0],
            validate_plan({'schemaVersion': 1, 'cases': [{'name': 'limited-vus', 'scenario': 'capacity',
                'stock': 41, 'segments': [{'name': 'first', 'rate': 4, 'seconds': 5},
                                         {'name': 'second', 'rate': 4, 'seconds': 5}]}]})['cases'][0]]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps({'schemaVersion': 1, 'datasetVersion': 'coupon-accounts-v1',
                'sourceDataset': {'id': 'local-contract', 'flywayVersion': 30, 'manifestSha256': 'a' * 64},
                'accountPool': {'capacity': 41, 'emails': [f'test-{i}@coupon.local' for i in range(41)]}}))
            sha = hashlib.sha256(manifest.read_bytes()).hexdigest()
            sessions = root / 'sessions.json'
            sessions.write_text(json.dumps({'datasetVersion': 'coupon-issuance-v2',
                'benchmarkDatasetManifestSha256': sha, 'sessions': [f'test-session-{i:016d}' for i in range(41)]}))
            token = root / 'token'
            token.write_text('test-benchmark-token')
            token.chmod(0o600)
            for case in cases:
                with self.subTest(case=case['name']):
                    seen, requests, preparations, lock = set(), [], [], threading.Lock()
                    class Handler(BaseHTTPRequestHandler):
                        def respond(self, status, body):
                            raw = json.dumps(body).encode()
                            self.send_response(status)
                            self.send_header('Content-Type', 'application/json')
                            self.send_header('Content-Length', str(len(raw)))
                            self.end_headers()
                            self.wfile.write(raw)

                        def do_DELETE(self):
                            self.respond(200, {'success': True})

                        def do_POST(self):
                            if self.path.startswith('/api/v1/admin/'):
                                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                                preparations.append(body)
                                self.respond(200, {'success': True, 'data': {'coupon_id': 2}})
                                return
                            cookie = self.headers.get('Cookie')
                            with lock:
                                duplicate = cookie in seen
                                seen.add(cookie)
                                requests.append(cookie)
                            if case['name'] == 'limited-vus':
                                time.sleep(0.6)
                            body = json.dumps({'success': not duplicate, 'error': {'code': 'CP003'}}).encode()
                            self.send_response(409 if duplicate else 201)
                            self.send_header('Content-Type', 'application/json')
                            self.send_header('Content-Length', str(len(body)))
                            self.end_headers()
                            self.wfile.write(body)
                        def log_message(self, *_):
                            pass
                    with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
                        thread = threading.Thread(target=server.serve_forever, daemon=True)
                        thread.start()
                        config = {'baseUrl': f'http://127.0.0.1:{server.server_port}',
                            'benchmarkDatasetManifest': str(manifest), 'benchmarkTokenFile': str(token),
                            'appVersion': 'a' * 40, 'appInstanceCount': 1,
                            'clientVUs': 1 if case['name'] == 'limited-vus' else 8,
                            'p95LimitMs': 500, 'p99LimitMs': 1000, 'localWorkload': case,
                            'runId': 'local-test', 'adminSessionFile': str(token), 'appMetricsUrls': ['unused']}
                        subject = local.LocalRunner(config, root)
                        subject.workload = case
                        subject.manifest_hash, subject.sequence = sha, 1
                        target = root / case['name']
                        target.mkdir()
                        try:
                            with patch.object(local, 'snapshot', side_effect=lambda *_: [{'requests': len(requests)}]), \
                                 patch.object(subject, 'state', return_value={'variant': 'db', 'stock': 100,
                                     'issued_quantity': 0, 'member_coupon_count': 0, 'distinct_member_count': 0,
                                     'legacy_issued_quantity': 0, 'stock_prepared': False, 'remaining_unit_count': None}):
                                artifact, _, code = subject.k6({'couponId': 1, 'stock': case['stock'],
                                    'variant': 'db', 'label': case['name']}, sessions, case['scenario'], 4,
                                    case['durationSeconds'], 1, 'measure', target)
                            p = artifact['performance']
                            if case['name'] == 'limited-vus':
                                self.assertNotEqual(code, 0)
                                self.assertLess(p['requestCount'], 40)
                                self.assertEqual(len(seen), p['requestCount'])
                                self.assertGreater(p['droppedIterations'], 0)
                                self.assertTrue(all(s['droppedIterations'] > 0 for s in p['segments'].values()))
                                self.assertEqual(sum(s['droppedIterations'] for s in p['segments'].values()),
                                                 p['droppedIterations'])
                                self.assertTrue(all(not s['passed'] for s in segment_evaluations(p, case, 500, 1000)))
                                continue
                            self.assertEqual(code, 0, (target / 'k6.log').read_text())
                            self.assertEqual(p['requestCount'], 40)
                            self.assertEqual(len(seen), 40 if case['scenario'] == 'capacity' else 4)
                            self.assertEqual(p['outcomes']['duplicate'], 0 if case['scenario'] == 'capacity' else 36)
                            self.assertEqual(sum(s['requestCount'] for s in p['segments'].values()), 40)
                            self.assertTrue(all(s['passed'] for s in segment_evaluations(p, case, 500, 1000)))
                            if case.get('otherPreparation'):
                                self.assertEqual(len(preparations), 1)
                                self.assertLessEqual(len(preparations[0]['label']), 64)
                                event = subject.preparation_events[0]
                                self.assertGreaterEqual(event['startedAtEpochMs'], p['measurementStartEpochMs'])
                                self.assertLessEqual(event['completedAtEpochMs'], p['measurementEndEpochMs'])
                                self.assertEqual(event['state']['member_coupon_count'], 0)
                        finally:
                            server.shutdown()
                            thread.join()


if __name__ == '__main__':
    unittest.main()
