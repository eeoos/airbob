# AWS performance-lab foundation

This root owns persistent resources only. Ephemeral VPC, compute, database,
load-generator, and service hosts belong to the separate `lab` state and must
never be added here.

The foundation also owns `lab.airbob.internal` and a subnet-free `/28` anchor
VPC because Route 53 private hosted zones must remain associated with at least
one VPC. The anchor VPC has no subnet, route to the internet, or hourly VPC
charge. The private hosted zone adds the standard hosted-zone monthly charge.
It is protected with `prevent_destroy`; the lab role can associate only its
regional VPC and change only the six approved service A records.

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
  configuration/token contract (foundation, lab, and image-publisher
  environments);
- whether the account-global GitHub OIDC provider already exists (if it does,
  import it into this state before the first apply rather than creating a
  duplicate);
- separate exact local IAM principal ARNs for foundation administration,
  ephemeral lab operation, and local dataset publication, plus an explicit
  MFA decision. The dataset publisher principal must remain disjoint from the
  lab principal; do not grant the developer principal access to the foundation
  role.
- when enabling the expiry observer, one reviewed email address whose SNS
  subscription the owner can confirm and acknowledge explicitly.

AWS STS accepts the GitHub OIDC `aud` and `sub` claims for this trust; it does
not evaluate GitHub's other token claims as independent IAM condition keys.
Each role therefore requires `aud=sts.amazonaws.com` plus one exact reviewed
legacy or immutable environment subject. GitHub environments `aws-foundation`,
`aws-performance-lab`, and `aws-image-publisher` must restrict deployment to
`main` and be protected before their workflows are enabled. The immutable
subjects carry owner and repository IDs inside `sub`; do not reintroduce
unsupported `repository_id`, `ref`, or `workflow` IAM condition keys.

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

## Local dataset publisher role

The foundation code defines the local-only
`airbob-dataset-publisher` role and an exact dataset-bucket policy. This change
has not been applied to account `942632789808`, and no dataset wrapper or
Elasticsearch snapshot has been published. Before using the producer, set the
reviewed local principal explicitly, create and inspect a saved foundation
plan, and apply it with the approved foundation administrator. The dataset
publisher contract always requires the one reviewed `admin-eeoos` principal
and MFA; `local_principal_requires_mfa` controls only the other local roles:

```hcl
dataset_publisher_local_principal_arns = [
  "arn:aws:iam::942632789808:user/admin-eeoos",
]
dataset_snapshot_writer_release = null
local_principal_requires_mfa = true
```

The role has no GitHub OIDC trust and is not assumed by the lab. It can read
and conditionally create immutable objects under `datasets/*`. With the default
null writer value, its Elasticsearch mutation and snapshot-lease permissions
point only at `__disabled__`. To produce one snapshot, set one exact release,
review and apply the foundation plan, and assume the role. That temporary
policy authorizes only the matching
`elasticsearch/releases/<release>/*` prefix and DynamoDB
`airbob-dataset-snapshot/<release>` row. The lease uses conditional
`UpdateItem` with a monotonic fencing token; it never deletes the row.
Revoking the writer keeps only `GetObject` and `GetObjectVersion` access to
`elasticsearch/seals/*`, so the wrapper publisher can verify any immutable
seal without regaining snapshot or seal mutation rights.

The bucket policy denies dataset-wrapper overwrites and deletion for every
principal. Elasticsearch retains narrowly scoped delete and multipart actions
only for the selected native repository because Elasticsearch manages its own
repository blobs. After the producer emits and verifies its receipt, set the
writer variable back to null, review and apply the revoke plan, refresh the
role session, and only then run the wrapper publisher. The publisher inspects
the role and refuses all S3 work if a real Elasticsearch writer grant remains.
At the end of successful snapshot production, the producer conditionally
creates the immutable `elasticsearch/seals/<release>.json` object. The bucket
policy denies overwriting or deleting any seal. Foundation checks that exact
key during plan and again during apply, so the release cannot regain its
writer grant even from a saved plan made before the seal appeared. The
repository is therefore writable by one locally leased producer during
snapshot creation and read-only for subsequent publication and AWS restore.
Before writing any `datasets/<release>/*` object, the publisher reads the
seal's exact S3 object version and verifies its encryption, content type,
release/snapshot metadata, and byte-level hashes of both the staged snapshot
reference and the full snapshot receipt.

The dataset bucket lifecycle deliberately has no current-object expiration,
noncurrent-version expiration, or expired-delete-marker cleanup. A snapshot
seal binds every object version and delete marker under its exact
`elasticsearch/releases/<release>/` prefix. S3 lifecycle cannot dynamically
exclude only prefixes that already have a seal, so a broad dataset expiration
rule could make that immutable inventory unverifiable. The only dataset
lifecycle action is aborting incomplete multipart uploads after seven days; an
incomplete upload is not a completed object version and is not part of a seal.
The separate bundle bucket retains its bounded 30-day noncurrent version
cleanup, and evidence expiration remains restricted to explicitly tagged
objects.

