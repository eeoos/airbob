# AWS performance-lab ephemeral state

This root is the destruction boundary for resources that exist only during a
performance-lab run. In Phase 1 it deliberately creates **zero AWS resources**.
It only validates the exact, non-secret SSM contract published by foundation.

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

Later phases add the VPC, ALB, ASG, RDS, service hosts, and load generator to
this state. Every such resource must carry the ephemeral tags and remain
destroyable by a lab-only `terraform destroy`; persistent resources must
continue to enter only through the validated SSM contract.

No live plan or apply has been executed, and no billable lab resource is
declared here yet.
