# Services after a Mac SQL import

This path consumes an actual `SQL_IMPORT_COMPLETED` receipt, its successful
lightweight postcheck, and `SAME_RDS_DOWNSIZED_AND_TERRAFORM_ALIGNED` for the same
RDS. It preserves the original operator record, dataset, application image,
bundle commit, resource fence and expiry. The existing `services` command creates
the normal five dependency hosts and then the application. It does not create
an importer host, run SQL import/ETL, or claim a full database fingerprint.

After the real import and downsize finish, select their unchanged JSON bytes
through exact S3 `key`, `versionId`, `sha256`, and `bytes` references. These are
the only different fields in the existing B service manifest's `preparation`:

```json
{
  "sourceMode": "mac-sql-postcheck",
  "receipt": "EXACT_REF: data-bootstrap/<run>/<dataset>-mac-rds-downsize.json",
  "sqlImportReceipt": "EXACT_REF: data-bootstrap/<run>/<owned-sql-completed>.json",
  "postcheckReceipt": "EXACT_REF: data-bootstrap/<run>/<owned-postcheck>.json",
  "rdsCaBundle": "EXISTING_EXACT_REF: datasets/<dataset>-aws-preparation/files/<sha>-rds-ca.pem"
}
```

The strings above describe references, not executable configuration. Each must
be replaced with the four-field object using actual successful artifact bytes.
Keep `search.restoreReceipt` null: this bootstrap produces its own scoped native
search result. Reuse the sealed B search transport and declared document
fingerprint metadata, the actual app runtime binding, and the current B Connect
image contract. Set `toolSources` from `growth_b_service.tools_for(manifest)`
(eleven files for this path) and package those exact local source files into the
existing `consumer-tools.tar.gz`. No old Linux ETL/JDK toolchain is downloaded.
Run the existing `growth_b_service.py validate` CLI before publishing the new
service manifest under `datasets/<dataset>-aws-service/<release>/aws-service.json`.
The original SQL/downsize evidence remains under the run's existing evidence
prefix; no new IAM policy is needed.

Use the existing workflow action `services`, exact retained `run_id`, original
dataset/bundle/image inputs and approved execution deadline. Select the new
service manifest's exact VersionId and SHA. The closed `b_operation` stages are:

1. `{"serviceRelease":"<release>","serviceManifestSha256":"<sha>","stage":"dependencies"}`
2. The same selection with `"stage":"bootstrap"`.
3. The same selection with `"stage":"application"`, plus the actual bootstrap
   `readinessVersionId` and `readinessSha256`.

For the existing CLI these map to `B_SERVICE_RELEASE`, `B_SERVICE_SHA256`,
`B_SERVICE_STAGE`, and `B_READINESS_VERSION_ID`/`B_READINESS_SHA256` followed by
`infra/aws/scripts/aws-lab.sh services`. Do not carry `B_IMPORT_FROM_MAC=true`
or the initial large-class selector into a service command. The reviewed
manifest's downsize reference selects the current small class automatically.
Do not invoke the old `native-restore` stage: it requires the separate Linux
full-preparation proof.

On the normal Connect host, bootstrap verifies exact source bytes, current
small RDS identity/tags, stopped application ASG, TLS, V28, an empty outbox and
the fixed published representative. It restores the pinned native snapshot
into a fresh index using `restore_index`, checks successful recovery, the
declared published document count and representative search, then activates
one write alias. It subsequently uses the existing separate Redis identities,
twelve business topic gates, fresh Connect identity and heartbeat checks.

The readiness receipt reports `fullDatasetValidated=false`,
`allDocumentSourceFieldsEqual=false` and `sqlReplayed=false`. It admits normal
application startup but is not R4/R7, snapshot provenance, or a full-row proof.
Native checks run over private DNS from the Connect host; they do not prove a
public HTTP route. The application stage retains the normal readiness/ALB
checks with a 30-minute controller wait for this Mac path. Original lease and
resource expiry still govern the operation. Review the caller's current ALB
ingress `/32` when planning execution; the provisioning runner's earlier `/32`
is not evidence that the Mac or a later runner can reach the endpoint.

An uncertain/failed restore retains its intent, index and any current connector
state. Repeating bootstrap is rejected rather than replaying SQL, restoring
again, deleting foreign data, or flushing Redis. Inspect the closed failure
receipt and reconcile the exact owned attempt before selecting further work.
