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

These are GET-only read targets. The allowlist intentionally excludes accommodation
availability, reservation quote, checkout, and every reservation mutation; an unknown or
mutation target is rejected before traffic starts.

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

## First AWS discovery run

`run-aws-discovery.sh` operates only an already-running `isolated-read` lab with
its load generator enabled. Local Make and the protected GitHub workflow call
the same script. The first slice accepts only the public
`accommodation-detail` target, so it neither reads nor creates a session.

```bash
make aws-discovery \
  RUN_ID=<active-lab-run-id> \
  TARGET=accommodation-detail \
  RATE=1 DURATION=30s WARMUP_DURATION=10s \
  MIN_COMPLETED_SAMPLES=30 ROUND=1 RUN_ORDER=1 \
  APP_COMMIT=<full-40-character-ECR-tag> \
  OCI_ORIGIN_IPV4=<current-OCI-origin-ipv4> \
  EXPECTED_SQL_CALLS_PER_REQUEST=<observed-contract>
```

The runner acquires the same DynamoDB lease used by `up`, `switch`, and
`down`, then binds its committed source archive and the official k6 v1.5.0
Linux amd64 archive to pinned SHA-256 values. It verifies the selected dataset
wrapper, benchmark-manifest hash, bootstrap receipt, deployed ECR digest,
Flyway V27, actual healthy app count, direct ALB health, and authoritative plus
public DNS convergence on the AWS weighted origin before traffic. Inspect and warm-up
must pass before it opens a same-duration idle SQL window. Any ambient SQL,
counter reset, digest eviction, or digest-text drift prevents measurement.

The measurement invocation performs no login or setup request. It records the
load-generator timestamps, k6 result, Performance Schema snapshots, and the
same Prometheus query window under
`measurements/<RUN_ID>/<RUN_LABEL>/`. Dataset hashes, ECR digest, Flyway
version, and healthy app count are read again after the run; drift marks the
aggregate invalid. The local aggregate is
`build/k6/traffic/<RUN_LABEL>-aggregate.json` and remains stamped
`pipeline-rehearsal` / `pipeline-only`.

Use a fresh `RUN_LABEL` for every attempt. Input and output objects are created
at immutable run-scoped paths, and existing remote staging is never replaced.
This harness has fake-AWS and mock-Terraform coverage but has not yet produced
live AWS performance evidence.
