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
            'loadErrors': 0, 'redisGetErrors': errors, 'coalesced': coalesced,
            'outcomes': {'hit': 0, 'hit_after_wait': 0, 'coalesced': coalesced,
                         'negative_hit': 0, 'negative_coalesced': 0,
                         'loaded': loads, 'negative_loaded': 0}}


def result():
    return {'completed': 60, 'requestedSamples': 60, 'dropped': 0, 'responseMismatches': 0,
            'errorRate': 0, 'latencyMs': {'p50': 20, 'p95': 40, 'p99': 60},
            'configuredRate': 20, 'successRps': 20, 'evidenceReasons': []}


class EvidenceTest(unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main()
