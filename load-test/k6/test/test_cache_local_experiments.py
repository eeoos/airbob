"""Evidence and capacity classification must not turn incomplete runs into improvements."""
import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).resolve().parents[1] / 'cache/run-local-experiments.py'
SPEC = importlib.util.spec_from_file_location('cache_experiments', PATH)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


def observation(requests=60, loads=5, coalesced=55, errors=60):
    return {'requests': requests, 'selects': loads * 3, 'loads': loads,
            'loadErrors': 0, 'redisGetErrors': errors, 'redisPutErrors': 0, 'coalesced': coalesced,
            'locks': {'acquired': 0, 'timeout': 0, 'interrupted': 0, 'error': 0},
            'outcomes': {'hit': 0, 'hit_after_wait': 0, 'coalesced': coalesced,
                         'negative_hit': 0, 'negative_coalesced': 0,
                         'loaded': loads, 'negative_loaded': 0}}


def result():
    return {'completed': 60, 'requestedSamples': 60, 'dropped': 0, 'responseMismatches': 0,
            'errorRate': 0, 'latencyMs': {'p50': 20, 'p95': 40, 'p99': 60},
            'configuredRate': 20, 'successRps': 20, 'evidenceReasons': []}


class EvidenceTest(unittest.TestCase):
    def test_stampede_control_allows_ordinary_hits_without_claiming_every_request_was_a_miss(self):
        first, second = observation(30, 8, 0, 0), observation(30, 12, 0, 0)
        first['outcomes']['hit'], second['outcomes']['hit'] = 22, 18
        instances = [first, second]
        observed = runner.aggregate_observations(instances)
        self.assertEqual(runner.evidence_reasons(result() | {'instances': instances},
                                                 observed, 'lock-off', 'stampede'), [])

    def test_stampede_lock_comparison_requires_actual_lock_attempts_on_both_apps(self):
        first, second = observation(30, 1, 0, 0), observation(30, 0, 0, 0)
        first['locks']['acquired'], second['locks']['acquired'] = 30, 30
        first['outcomes']['hit_after_wait'], second['outcomes']['hit_after_wait'] = 29, 30
        instances = [first, second]
        self.assertEqual(runner.evidence_reasons(result() | {'instances': instances},
            runner.aggregate_observations(instances), 'lock-on', 'stampede'), [])
        second['locks']['acquired'] = 0
        second['outcomes']['hit_after_wait'] = 0
        second['outcomes']['hit'] = 30
        self.assertIn('stampede-lock-not-observed-on-every-app', runner.evidence_reasons(
            result() | {'instances': instances}, runner.aggregate_observations(instances), 'lock-on', 'stampede'))

    def test_stampede_rejects_coalescing_outages_errors_and_missing_cold_loads(self):
        for change, reason in [
            ({'redisGetErrors': 1}, 'stampede-redis-or-lock-error'),
            ({'redisPutErrors': 1}, 'stampede-redis-or-lock-error'),
            ({'loads': 0}, 'cold-cache-not-proven'),
            ({'loadErrors': 1}, 'stampede-response-or-load-error'),
            ({'locks': {'acquired': 1, 'timeout': 0, 'interrupted': 0, 'error': 0}}, 'unlocked-control-not-proven'),
        ]:
            with self.subTest(change=change):
                row = observation(loads=60, coalesced=0, errors=0) | change
                self.assertIn(reason, runner.evidence_reasons(result() | {'instances': [row]},
                    runner.aggregate_observations([row]), 'lock-off', 'stampede'))
        row = observation(loads=5, coalesced=55, errors=0)
        self.assertIn('stampede-local-coalescing-not-disabled', runner.evidence_reasons(
            result() | {'instances': [row]}, runner.aggregate_observations([row]), 'lock-off', 'stampede'))

    def test_lock_and_redis_write_error_counters_use_only_the_measured_interval(self):
        before = ('accommodation_detail_cache_lock_wait_duration_seconds_count{result="acquired"} 10\n'
                  'accommodation_detail_cache_lock_wait_duration_seconds_count{result="timeout"} 2\n'
                  'accommodation_detail_cache_lock_wait_duration_seconds_count{result="interrupted"} 0\n'
                  'accommodation_detail_cache_lock_wait_duration_seconds_count{result="error"} 0\n'
                  'accommodation_detail_cache_load_duration_seconds_count{result="error"} 0\n'
                  'accommodation_detail_cache_redis_operation_total{operation="get",result="error"} 0\n'
                  'accommodation_detail_cache_redis_operation_total{operation="put",result="error"} 3\n')
        before += ''.join('accommodation_detail_cache_request_total{result="' + outcome + '"} 0\n'
                          for outcome in observation()['outcomes'])
        before += ''.join('app_query_per_request_queries_' + suffix
                          + '{path="/api/v1/accommodations/{accommodationId}",http_method="GET",query_type="SELECT"} 0\n'
                          for suffix in ('count', 'sum'))
        after = before.replace(' 10\n', ' 70\n').replace(' 2\n', ' 4\n').replace(' 3\n', ' 4\n')
        observed = runner.server_observation(before, after)
        self.assertEqual(observed['locks'], {'acquired': 60, 'timeout': 2, 'interrupted': 0, 'error': 0})
        self.assertEqual(observed['redisPutErrors'], 1)

    def test_non_single_flight_outage_is_valid(self):
        self.assertEqual(runner.evidence_reasons(result(), observation(), 'coalescing-on', 'outage'), [])

    def test_uncoalesced_control_rejects_accidental_coalescing(self):
        self.assertIn('uncoalesced-control-not-proven', runner.evidence_reasons(
            result(), observation(), 'coalescing-off', 'outage'))
        self.assertEqual(runner.evidence_reasons(
            result(), observation(loads=60, coalesced=0), 'coalescing-off', 'outage'), [])

    def test_all_outage_requests_must_have_seen_redis_failure(self):
        self.assertIn('redis-outage-not-proven-for-every-request', runner.evidence_reasons(
            result(), observation(errors=59), 'coalescing-on', 'outage'))

    def test_sql_calls_are_not_confused_with_loader_calls(self):
        values = observation()
        values['selects'] = values['loads']
        self.assertIn('sql-loader-count-mismatch',
                      runner.evidence_reasons(result(), values, 'coalescing-on', 'outage'))

    def test_missing_requests_and_changed_response_are_rejected(self):
        r = result()
        r['responseMismatches'] = 1
        reasons = runner.evidence_reasons(r, observation(requests=59), 'coalescing-on', 'outage')
        self.assertIn('client-server-request-count-mismatch', reasons)
        self.assertIn('response-mismatch', reasons)

    def test_warm_cache_cannot_be_claimed_after_a_db_load(self):
        self.assertIn('warm-cache-not-proven',
                      runner.evidence_reasons(result(), observation(), 'cache-on', 'latency'))

    def test_fault_comparison_rejects_insufficient_client_concurrency(self):
        r = result() | {'dropped': 2}
        self.assertIn('offered-load-not-delivered', runner.evidence_reasons(
            r, observation(), 'coalescing-on', 'outage'))

    def test_aggregates_actual_per_jvm_loads_without_assuming_one(self):
        observed = runner.aggregate_observations([observation(40, 6, 34, 40), observation(20, 4, 16, 20)])
        self.assertEqual(observed['loads'], 10)
        self.assertEqual(observed['selects'], 30)
        self.assertEqual(observed['coalesced'], 50)
        self.assertAlmostEqual(observed['coalescingRatio'], 50 / 60)