After apply, authenticate the approved IAM user and configure the local AWS CLI
publisher profile documented in `infra/aws/datasets/README.md`. An `aws login`
source produces a role-chained session, so that profile requests 3,600 seconds
and starts each production attempt with a unique role-session name. The
publisher and snapshot producer both reject direct IAM-user credentials and
require the resulting `assumed-role/airbob-dataset-publisher/...` session.
The publisher profile must remain unresolved until the snapshot producer has
completed its AWS-free live lineage verification. Commands, credential
headroom, and complete retry semantics are documented in the dataset runbook.

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
default `airbob-lab-operator` is therefore direct-only: it can read the public
zone, mutate the six private Lab service records, and write only the Lab state.
It cannot write the DNS state or assume the public DNS controller. The separate
`airbob-lab-cutover-operator` uses the distinct protected
`aws-performance-lab-cutover` OIDC subject (while retaining the same reviewed
MFA-local principals) and the same five Lab policies, plus one exact inline extension for the DNS state key,
its lock file, and session-tagged assumption of `airbob-dns-controller`.
Both Lab roles keep `MaxSessionDuration = 18000`; their shared base policy may
read IAM definitions for the global Lab orphan scan, while stale-lock recovery
queries only those two exact role names. Recovery binds the lock and its
create-only clock receipt to S3-server timestamps and waits out that maximum
plus the safety margin before invoking Terraform's own `force-unlock`.
Before any live DNS mutation, that lease-held controller must verify both
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

Route 53 does not support tag-based IAM conditions. For that reason the lab
role never creates, deletes, or tags a hosted zone. Its private-DNS permission
uses the exact foundation zone ARN, the `route53:VPCs` regional condition, and
the six normalized record names. This prevents Phase 2 teardown from reaching
the public `airbob.cloud` zone.

Evidence publication uses a single tagged `PutObject` request with
`Retention=raw|summary`; the lab role does not authorize multipart upload
parts because those requests cannot carry the required tag condition. Reject
artifacts at or above the 5 GiB single-request limit before upload. An
unconditioned, evidence-bucket-only abort permission exists solely to clean up
an accidentally initiated multipart upload.

Both Lab operator roles receive the same five customer-managed policies. Each
document stays within the 6,144-character managed-policy quota and each role
remains below the default ten-policy attachment quota. Only the cutover role
receives the small inline public-DNS extension. Do not collapse the five shared
policies into inline policies: their aggregate size exceeds the 10,240-character
per-role inline-policy quota.

## Expiry observer is alert-only

The foundation declares a small EventBridge-scheduled Lambda that discovers
resources by the stable `Project`, `Environment`, and `Stack=lab` identity tags
every fifteen minutes, then validates the exact identity/lifecycle tags below in code:

```text
Project=airbob
Environment=performance-lab
Stack=lab
ManagedBy=terraform
Persistence=ephemeral
FencingToken=<positive decimal lease token>
```

`ExpiresAt` must be a canonical positive decimal Unix timestamp in seconds;
`FencingToken` must be a canonical positive decimal issued by the lease.
The observer publishes only aggregate counts and a heartbeat to
`Airbob/PerformanceLab`; it does not log resource ARNs. Its execution role can
read Resource Groups Tagging API results and write that metric namespace and
its own logs. It has no EC2/RDS/ELB/Auto Scaling, Route 53, CodeBuild,
DynamoDB, S3, or cleanup mutation permission. `CLEANUP_ENABLED=false` is both
a Terraform contract and a runtime fail-closed check.

The observer uses the account's unreserved Lambda concurrency. New accounts
can have only ten concurrent executions, and AWS refuses any reservation that
would reduce unreserved concurrency below ten. The schedule is disabled by
default and can invoke only this read-only function every fifteen minutes;
activation monitoring must include Lambda throttles and errors.

The schedule and alarm actions default to disabled. Alarm resources keep stable
Terraform addresses while disabled so `prevent_destroy` cannot block a later
enable/disable transition. Activation is intentionally two pass:

1. Set `expiry_alert_email` while keeping `expiry_observer_enabled=false`,
   review/apply with an approved local foundation principal, and confirm the
   SNS subscription email. Verify the subscription in AWS and send a test
   notification before treating delivery as proven.
2. Set `expiry_alert_subscription_confirmed=true` and
   `expiry_observer_enabled=true`, review a second plan, and apply. Terraform
   also verifies that AWS reports the subscription as no longer pending before
   enabling the schedule or alarm actions. Both use the same
   `delivery_ready` condition, so an unconfirmed first-pass apply leaves all of
   them disabled even if activation was requested too early.

To rotate the email, first disable the observer and set the confirmation flag
to false, then apply the new email, confirm it, and re-enable in a separate
reviewed apply. The email subscription is the only replaceable foundation
resource; its KMS key, topic, alarms, Lambda, and schedule remain protected by
`prevent_destroy`.

This creates alarms for an expired/invalid resource, a missing heartbeat, and
Lambda errors. The SNS topic uses a customer-managed rotating KMS key whose
policy grants only the CloudWatch alarm publisher the required encrypt/decrypt
operations for this account and alarm-name prefix. It does **not** destroy
resources, change DNS, acquire the
orchestration lease, or prove that every AWS resource type participates in the
Resource Groups Tagging API. Phase 5 now provides a separate lease/fencing
operator and bounded cleanup path, but the observer remains alert-only and the
live cleanup path is unverified. The GitHub foundation role has
read-only refresh access to these observer resources. At the current U4
boundary, creation, activation, mutation, or replacement remains a local-admin
operation. U5 must add a protected GitHub foundation workflow that calls the
same repository-owned configure/status primitives as local operation; it may
receive only the exact rule/alarm/subscription mutations needed for activation,
not IAM, Lambda-code, KMS, topic, cleanup, or DNS mutation.
Resources missing any identity tag are outside this observer's inventory;
the later controller/orphan scan must compare against authoritative lab state
instead of treating this tag query as complete.
Mocked plans cover both enabled and disabled alarm configurations. The real
enable-to-disable state transition and CloudWatch-to-SNS-to-email delivery
remain live acceptance checks; this repository has not applied either path.

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
