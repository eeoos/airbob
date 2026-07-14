import copy
import json
import tempfile
import unittest
from pathlib import Path

import analyze


class AnalyzeTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def _round(self, size):
        apis = ["recently_viewed"] if size == 100 else list(analyze.ROUTES)
        before = self.root / f"before-{size}.prom"
        after = self.root / f"after-{size}.prom"
        summary = self.root / f"k6-{size}.json"
        before_lines = []
        after_lines = []
        samples = []
        for api in apis:
            path = analyze.ROUTES[api]
            common = f'http_method="GET",path="{path}"'
            before_lines.extend(
                [
                    f'{analyze.QUERY_SUM}{{{common},query_type="SELECT"}} 22',
                    f'{analyze.QUERY_COUNT}{{{common},query_type="SELECT"}} 11',
                    f'{analyze.QUERY_SUM}{{{common},query_type="TOTAL"}} 30',
                    f'{analyze.QUERY_COUNT}{{{common},query_type="TOTAL"}} 10',
                    (
                        f'{analyze.SERVER_DURATION_BUCKET}'
                        f'{{method="GET",status="200",uri="{path}",le="0.1"}} 10'
                    ),
                    (
                        f'{analyze.SERVER_DURATION_BUCKET}'
                        f'{{method="GET",status="200",uri="{path}",le="0.25"}} 20'
                    ),
                    (
                        f'{analyze.SERVER_DURATION_BUCKET}'
                        f'{{method="GET",status="200",uri="{path}",le="+Inf"}} 20'
                    ),
                ]
            )
            after_lines.extend(
                [
                    f'{analyze.QUERY_SUM}{{{common},query_type="SELECT"}} 32',
                    f'{analyze.QUERY_COUNT}{{{common},query_type="SELECT"}} 16',
                    f'{analyze.QUERY_SUM}{{{common},query_type="TOTAL"}} 45',
                    f'{analyze.QUERY_COUNT}{{{common},query_type="TOTAL"}} 15',
                    (
                        f'{analyze.SERVER_DURATION_BUCKET}'
                        f'{{method="GET",status="200",uri="{path}",le="0.1"}} 12'
                    ),
                    (
                        f'{analyze.SERVER_DURATION_BUCKET}'
                        f'{{method="GET",status="200",uri="{path}",le="0.25"}} 25'
                    ),
                    (
                        f'{analyze.SERVER_DURATION_BUCKET}'
                        f'{{method="GET",status="200",uri="{path}",le="+Inf"}} 25'
                    ),
                ]
            )
            samples.append(
                {
                    "api": api,
                    "size": size,
                    "attempted": 5,
                    "successful": 5,
                    "p95_ms": 50,
                    "dropped": 0,
                }
            )
        before.write_text("\n".join(before_lines) + "\n", encoding="utf-8")
        after.write_text("\n".join(after_lines) + "\n", encoding="utf-8")
        summary.write_text(
            json.dumps(
                {
                    "duration_seconds": 10,
                    "aggregate": {
                        "count": 5 * len(samples),
                        "success_count": 5 * len(samples),
                        "p95_ms": 50,
                        "error_rate": 0,
                        "rps": len(samples) / 2,
                        "dropped": 0,
                    },
                    "samples": samples,
                }
            ),
            encoding="utf-8",
        )
        return analyze.RoundInput(size, before, after, summary)

    def _rounds(self):
        return [self._round(size) for size in (1, 20, 50, 100)]

    def test_combines_exact_query_counts_and_histogram_latency(self):
        result = analyze.analyze("20", 256.5, self._rounds())

        self.assertEqual(1, result["fixture_valid"])
        self.assertEqual(1, result["query_count_not_worse"])
        self.assertEqual(1, result["latency_within_baseline_tolerance"])
        self.assertEqual(2.0, result["select_queries_per_request"])
        self.assertEqual(3.0, result["total_queries_per_request"])
        self.assertAlmostEqual(237.5, result["server_p95_ms"])
        self.assertAlmostEqual(2.75, result["requests_per_second"])
        self.assertEqual(22, len(result["samples"]))
        self.assertTrue(all(sample["query_count_consistent"] == 1 for sample in result["samples"]))

    def test_baseline_comparison_fails_each_regression_gate(self):
        rounds = self._rounds()
        baseline = analyze.analyze("100", 256, rounds)
        slower_and_cheaper_baseline = copy.deepcopy(baseline)
        for sample in slower_and_cheaper_baseline["samples"]:
            sample["client_p95_ms"] = 40
            sample["select_queries_per_request"] = 1
            sample["total_queries_per_request"] = 2
        baseline_path = self.root / "baseline.json"
        baseline_path.write_text(json.dumps(slower_and_cheaper_baseline), encoding="utf-8")

        result = analyze.analyze("20", 256, rounds, baseline_path)

        self.assertEqual(0, result["query_count_not_worse"])
        self.assertEqual(0, result["latency_within_baseline_tolerance"])
        self.assertTrue(all(sample["latency_ratio"] == 1.25 for sample in result["samples"]))
        self.assertTrue(all(sample["query_count_not_worse"] == 0 for sample in result["samples"]))


if __name__ == "__main__":
    unittest.main()
