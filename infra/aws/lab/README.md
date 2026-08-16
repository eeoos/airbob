# AWS performance-lab ephemeral state

This root is the destruction boundary for resources that exist only during a
performance-lab run. Phase 2 declares the two-AZ VPC, test-only NAT instance,
S3 gateway endpoint, the protected private-zone association and records,
disposable egress probe, and the five dependency-service EC2 hosts. Phase 3
adds one Single-AZ RDS MySQL instance plus its ordered dataset bootstrap. It still
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

The Phase 2/3 transition is deliberately four applies, not one:

1. `deployment_phase=network` creates networking, NAT, and one private
   `t3.nano` probe. It creates no dependency-service host.
2. Run `infra/aws/scripts/verify-network-egress.sh egress ...`, then apply
   `deployment_phase=probe-cleared`. The exact S3/NAT/ECR/SSM/Secrets Manager
   receipt is required and the probe is removed; service count remains zero.
3. Run `verify-network-egress.sh cleared ...`, then apply
   `deployment_phase=services` with the bundle commit/SHA and nine ECR digest
   references, plus the immutable dataset release, manifest SHA, MySQL patch
   version, and `database_bootstrap=dump|snapshot`. A second receipt must attest
   that the probe is terminated before the five service hosts and RDS can enter
   the graph. The Debezium host then runs the ordered Phase 3 bootstrap.
4. After the bootstrap has written its exact S3 receipt, apply
   `deployment_phase=data-ready` with the same inputs. This transition creates
   no replacement dataset; it only attests the receipt for this run, manifest,
   RDS resource id, and dependency state.

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
`mysql/airbob.sql.zst` and `mysql/sha256.txt`; a search-enabled release also
contains `elasticsearch/snapshot-reference.json` and points to a read-only
native S3 snapshot repository. Run
`infra/aws/scripts/verify-dataset-release.sh` before publication or restore.
The exact schema and fingerprint procedure are documented in
`infra/aws/datasets/README.md`.

The current application lineage is Flyway V16. The historical V12 ETL dump is
therefore deliberately rejected and must not be relabelled. A new V16
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
and declared coupon preparation, exact empty Kafka topics, then a Debezium
`no_data` connector with one running task. RDS and Debezium credentials are
resolved on the host from Secrets Manager into mode-0600 temporary files and
are not Terraform values. The final receipt is written only after every gate
passes.

Later phases add ALB, App ASG, and the load generator to this same state.
Every resource must retain the ephemeral identity/expiry tags and remain
destroyable by a lab-only `terraform destroy`; persistent resources continue
to enter only through the validated SSM contract.

No live plan or apply has been executed. The Terraform configuration and mock
tests are implemented, but applying `network` or `services` will create
billable EC2/EBS/EIP/RDS resources. The orchestration lease/wrapper is a later
phase, so these low-level phase transitions must not be treated as the finished
one-command operator workflow yet.

Until that wrapper exists, a manual emergency teardown must run `terraform
destroy` with `deployment_phase=network` plus the same `run_id`, `expires_at`,
and `ami_id`. Destroy mode then does not read network receipts or bundle
objects, so missing evidence cannot prevent cost cleanup. Do not run a normal
`apply` with those emergency values as a substitute for the documented phase
sequence.
