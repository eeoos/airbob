# AWS performance-lab ephemeral state

This root is the destruction boundary for resources that exist only during a
performance-lab run. Phase 2 declares the two-AZ VPC, test-only NAT instance,
S3 gateway endpoint, the protected private-zone association and records,
disposable egress probe, and the five dependency-service EC2 hosts. Phase 3
adds one Single-AZ RDS MySQL instance plus its ordered dataset bootstrap. Phase
4 adds an HTTPS-only ALB, a `c6i.large` application ASG, two capacity modes,
and an optional no-ingress `c6i.xlarge` load-generator host. It still
consumes persistent identifiers only through the exact, non-secret SSM
contract published by foundation.

It does not use a remote-state data source, read the foundation state object,
or own the Route 53 hosted zone and weighted API records. A future lab destroy
must therefore be unable to delete persistent datasets, evidence, bundles,
ECR repositories, the orchestration lease table, ACM, OCI DNS, or the hosted
zone through this Terraform graph.

Prepare its committed S3 backend with:

```bash
AWS_REGION=ap-northeast-2 \
  infra/aws/scripts/prepare-terraform-backend.sh lab
terraform -chdir=infra/aws/lab init \
  -backend-config=backend.generated.hcl \
  -input=false
```

Before a live run, apply the reviewed foundation change that creates the host
permissions boundary, Phase 2 lab-operator policy, and protected
`lab.airbob.internal` private zone with its subnet-free anchor VPC. Publish the
immutable ECR images and service bundle, and select one reviewed Amazon-owned
AL2023 x86_64 AMI id. No default AMI lookup is used, so the measured host image
cannot silently change between runs.

The Phase 2-4 transition is deliberately four applies, not one:

1. `deployment_phase=network` creates networking, NAT, and one private
   `t3.nano` probe. It creates no dependency-service host.
2. Run `infra/aws/scripts/verify-network-egress.sh egress ...`, then apply
   `deployment_phase=probe-cleared`. The exact S3/NAT/ECR/SSM/Secrets Manager
   receipt is required and the probe is removed; service count remains zero.
3. Run `verify-network-egress.sh cleared ...`, then apply
   `deployment_phase=services` with the bundle commit/SHA, nine infrastructure
   ECR digest references, the application ECR digest, the immutable dataset
   release, manifest SHA, MySQL patch
   version, and `database_bootstrap=dump|snapshot`. A second receipt must attest
   that the probe is terminated before the five service hosts and RDS can enter
   the graph. The Debezium host then runs the ordered Phase 3 bootstrap. The
   ALB and App ASG are present, but the ASG contract is exactly `0/0/0` and no
   scaling policy exists.
4. After the bootstrap has written its exact S3 receipt, apply
   `deployment_phase=data-ready` with the same immutable inputs and
   `app_enabled=true`. Choose `mode=performance` for single-AZ `1/1/1` or
   `mode=scaling` for two-AZ `1/1/4`. Scaling requires
   `measurement_policy=isolated-read` and the baseline-derived one-minute
   request target; only scaling creates its CPU 50% and ALB request-count
   target tracking policies. This transition creates no
   replacement dataset and accepts only the exact receipt for this run,
   manifest, RDS resource id, and dependency state.

The network apply associates its VPC with the foundation-owned private zone;
destroy removes only that association and the six service records, never the
zone or its anchor VPC. The services apply starts bundles through SSM only
after all private A records exist. Redis remains one `t3.small` host with
exactly two Redis containers and two exporters. The SSM gate checks
host/cgroup OOM and swap, container peak
memory, a seeded fragmentation check, `BGREWRITEAOF`, exporter metrics, the
shared Redis private IP with distinct 9121/9122 targets, and Prometheus target
health. Elasticsearch must set and read back `vm.max_map_count=1048576` before
its Compose bundle starts. A failed gate stops the apply; it does not resize or
merge hosts.

## Phase 3 dataset contract

The canonical release lives below
`s3://<dataset-bucket>/datasets/<release>/`. `manifest.json` is published last
and its SHA-256 is an explicit Terraform input. The release contains
the immutable `benchmark/manifest.json` workload input bound by the wrapper,
`mysql/airbob.sql.zst`, and `mysql/sha256.txt`; a search-enabled release also
contains `elasticsearch/snapshot-reference.json` and points to a read-only
native S3 snapshot repository. Bootstrap restores the snapshot's concrete
`accommodations` index under a dataset-versioned physical index, verifies its
count, mapping, ids, and content, then atomically assigns the `accommodations`
write alias. Run
`infra/aws/scripts/verify-dataset-release.sh` before publication or restore.
The exact schema and fingerprint procedure are documented in
`infra/aws/datasets/README.md`.

