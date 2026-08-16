# AWS performance-lab ephemeral state

This root is the destruction boundary for resources that exist only during a
performance-lab run. Phase 2 declares the two-AZ VPC, test-only NAT instance,
S3 gateway endpoint, the protected private-zone association and records,
disposable egress probe, and the five dependency-service EC2 hosts. It still
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

Phase 2 is deliberately three applies, not one:

1. `deployment_phase=network` creates networking, NAT, and one private
   `t3.nano` probe. It creates no dependency-service host.
2. Run `infra/aws/scripts/verify-network-egress.sh egress ...`, then apply
   `deployment_phase=probe-cleared`. The exact S3/NAT/ECR/SSM/Secrets Manager
   receipt is required and the probe is removed; service count remains zero.
3. Run `verify-network-egress.sh cleared ...`, then apply
   `deployment_phase=services` with the bundle commit/SHA and nine ECR digest
   references. A second receipt must attest that the probe is terminated before
   the five service hosts can enter the graph.

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

Later phases add RDS, ALB, App ASG, and the load generator to this same state.
Every resource must retain the ephemeral identity/expiry tags and remain
destroyable by a lab-only `terraform destroy`; persistent resources continue
to enter only through the validated SSM contract.

No live plan or apply has been executed. The Terraform configuration and mock
tests are implemented, but applying `network` or `services` will create
billable EC2/EBS/EIP resources. The orchestration lease/wrapper is a later
phase, so these low-level phase transitions must not be treated as the finished
one-command operator workflow yet.

Until that wrapper exists, a manual emergency teardown must run `terraform
destroy` with `deployment_phase=network` plus the same `run_id`, `expires_at`,
and `ami_id`. Destroy mode then does not read network receipts or bundle
objects, so missing evidence cannot prevent cost cleanup. Do not run a normal
`apply` with those emergency values as a substitute for the documented phase
sequence.
