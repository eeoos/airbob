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
| Bundle upload, repository-s3 proof, and trusted SSM bootstrap | Bundle publication and Phase 2 SSM bootstrap configured; local repository-s3 producer contract implemented, live proof pending |
| Elasticsearch host `vm.max_map_count` runtime enforcement | SSM fail-closed configuration implemented; not executed |
| Terraform persistent foundation | Applied baseline in account `942632789808`, including the protected private DNS anchor and issued API ACM certificate; last applied state was drift-free, dataset-publisher delta is unapplied |
| Terraform DNS/lab state boundaries | Route 53-authoritative OCI-only DNS state applied; Phase 2-4 ephemeral lab root remains unapplied |
| Expiry observer and SNS/CloudWatch alerts | Applied read-only and disabled; delivery remains unverified |
| Lease/fencing controller and scheduled GitHub expiry cleanup | Implemented (configuration/fake-CLI tests only; AWS-native sweeper and live execution remain pending) |
| Ephemeral VPC and dependency-service EC2 Terraform | Implemented (configuration/mock tests only; not applied) |
| Immutable V17 dataset assembly and publication | Local DB/search assembly, native ES snapshot producer, and manifest-last publisher implemented (static/mock tests only; publisher role/policy unapplied and nothing published) |
| Ordered RDS/Redis/Kafka/Debezium/ES bootstrap | Implemented (configuration/static and mock tests only; no V17 release published or restored) |
| Ephemeral RDS Terraform | Implemented (`db.t3.micro`, Single-AZ, dump or validated snapshot; not applied) |
| Ephemeral ALB/App ASG/load generator Terraform | Implemented (configuration/mock tests only; SSM/app/image runtime and k6 tooling not executed) |
| Route 53 cutover | Gabia delegation completed; Route 53 serves the verified OCI weight-100 record, while the AWS alias remains pending |
| AWS discovery and SQL-digest harness | Implemented for public accommodation detail (fake-AWS tests only; not executed) |
| AWS performance evidence | Not collected |

The repository now supplies application guards and statically verified service
bundle/config contracts. The bundle package contains only the reviewed nineteen
configuration files; runtime env files and the fixed forbidden secret-bearing
path families are excluded. The content gate rejects the enumerated password,
secret, token, credential, API/access/private-key, service-account, and private
key marker families except for six exact reviewed placeholder/guard lines. It
does not prove that arbitrary secret material hidden under a benign key is
absent. The persistent foundation and OCI-only weighted-DNS state are applied;
the last applied state was drift-free, while the new dataset-publisher delta
remains unapplied. The Phase 2 lab destruction boundary remains statically
tested but unapplied. Applying the lab root now declares billable
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
host prerequisites through SSM, but neither path has run against AWS. The live
foundation and OCI-only Route 53 record are now authoritative after the Gabia
delegation. The public API origin is unchanged: Route 53 still sends all
traffic to OCI at `140.245.76.140`, and the public `/health` probe remains
healthy. The AWS alias is absent. The ephemeral lab has not been created, data
has not been restored, and no performance results have been established. The
historical ETL dump is Flyway V12 while the application is V17, so the Phase 3
validator intentionally refuses it; a newly generated immutable V17 release is
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

The local search-data path now uses the exact digest-pinned Elasticsearch image
from the `infra-image-release-<full-commit>` workflow artifact. A version-2
database attestation reruns the exact verifier SQL from the source ETL commit
and binds the restored MySQL server UUID and approved fingerprint subset. One
local producer then acquires the release-specific DynamoDB lease and uses a
temporarily enabled, exact-prefix IAM grant to write a native snapshot directly
to the initially empty `elasticsearch/releases/<dataset-release>/` S3 prefix.
It unregisters the writer repository, registers the same prefix read-only,
restores the snapshot to a temporary verification index, compares DB/ES
membership and IDs, verifies source-to-restored ES mapping/content fidelity,
and records a stable S3 version inventory. It does not claim a field-by-field
MySQL-to-ES content projection proof.

After snapshot receipt generation, a second foundation plan sets the writer
release back to null. The wrapper publisher self-inspects its role and refuses
all S3 work until that revoke is active. It then checks the receipt and
inventory, writes wrapper payloads with no-overwrite semantics, and writes
`manifest.json` last. The AWS bootstrap registers only the read-only repository
and cannot publish a snapshot. The dedicated local MFA role, temporary grant,
and bucket policy exist in Terraform but are unapplied, and neither a V17
wrapper nor native snapshot currently exists in the dataset bucket.

