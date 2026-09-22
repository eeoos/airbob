"""Offline evidence checks for the local cache comparison runner."""

from copy import deepcopy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


RUNNER_PATH = Path(__file__).resolve().parents[1] / "cache" / "run-local-comparison.py"
SPEC = importlib.util.spec_from_file_location("cache_local_runner", RUNNER_PATH)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)

REQUEST = "accommodation_detail_cache_request_total"
LOAD = "accommodation_detail_cache_load_duration_seconds_count"
REDIS = "accommodation_detail_cache_redis_operation_total"


def metrics(variant, *, requests=20, selects=60, hits=10, waited_hits=2,
            fallback=1, loads=3, get_errors=1, put_errors=2, query_unit=True):
    """Use nonzero baselines so the assertions exercise deltas, not absolute totals."""
    version = 2 if variant == "before" else 1
    query_prefix = "app_query_per_request_queries" if query_unit else "app_query_per_request"
    tags = {"path": f"/api/v{version}/accommodations/{{accommodationId}}",
            "http_method": "GET", "query_type": "SELECT"}
    return [
        (query_prefix + "_sum", tags.copy(), float(selects)),
        (query_prefix + "_count", tags.copy(), float(requests)),
        (REQUEST, {"result": "hit"}, float(hits)),
        (REQUEST, {"result": "hit_after_wait"}, float(waited_hits)),
        (REQUEST, {"result": "loaded"}, float(fallback)),
        (LOAD, {"result": "found"}, float(loads)),
        (LOAD, {"result": "error"}, 0.0),
        (REDIS, {"operation": "get", "result": "error"}, float(get_errors)),
        (REDIS, {"operation": "put", "result": "error"}, float(put_errors)),
        (REDIS, {"operation": "get", "result": "success"}, 500.0),
    ]


class PrometheusParsingTest(unittest.TestCase):
    def test_parses_exponents_timestamps_unlabeled_values_and_escaped_labels(self):
        parsed = runner.parse_metrics(r'''
# HELP requests_total Example counter
# TYPE requests_total counter
requests_total{path="/api/v1/accommodations/{accommodationId}",note="quote\" and slash\\ and newline\n"} 1.2e2 1770000000000
unlabeled_total 3.5
unrelated text that is not a sample
''')
        self.assertEqual(parsed, [
            ("requests_total", {
                "path": "/api/v1/accommodations/{accommodationId}",
                "note": 'quote" and slash\\ and newline\n',
            }, 120.0),
            ("unlabeled_total", {}, 3.5),
        ])

    def test_metric_sum_filters_name_and_exact_label_value(self):
        values = runner.parse_metrics('''
request_total{result="hit",instance="one"} 3
request_total{result="hit",instance="two"} 5
request_total{result="hit_after_wait"} 17
another_total{result="hit"} 100
''')
        self.assertEqual(runner.metric_sum(values, ["request_total"], {"result": "hit"}), 8)
        self.assertEqual(runner.metric_sum(values, ["request_total"]), 25)
        with self.assertRaisesRegex(RuntimeError, "Required server metric is absent"):
            runner.metric_sum(values, ["request_total"], {"result": "missing"})


class LocalEnvironmentTest(unittest.TestCase):
    def test_keeps_basic_runtime_settings_and_drops_external_overrides(self):
        basic = {"PATH": "/usr/bin:/bin", "JAVA_HOME": "/local/jdk", "HOME": "/local/user",
                 "TMPDIR": "/local/tmp", "LANG": "en_US.UTF-8", "LC_ALL": "C",
                 "USER": "local", "LOGNAME": "local", "TZ": "UTC"}
        external = {
            "SPRING_DATASOURCE_URL": "jdbc:mysql://remote.invalid/airbobdb",
            "SPRING_DATASOURCE_USERNAME": "remote-user",
            "SPRING_DATASOURCE_PASSWORD": "test-secret",
            "SPRING_APPLICATION_JSON": '{"spring":{"profiles":{"active":"production"}}}',
            "SPRING_CONFIG_LOCATION": "/external/config.yaml",
            "SPRING_CONFIG_ADDITIONAL_LOCATION": "/external/extra.yaml",
            "SPRING_PROFILES_INCLUDE": "production",
            "JAVA_TOOL_OPTIONS": "-javaagent:/external/agent.jar",
            "_JAVA_OPTIONS": "-Dspring.profiles.active=production",
            "JDK_JAVA_OPTIONS": "-Dspring.profiles.active=production",
            "K6_OUT": "influxdb=http://remote.invalid",
            "K6_HTTP_DEBUG": "full",
            "K6_CONFIG": "/external/k6.json",
            "AWS_ACCESS_KEY_ID": "external-test-id",
            "AWS_SECRET_ACCESS_KEY": "external-test-secret",
            "AWS_SESSION_TOKEN": "external-test-token",
            "BENCHMARK_READ_MODEL_TOKEN": "external-test-token",
            "HTTP_PROXY": "http://remote.invalid",
            "HTTPS_PROXY": "http://remote.invalid",
        }
        source = basic | external
        with patch.dict(runner.os.environ, source, clear=True):
            actual = runner.process_environment()
            self.assertEqual(actual, basic)
            actual["PATH"] = "/changed/child/path"
            self.assertEqual(dict(runner.os.environ), source)

    def test_empty_environment_does_not_create_unverified_defaults(self):
        with patch.dict(runner.os.environ, {}, clear=True):
            self.assertEqual(runner.process_environment(), {})


