# AWS Lab repeatability evidence

This is the durable acceptance record for restoring the final Airbob search-enabled dataset in a
dump-mode AWS Performance Lab, promoting the verified RDS instance, destroying the Lab, and then
replaying the same qualification from the promoted snapshot. It is intentionally limited to
environment readiness. It does not authorize or contain a performance experiment.

> Evidence status, 2026-09-04: `DUMP_RETRY_PENDING`. The immutable dataset, images, bundles,
> local MySQL restore, administrative promoter path, and protected GitHub OIDC Lab path remain
> fixed. The first billable dump attempt, workflow `33763841701` / run
> `lab-33763841701-1`, failed before producing a data-bootstrap or direct-readiness receipt and is
> `FAILED_NOT_QUALIFIED`. Its fenced teardown completed with empty state, zero global orphans, a
> released lease, and healthy unchanged OCI direct/public endpoints. No RDS snapshot was promoted.
> The retry contract raises the fixed Single-AZ RDS class to `db.t3.small`; both live
> qualifications and the final verdict remain pending.

## Decision and stop boundary

The environment is accepted only after both runs have immutable direct-readiness evidence, the
first RDS has been promoted, both Labs have clean teardown evidence, OCI and public DNS remain
unchanged, and the two common receipt fields are identical. At that point this record may state:

```text
반복 가능한 저비용 AWS 실험 환경 준비 완료
```

Stop immediately at that verdict. Do not run or change `EXPLAIN`, `EXPLAIN ANALYZE`, index A/B,
Redis cache experiments, N+1 work, denormalization, bulk-operation tests, coupon concurrency,
Kafka/outbox performance measurements, k6, or any other performance experiment.

## Immutable receipt

### Execution and runtime

| Identity | Fixed value | Audit result |
|---|---|---|
| Audited repository base | `5681847ab10dc2069f2462b9e190bd2d144aba78` | PR #97 merge, checked out from `origin/main` |
| Lab operator revision | Pending reviewed operator commit | Must be frozen before billable preflight |
| Published runtime commit | `1537e845b2b6f3cda915dfaf202a1592db7d73cf` | App, infra images, and bundle all bind this commit |
| App image | `airbob-repo@sha256:5bfb76de29b9e1a3f58abad51326791471fb73e58649a55146da600b13312a55` | Exact ECR tag `1537e845b2b6f3cda915dfaf202a1592db7d73cf` resolves to this digest |
| App release receipt | GitHub run `33467484602`, artifact `9785387916`, SHA-256 `07b871388ea75c5a30eeeefa5736dcf968ecdd2ceb91146048cd8418470333b1` | ECR publication job succeeded; the run's unrelated OCI sync failed |
| Infra release receipt | GitHub run `33467660204`, artifact `9785472569`, SHA-256 `c6647fcb7e11934d8afdb3ef6612d385cce6ea191318bd7bd2660699ac3cfc26` | Succeeded |
| Bundle completion marker | VersionId `a1Up1NszNzbfO7T8fFQJFQ9CjVrmplho`, SHA-256 `35f92fbf1bed7ff107785708f53f045e48cafcca514a2da013f99b55494c3048` | Exact 19-file inventory |
| Bundle checksum object | VersionId `msGsJg99MLxs7QvLHyaszwVI6Lpwq2qm` | Binds the archive SHA-256 below |
| Bundle archive | VersionId `GR5IKx.oJ.lU5HZRvLRKmT6.3JauW5AQ`, SHA-256 `471316f3914567440b88326d402de3bf2422c46ff145b3aab21a2c40393781e2` | Binds runtime commit |

The infra receipt fixes these exact image digests:

