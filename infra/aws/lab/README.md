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

The supported operator defaults to `dns_mode=direct-only`. Terraform then
accepts exactly one operator public `/32` as `alb_ingress_cidr`; explicit
`cutover` is the only mode that accepts `0.0.0.0/0`. The direct operator role
cannot write public-DNS state or assume the DNS controller; those permissions
exist only on the separate cutover operator role. Direct-only never stages,
switches, or removes public DNS and verifies OCI authority around the Lab
lifecycle. Both modes repeat the OCI check after direct ALB smoke and publish
direct-readiness before any optional DNS stage/switch. The protected hosted
runner always resolves its live public IPv4; a supplied `/32` must match it
exactly. The qualification footprint uses `performance`,
`integrated-smoke`, one app instance, and `load_generator_enabled=false`.

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

Before those applies, the operator performs a targeted apply of only
`terraform_data.run_identity`. This no-cost state record binds the immutable
operator manifest's run ID and original resource fencing token before any
network resource can be created. Recovery reads that identity independently of
`phase2_contract`, including from the exact current-state JSON if the output
was not published.

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
   version, and `database_bootstrap=dump|snapshot`. Snapshot mode additionally
   requires the exact promoted snapshot's source Lab run and source RDS resource
   ID; dump mode requires every snapshot-source field to be empty. A second receipt must attest
   that the probe is terminated before the five service hosts and RDS can enter
   the graph. Readiness later fetches that receipt's nonempty exact S3 VersionId,
   validates schema/run/VPC/probe/terminated state, and binds both its byte SHA
   and normalized semantic SHA. The Debezium host then runs the ordered Phase 3 bootstrap. The
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
   manifest, RDS resource id, and dependency state. Readiness also re-reads the
   live ALB attachment, requires exactly one TCP/443 ingress rule from the
   requested CIDR with no IPv6 alternative, and matches live ASG capacity to
   the Phase 4 contract.

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
and its SHA-256 is an explicit Terraform input. The operator also requires the
audited `DATASET_MANIFEST_VERSION_ID` and fetches that exact S3 version. The
service bundle is independently pinned by `BUNDLE_COMMIT`, archive SHA, and
the exact `BUNDLE_MANIFEST_VERSION_ID`; the supplied app digest must equal the
ECR digest tagged by that runtime commit. The release contains
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

The current application lineage is Flyway V27. The historical V12 ETL dump is
therefore deliberately rejected and must not be relabelled. A new V27
`pipeline-rehearsal` or `evidence` release must be produced before a live Phase
3 run. No dataset has been uploaded by this implementation.

The V27 dataset is built by applying migrations to an empty database before
ETL fixtures are inserted. In particular, V25 must see zero reservation rows;
there is no mixed-version window with a V24 writer and no automatic rollback
to a pre-inventory app. Phase 3 accepts only the immutable V27 manifest and
schema fingerprint, then checks published accommodation timezone shape before
an app target can become healthy. Normal reservation-capable AWS and OCI
profiles additionally perform full Java IANA `ZoneId` validation during their
mandatory inventory bootstrap; the read-only performance-lab exception is
described below.

The lab application is deliberately read-only for reservation inventory. Both
`aws,performance-lab,test` and the `aws,traffic-benchmark` profile group disable
inventory startup, rolling seed, and retention, so the manifest's exact
`accommodation_inventory_day=0` contract is preserved instead of materializing
date rows for 200,201 accommodations. For the fixed immutable application image,
the additional `test` profile excludes the main scheduling configuration while
the explicit runtime environment restores every integrated Kafka listener to
`auto-startup=true`; the runtime and context contracts verify both sides. The
benchmark target allowlist contains
only GET requests and excludes availability, quote, checkout, and reservation
mutations. Any inventory-dependent call therefore fails closed with HTTP 503 /
`R026`. Ordinary `aws` and `oci` deployments retain mandatory inventory startup.

Dump mode creates an empty `db.t3.micro` RDS MySQL instance; snapshot mode may
use only an encrypted, available snapshot whose release, run, dump, Flyway,
and manifest tags match the selected release. The dump remains canonical and
the snapshot is only a rebuild cache. Snapshot promotion is a separate
publisher/admin operation:

```bash
AIRBOB_REGION=ap-northeast-2 \
  infra/aws/scripts/promote-rds-snapshot.sh \
  manifest.json data-bootstrap-receipt.json "$DATA_RECEIPT_VERSION_ID" \
  direct-readiness.json "$DIRECT_READINESS_VERSION_ID" \
  airbob-<run-id> airbob-dataset-<release> promotion.json
```

