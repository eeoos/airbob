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