| Service | Digest |
|---|---|
| Redis | `sha256:734bdb0cff0be2a04518e00aa4a57a4a90d2d88643a352168d1dc45726b83cfd` |
| Redis exporter | `sha256:7356a9e34d57b46d948f62e35b0aba1356b118baaf4f18bd9dc6dc2a121f9786` |
| Node exporter | `sha256:d8474f9994a47ba181719ba3f3ddc7a0841334fad060d96e3e881dac77eeca52` |
| Kafka | `sha256:affd2f43f7cd3dc105e8ae2ac8fa23f5ada9be44a2898c248eb87a539bb3d96b` |
| Debezium | `sha256:44a2638911224743bea5dd513443dba8db2ab00d5f2e85a9d6ed8bd23cff01ad` |
| Elasticsearch | `sha256:3cfcf40b100763afba50c1671c4353ee767d19523dc30d97e6134e8e763d7da7` |
| Elasticsearch exporter | `sha256:7dba0bd60bdffe2c41a7f7b3a331dc82f1aba28a2bb3873a61864d1d269377cd` |
| Prometheus | `sha256:412b011cead731e4e8292b6dd4ea384cae4b61adbd63a44b0ad263a80dd6c2fc` |
| Grafana | `sha256:0b53e158ea5e52f70ba7ba9373969f4c1173014972f79ae6f2fdfe78bb2fe302` |

### Dataset completion marker

| Identity | Fixed value |
|---|---|
| Release | `production-seed-20260830t223254z-search-rehearsal-r3` |
| Dataset run | `20260830T223316Z-ff8815b2` |
| S3 manifest | `s3://airbob-performance-lab-dataset-942632789808/datasets/production-seed-20260830t223254z-search-rehearsal-r3/manifest.json` |
| Manifest VersionId | `jpC16KgrDUiHLRePM9pyRz5451.WnHTj` |
| Manifest last modified | `2026-09-01T05:27:39Z` |
| Manifest SHA-256 | `149f950df4d96ad8db7acadf243c16b01c03e76d8abc85c744a550385a9f1c91` |
| Dump SHA-256 | `46e75f72cca35426e5a705e7afb759526160328a83ff3773b25d1552761d816d` |
| Flyway | `27` |
| Migration SHA-256 | `84418ae0df964edfef4d05aa8f03530a5e9f66a9a8c637498b314f236f2c73b8` |
| Schema fingerprint | `49c5326ac8f0021cbf8615b5b58f82bbfbb5e91f987b09e93a122ce42a92e1b2` |
| Final-world fingerprint | `3d97432c20e44c2394d2136914cae01fd42f8e9455d67801f16bd5c959809d79` |
| Base-world fingerprint | `7b6bc1ae50bab86ef4fc575b030d03d26f0788c55564569bc22514c55210a83c` |
| Distribution fingerprint | `d3d2741431b67523d1a111716d9a0341879f2888ee9b5356864deb9a870a8181` |
| Target fingerprint | `01b3c82a23eda0270bc083f874c23f378067d4114f1f93cd790e584b8ec9637f` |
| Inventory fingerprint | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| Database fingerprint | `75b1039f95eb9c659b3b1f5a4f69b0a253f007da261360ea9a33e34410e9c346` |

The wrapper prefix contains exactly 12 current object versions, no noncurrent versions, and no
delete markers. Eleven payloads precede the manifest, so the marker is the manifest-last completion
signal. The compressed dump is `1,904,056,910` bytes. This receipt is authoritative; stale
repository status prose is not.

### Native Elasticsearch snapshot

| Identity | Fixed value |
|---|---|
| Snapshot | `airbob-production-seed-20260830t223254z-search-rehearsal-r3` |
| Physical index | `accommodations-v20260901015436` |
| Logical alias | `accommodations` |
| Elasticsearch version | `8.18.8` |
| Expected documents | `170201` |
| Snapshot reference VersionId | `baHnZGG_w9Bz12D65B_N7yXlpiKtVs0V` |
| Snapshot reference SHA-256 | `e382dbc633140bd00a3553681c8be9f1723b78c1150fb45e8e400e474b54fc5c` |
| Seal VersionId | `rZv_MRHKFx.KFiFcqnTiJ0LHenE6wWRR` |
| Seal SHA-256 | `3ff0083516e56a093f9c331d07be4bafc46bc07aec0ee14f6e586fadd36c6776` |
| Seal receipt SHA-256 | `ac3c7b675aaa762e03d7e86684f0bec6c97070b7c4c1fcc573c5b7efc9a565b4` |
| Mapping fingerprint | `69914e3532b5f30162403ec757a13e38b48d561b7febf9019ac7cfd59184c95d` |
| Content fingerprint | `c31eed4fb9bcc0f4c6ef2ed217474a7471f8c12310a0be19b8e8d32a666f2961` |
| DB/ES numeric-ID fingerprint | `17603d72d74de02fe214e88af67558be618ed6b0bd1c3f9f810dc6d7934eab83` on both sides |
| DB/ES identity-pair fingerprint | `22867135c8dd53c11ffbc158779936482e61cee933fffeca854c50bb2566ce5f` on both sides |