That command creates and validates a persistent snapshot candidate; it does
not grant the ephemeral lab role authority to retain data or update a
persistent release inventory. `promotion.json` is create-only. Before any RDS
call, the promoter downloads both receipts from the fixed evidence bucket by
their exact S3 VersionIds and requires byte-for-byte equality with the supplied
files. It requires `airbob-<run-id>` to match the exact bootstrap and readiness
receipts, then stamps the validated source run, source RDS resource ID, both
receipt identities, and promotion schema onto the snapshot. Snapshot bootstrap
rejects a snapshot that merely copies the dataset tuple without those promotion
markers.

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
destroyable by the fenced operator; persistent resources continue to enter
only through the validated SSM contract. Applying `network`, `services`, or
`data-ready` creates billable EC2/EBS/EIP/RDS/ALB resources. Use the Phase 5
wrapper and immediate teardown; dump bootstrap requires a minimum five-hour
TTL, while snapshot bootstrap continues to use an explicit two-hour TTL. Dump
up alone uses a 14,400-second operator and lease deadline: its envelope covers
the 7,200-second SSM restore plus 3,600 seconds before bootstrap and 2,400
seconds after bootstrap. That deadline remains inside the 270-minute workflow
ceiling and the 18,000-second credential/session and TTL boundaries. Snapshot
and down retain the 5,400-second operator deadline and 7,200-second workflow
and credential boundaries. The longer dump safety window does not extend
normal resource lifetime because a successful rehearsal is torn down
immediately. Low-level phase applies remain diagnostic/emergency tools.

The supported teardown is `make aws-down RUN_ID=<run-id>`. It publishes a
create-only teardown-start journal before destroy. The first saved plan targets
every managed state address except `terraform_data.run_identity`, verifies the
exact delete-only address set, and applies it. Any remaining data-source state
is removed only after the current JSON inventory proves an exact `mode=data`
allowlist. With the state reduced to the matching run identity, the operator
publishes and exact-version reads
`measurements/<run-id>/teardown-finalize.json`; that journal binds the
identity-only state VersionId, object SHA, lineage, serial, resource fence, and
the versioned teardown-start journal. A second refresh-free destroy plan must
then describe exactly one delete for the state-only built-in `terraform_data`
identity. The operator verifies that plan but does not apply it; it removes the
single literal state address with `terraform state rm`, avoiding an external
API call and producing a stable one-serial transition. The resulting empty
state must have the same lineage and exactly the predecessor serial plus one.
A failed first apply or finalize publication therefore leaves the identity
available for an explicit retry, and a lost identity state-removal response
can be recovered only by the matching run's versioned finalize journal.
Teardown then requires verified OCI-only DNS/direct/public health and zero
globally tagged or explicitly scanned compute, network, ALB, RDS, IAM, SSM,
secret, dashboard, alarm, and six exact private-zone service A-record
resources. Every explicit AWS scan fails closed when the service query itself
fails. Its final authority is
`measurements/state-clean/<sha256(state-VersionId)>.json`, which also binds the
state-object SHA. Do not delete the backend object. A later `up` accepts it only
when that exact final receipt, exact versioned teardown-start and
teardown-finalize journals, the direct lineage/serial transition, and a
fresh account-wide Lab orphan scan all validate. Run IDs are globally
single-use; their create-only operator manifest is read back before the first
Terraform mutation, and data-bootstrap receipts are create-only at the bucket
boundary. Even the first run with no backend state must pass the same global
orphan scan before publishing its manifest. Automatic failure cleanup requires a
fresh OCI observation and successful create-only/read-back teardown-start
journal before destroy; if either fails it preserves the Lab for explicit
recovery and still releases the orchestration lease. If final publication failed after a
successful destroy, repeat `aws-down RUN_ID=<old-run>`; the journal allows
finalization without another apply or destroy. Scheduled forced cleanup is
eligible at `expiresAt`, and the minute-17/47 workflow cadence bounds pickup to
30 minutes. A native S3 `.tflock` left by a SIGKILL is never removed by direct
S3 deletion. The current lease preserves a lock it could have created; a later
lease may invoke Terraform `force-unlock` only after the exact LockInfo ID,
version, backend path, byte stability, and `Created < AcquiredAt` fence all pass.
It additionally fixes the current S3 lock `VersionId` and server `LastModified`,
writes a create-only S3 clock receipt, waits 18,300 seconds of server-observed
elapsed time, and rechecks the unchanged bytes and S3 identity immediately
before Terraform performs the ID-checked unlock.
