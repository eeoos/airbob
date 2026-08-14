# AWS Performance Lab — Application and Service Bundle Contracts

## Current status

| Capability | Status |
|---|---|
| Redis general/cache endpoint fail-fast | Implemented |
| Same-image accommodation cache toggle | Implemented |
| External Toss/Google/Slack/S3 side-effect block | Implemented |
| Scheduler/Kafka isolated-read policy | Implemented |
| Six service-host Compose/config contracts | Implemented (configuration only) |
| Debezium worker and connector template | Implemented (unrendered, secret-free) |
| Prometheus AWS target definitions | Implemented (static/config validation only) |
| Verified nineteen-file bundle package | Implemented (local artifact only) |
| Immutable app/infra image construction and publication | Not implemented yet |
| Bundle upload, repository-s3 proof, and trusted SSM bootstrap | Not implemented yet |
| Elasticsearch host `vm.max_map_count` runtime enforcement | Not implemented yet |
| Terraform foundation/lab | Not implemented yet |
| Route 53 cutover | Not executed |
| AWS performance evidence | Not collected |

The repository now supplies application guards and statically verified service
bundle/config contracts. The bundle package contains only the reviewed nineteen
configuration files; runtime env files and credentials are excluded. This work
does not build or publish immutable images, upload the package, run container
smoke tests, enforce host prerequisites through SSM, create AWS resources,
apply Terraform, migrate authoritative DNS, change Route 53 traffic, or
establish performance results.

## Application profile matrix

```text
integrated-smoke: SPRING_PROFILES_ACTIVE=aws,performance-lab
isolated-read:    SPRING_PROFILES_ACTIVE=aws,traffic-benchmark
```

`integrated-smoke` retains the application's internal scheduler and Kafka flow while blocking external Toss, Google, Slack, and ordinary S3 write side effects. `isolated-read` adds the scheduler and Kafka listener isolation policy. `application-traffic-benchmark.yaml` includes `performance-lab` through a Spring profile group, so it also inherits the external side-effect block.

## Redis and cache experiment boundary

The lab topology remains exactly two Redis containers/endpoints and exactly two
Redis exporters on one Redis host: general Redis plus its exporter, and the
dedicated accommodation-detail cache Redis plus its exporter. The
`performance-lab` profile fails startup if the two Redis endpoints resolve to
the same normalized host and port.

For the same-image cache A/B experiment, change only `ACCOMMODATION_DETAIL_CACHE_ENABLED=true|false`. When disabled, accommodation-detail cache reads and eviction bypass the cache clients; the two-endpoint topology remains configured.

Cache reset means `FLUSHDB` on the dedicated accommodation-detail cache Redis only. It must not flush the general Redis, which holds general application data such as sessions and locks. No HTTP cache-reset endpoint exists.