The sealed native prefix contains 41 object versions, including 9 inventory-bound delete markers,
and 35 current objects totaling about `197,590,580` bytes. The publisher writer grant is revoked to
`__disabled__`.

## Re-audited local restore

Only the Docker MySQL container was running during the audit. It reports MySQL `8.0.33`; no local
application, Elasticsearch, Kafka, Redis, or Debezium service was used. The source ETL commit was
`81990a2eaca7bc1fc61749c3f4ab1ca3c753ec6d`, the verification backend commit was
`28bde8e96a54de7fc1bfbd3a4f4778f9ee84161e`, and the 78-assertion verification receipt passed
with no failures (receipt SHA-256
`20a9f7f7726212f9820a070e7bc11ec5d77feec3e757d22a29c605eb2061021b`). Data generation and ETL
were not rerun.

| Table | Exact rows | Table | Exact rows |
|---|---:|---|---:|
| `accommodation` | 200201 | `accommodation_amenity` | 3029192 |
| `accommodation_history` | 170201 | `accommodation_image` | 200201 |
| `accommodation_inventory_day` | 0 | `accommodation_review_summary` | 150000 |
| `address` | 200201 | `common_code` | 46 |
| `common_code_group` | 2 | `coupon` | 0 |
| `daily_revenue_stats` | 5922896 | `failed_indexing_events` | 0 |
| `flyway_schema_history` | 27 | `member` | 803008 |
| `member_coupon` | 0 | `member_history` | 803008 |
| `occupancy_policy` | 200201 | `outbox` | 0 |
| `payment` | 9000402 | `payment_operation` | 0 |
| `payment_operation_resolution` | 0 | `payment_transaction` | 11000402 |
| `reservation` | 10000402 | `reservation_checkout_request` | 0 |
| `reservation_history` | 10000402 | `reservation_quote` | 0 |
| `review` | 4000000 | `review_image` | 201 |
| `settlement` | 0 | `settlement_history` | 0 |
| `wishlist` | 1600273 | `wishlist_accommodation` | 6000401 |

All 32 exact counts match the release manifest. Flyway has 27 successful rows and maximum numeric
version 27. The migration and schema fingerprints match the immutable receipt; `outbox` and
`accommodation_inventory_day` are both empty.

The application-writer preflight also evaluated rows that ordinary serving schedulers could mutate
immediately after startup. At `2026-09-01T12:43:57Z`, the restored dataset had zero recoverable
`payment_operation` rows, zero reservation quotes older than 30 days, and zero outbox rows, but it
had exactly `1,500,000` expired `PAYMENT_PENDING` reservations. Therefore an ordinary
`aws,performance-lab` JVM is not a safe snapshot-promotion source: its reservation cleanup scheduler
can change the restored world before promotion. The integrated-smoke runtime is being hardened to
use the fixed image's `aws,performance-lab,test` profile set, which excludes only the main
`SchedulingConfig` among production classes. The explicit integrated-smoke environment overrides
the test resource defaults and requires all four Kafka listener controls to be `true`; Spring
context coverage loads the test resource, verifies those overrides, and retains the Kafka
infrastructure and consumer configuration. The exact runtime-env and Spring context tests must pass before this preflight can be
marked complete. This is a write-suppression gate, not a performance experiment.

## AWS clean-start audit