class ServerEvidenceTest(unittest.TestCase):
    def test_before_requires_three_selects_per_request_and_no_cache_activity(self):
        before = metrics("before")
        after = metrics("before", requests=25, selects=75)
        observed = runner.server_delta(before, after, "before", 5)
        self.assertEqual(observed["validityReasons"], [])
        self.assertEqual(observed["requests"], 5)
        self.assertEqual(observed["selects"], 15)
        self.assertEqual(observed["selectsPerRequest"], 3)
        self.assertEqual(observed["cacheHits"], 0)
        self.assertEqual(observed["cacheOutcomes"], 0)
        self.assertEqual(observed["cacheLoads"], 0)
        self.assertEqual(observed["redisErrors"], 0)

    def test_after_requires_zero_selects_and_a_hit_for_every_request(self):
        before = metrics("after")
        after = metrics("after", requests=25, hits=13, waited_hits=4)
        observed = runner.server_delta(before, after, "after", 5)
        self.assertEqual(observed["validityReasons"], [])
        self.assertEqual(observed["selectsPerRequest"], 0)
        self.assertEqual(observed["cacheHits"], 5)
        self.assertEqual(observed["cacheOutcomes"], 5)
        self.assertEqual(observed["cacheLoads"], 0)

    def test_supports_prometheus_query_metric_without_base_unit_suffix(self):
        observed = runner.server_delta(
            metrics("before", query_unit=False),
            metrics("before", query_unit=False, requests=22, selects=66),
            "before", 2,
        )
        self.assertEqual(observed["validityReasons"], [])
        self.assertEqual(observed["selects"], 6)

    def test_other_endpoint_method_and_query_type_do_not_enter_request_evidence(self):
        before = metrics("after")
        after = metrics("after", requests=25, hits=15)
        for name in ["app_query_per_request_queries_sum", "app_query_per_request_queries_count"]:
            for tags in [
                {"path": "/api/v2/accommodations/{accommodationId}", "http_method": "GET", "query_type": "SELECT"},
                {"path": "/api/v1/accommodations/{accommodationId}", "http_method": "POST", "query_type": "SELECT"},
                {"path": "/api/v1/accommodations/{accommodationId}", "http_method": "GET", "query_type": "UPDATE"},
            ]:
                before.append((name, tags, 0.0))
                after.append((name, tags, 1000.0))
        self.assertEqual(runner.server_delta(before, after, "after", 5)["validityReasons"], [])

    def test_rejects_missing_required_metric_in_either_snapshot(self):
        for side in ["before", "after"]:
            for family in ["app_query_per_request_queries_sum", "app_query_per_request_queries_count",
                           REQUEST, LOAD, REDIS]:
                with self.subTest(side=side, family=family):
                    snapshots = {"before": metrics("after"),
                                 "after": metrics("after", requests=25, hits=15)}
                    snapshots[side] = [row for row in snapshots[side] if row[0] != family]
                    with self.assertRaisesRegex(RuntimeError, "Required server metric is absent"):
                        runner.server_delta(snapshots["before"], snapshots["after"], "after", 5)

    def test_rejects_counter_resets_in_request_query_and_cache_evidence(self):
        changes = [
            {"requests": 19}, {"selects": 59}, {"hits": 9},
            {"waited_hits": 1}, {"loads": 2}, {"get_errors": 0},
        ]
        for change in changes:
            with self.subTest(change=change):
                with self.assertRaisesRegex(RuntimeError, "counter reset"):
                    runner.server_delta(metrics("after"), metrics("after", **change), "after", 5)

    def test_after_rejects_fallback_instead_of_one_cache_hit(self):
        observed = runner.server_delta(
            metrics("after"),
            metrics("after", requests=25, hits=14, fallback=2, loads=4, selects=63),
            "after", 5,
        )
        self.assertIn("warm-cache-not-proven", observed["validityReasons"])
        self.assertIn("unexpected-select-count", observed["validityReasons"])

    def test_after_rejects_extra_outcome_or_load_even_with_all_expected_hits(self):
        for change in [{"fallback": 2}, {"loads": 4}]:
            with self.subTest(change=change):
                observed = runner.server_delta(
                    metrics("after"), metrics("after", requests=25, hits=15, **change), "after", 5,
                )
                self.assertIn("warm-cache-not-proven", observed["validityReasons"])

    def test_before_rejects_any_cache_request_or_database_load_through_cache(self):
        for change in [{"hits": 11}, {"fallback": 2}, {"loads": 4}]:
            with self.subTest(change=change):
                observed = runner.server_delta(
                    metrics("before"), metrics("before", requests=25, selects=75, **change), "before", 5,
                )
                self.assertIn("before-used-cache-path", observed["validityReasons"])

    def test_redis_errors_invalidate_even_otherwise_matching_requests(self):
        for variant in ["before", "after"]:
            with self.subTest(variant=variant):
                after = metrics(variant, requests=25, selects=75 if variant == "before" else 60,
                                hits=10 if variant == "before" else 15, put_errors=3)
                observed = runner.server_delta(metrics(variant), after, variant, 5)
                self.assertIn("redis-errors", observed["validityReasons"])

    def test_missing_server_requests_and_empty_measurement_are_invalid(self):
        mismatch = runner.server_delta(metrics("after"), metrics("after", requests=24, hits=15), "after", 5)
        self.assertIn("server-request-count-mismatch", mismatch["validityReasons"])
        empty = runner.server_delta(metrics("before"), metrics("before"), "before", 0)
        self.assertIn("server-request-count-mismatch", empty["validityReasons"])
        self.assertIsNone(empty["selectsPerRequest"])

    def test_before_rejects_wrong_select_count_even_if_response_count_matches(self):
        observed = runner.server_delta(metrics("before"), metrics("before", requests=25, selects=70), "before", 5)
        self.assertEqual(observed["validityReasons"], ["unexpected-select-count"])


