# Pause, resume and local access

Use the common `airbob-lab.py` entrypoint for saved lab configuration and start/down.
The underlying power commands also work directly from the reviewed checkout:

```bash
python3 infra/aws/scripts/growth_b_power.py status --profile admin-eeoos
python3 infra/aws/scripts/growth_b_power.py pause --profile admin-eeoos
python3 infra/aws/scripts/growth_b_power.py resume --profile admin-eeoos
python3 infra/aws/scripts/growth_b_power.py access --profile admin-eeoos
```

The CLI discovers the current run from the existing Lab backend. It retrieves
the original operator and the exact current service manifest, reconstructs the
current ready configuration, and obtains/heartbeats/releases the existing Lab
orchestration lease. No instance IDs, receipt hashes or lease tokens are typed
by the user. `status` is read-only and distinguishes an absent state from a
partial or unknown run. Power operations require the ordinary B topology:
one small 100 GiB RDS, NAT, five dependency hosts and one app. They do not enable
the optional eighth load generator or create another application.

All power changes happen through Terraform apply in the original backend.
The app ASG remains 1/1/1 with the same launch template. Pause first suspends its
nine processes, stops the exact app and Connect instances, then stops the
remaining EC2 instances and RDS. Resume starts the same NAT/RDS/core dependency
instances, verifies dependency health, starts Connect, verifies its task state,
then starts the same app. The original ASG suspension set is restored only
after actual instance-local readiness and ALB health succeed. Instance IDs,
volumes, source inputs, runtime revision, the creation fence and expiry remain
bound throughout. Normal startup seeding remains enabled.

`access` changes only the existing ALB HTTPS ingress rule to the caller's current
public IPv4 `/32`. It leaves DNS on OCI and prints a working `curl --connect-to`
command using `api.airbob.cloud` for TLS SNI. `--cidr` can select an explicit
public `/32`; private addresses and broader networks are rejected. A new GitHub
runner's ingress does not grant access to the user's Mac automatically.

The selected AWS provider 6.55.0 exposes a native RDS power resource, but its
[Create/Update implementation](https://github.com/hashicorp/terraform-provider-aws/blob/v6.55.0/internal/service/rds/instance_state.go#L128)
waits for `available` before calling StartDBInstance. Its
[waiter](https://github.com/hashicorp/terraform-provider-aws/blob/v6.55.0/internal/service/rds/instance.go#L2719)
rejects `stopped`. This source-level defect has not been deliberately exercised
against a paid database. EC2 state and ASG suspension use native resources;
RDS uses exactly one `terraform_data.rds_power` create action calling
`growth_b_rds_power.py`. There is no competing native RDS state manager. The
helper submits one Start/Stop request only from the corresponding stable state,
waits through transitions, and performs no request when already at the target.

Requests and closed evidence are kept in `~/.local/state/airbob-lab/power` by
default (`--evidence-directory` overrides it). Directories are 0700 and files
0600. Raw state, plan/UI streams, cloud responses and logs stay in bounded RAM.
The complete plan UI permits only power state entries and the existing ASG's
suspension change; access permits only the HTTPS rule update. The current lease
is supplied separately from the retained resource creation fence. Source and
input bytes are rechecked before apply; the backend S3 VersionId, ETag and size
must stay unchanged across state pulls and the plan. Each actual pull response
has its own recorded SHA, since Terraform may reorder check results while
serializing the same remote state. This is a same-input replan, **not
a saved binary plan**. Terraform's backend lock and the existing orchestration
lease remain active.

On failure, resources are retained and ASG protection stays in place. Repeat
the same command to continue its recorded operation. An uncertain RDS submission
is only observed; its intent prevents resubmission, including a tainted
Terraform control entry retry. `resume` never reruns SQL, native ES restore or
container deployment. The resource expiry is not extended. After restart,
application/CDC/search business verification remains a separate check; a power
receipt does not claim that it performed an API mutation/reset cycle.

The Root operator performs the actual paid lifecycle exercises after normal
service verification. Offline tests do not establish that those exercises have
already happened. Future `start` commands use the normal explicitly selected
TTL; the retained snapshot's historical source expiry is only provenance.