| Gate | Observation on 2026-09-01 | Result |
|---|---|---|
| Account/region contract | Account `942632789808`; foundation contract present | Pass |
| Terraform backend | `airbob-performance-lab-tfstate-942632789808/airbob/lab/terraform.tfstate` has no current object | Pass |
| Tagged Lab resources | No resources carrying the Lab identity tags | Pass |
| RDS instances | None | Pass |
| Manual dataset snapshots | No `airbob-dataset-*` snapshot | Pass |
| Dataset/native snapshot | Existing immutable release and seal retained | Pass |
| Route 53 | One `api.airbob.cloud.` A record: `oci`, weight 100, TTL 60, `140.245.76.140`; no AWS alias | Pass |
| OCI origin/public health | Direct `--resolve` and public `/health` both returned exact `healthy` | Pass |
| AL2023 AMI | `ami-00b5b2470beafd65f`, `al2023-ami-2023.12.20260831.0-kernel-6.18-x86_64`, Amazon owner `137112412989`, x86_64/HVM/EBS/available | Pass |
| RDS retry contract | MySQL `8.0.46`, `db.t3.small`, gp3, VPC and storage encryption in `ap-northeast-2` | Recheck orderability immediately before retry |
| EC2 capacity quota | 32 standard-family vCPUs; 0 current instances; qualification topology needs 16 vCPUs | Pass |
| EIP/VPC quota | EIP 0/5; VPC 2/5 before Lab | Pass |
| ALB/target-group quota | ALB 0/50; target groups 0/3000 | Pass |
| RDS quota | DB instances 0/40; manual snapshots 0/100; storage ceiling 100000 GiB | Pass |
| Local Lab authentication | `admin-eeoos` and the default Terraform user cannot assume the Lab roles; trust deliberately admits only MFA `dev-eeoos` or the role-specific protected GitHub OIDC environment. The default operator has no public-DNS state/controller permission; a distinct OIDC subject and cutover role hold only that extension. | Pass in reviewed code; apply and simulate both roles before Lab creation |
| Protected OIDC path | The direct GitHub environment exists and admits only `main`; role and MySQL/OCI variables are present, but its AMI variable is the older `ami-00f6db7984ad32b20`. The distinct cutover environment/role variable is introduced by this reviewed foundation change. | Pending reviewed `main`, foundation apply, cutover-environment creation, and AMI update; no user action required |
| Promoter separation | IAM simulation for `admin-eeoos` allows RDS create/tag/describe/list-tags and KMS describe; Lab role remains separate | Pass |
| Docker Compose | Host CLI `2.29.7`; temporary official `2.40.2` arm64 plugin SHA-256 `cc3a8774fdadf65b53a12ef54b3e0e63f2267e1e843ebfa46c4976fc4f80b46b`; aggregate bundle test passed | Pass |

Do not widen the Lab role trust to the administrative or Terraform users. Use the protected OIDC
path after the reviewed merge, then rerun status and preflight without acquiring a mutation lease.

## Cost gate before apply

The minimum implemented qualification topology still creates a NAT instance (`t3.micro`), egress
probe (`t3.nano`), Redis and monitoring (`2 x t3.small`), Kafka, Debezium, and Elasticsearch
(`3 x t3.medium`), one application instance (`c6i.large`), an HTTPS ALB, a Single-AZ
`db.t3.small` RDS with 100-GiB gp3 storage, EBS volumes, public IPv4/EIP, and supporting
CloudWatch, Secrets Manager, S3, ECR, and SSM activity. The `c6i.xlarge` load generator is disabled;
there is no NAT Gateway, no Multi-AZ RDS, no scaling fleet, and no standby Lab. Dump mode uses a
six-hour TTL because its SSM bootstrap may run for up to 3.5 hours after bounded
network/RDS/service setup and must still leave a separate teardown reserve; snapshot mode retains a
two-hour TTL. These are failure backstops, not scheduled lifetimes: normal teardown runs immediately
after evidence or promotion.

