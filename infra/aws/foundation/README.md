# AWS performance-lab foundation

This root owns persistent resources only. Ephemeral VPC, compute, database,
load-generator, and service hosts belong to the separate `lab` state and must
never be added here.

## Live prerequisites

Do not run a live plan until all of these inputs are known and reviewed:

- a caller that can administer the named foundation resources in account
  `942632789808`;
- the existing application object bucket name (the foundation reads it as a
  data source and never manages its policy, lifecycle, or objects);
- globally available canonical dataset, evidence, and bundle bucket names;
- the complete Gabia DNS inventory, excluding NS/SOA, `api.airbob.cloud`
  origin records, and ACM validation records;
- the registrar DNSSEC/DS state;
- the three exact repository OIDC subjects returned by the reviewed GitHub
  configuration/token contract (foundation environment, lab environment, and
  main-branch image publication);
- whether the account-global GitHub OIDC provider already exists (if it does,
  import it into this state before the first apply rather than creating a
  duplicate);
- exact local IAM principal ARNs and an explicit MFA decision.

The approved workflow binding is `workflow-name`. Trust also requires each
exact subject, repository ID `1056501820`, owner ID `119295425`, and the main
branch ref. GitHub environments `aws-foundation` and `aws-performance-lab`
must be protected before their workflows are enabled.

## Initialize

Generate the ignored backend config from the repository-owned toolchain
contract, then initialize the committed S3 backend:

```bash
AWS_REGION=ap-northeast-2 \
  infra/aws/scripts/prepare-terraform-backend.sh foundation
terraform -chdir=infra/aws/foundation init \
  -backend-config=backend.generated.hcl \
  -input=false
```

Keep live values in an ignored `terraform.tfvars`; never commit credentials or
runtime secrets. Review a saved plan before either apply.

## DNS and ACM are a two-pass operation

The first apply uses `dns_delegation_confirmed = false` and an approved local
bootstrap principal. The GitHub foundation role intentionally cannot create or
rewrite IAM/OIDC identities, so a compromised workflow cannot grant itself
more access. Identity changes and replacement/creation of protected foundation
resources remain local-admin operations.

The first apply creates the new
Route 53 zone, reviewed static records, the exact `api.airbob.cloud`
certificate request, and its validation CNAME. It does not wait for issuance.

After that apply:

1. Apply the separate DNS state so the Route 53 zone already contains the OCI
   origin at weight 100.
2. Compare every Route 53 record against the Gabia inventory and query all four
   authoritative servers.
3. Verify an SNI-preserving HTTPS request still reaches the OCI origin.
4. Confirm the current registrar DS/DNSSEC state is compatible.
5. Replace the authoritative NS at Gabia with
   `public_zone_name_servers` from Terraform output.
6. Verify the new authoritative servers from outside AWS.
7. Set `dns_delegation_confirmed = true`, review a second plan, and apply.
8. Require `api_certificate_status = ISSUED` before staging an AWS ALB alias.

The foundation deliberately does not own the weighted OCI/AWS
`api.airbob.cloud` A records. The separate DNS state owns them so lab teardown
does not include either record or the hosted zone in its Terraform destroy
graph. Route 53 IAM can restrict writes to the API A name/type/actions but
cannot distinguish the OCI and AWS weighted set identifiers or values. The
general lab role therefore has read-only DNS access and no direct
`ChangeResourceRecordSets` permission. Before any live DNS mutation, a later
lease-held controller must receive a separate narrow role and verify both
weighted records, the fencing token, and OCI health before and after every
change, including rollback. Its tests must prove `down` leaves OCI at weight
100.

If the bounded 20-minute ACM validation wait fails, leave the delegated zone
and OCI record intact, correct the authoritative DNS or validation CNAME, and
review a fresh second-pass plan. Do not delete or recreate the protected zone.

The caller-chosen keys in `static_dns_records` are stable Terraform resource
identities. Renaming a key is a deliberate state/lifecycle operation even when
the DNS name and value are unchanged.

The DNS and lab consumers must reject any SSM contract whose `schemaVersion`
is not exactly supported; they must not infer missing fields or read the full
foundation state as a fallback.

Evidence publication uses a single tagged `PutObject` request with
`Retention=raw|summary`; the lab role does not authorize multipart upload
parts because those requests cannot carry the required tag condition. Reject
artifacts at or above the 5 GiB single-request limit before upload. An
unconditioned, evidence-bucket-only abort permission exists solely to clean up
an accidentally initiated multipart upload.

## Imported application repository

`airbob-repo` is declared with a Terraform import block. Supply
`application_ecr_scan_on_push` explicitly with the value observed immediately
before planning. The live repository was last observed as `IMMUTABLE` with
AES256 encryption, so the saved import plan must not change tag mutability,
encryption, or scanning accidentally. The current publisher already uses a
commit-derived ECR tag; U5 still must make retries idempotent and move it to the
full 40-character commit plus digest verification before that workflow is
treated as immutable-publication complete. The
foundation adds an image lifecycle policy; review its retained-image count
against evidence manifests before the first live apply.
