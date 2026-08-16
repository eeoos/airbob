# AWS traffic benchmark vertical slice

This directory implements the first, deliberately narrow traffic pipeline over
the existing `nplus1-v1` fixture. It is a pipeline rehearsal, not representative
traffic and not evidence for an index or production-capacity claim.

## Invocation boundary

Every run selects exactly one `ROLE` and one `TARGET`. The first slice accepts
only `ROLE=guest`; host, admin, mixed, search, and mutation workloads are
rejected. Preparation, warm-up, and measurement are separate k6 invocations:

- `MODE=inspect` validates the manifest and selected target without sending an
  application request.
- `MODE=warmup` sends only warm-up traffic and emits no measurement artifact.
- `MODE=measure` sends one constant-arrival-rate endpoint workload and writes
  `build/k6/traffic/<RUN_LABEL>.json`.

The common runner requires an explicit positive `RATE`, `DURATION`,
`MIN_COMPLETED_SAMPLES`, `ROUND`, and `RUN_ORDER`, plus the full 40-character
`APP_COMMIT` and `APP_INSTANCE_COUNT`. A measurement is marked invalid when an
HTTP or response check fails, an iteration is dropped, or the minimum completed
sample count is not met.

The public artifact records the role, target, `nplus1-v1` version, manifest
SHA-256, application commit, instance count, round, run order, offered load,
actual iterations, latency percentiles, and explicit invalidation reasons. It
does not include a password, session cookie, or benchmark token. Credentials
remain process environment inputs to the target-specific script and must be
removed after the run.

## Current claim boundary

Every artifact from this fixture is stamped with:

```text
releaseKind=pipeline-rehearsal
claimScope=pipeline-only
```

The single benchmark account can exercise the execution and observation path,
but it cannot establish representative latency, capacity, or an index decision.
Those claims remain blocked until the separate `traffic-v1` dataset contract,
account pool, immutable AWS release tuple, and repeated A/A plus A/B gates exist.

Run the common contract test with the repository-pinned k6 version:

```bash
k6 run load-test/k6/test/traffic-benchmark-test.js
```

## Guest read slice

`guest-read.js` accepts one of these targets:

- `accommodation-detail`
- `review-list`
- `review-summary`
- `guest-reservations`
- `wishlist-list`
- `wishlist-accommodations`
- `recently-viewed`

Paginated targets additionally require `PAGE_SIZE=1|20|50`; the selected size
must not exceed the count guaranteed by the manifest. `recently-viewed` instead
requires `RECENTLY_VIEWED_SIZE=1|20|50|100`, bounded by the manifest. All IDs,
filters, and expected row counts come from the selected manifest. There is no
production ID fallback.

Public targets can run without a session. Authenticated warm-up and measurement
targets require `BENCHMARK_SESSION_FILE`, a mode-0600 temporary file containing
only the already-prepared `SESSION_ID` value. The script never creates a login
session during a measurement invocation. For `recently-viewed`, prepare the
benchmark account's ZSET before the digest baseline; the measurement script
does not call the fixture mutation endpoint.

Use an absolute manifest path outside tests. k6 resolves a relative path from
the module containing `open()`, not necessarily from the shell working
directory.

```bash
export BENCHMARK_MANIFEST=/absolute/path/to/benchmark-fixture.json
export APP_COMMIT="$(git rev-parse HEAD)"

MODE=inspect ROLE=guest TARGET=accommodation-detail \
RATE=1 DURATION=1s MIN_COMPLETED_SAMPLES=1 \
ROUND=1 RUN_ORDER=1 RUN_LABEL=guest-detail-inspect \
APP_INSTANCE_COUNT=1 BASE_URL=http://localhost:8080 \
k6 run load-test/k6/traffic/guest-read.js
```

For authenticated targets, create the session file outside the repository,
restrict it to the current user, and delete it after the invocation. Do not put
the cookie in `RUN_LABEL`, `K6_RESULT_PATH`, a command argument, or a result.

The current manifest cannot establish account concurrency capacity, dataset
run identity, canonical payload digest, ETL commit, Flyway version/checksums,
evaluation time/expiry, or target cardinality/popularity. These fields are
preserved as a machine-readable `manifestGaps` list in every measure artifact;
they must become part of `traffic-v1` before representative AWS conclusions.