Phase 4 now creates an HTTPS-only ALB and an application ASG at `0/0/0` while
data bootstrap is in progress. An exact `data-ready` receipt is required before
`app_enabled=true` can select single-AZ `performance` (`1/1/1`, no scaling
policy), fixed two-AZ `distributed-lock` (`2/2/2`, no scaling policy), or
two-AZ `scaling` (`1/1/4`, CPU 50% plus a caller-supplied baseline-derived ALB
request target). `distributed-lock` requires `isolated-read` and a null request
target. The immutable app digest, measurement
policy, cache toggle, dataset, and RDS identity form a launch-template runtime
revision. New targets receive the RDS credential only through a tag-targeted
SSM association and a mode-0600 env file. ALB stickiness is disabled, app/node
metrics are discoverable by Prometheus tags, and a CloudWatch dashboard covers
ALB, ASG, RDS, dependency credit/surplus, and optional load-generator metrics.
The optional `c6i.xlarge` load-generator host has a direct public route and no
inbound rule. The Phase 6 discovery runner installs checksum-pinned k6 v1.5.0,
executes inspect/warm-up/idle-control/measure as separate SSM commands, and
joins k6, Prometheus, and MySQL digest artifacts. The path is covered by a
hermetic fake-AWS execution but remains unproven on the live host.
Terraform does not wait for an ASG instance refresh to finish, so the 15-minute
poll and pre-DNS target-health decision are now implemented by the Phase 5
controller. They remain an unproven runtime guarantee until a live rehearsal.

`distributed-lock` currently prepares only a cross-JVM, shared-Redis topology.
Here `isolated-read` means that schedulers, the connector, and Kafka consumers
are isolated; it does not technically block an HTTP write.
No mutating reservation contention runner or correctness evidence has been
implemented or executed. A future run must use synthetic reservation data and
must reset the dataset or tear the lab down afterward; read-performance results
from a mutated dataset are invalid.

## Phase 5 operator workflow

Local operation and the protected GitHub workflow both call
`infra/aws/scripts/aws-lab.sh`. Every mutating operation acquires the one-row
DynamoDB lease, increments its fencing token, heartbeats it, and rechecks the
exact owner/token/run/command before every Terraform or DNS mutation. Status is
read-only and does not acquire or update the lease. Public DNS is changed only
after assuming `airbob-dns-controller`; the general lab role cannot write the
public hosted zone.

The local interface is:

```bash
make aws-up \
  MODE=performance \
  POLICY=isolated-read \
  IMAGE_DIGEST=sha256:<64-hex> \
  DATASET_RELEASE=<published-v17-release> \
  AMI_ID=<reviewed-al2023-x86_64-ami> \
  OCI_ORIGIN_IPV4=<reviewed-oci-ip> \
  RDS_ENGINE_VERSION=8.0.<reviewed-patch>

make aws-status
make aws-switch TARGET=oci
make aws-down
```

Scaling additionally requires `REQUEST_TARGET=<baseline requests/target/min>`.
The fixed distributed-lock topology uses
`MODE=distributed-lock POLICY=isolated-read` and rejects `REQUEST_TARGET`.
Before DNS cutover, the operator requires exactly two healthy `InService` ASG
instances across the two configured AZs and the same two healthy ALB target
IDs. It repeats that check immediately before and after the AWS DNS switch;
post-switch drift rolls DNS back to OCI. Only the final topology is stored in
`runs/<run-id>/application-readiness.json`.
`TTL_HOURS` defaults to 6 and is limited to 24. The default dump bootstrap can
be changed to a prevalidated snapshot only with both
`DATABASE_BOOTSTRAP=snapshot` and its exact snapshot identifier. The bundle
commit defaults to the checked-out full Git commit; the operator resolves its
nine ECR image digests, bundle checksum, application digest, and dataset
manifest SHA before creating a VPC. It never discovers or guesses the AMI, OCI
origin, RDS patch, application digest, or dataset release.

`up` performs four ordered Terraform transitions (`network`, `probe-cleared`,
`services`, `data-ready`). Between the last two transitions, `isolated-read`
pauses the Debezium connector, requires an empty outbox/idle DB threads, and
proves Kafka end offsets remain stable over the idle window. It then waits at
most 15 minutes for the ASG refresh and all
desired ALB targets, probes OCI with `--resolve` and AWS with `--connect-to` to
preserve SNI, stages AWS at weight zero, and then switches weights. A failed
public AWS smoke attempts OCI rollback. Every lab plan is inspected before
apply and rejects any deletion carrying `Persistence=persistent`. `up` also
refuses to replace an existing lab state; inspect it with `aws-status` and run
`aws-down` before starting a different run. Unless `KEEP_ON_FAILURE=true`, a
failed pre-cutover run records bounded failure and redacted Terraform-output
evidence before destroying the lab-only state. `down` first restores and
verifies OCI, repeatedly checks the public API for two TTL intervals (120
seconds), removes the AWS alias, records the final non-sensitive Terraform
outputs, destroys only the lab state, and checks tagged plus explicit
EC2/RDS/ALB/EBS/EIP/ASG orphans. `FORCE=true` is rejected until two hours after
the run expiry.

Before the first live run, apply the reviewed dataset-publisher foundation
change, publish the full-commit images and bundle, download the matching
immutable infrastructure image-release artifact, and publish a compatible V17
dataset. The DNS delegation and ACM issuance are already complete. Protect the
GitHub Environment `aws-performance-lab` and define
`AWS_LAB_OPERATOR_ROLE_ARN`, `AWS_LAB_AMI_ID`,
`OCI_ORIGIN_IPV4`, and `AWS_LAB_RDS_ENGINE_VERSION`. The workflow uses OIDC
only, has a fixed concurrency group with in-progress cancellation disabled,
and calls the same operator script. The dataset-publisher foundation delta,
image and bundle publication, V17 dataset publication, and the operator's live
AWS execution all remain pending.

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
