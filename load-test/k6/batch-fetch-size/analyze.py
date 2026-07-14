#!/usr/bin/env python3
"""Combine k6 summaries with exact Micrometer counter deltas.

The query-count result intentionally uses ``_sum / _count`` deltas instead of
histogram quantiles.  Query counts are discrete and the exact mean is what the
batch-size experiment needs.  The server-side latency p95 is diagnostic only:
it is interpolated from Prometheus histogram buckets and is therefore an
approximation.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, Mapping


QUERY_SUM = "app_query_per_request_queries_sum"
QUERY_COUNT = "app_query_per_request_queries_count"
SERVER_DURATION_BUCKET = "http_server_requests_seconds_bucket"

ROUTES = {
    "reviews": "/api/v1/accommodations/{accommodationId}/reviews",
    "host_accommodations": "/api/v1/profile/host/accommodations",
    "guest_reservations": "/api/v1/profile/guest/reservations",
    "host_reservations": "/api/v1/profile/host/reservations",
    "wishlists": "/api/v1/members/wishlists",
    "wishlist_accommodations": "/api/v1/members/wishlists/accommodations/{wishlistId}",
    "recently_viewed": "/api/v1/members/recently-viewed",
}

FULL_ROUND_SIZES = {1, 20, 50}
RECENT_ONLY_SIZE = 100
SUPPORTED_SIZES = FULL_ROUND_SIZES | {RECENT_ONLY_SIZE}
COUNTER_EPSILON = 1e-9

METRIC_LINE = re.compile(
    r"^(?P<name>[a-zA-Z_:][a-zA-Z0-9_:]*)"
    r"(?P<labels>\{.*\})?\s+"
    r"(?P<value>[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?|[-+]?Inf|NaN)"
    r"(?:\s+\d+)?(?:\s+#.*)?$"
)
LABEL_NAME = re.compile(r"[a-zA-Z_][a-zA-Z0-9_]*")


class AnalysisError(ValueError):
    """Raised when measurement input cannot produce a trustworthy result."""


@dataclass(frozen=True)
class MetricSeries:
    labels: Mapping[str, str]
    value: float


@dataclass(frozen=True)
class RoundInput:
    size: int
    before: Path
    after: Path
    k6: Path


class PrometheusSnapshot:
    def __init__(self, metrics: Mapping[str, list[MetricSeries]]) -> None:
        self.metrics = metrics

    @classmethod
    def read(cls, path: Path) -> "PrometheusSnapshot":
        try:
            content = path.read_text(encoding="utf-8")
        except OSError as exc:
            raise AnalysisError(f"cannot read Prometheus snapshot {path}: {exc}") from exc

        metrics: dict[str, list[MetricSeries]] = {}
        for line_number, raw_line in enumerate(content.splitlines(), start=1):
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            match = METRIC_LINE.match(line)
            if not match:
                raise AnalysisError(f"invalid Prometheus sample at {path}:{line_number}")
            value = _prometheus_number(match.group("value"), path, line_number)
            labels = _parse_labels(match.group("labels") or "", path, line_number)
            metrics.setdefault(match.group("name"), []).append(MetricSeries(labels, value))
        return cls(metrics)

    def selected(
        self,
        metric: str,
        labels: Mapping[str, str],
        predicate: Callable[[Mapping[str, str]], bool] | None = None,
    ) -> dict[tuple[tuple[str, str], ...], float]:
        selected: dict[tuple[tuple[str, str], ...], float] = {}
        for series in self.metrics.get(metric, []):
            if any(series.labels.get(key) != value for key, value in labels.items()):
                continue
            if predicate is not None and not predicate(series.labels):
                continue
            if math.isnan(series.value):
                raise AnalysisError(
                    f"NaN sample for required metric {metric}: {dict(series.labels)}"
                )
            key = tuple(sorted(series.labels.items()))
            if key in selected:
                raise AnalysisError(f"duplicate Prometheus series for {metric}: {dict(key)}")
            selected[key] = series.value
        return selected


def _prometheus_number(value: str, path: Path, line_number: int) -> float:
    normalized = value.replace("+Inf", "inf").replace("-Inf", "-inf")
    try:
        parsed = float(normalized)
    except ValueError as exc:
        raise AnalysisError(f"invalid number at {path}:{line_number}: {value}") from exc
    return parsed


def _parse_labels(raw: str, path: Path, line_number: int) -> dict[str, str]:
    if not raw:
        return {}
    if not (raw.startswith("{") and raw.endswith("}")):
        raise AnalysisError(f"invalid labels at {path}:{line_number}")

    labels: dict[str, str] = {}
    index = 1
    end = len(raw) - 1
    while index < end:
        name_match = LABEL_NAME.match(raw, index)
        if not name_match:
            raise AnalysisError(f"invalid label name at {path}:{line_number}")
        name = name_match.group(0)
        index = name_match.end()
        if index >= end or raw[index:index + 2] != '=\"':
            raise AnalysisError(f"invalid label assignment at {path}:{line_number}")
        index += 2

        value_chars: list[str] = []
        while index < end:
            char = raw[index]
            if char == '"':
                index += 1
                break
            if char == "\\":
                index += 1
                if index >= end:
                    raise AnalysisError(f"unterminated label escape at {path}:{line_number}")
                escaped = raw[index]
                value_chars.append({"n": "\n", "\\": "\\", '"': '"'}.get(escaped, escaped))
                index += 1
                continue
            value_chars.append(char)
            index += 1
        else:
            raise AnalysisError(f"unterminated label value at {path}:{line_number}")

        if name in labels:
            raise AnalysisError(f"duplicate label {name} at {path}:{line_number}")
        labels[name] = "".join(value_chars)
        if index < end:
            if raw[index] != ",":
                raise AnalysisError(f"invalid label separator at {path}:{line_number}")
            index += 1
    return labels


def _counter_delta(
    before: PrometheusSnapshot,
    after: PrometheusSnapshot,
    metric: str,
    labels: Mapping[str, str],
    predicate: Callable[[Mapping[str, str]], bool] | None = None,
) -> float:
    before_values = before.selected(metric, labels, predicate)
    after_values = after.selected(metric, labels, predicate)
    if not after_values:
        raise AnalysisError(f"missing {metric} after measurement for labels {dict(labels)}")

    total = 0.0
    for key in before_values.keys() | after_values.keys():
        if key not in after_values:
            raise AnalysisError(f"Prometheus series disappeared for {metric}: {dict(key)}")
        old = before_values.get(key, 0.0)
        new = after_values[key]
        if new + COUNTER_EPSILON < old:
            raise AnalysisError(f"counter reset for {metric}: {dict(key)} ({old} -> {new})")
        total += max(0.0, new - old)
    return total


def _query_measurement(
    before: PrometheusSnapshot,
    after: PrometheusSnapshot,
    path: str,
    query_type: str,
) -> tuple[float, float]:
    labels = {"http_method": "GET", "path": path, "query_type": query_type}
    observed_sum = _counter_delta(before, after, QUERY_SUM, labels)
    observed_count = _counter_delta(before, after, QUERY_COUNT, labels)
    if observed_count <= 0:
        raise AnalysisError(f"no {query_type} query-count samples for GET {path}")
    return observed_sum / observed_count, observed_count


def _successful_http_series(labels: Mapping[str, str]) -> bool:
    status = labels.get("status")
    if status is not None:
        return status.startswith("2")
    outcome = labels.get("outcome")
    return outcome is None or outcome.upper() == "SUCCESS"


def _server_p95_ms(
    before: PrometheusSnapshot,
    after: PrometheusSnapshot,
    path: str,
) -> tuple[float, float]:
    base_labels = {"method": "GET", "uri": path}
    after_buckets = after.selected(SERVER_DURATION_BUCKET, base_labels, _successful_http_series)
    boundaries: set[float] = set()
    raw_boundary: dict[float, str] = {}
    for key in after_buckets:
        labels = dict(key)
        if "le" not in labels:
            raise AnalysisError(f"latency histogram bucket lacks le label for GET {path}")
        boundary = _histogram_boundary(labels["le"], path)
        boundaries.add(boundary)
        raw_boundary[boundary] = labels["le"]
    if not boundaries or math.inf not in boundaries:
        raise AnalysisError(f"incomplete latency histogram for GET {path}")

    cumulative: list[tuple[float, float]] = []
    previous = 0.0
    for boundary in sorted(boundaries):
        labels = {**base_labels, "le": raw_boundary[boundary]}
        delta = _counter_delta(
            before,
            after,
            SERVER_DURATION_BUCKET,
            labels,
            _successful_http_series,
        )
        if delta + COUNTER_EPSILON < previous:
            raise AnalysisError(f"non-monotonic latency histogram for GET {path}")
        previous = max(previous, delta)
        cumulative.append((boundary, previous))

    request_count = cumulative[-1][1]
    if request_count <= 0:
        raise AnalysisError(f"no successful latency samples for GET {path}")
    quantile_seconds = _histogram_quantile(0.95, cumulative)
    return quantile_seconds * 1000.0, request_count


def _histogram_boundary(value: str, path: str) -> float:
    if value in {"+Inf", "Inf", "inf", "+inf"}:
        return math.inf
    try:
        boundary = float(value)
    except ValueError as exc:
        raise AnalysisError(f"invalid histogram boundary {value!r} for GET {path}") from exc
    if not math.isfinite(boundary):
        raise AnalysisError(f"invalid histogram boundary {value!r} for GET {path}")
    return boundary


def _histogram_quantile(quantile: float, cumulative: list[tuple[float, float]]) -> float:
    total = cumulative[-1][1]
    rank = quantile * total
    previous_boundary = 0.0
    previous_count = 0.0
    for boundary, count in cumulative:
        if count + COUNTER_EPSILON < rank:
            if math.isfinite(boundary):
                previous_boundary = boundary
            previous_count = count
            continue
        if math.isinf(boundary):
            return previous_boundary
        bucket_count = count - previous_count
        if bucket_count <= COUNTER_EPSILON:
            return boundary
        fraction = (rank - previous_count) / bucket_count
        return previous_boundary + (boundary - previous_boundary) * fraction
    raise AnalysisError("histogram quantile could not be calculated")


def _read_json(path: Path, purpose: str) -> Mapping[str, object]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise AnalysisError(f"cannot read {purpose} JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise AnalysisError(f"{purpose} JSON must be an object: {path}")
    return value


def _number(value: object, name: str, *, nonnegative: bool = True) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise AnalysisError(f"{name} must be numeric")
    parsed = float(value)
    if not math.isfinite(parsed):
        raise AnalysisError(f"{name} must be finite")
    if nonnegative and parsed < 0:
        raise AnalysisError(f"{name} must be nonnegative")
    return parsed


def _integer(value: object, name: str) -> int:
    parsed = _number(value, name)
    if not parsed.is_integer():
        raise AnalysisError(f"{name} must be an integer")
    return int(parsed)


def _expected_apis(size: int) -> set[str]:
    if size in FULL_ROUND_SIZES:
        return set(ROUTES)
    if size == RECENT_ONLY_SIZE:
        return {"recently_viewed"}
    raise AnalysisError(f"unsupported measurement size {size}; expected one of {sorted(SUPPORTED_SIZES)}")


def _load_k6_round(path: Path, size: int) -> tuple[float, list[dict[str, object]]]:
    payload = _read_json(path, "k6 summary")
    duration = _number(payload.get("duration_seconds"), f"{path}: duration_seconds")
    if duration <= 0:
        raise AnalysisError(f"{path}: duration_seconds must be positive")

    aggregate = payload.get("aggregate")
    if not isinstance(aggregate, dict):
        raise AnalysisError(f"{path}: aggregate must be an object")
    raw_samples = payload.get("samples")
    if not isinstance(raw_samples, list) or not raw_samples:
        raise AnalysisError(f"{path}: samples must be a non-empty array")

    samples: list[dict[str, object]] = []
    seen: set[str] = set()
    for index, raw_sample in enumerate(raw_samples):
        if not isinstance(raw_sample, dict):
            raise AnalysisError(f"{path}: samples[{index}] must be an object")
        api = raw_sample.get("api")
        if not isinstance(api, str) or api not in ROUTES:
            raise AnalysisError(f"{path}: samples[{index}].api is not a supported API")
        if api in seen:
            raise AnalysisError(f"{path}: duplicate sample for API {api}")
        seen.add(api)

        sample_size = _integer(raw_sample.get("size"), f"{path}: {api}.size")
        if sample_size != size:
            raise AnalysisError(f"{path}: {api}.size is {sample_size}, expected round size {size}")
        attempted = _integer(raw_sample.get("attempted"), f"{path}: {api}.attempted")
        successful = _integer(raw_sample.get("successful"), f"{path}: {api}.successful")
        dropped = _integer(raw_sample.get("dropped", 0), f"{path}: {api}.dropped")
        if successful > attempted:
            raise AnalysisError(f"{path}: {api}.successful exceeds attempted")
        p95_value = raw_sample.get("p95_ms", raw_sample.get("client_p95_ms"))
        p95_ms = _number(p95_value, f"{path}: {api}.p95_ms")
        if attempted > 0 and p95_ms <= 0:
            raise AnalysisError(f"{path}: {api}.p95_ms must be positive when requests were attempted")
        samples.append(
            {
                "api": api,
                "size": size,
                "attempted": attempted,
                "successful": successful,
                "dropped": dropped,
                "client_p95_ms": p95_ms,
                "requests_per_second": attempted / duration,
            }
        )

    expected = _expected_apis(size)
    if seen != expected:
        missing = sorted(expected - seen)
        unexpected = sorted(seen - expected)
        raise AnalysisError(f"{path}: round API set mismatch; missing={missing}, unexpected={unexpected}")

    attempted_total = sum(int(sample["attempted"]) for sample in samples)
    successful_total = sum(int(sample["successful"]) for sample in samples)
    dropped_total = sum(int(sample["dropped"]) for sample in samples)
    if _integer(aggregate.get("count"), f"{path}: aggregate.count") != attempted_total:
        raise AnalysisError(f"{path}: aggregate.count does not equal sample attempts")
    if _integer(aggregate.get("success_count"), f"{path}: aggregate.success_count") != successful_total:
        raise AnalysisError(f"{path}: aggregate.success_count does not equal sample successes")
    if _integer(aggregate.get("dropped", 0), f"{path}: aggregate.dropped") != dropped_total:
        raise AnalysisError(f"{path}: aggregate.dropped does not equal sample drops")
    _number(aggregate.get("p95_ms"), f"{path}: aggregate.p95_ms")
    _number(aggregate.get("error_rate"), f"{path}: aggregate.error_rate")
    _number(aggregate.get("rps"), f"{path}: aggregate.rps")
    return duration, samples


def _approximately_equal(left: float, right: float) -> bool:
    return math.isclose(left, right, rel_tol=1e-9, abs_tol=1e-6)


def _parse_round(raw: str) -> RoundInput:
    parts = raw.split(":", 3)
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("round must be SIZE:BEFORE.prom:AFTER.prom:K6.json")
    try:
        size = int(parts[0])
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f"invalid round size: {parts[0]}") from exc
    if size not in SUPPORTED_SIZES:
        raise argparse.ArgumentTypeError(f"unsupported round size {size}")
    return RoundInput(size, Path(parts[1]), Path(parts[2]), Path(parts[3]))


def _configured_batch_size(candidate: str) -> int:
    if candidate.strip().lower() in {"off", "disabled"}:
        return 0
    try:
        parsed = int(candidate)
    except ValueError as exc:
        raise AnalysisError(f"candidate must be an integer or OFF: {candidate}") from exc
    if parsed < 0:
        raise AnalysisError("candidate batch size must be nonnegative")
    return parsed


def _baseline_samples(path: Path | None) -> dict[tuple[str, int], Mapping[str, object]]:
    if path is None:
        return {}
    payload = _read_json(path, "baseline")
    raw_samples = payload.get("samples")
    if not isinstance(raw_samples, list) or not raw_samples:
        raise AnalysisError(f"baseline samples must be a non-empty array: {path}")
    samples: dict[tuple[str, int], Mapping[str, object]] = {}
    for index, sample in enumerate(raw_samples):
        if not isinstance(sample, dict):
            raise AnalysisError(f"baseline samples[{index}] must be an object")
        api = sample.get("api")
        if not isinstance(api, str) or api not in ROUTES:
            raise AnalysisError(f"baseline samples[{index}].api is invalid")
        size = _integer(sample.get("size"), f"baseline {api}.size")
        key = (api, size)
        if key in samples:
            raise AnalysisError(f"duplicate baseline sample for {api}, size={size}")
        for field in ("client_p95_ms", "select_queries_per_request", "total_queries_per_request"):
            _number(sample.get(field), f"baseline {api}/{size}.{field}")
        samples[key] = sample
    return samples


def analyze(
    candidate: str,
    max_rss_mb: float,
    rounds: Iterable[RoundInput],
    baseline_path: Path | None = None,
) -> dict[str, object]:
    configured_batch_size = _configured_batch_size(candidate)
    baseline = _baseline_samples(baseline_path)
    samples: list[dict[str, object]] = []
    total_duration = 0.0
    seen_rounds: set[int] = set()

    for round_input in rounds:
        if round_input.size in seen_rounds:
            raise AnalysisError(f"duplicate measurement round for size {round_input.size}")
        seen_rounds.add(round_input.size)
        before = PrometheusSnapshot.read(round_input.before)
        after = PrometheusSnapshot.read(round_input.after)
        duration, k6_samples = _load_k6_round(round_input.k6, round_input.size)
        total_duration += duration

        for k6_sample in k6_samples:
            api = str(k6_sample["api"])
            path = ROUTES[api]
            successful = int(k6_sample["successful"])
            select_qpr, select_count = _query_measurement(before, after, path, "SELECT")
            total_qpr, total_count = _query_measurement(before, after, path, "TOTAL")
            server_p95_ms, server_count = _server_p95_ms(before, after, path)
            count_consistent = (
                _approximately_equal(select_count, successful)
                and _approximately_equal(total_count, successful)
            )

            sample: dict[str, object] = {
                **k6_sample,
                "path": path,
                "server_p95_ms": server_p95_ms,
                "select_queries_per_request": select_qpr,
                "total_queries_per_request": total_qpr,
                "select_metric_request_count": select_count,
                "total_metric_request_count": total_count,
                "server_metric_request_count": server_count,
                "query_count_consistent": int(count_consistent),
            }

            if baseline_path is not None:
                key = (api, round_input.size)
                if key not in baseline:
                    raise AnalysisError(f"baseline lacks sample for {api}, size={round_input.size}")
                baseline_sample = baseline[key]
                baseline_p95 = _number(
                    baseline_sample.get("client_p95_ms"),
                    f"baseline {api}/{round_input.size}.client_p95_ms",
                )
                if baseline_p95 <= 0:
                    raise AnalysisError(f"baseline p95 must be positive for {api}, size={round_input.size}")
                baseline_select = _number(
                    baseline_sample.get("select_queries_per_request"),
                    f"baseline {api}/{round_input.size}.select_queries_per_request",
                )
                baseline_total = _number(
                    baseline_sample.get("total_queries_per_request"),
                    f"baseline {api}/{round_input.size}.total_queries_per_request",
                )
                sample.update(
                    {
                        "baseline_client_p95_ms": baseline_p95,
                        "baseline_select_queries_per_request": baseline_select,
                        "baseline_total_queries_per_request": baseline_total,
                        "latency_ratio": float(k6_sample["client_p95_ms"]) / baseline_p95,
                        "latency_within_baseline_tolerance": int(
                            float(k6_sample["client_p95_ms"]) <= baseline_p95 * 1.05 + COUNTER_EPSILON
                        ),
                        "query_count_not_worse": int(
                            count_consistent
                            and select_qpr <= baseline_select + COUNTER_EPSILON
                            and total_qpr <= baseline_total + COUNTER_EPSILON
                        ),
                    }
                )
            samples.append(sample)

    if not samples:
        raise AnalysisError("at least one --round is required")
    if seen_rounds != SUPPORTED_SIZES:
        raise AnalysisError(
            f"measurement rounds must cover {sorted(SUPPORTED_SIZES)}; got {sorted(seen_rounds)}"
        )

    attempted = sum(int(sample["attempted"]) for sample in samples)
    successful = sum(int(sample["successful"]) for sample in samples)
    dropped = sum(int(sample["dropped"]) for sample in samples)
    scheduled = attempted + dropped
    request_success_rate = successful / scheduled if scheduled else 0.0
    fixture_valid = int(
        scheduled > 0
        and all(int(sample["successful"]) == int(sample["attempted"]) for sample in samples)
        and dropped == 0
    )
    all_counts_consistent = all(int(sample["query_count_consistent"]) == 1 for sample in samples)

    if baseline_path is None:
        query_count_not_worse = int(all_counts_consistent)
        latency_within_tolerance = 1
    else:
        query_count_not_worse = int(
            all_counts_consistent and all(int(sample["query_count_not_worse"]) == 1 for sample in samples)
        )
        latency_within_tolerance = int(
            all(int(sample["latency_within_baseline_tolerance"]) == 1 for sample in samples)
        )

    return {
        "schema_version": "batch-fetch-size-v1",
        "candidate": candidate,
        "configured_batch_size": configured_batch_size,
        "fixture_valid": fixture_valid,
        "request_success_rate": request_success_rate,
        "query_count_not_worse": query_count_not_worse,
        "latency_within_baseline_tolerance": latency_within_tolerance,
        "client_p95_ms": max(float(sample["client_p95_ms"]) for sample in samples),
        "server_p95_ms": max(float(sample["server_p95_ms"]) for sample in samples),
        "select_queries_per_request": max(float(sample["select_queries_per_request"]) for sample in samples),
        "total_queries_per_request": max(float(sample["total_queries_per_request"]) for sample in samples),
        "requests_per_second": attempted / total_duration if total_duration else 0.0,
        "error_rate": 1.0 - request_success_rate,
        "max_rss_mb": max_rss_mb,
        "attempted_requests": attempted,
        "successful_requests": successful,
        "dropped_iterations": dropped,
        "query_count_consistent": int(all_counts_consistent),
        "samples": sorted(samples, key=lambda sample: (int(sample["size"]), str(sample["api"]))),
    }


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True, help="Configured batch size, or OFF")
    parser.add_argument("--max-rss-mb", required=True, type=float, help="Maximum application RSS in MiB")
    parser.add_argument(
        "--round",
        required=True,
        action="append",
        type=_parse_round,
        metavar="SIZE:BEFORE:AFTER:K6",
        help="Prometheus snapshots and k6 summary for one dataset-size round",
    )
    parser.add_argument("--baseline", type=Path, help="Baseline analyzer JSON for candidate comparison")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        max_rss_mb = _number(args.max_rss_mb, "max_rss_mb")
        result = analyze(args.candidate, max_rss_mb, args.round, args.baseline)
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":"), allow_nan=False))
    except AnalysisError as exc:
        print(f"analyze.py: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