The current application lineage is Flyway V20. The historical V12 ETL dump is
therefore deliberately rejected and must not be relabelled. A new V20
`pipeline-rehearsal` or `evidence` release must be produced before a live Phase
3 run. No dataset has been uploaded by this implementation.

Dump mode creates an empty `db.t3.micro` RDS MySQL instance; snapshot mode may
use only an encrypted, available snapshot whose release, run, dump, Flyway,
and manifest tags match the selected release. The dump remains canonical and
the snapshot is only a rebuild cache. Snapshot promotion is a separate
publisher/admin operation:

```bash
AIRBOB_REGION=ap-northeast-2 \
  infra/aws/scripts/promote-rds-snapshot.sh \
  manifest.json data-bootstrap-receipt.json \
  airbob-<run-id> airbob-dataset-<release> promotion.json
```

That command creates and validates a persistent snapshot candidate; it does
not grant the ephemeral lab role authority to retain data or update a
persistent release inventory.

The SSM bootstrap is fail-closed and ordered: RDS readiness/import and Flyway
plus schema fingerprints, optional Elasticsearch restore, both Redis resets
and declared coupon preparation, the exact 12 canonical Kafka main/retry/DLT
topics at three partitions each, then a Debezium
`no_data` connector with one running task. RDS and Debezium credentials are
resolved on the host from Secrets Manager into mode-0600 temporary files and
are not Terraform values. The final receipt is written only after every gate
passes.

## Phase 4 application contract

`performance` uses only the primary private subnet and has no scaling policy.
`scaling` uses both private subnets and creates exactly two target-tracking
policies. `integrated-smoke` is performance-only and `scaling` requires
`isolated-read`. The
accommodation-detail cache toggle remains a separate
explicit boolean and is included in the launch-template runtime revision, so a
new target receives a fresh JVM and exact root-only runtime env. The app host
resolves the RDS-managed secret only from Secrets Manager during its
tag-targeted SSM association. Terraform state and user-data contain only the
secret ARN.

The ALB has only an HTTPS 443 listener using the protected
`api.airbob.cloud` ACM certificate. Its target group uses port 8080,
`/actuator/health`, no stickiness, and a fixed 30-second deregistration delay.
Launch templates require IMDSv2 with hop limit 2, detailed monitoring,
encrypted delete-on-termination gp3 storage, fixed `c6i.large`, and immutable
app plus node-exporter digests. The CloudWatch dashboard covers ALB, ASG, RDS,
dependency T3 credit/surplus, and optional load-generator metrics; Prometheus
discovers app and node-exporter targets from their EC2 tags.

Terraform starts an instance refresh from the exact numeric launch-template
version and configures the health alarm plus automatic rollback. The AWS
provider returns before that asynchronous refresh finishes. The Phase 5
controller now polls it for at most 15 minutes and refuses DNS switching until
the desired targets are healthy. Scaling's configured two-AZ placement remains
part of the redacted Phase 4 Terraform output evidence. This path is covered by
static/fake-CLI contracts only and remains unproven in live AWS.

The optional load-generator instance is a public-subnet `c6i.xlarge` with an
ephemeral public IPv4, no inbound security-group rule, detailed monitoring,
and SSM-only management, so its ALB traffic bypasses the test NAT instance.
Phase 4 creates only the host boundary; the digest/checksum-locked k6 tooling
and evidence runner remain Phase 6 work.

Every resource must retain the ephemeral identity/expiry tags and remain
destroyable by a lab-only `terraform destroy`; persistent resources continue
to enter only through the validated SSM contract.

No live plan or apply has been executed. The Terraform configuration and mock
tests are implemented, but applying `network`, `services`, or `data-ready` will
create billable EC2/EBS/EIP/RDS/ALB resources. The Phase 5 wrapper is now the
supported entry point because it supplies the required fencing token and
ordered receipts; low-level phase applies remain diagnostic/emergency tools.

The supported teardown is `make aws-down`. A manual emergency teardown must
still use `deployment_phase=network` plus the same `run_id`, `expires_at`,
`fencing_token`, and `ami_id`; destroy mode then avoids receipt and bundle
reads. Do not run a normal low-level `apply` as a substitute for the operator.