EC2 and gp3 have a 60-second minimum; RDS has a 10-minute minimum after a billable state change.
ALB is billed by time and capacity usage. The final S3 release/native snapshot and the promoted RDS
snapshot are persistent storage costs; every other new resource must be transient. Dollar estimates
remain provisional until exact regional prices or posted Cost Explorer data are available.

AWS Price List observations effective 2026-08-01 give the fixed compute footprint a peak On-Demand
rate of `$0.3235/hour`: `t3.nano` `$0.0065`, `t3.micro` `$0.013`, two `t3.small`
`$0.052`, three `t3.medium` `$0.156`, and one `c6i.large` `$0.096`. The declared 186 GiB of
EC2 gp3 is about `$0.0233/hour` at `$0.0912/GB-month`; in Seoul (`ap-northeast-2`), the
`db.t3.small` RDS instance plus 100-GiB gp3 is about `$0.0700/hour` at `$0.052/hour` and
`$0.131/GB-month` (the superseded `db.t3.micro` compute rate was `$0.026/hour`); the ALB base is
`$0.0225/hour` plus `$0.008/LCU-hour`; and a conservative four in-use public IPv4 addresses add
`$0.020/hour`. The combined base peak is about `$0.4593/hour`. At the conservative six-hour dump
plus two-hour snapshot failure ceilings, the
two-run base-resource estimate is about `$3.68`, up from about `$3.47`, before fractional LCU usage,
T3 surplus CPU credits,
CloudWatch/Secrets/S3 requests, tax, and propagation delays. This is a planning estimate, not a
bill; successful runs are torn down as soon as their required evidence is durable.

Retained MySQL backup storage is `$0.095/GB-month` beyond any free allocation. A fully billed
100-GiB promoted snapshot is therefore a conservative `$9.50/month` upper bound; actual snapshot
storage can be lower because RDS bills backup data stored, not this document's estimate. This
persistent cost is user-required and is reported separately from transient Lab cost. Changing the
transient instance class does not change this snapshot-storage upper bound.

