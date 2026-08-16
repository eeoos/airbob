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
| Verified nineteen-file bundle package | Implemented (local package + immutable S3 publication workflow; not executed) |
| Immutable app/infra image construction and publication | Implemented (workflow/config only; no ECR publication executed) |
| Bundle upload, repository-s3 proof, and trusted SSM bootstrap | Bundle publication and Phase 2 SSM bootstrap configured; repository-s3 and live proof pending |
| Elasticsearch host `vm.max_map_count` runtime enforcement | SSM fail-closed configuration implemented; not executed |
| Terraform persistent foundation | Implemented, including protected private DNS anchor (configuration/static tests only; not applied) |
| Terraform DNS/lab state boundaries | Implemented (DNS contract + Phase 2-4 ephemeral lab root; not applied) |
| Expiry observer and SNS/CloudWatch alerts | Implemented (read-only configuration/static tests; disabled, delivery unverified, and not applied) |
| Lease/fencing cleanup controller and automatic destroy | Not implemented yet |
| Ephemeral VPC and dependency-service EC2 Terraform | Implemented (configuration/mock tests only; not applied) |
| Immutable dataset validator and ordered RDS/Redis/Kafka/Debezium/ES bootstrap | Implemented (configuration/static and mock tests only; no V16 release published or restored) |
| Ephemeral RDS Terraform | Implemented (`db.t3.micro`, Single-AZ, dump or validated snapshot; not applied) |
| Ephemeral ALB/App ASG/load generator Terraform | Implemented (configuration/mock tests only; SSM/app/image runtime and k6 tooling not executed) |
| Route 53 cutover | Not executed |
| AWS performance evidence | Not collected |

The repository now supplies application guards and statically verified service
bundle/config contracts. The bundle package contains only the reviewed nineteen
configuration files; runtime env files and the fixed forbidden secret-bearing
path families are excluded. The content gate rejects the enumerated password,
secret, token, credential, API/access/private-key, service-account, and private
key marker families except for six exact reviewed placeholder/guard lines. It
does not prove that arbitrary secret material hidden under a benign key is
absent. The persistent foundation, separate weighted-DNS state, and Phase 2 lab
destruction boundary are represented and statically tested, but have not been
planned against or applied to AWS. Applying the lab root now declares billable
NAT/probe or dependency-service EC2/EBS/EIP resources according to its explicit
phase. The foundation additionally declares one persistent private hosted zone
(standard monthly hosted-zone charge) and a subnet-free anchor VPC with no
hourly VPC charge. A disabled-by-default observer discovers the lab by stable
identity tags, reports missing/invalid lifecycle tags, and can alert through a
customer-KMS-encrypted CloudWatch/SNS path after explicit email confirmation.
It cannot mutate DNS,
start cleanup, or delete resources. The repository now defines digest-pinned
multi-architecture app/infra builds, exact ECR publication manifests, OIDC-only
publisher workflows, and CI-side image runtime checks, but none has been run
against ECR yet. The OCI compatibility job alone retains mutable GHCR `latest`
tags for the two custom images; AWS consumers accept only ECR digest references.
The repository can now publish the bundle package immutably and enforce Phase 2
host prerequisites through SSM, but neither path has run against AWS. This work
does not apply Terraform, create live AWS resources, migrate authoritative DNS,
change Route 53 traffic, restore data, or establish performance results. The
historical ETL dump is Flyway V12 while the application is V16, so the Phase 3
validator intentionally refuses it; a newly generated immutable V16 release is
a prerequisite for the first live rehearsal.

Phase 3 now binds one manifest SHA to an ephemeral RDS instance and runs the
ordered bootstrap from the Debezium host: database import and Flyway/schema
fingerprints, optional read-only Elasticsearch S3 snapshot restore, both Redis
resets plus declared coupon preparation, exact empty Kafka topics, then a
Debezium `no_data` connector. A separate `data-ready` Terraform transition
accepts only the resulting exact S3 receipt. RDS-managed and Debezium passwords
are resolved only on the host from Secrets Manager. Persistent RDS snapshot
promotion is a separate publisher/admin command and remains outside the lab
role and lab destroy graph.

Phase 4 now creates an HTTPS-only ALB and an application ASG at `0/0/0` while
data bootstrap is in progress. An exact `data-ready` receipt is required before
`app_enabled=true` can select single-AZ `performance` (`1/1/1`, no scaling
policy) or two-AZ `scaling` (`1/1/4`, CPU 50% plus a caller-supplied
baseline-derived ALB request target). The immutable app digest, measurement
policy, cache toggle, dataset, and RDS identity form a launch-template runtime
revision. New targets receive the RDS credential only through a tag-targeted
SSM association and a mode-0600 env file. ALB stickiness is disabled, app/node
metrics are discoverable by Prometheus tags, and a CloudWatch dashboard covers
ALB, ASG, RDS, dependency credit/surplus, and optional load-generator metrics.
The optional `c6i.xlarge` load-generator host has a direct public route and no
inbound rule; k6 installation and evidence execution are still deferred.
Terraform does not wait for an ASG instance refresh to finish, so the 15-minute
poll/cancel/rollback and pre-DNS health decision remain Phase 5 orchestration,
not a completed runtime guarantee.

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
archive; it is not an authenticity signature. Remote upload is implemented
with an immutable-key/manifest-last contract but has not been executed;
repository/S3 runtime trust remains unproven. The fixed-corpus sensitive-key gate complements,
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