class PayloadEquivalenceTest(unittest.TestCase):
    def setUp(self):
        self.detail = {"id": 7, "name": "숙소", "amenities": [{"type": "WIFI"}, {"type": "TV"}],
                       "images": [{"id": 10}, {"id": 20}], "policy": {"ordered_values": [1, 2]}}

    def test_amenity_order_is_ignored_without_mutating_original(self):
        original = deepcopy(self.detail)
        reordered = deepcopy(self.detail)
        reordered["amenities"].reverse()
        self.assertEqual(runner.canonical_detail(self.detail), runner.canonical_detail(reordered))
        self.assertEqual(self.detail, original)
        missing = deepcopy(self.detail)
        missing["amenities"].pop()
        self.assertNotEqual(runner.canonical_detail(self.detail), runner.canonical_detail(missing))

    def test_non_amenity_array_order_remains_significant(self):
        for field in ["images", "nested"]:
            with self.subTest(field=field):
                reordered = deepcopy(self.detail)
                if field == "images":
                    reordered["images"].reverse()
                else:
                    reordered["policy"]["ordered_values"].reverse()
                self.assertNotEqual(runner.canonical_detail(self.detail), runner.canonical_detail(reordered))

    def test_preflight_accepts_equal_payload_with_different_amenity_order(self):
        after = deepcopy(self.detail)
        after["amenities"].reverse()
        with patch.object(runner, "get_json", side_effect=[
            {"success": True, "data": self.detail}, {"success": True, "data": after},
        ]) as get_json:
            result = runner.preflight("http://127.0.0.1:9999", "local-token", [7], {7: self.detail})
        self.assertEqual(result, [{"id": 7, "data": self.detail}])
        self.assertEqual([call.args[1] for call in get_json.call_args_list],
                         ["/api/v2/accommodations/7", "/api/v1/accommodations/7"])

    def test_preflight_rejects_reordered_images_and_dataset_drift(self):
        after = deepcopy(self.detail)
        after["images"].reverse()
        with patch.object(runner, "get_json", side_effect=[
            {"success": True, "data": self.detail}, {"success": True, "data": after},
        ]):
            with self.assertRaisesRegex(RuntimeError, "Before/after response differs"):
                runner.preflight("http://127.0.0.1:9999", "local-token", [7])
        expected = deepcopy(self.detail)
        expected["name"] = "original name"
        with patch.object(runner, "get_json", return_value={"success": True, "data": self.detail}):
            with self.assertRaisesRegex(RuntimeError, "Dataset changed"):
                runner.preflight("http://127.0.0.1:9999", "local-token", [7], {7: expected})


if __name__ == "__main__":
    unittest.main()