References: [EC2 On-Demand billing](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-on-demand-instances.html),
[RDS for MySQL pricing](https://aws.amazon.com/rds/mysql/pricing/),
[EBS pricing](https://aws.amazon.com/ebs/pricing/), and
[ALB billing dimensions](https://docs.aws.amazon.com/elasticloadbalancing/latest/userguide/load-balancer-billing-usage-reports.html).

## Fixed run inputs

| Input | Dump mode | Snapshot mode |
|---|---|---|
| Run ID | Pending | Pending; must differ |
| Database bootstrap | `dump` | `snapshot` |
| Snapshot ID | Not applicable | `airbob-dataset-20260830-r3-149f950df4d9` |
| Mode | `performance` | `performance` |
| Policy | `integrated-smoke` | `integrated-smoke` |
| Cache | Enabled | Enabled |
| Load generator | Disabled | Disabled |
| TTL | 6 hours | 2 hours |
| DNS mode | `direct-only` | `direct-only` |
| AMI | `ami-00b5b2470beafd65f` | Identical |
| MySQL patch | `8.0.46` | Identical |
| RDS class | `db.t3.small` | Identical |
| Dataset/images/bundle | Fixed receipt above | Identical |

## Dump-mode qualification

Status: `FAILED_NOT_QUALIFIED`; a new run ID is required for the retry.

The failed attempt is retained as operational evidence, not qualification evidence:

| Evidence | Recorded result |
|---|---|
| GitHub workflow / Lab run | `33763841701` / `lab-33763841701-1` |
| Failure observation | Two RDS recovery cycles interrupted the dump import. Recovery began at `2026-09-03T15:42:08Z` and again at `2026-09-03T15:47:08Z`; MySQL logged controlled shutdown at `15:42:52Z` and `15:47:46Z`. A stale `zstd \| mysql` import pipeline remained on the bootstrap host after the second recovery. |
| Bounded intervention | The exact SSM command `44b165b1-a7a5-47cf-8228-44d86c2493c4` was cancelled so the existing fenced automatic-failure cleanup path could run. The workflow produced neither a data-bootstrap nor direct-readiness receipt. |
| Teardown journals | teardown-start VersionId `UwIcvPk4krPum4DAXMJ9Tqrx_SZmtcUD`; teardown-finalize VersionId `PDi.KQHVvmpoqObhaUoYxuTQ92mE6LBv` |
| Clean-state receipt | VersionId `lV4nv6MqGBs1UkmX2ES39.beY_5FYxGX`; `resourceCount=0`; global orphan count `0` |
| External safety gates | Orchestration lease released; OCI direct and public health both returned exact `healthy`; OCI and public DNS were not changed |
| Qualification outcome | No data-ready receipt, app/ALB qualification, promotion, or reusable RDS snapshot; this run cannot be a snapshot source |

The retry keeps the same immutable dataset, images, bundle, MySQL patch, Single-AZ topology, and
validation gates while fixing both runs at `db.t3.small`. Dump import and long MySQL validation
operations have bounded process deadlines so a disconnected client pipeline fails explicitly and
hands control back to fenced cleanup.

The acceptance receipt must record the exact MySQL/Flyway/row/fingerprint/outbox result,
Elasticsearch count/alias/mapping/ID/pair/content result, both Redis resets, 12 Kafka topics and
zero offsets, Debezium `no_data` plus one RUNNING task, immutable data-ready receipt, direct ALB
health/detail/search smoke, OCI observation, resource attributes, and phase timestamps.

## Snapshot promotion and first teardown

Status: `NOT_STARTED`.

The promoted snapshot must be encrypted and bind the release, manifest and dump digests, source Lab
run, exact source RDS resource ID, exact data-bootstrap and direct-readiness S3 VersionIds and hashes,
promotion schema 2, and the fixed `db.t3.small` source-instance class. The promoter must read both
immutable S3 versions back byte-for-byte before
contacting RDS. Snapshot replay supplies and verifies the two source identities rather than accepting
any older same-dataset snapshot. The first teardown is accepted only when OCI is authoritative,
Terraform state is empty, the explicit orphan scan is empty, the state-version clean receipt exists,
and the dataset/native snapshot plus promoted RDS snapshot remain. A Terraform process killed past
its termination grace may leave the native S3 lock; the current lease must preserve it and a later,
newer fenced lease may recover only the exact prior LockInfo through Terraform `force-unlock` before
continuing teardown. That recovery uses the lock object's S3 `VersionId` and
server `LastModified` plus a create-only S3 clock receipt, not either runner's
local clock, and requires 21,900 seconds of AWS-observed elapsed time.

## Snapshot-mode qualification and teardown

Status: `NOT_STARTED`.

Only run/fencing identity, database bootstrap mode, and the promoted snapshot identifier plus its
derived source-run/source-resource binding may differ. The source binding must point back to the
first readiness and promotion receipts; it is not a free experimental input.
The actual RDS class, engine, 100-GiB gp3 storage, encryption, Single-AZ/Multi-AZ setting, parameter
group, and all common receipt fields must equal the dump run. The same full data and direct ALB
gates apply. Normal teardown must again leave empty state, zero orphans, and OCI authority.

## Paired readiness and cost result

Status: `PENDING`.

| Observation | Dump mode | Snapshot mode | Comparison |
|---|---:|---:|---:|
| Network-clearance to data-ready | Pending | Pending | Pending |
| Resource start to direct readiness | Pending | Pending | Pending |
| RDS available to data-ready | Pending | Pending | Pending |
| Total Lab resource lifetime | Pending | Pending | Pending |
| Billable footprint | Pending | Pending | Must be identical outside bootstrap source |

This is an `n=1` operational comparison. It may prove that the environment can be rebuilt and show
the observed preparation-time difference; it cannot establish statistically stable performance or
cost savings.

## Final verdict

`NOT READY` — the first dump attempt is `FAILED_NOT_QUALIFIED`; dump retry, promotion, snapshot
replay, and both qualification teardowns remain pending. The failed attempt's cleanup is complete,
and no performance work has started.
