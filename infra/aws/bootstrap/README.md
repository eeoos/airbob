# Terraform state bootstrap

This root module creates the one persistent S3 bucket that stores the
`bootstrap`, `foundation`, `dns`, and `lab` Terraform states under four
different keys. It deliberately starts with Terraform's default local backend;
there is no committed backend block. After the bucket exists,
`bootstrap-state.sh migrate` creates two clearly named, ignored generated files
and migrates the local bootstrap state to S3.

The bucket uses SSE-S3 (`AES256`) rather than KMS. The state is non-production
synthetic-lab configuration, and SSE-S3 avoids a permanent KMS key and its
additional permissions/cost while still enforcing encryption at rest. Bucket
versioning, bucket-owner-enforced object ownership, all four public-access
blocks, and a deny-insecure-transport policy are mandatory. Terraform protects
the bucket with `prevent_destroy`.

Use caller-provided AWS credentials from the standard AWS credential chain.
Do not put access keys in Terraform variables or generated backend files.

```bash
export AWS_PROFILE=your-sso-profile
export AWS_REGION=ap-northeast-2
export STATE_BUCKET_NAME=airbob-performance-lab-tfstate-942632789808

infra/aws/scripts/bootstrap-state.sh create
infra/aws/scripts/bootstrap-state.sh status
```

`create` performs the one-time local `terraform apply`, verifies the bucket
contract, and then runs the same migration path as `migrate`. `migrate` uses
the S3 backend's native `use_lockfile = true`, verifies the remote state object,
and runs a no-refresh Terraform plan to prove that the backend can acquire and
release its native lock file. DynamoDB is not used for Terraform backend
locking. `status` is read-only and does not run the lock probe.

The script generates and names these ignored files before migration:

- `infra/aws/bootstrap/zz_backend.generated.tf`
- `infra/aws/bootstrap/backend.generated.hcl`

The generated HCL contains only bucket, state key, region, encryption, and
native-lock settings. The script refuses a different account, region, tool
version, bucket name, or state key. A real local-state-to-S3 migration still
requires the account owner to run `create` or `migrate`; repository tests do
not create AWS resources or claim that live migration has been exercised.
