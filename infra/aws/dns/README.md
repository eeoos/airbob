# AWS performance-lab DNS state

This state owns only the two weighted `api.airbob.cloud` A origins. The
foundation state owns the hosted zone, reviewed static records, and ACM
validation. The ephemeral lab state owns neither public DNS record.

The initial safe state is:

- OCI: ordinary weighted A, weight 100, TTL 60, protected from destroy;
- AWS: absent until the reviewed ALB ARN is supplied, then weight 0.

The root reads the exact non-secret SSM DNS contract. It never reads the
foundation Terraform state. Prepare its committed S3 backend with:

```bash
AWS_REGION=ap-northeast-2 \
  infra/aws/scripts/prepare-terraform-backend.sh dns
terraform -chdir=infra/aws/dns init \
  -backend-config=backend.generated.hcl \
  -input=false
```

The general lab role can plan and refresh this state but intentionally cannot
mutate Route 53. Do not apply it until the foundation first pass exists, the
OCI origin IPv4 is confirmed, and the lease-held DNS controller role exists.
Before Gabia delegation, that approved controller (or the explicitly reviewed
local bootstrap principal) stages only the OCI record and verifies all four
Route 53 authoritative servers plus an SNI-preserving HTTPS request to the OCI
address. OCI terminates TLS at Nginx, which exposes only the plain-text
`/health` probe and intentionally returns 404 for `/actuator/**`. AWS ALB
probes continue to use the Spring `/actuator/health` JSON endpoint because the
ALB targets the application container directly.

Supplying the same-account Seoul ALB ARN makes Terraform resolve and verify its
application type, internet-facing scheme, and required lab tags before an
alias is created at weight 0. Raw DNS name and hosted-zone ID inputs are not
accepted. This Terraform root does not provide the later traffic-switch
interface. U4/U5 orchestration must hold the DynamoDB lease, verify OCI and AWS origins, change weights, verify public
DNS, and roll back to OCI 100/AWS 0 on failure. Raw `terraform apply` is not an
approved cutover operation.

The OCI-only state was applied on 2026-08-17 in account `942632789808` with
`140.245.76.140`, weight 100, and TTL 60. Gabia now delegates `airbob.cloud` to
the four Route 53 name servers. Public resolvers return the Route 53 OCI record,
and the direct SNI-preserving `/health` probe passes. The apex and `www` records
still resolve to their reviewed Vercel targets. The API ACM certificate is
issued. The AWS alias and any traffic switch remain pending, so public API
traffic continues to use OCI.
