# AWS Performance Lab — Application and Service Bundle Contracts

## Current status

| Capability | Status |
|---|---|
| Redis general/cache endpoint fail-fast | Implemented |
| Same-image accommodation cache toggle | Implemented |
| External Toss/Google/Slack/S3 side-effect block | Implemented |
| Scheduler/Kafka isolated-read policy | Implemented |
| Six service-host Compose/config contracts | Implemented (configuration only) |
| Debezium worker and connector template | Implemented (unrendered, enumerated sensitive-marker gate) |
| Prometheus AWS target definitions | Implemented (static/config validation only) |
| Verified nineteen-file bundle package | Implemented (local artifact only) |
| Immutable app/infra image construction and publication | Implemented (workflow/config only; no ECR publication executed) |
| Bundle upload, repository-s3 proof, and trusted SSM bootstrap | Image build/runtime proof configured; upload and SSM bootstrap not implemented |
| Elasticsearch host `vm.max_map_count` runtime enforcement | Not implemented yet |
| Terraform persistent foundation | Implemented (configuration/static tests only; not applied) |
| Terraform DNS/lab state boundaries | Implemented (DNS contract + zero-resource lab root; not applied) |
| Expiry observer and SNS/CloudWatch alerts | Implemented (read-only configuration/static tests; disabled, delivery unverified, and not applied) |
| Lease/fencing cleanup controller and automatic destroy | Not implemented yet |
| Ephemeral VPC/compute/RDS/ALB Terraform | Not implemented yet |
| Route 53 cutover | Not executed |
| AWS performance evidence | Not collected |

The repository now supplies application guards and statically verified service
bundle/config contracts. The bundle package contains only the reviewed nineteen
configuration files; runtime env files and the fixed forbidden secret-bearing
path families are excluded. The content gate rejects the enumerated password,
secret, token, credential, API/access/private-key, service-account, and private
key marker families except for six exact reviewed placeholder/guard lines. It
does not prove that arbitrary secret material hidden under a benign key is
absent. The persistent foundation, separate weighted-DNS state, and empty lab
destruction boundary are represented and statically tested, but have not been
planned against or applied to AWS. The lab root intentionally has no billable
resources yet. A disabled-by-default observer discovers the lab by stable
identity tags, reports missing/invalid lifecycle tags, and can alert through a
customer-KMS-encrypted CloudWatch/SNS path after explicit email confirmation.
It cannot mutate DNS,
start cleanup, or delete resources. The repository now defines digest-pinned
multi-architecture app/infra builds, exact ECR publication manifests, OIDC-only
publisher workflows, and CI-side image runtime checks, but none has been run
against ECR yet. The OCI compatibility job alone retains mutable GHCR `latest`
tags for the two custom images; AWS consumers accept only ECR digest references.
This work does not upload the bundle package, enforce host prerequisites through SSM, create AWS resources, apply
Terraform, migrate authoritative DNS, change Route 53 traffic, or establish
performance results.

The local packager binds every archive member's regular-file type and bytes to
the named current `HEAD`. It also materializes the aggregate validator, its
child verifier, manifest, and image/runtime fixtures from that same commit in a
private staging directory and validates those committed bytes rather than a
mutable working-tree validator. It then emits the archive, checksum, and
release manifest. Run it with a caller-owned mode-0700 output directory and a
trusted `PATH`/toolchain. The three files cannot appear atomically as one
filesystem transaction, so consumers must treat the release manifest as the
completion marker and verify its archive name, checksum, commit, and exact file
list before using the archive. The SHA-256 protects integrity of the produced
archive; it is not an authenticity signature. Remote upload and repository/S3
trust remain unimplemented. The fixed-corpus sensitive-key gate complements,
but does not replace, repository secret scanning and human review for
benign-looking keys.

Before enabling the ECR publication jobs, create and protect the GitHub
Environment `aws-image-publisher`, allow only `main`, and expose the applied
foundation role ARN as `AWS_IMAGE_PUBLISHER_ROLE_ARN`. The AWS role trusts only
`aud=sts.amazonaws.com` plus that Environment's exact reviewed OIDC `sub`.
These settings are a one-time prerequisite; the repository does not fall back
to static AWS access keys when they are absent. OCI publication and deployment
are separate jobs, so an unconfigured or failed AWS publisher cannot block the
always-on OCI deployment path.
Because none of the nineteen approved files requires hexadecimal character
escapes, the gate rejects every `\xNN`, `\uXXXX`, and `\UXXXXXXXX` occurrence
and every physical line ending in `\` before placeholder approval. It rejects
raw CR, NEL, LS, and PS line-break bytes anywhere in the corpus, while the
Debezium Java Properties file rejects every backslash because its reviewed form
requires none. Ordinary UTF-8 and required non-hexadecimal content such as the
JMX regex `\\w` remain allowed outside that Properties file.

For each Compose bundle, the canonical empty-profile and all-profile views must
both match the exact service allowlist and the exact seventeen
service-to-resolved-digest associations. The fixture is parsed without shell
evaluation as exactly ten unique, non-colliding image variables plus the two
approved runtime-env path variables. Canonical service-level `scale` and
`deploy` declarations are forbidden, so the bundle-declared cardinality is one
per service and application scale-out remains an ASG concern. These static
checks do not prove actual container count, health, CLI overrides, or runtime
behavior; repository scanning, human review, and later SSM runtime smoke remain
required.

## Application profile matrix

```text
integrated-smoke: SPRING_PROFILES_ACTIVE=aws,performance-lab
isolated-read:    SPRING_PROFILES_ACTIVE=aws,traffic-benchmark
```

`integrated-smoke` retains the application's internal scheduler and Kafka flow while blocking external Toss, Google, Slack, and ordinary S3 write side effects. `isolated-read` adds the scheduler and Kafka listener isolation policy. `application-traffic-benchmark.yaml` includes `performance-lab` through a Spring profile group, so it also inherits the external side-effect block.

## Redis and cache experiment boundary

The statically resolved Redis bundle declares exactly two Redis services and
exactly two Redis exporters for one Redis host: general Redis plus its exporter,
and the dedicated accommodation-detail cache Redis plus its exporter. The
`performance-lab` profile fails startup if the two Redis endpoints resolve to
the same normalized host and port.

For the same-image cache A/B experiment, change only `ACCOMMODATION_DETAIL_CACHE_ENABLED=true|false`. When disabled, accommodation-detail cache reads and eviction bypass the cache clients; the two-endpoint topology remains configured.

Cache reset means `FLUSHDB` on the dedicated accommodation-detail cache Redis only. It must not flush the general Redis, which holds general application data such as sessions and locks. No HTTP cache-reset endpoint exists.