class RuntimePolicyTest(unittest.TestCase):
    def test_old_image_cannot_silently_run_the_lock_comparison(self):
        runtime = {'cache_enabled': True, 'coalescing_enabled': False}
        runner.validate_runtime_policy(runtime, True, False, True)
        for enabled in (False, True):
            with self.subTest(enabled=enabled), self.assertRaisesRegex(RuntimeError, 'distributed lock'):
                runner.validate_runtime_policy(runtime, True, False, enabled, require_lock_control=True)

    def test_actual_bound_lock_and_coalescing_flags_must_match(self):
        for enabled in (False, True):
            runtime = {'cache_enabled': True, 'coalescing_enabled': False, 'distributed_lock_enabled': enabled}
            runner.validate_runtime_policy(runtime, True, False, enabled, require_lock_control=True)
            with self.assertRaises(RuntimeError):
                runner.validate_runtime_policy(runtime, True, False, not enabled, require_lock_control=True)
            with self.assertRaises(RuntimeError):
                runner.validate_runtime_policy(runtime, True, True, enabled, require_lock_control=True)


class CapacityTest(unittest.TestCase):
    def test_client_concurrency_accounts_for_redis_timeout_and_tail_latency(self):
        self.assertEqual(runner.client_vus(40, 100, True), 80)
        self.assertEqual(runner.client_vus(200, 100, False), 160)
        self.assertEqual(runner.client_vus(800, 100, False), 320)

    def test_good_run_meets_slo(self):
        self.assertTrue(runner.qualifies(result(), 100, 0))

    def test_latency_errors_drops_missing_samples_or_slow_drain_fail_slo(self):
        for change in [{'latencyMs': {'p95': 101}}, {'errorRate': .01}, {'dropped': 1},
                       {'completed': 59}, {'successRps': 19}, {'evidenceReasons': ['bad']}]:
            with self.subTest(change=change):
                r = result() | change
                self.assertFalse(runner.qualifies(r, 100, 0))

    def test_all_passing_steps_are_only_a_lower_bound(self):
        rows = [result() | {'configuredRate': rate, 'sloPassed': True} for rate in [20, 40, 80]]
        self.assertEqual(runner.capacity_bound(rows), {
            'highestPassingRps': 80, 'firstNonPassingRps': None, 'status': 'lower-bound-only'})

    def test_failure_brackets_the_grid_without_claiming_an_exact_maximum(self):
        rows = [result() | {'configuredRate': 40, 'sloPassed': True},
                result() | {'configuredRate': 80, 'sloPassed': False}]
        self.assertEqual(runner.capacity_bound(rows), {
            'highestPassingRps': 40, 'firstNonPassingRps': 80, 'status': 'bracketed-on-grid'})

    def test_generator_drops_do_not_establish_server_capacity(self):
        rows = [result() | {'configuredRate': 40, 'sloPassed': True},
                result() | {'configuredRate': 80, 'sloPassed': False, 'dropped': 2}]
        self.assertEqual(runner.capacity_bound(rows)['status'], 'inconclusive')

    def test_empty_capacity_has_no_fabricated_zero(self):
        self.assertIsNone(runner.capacity_bound([])['highestPassingRps'])
        self.assertEqual(runner.capacity_bound([])['status'], 'inconclusive')

    def test_no_passing_step_does_not_claim_a_bracket(self):
        rows = [result() | {'configuredRate': 1000, 'sloPassed': False}]
        self.assertEqual(runner.capacity_bound(rows)['status'], 'upper-bound-only')


if __name__ == '__main__':
    unittest.main()
