# Global B AWS execution

This path uses MySQL 8.4.11 / Flyway V28 and the sealed B application and native Elasticsearch bytes. It is separate from the existing MySQL 8.0 / V27 dataset path. Offline tests validate contracts; they do not establish that a cloud operation completed.

## Review and execution identity

Use the protected main branch of aws-performance-lab.yml after the AWS-only code review. Every manual mutation requires expected_execution_commit equal to both the dispatch SHA and checked-out HEAD. An arbitrary checkout ref is not accepted. Prepare, services, source R4 CDC and snapshot host operations use the existing six-hour OIDC session, 18,000-second command deadline, 20,700-second lease deadline and cleanup margins. Existing resource TTLs are preserved.

Before the first paid B dispatch, approve one absolute UTC deadline and pass its integer epoch as approved_execution_deadline_epoch (APPROVED_EXECUTION_DEADLINE_EPOCH for the CLI). Reuse that exact value for the small run, final run, snapshot target and every retained service/snapshot operation. New B runs cap expiresAt at min(now + ttl_hours, approved deadline), require at least six hours remaining before lease acquisition, and store the approved deadline in the immutable operator manifest. Continuations require the same deadline and original resource expiry; they cannot extend either value. Legacy operations and down/expired cleanup keep their existing behavior. For a total 24-hour approval, the common deadline is fixed once before the first paid dispatch; do not calculate a new 24-hour deadline for each run. Resource deletion can still finish after expiry if the scheduled workflow queues or teardown fails; the deadline is not a billing cutoff.

An approval network plan uses review-only lease/TTL values. Actual execution acquires a fresh lease and resource identity. A full B service plan is a later gate: it requires the actual RDS resource ID/MySQL UUID, successful preparation receipts and published immutable image digests. A network plan does not establish service readiness.

The reviewed image inputs contain the baseline application/config bundle and nine infrastructure images. Normal services additionally require the separate B Debezium image. Its exact five manifest fields are image, buildCommit, pluginVersion=3.0.8.Final, pluginJarSha256=6de35d7c20ca1d00e6d9d8ae0e033203e487bf29e335a096dcaf38d4e0316f59, and connectVersion=3.7.0. The host separately verifies container digest, image revision, plugin JAR bytes, connector-plugins version and Kafka Connect root version. Existing release.json, ordinary commit tags and V27 receipts retain their meaning.

## SQL preparation and normal services

1. Use action prepare, database_bootstrap=dump, mode=performance, policy=isolated-read, dns_mode=direct-only, load_generator_enabled=false. Bind dataset_release, dataset_manifest_version_id, b_preparation_sha256, bundle_commit, bundle_manifest_version_id and app_image_digest. This creates the preparation host and RDS without an application ASG/ALB. The six preparation helpers and content-addressed wrapper are verified on the host.
2. Preserve the actual small rehearsal receipt, then complete down and verify the empty Lab state. Publish the final preparation wrapper in the sibling preparation prefix with that successful same-tool small receipt, then perform final prepare under a new run/RDS identity with the same approved execution deadline. The controller cannot switch the small run's dataset on its existing RDS. Preparation-only retries retain the selected final dataset and RDS identity. The sealed SQL inventory remains unchanged.
3. Publish a service manifest with the actual RDS resource ID/MySQL UUID, preparation receipt/config/fingerprint hashes, B Debezium provenance and native search transport. Its appRuntimeBinding is an exact key/VersionId/SHA/bytes reference to files/app-runtime-binding.json in the selected service sibling prefix. The nine service helpers include growth_b_app_runtime.py; the six preparation helpers remain unchanged. Use action services, the retained run_id, original dataset/application tuple, selected service manifest VersionId and b_operation:

       {"serviceRelease":"<release>","serviceManifestSha256":"<sha>","stage":"dependencies"}

   Run the `native-restore` continuation below on those existing dependencies. After actual native S3 restore, bind its receipt in a new immutable service release and select stage=bootstrap. Then select stage=application, adding readinessVersionId and readinessSha256 from bootstrap.
4. Application admission enables the normal aws profile, inventory readiness, schedulers and Kafka consumers, with separate general/cache Redis. It verifies MySQL TLS identity, exact application image and actual container JAR, and native ES fingerprint. The sealed application.appJarSha256 retains its preparation/search meaning. The reviewed main479 ECR JAR has the same 1277 source file entries including loader/META-INF and adds only the pinned API reference page plus two empty directory entries. The runtime validator verifies both complete inventories and its own source hash; it does not claim whole-JAR equality. Readiness and the runtime revision bind this proof, and startup independently verifies the actual image JAR SHA before launching. External payment, Slack, Google and image-write integrations remain disabled for the lab.
5. Run the separate `cdc` source R4 operation below. It verifies account/API/search/image/reservable-date reads, then actual domain API → outbox → Kafka → consumer → ES propagation and full reset. Connector RUNNING and heartbeat alone are dependency checks.

Initial B preparation fixes the accommodation detail cache to disabled, and normal service stages retain that original setting. General Redis and cache Redis remain separate. The source R4 runtime observation checks the actual application environment and JAR; a declared receipt flag cannot override a running cache-enabled app.

## Native search on the original prepared RDS

`services` stage `native-restore` closes the gap between dependency creation and
service bootstrap. It retains the original preparation run, RDS resource ID and
server UUID, resource fence, application tuple and common execution deadline.
The current adapter is admitted only for the sealed final dataset
`global-growth-b-b0fbda4d12511eeb` and its approved deadline `1789366861`.
Application capacity must remain 0/0/0, Connect must have no business connector,
and the selected dependency manifest must have `search.restoreReceipt: null`.

Create the reviewed source package with:

```bash
python3 -B infra/aws/scripts/growth_b_search_controller.py package-sources \
  --directory /absolute/new/native-search-source
```

The workflow uses `action=services`, `policy=isolated-read`, `database_bootstrap=dump`,
the original dataset/application tuple and retained `run_id`. Its `b_operation`
has exactly these fields:

```json
{
  "schemaVersion": 1,
  "kind": "global-b-aws-native-search-operation",
  "stage": "native-restore",
  "operationId": "<new-operation-id>",
  "runId": "<original-preparation-run>",
  "datasetId": "global-growth-b-b0fbda4d12511eeb",
  "serviceRelease": "<selected-dependency-release>",
  "executionCommit": "<reviewed-main-commit>",
  "sourceArchiveSha256": "<actual-source-archive-sha256>",
  "manifest": {
    "key": "<exact-selected-service-manifest-key>",
    "versionId": "<actual-version-id>",
    "sha256": "<actual-manifest-sha256>",
    "bytes": 1
  }
}
```

Replace the example byte count with the actual manifest size. The source archive
contains the 13 host helpers and the controller. The six sealed preparation
helpers, native search engine, service consumer and runtime binding retain their
original bytes. The stage executes through the existing operator OIDC role and
SSM permissions; it never builds Terraform variables or applies infrastructure.

The original preparation host exports its exact import receipt, raw/prepared
fingerprints and runtime proof under its original control lock. This establishes
an inherited pre-preparation baseline with the original validation timestamp.
Its source package explicitly says that current complete rows and inventory
ownership were not rescanned in that phase. The ES host then runs the unchanged
native engine against the same RDS through TLS, including current full source
and ownership validation, exact S3 versions/bytes, every document source field,
post-restore source drift verification and final alias activation.

The ES role has no DynamoDB or ASG read permission. The controller observes the
live lease, original resources, zero application capacity and empty Connect
registry and delivers an immutable chained ACK at most 90 seconds in duration.
The host checks this ACK while its own worker runs. Expiry or identity drift
closes admission and settles only that invocation's work. No SSM cancellation,
new SQL import, account preparation, Redis flush, writer start or IAM change is
part of this stage.

The host work deadline is ten minutes before the existing controller deadline,
leaving time to settle processes and retrieve and publish exact receipts. The
SSM wrapper first requests orderly termination of its own supervisor and waits
for cleanup; uncertain forced termination cannot produce a completion receipt.
The worker independently checks its original supervisor identity and ACK so
losing that supervisor does not leave an unguarded native restore running.

Every SSM request and S3 publication has a durable intent before submission.
Unknown submissions retain the exact command/recovery evidence and cannot be
replayed automatically. Inspect the original namespace and terminal evidence
before choosing another operation; a new operation ID alone does not authorize
reusing or replacing an index. Failure preserves resources and their original
expiry. The workflow uploads the controller evidence even on failure.

Success requires the host receipt and exact native receipt to pass the controller
completion gate, including their actual UUID, inherited baseline, full content
fingerprint and delivered final ACK. Exact versions of the source package, host
receipt and native receipt are published under
`data-bootstrap/<run>/<dataset>-native-search/<operation>/`. Use the verified
native receipt reference in a new immutable service manifest before bootstrap.
This completion does not admit application traffic, CDC verification or snapshot
creation on its own.

## Source R4 verification and reset

Package the reviewed source inventory locally with `python3 infra/aws/scripts/growth_b_cdc_supervisor.py package-sources --output <new directory>`. This includes the exact preserved CDC/read helpers under `infra/aws/vendor/`, the AWS adapters and service producer. It does not require the root OCI scripts to be present. Use the actual archive SHA and exact current service manifest/readiness versions in the closed `b_operation`:

    {"schemaVersion":1,"kind":"global-b-aws-source-r4-operation","stage":"all","operationId":"<unique operation>","runId":"<retained source run>","datasetId":"<final dataset>","serviceRelease":"<current release>","executionCommit":"<reviewed main SHA>","sourceArchiveSha256":"<actual archive SHA>","manifest":{"key":"datasets/<dataset>-aws-service/<release>/aws-service.json","versionId":"<version>","sha256":"<sha>","bytes":1000},"readiness":{"key":"data-bootstrap/<run>/<dataset>-service-<release>.json","versionId":"<version>","sha256":"<sha>","bytes":1000}}

Replace every placeholder and both example byte counts with actual values. `action=cdc` retains the existing run, original resource fence and common approved deadline. The operator takes the existing `up` lease and obtains the current phase 2/3/4 and service outputs without applying Terraform. Offline admission checks the selected archive before OIDC; live admission checks the actual RDS, ASG, instances, containers and immutable service references.

The existing `AWS-RunShellScript` document transfers bounded, hash-checked source chunks to the retained Connect host. Representative credentials, cookies and raw SQL/Kafka records stay private on that host. Three representative logins, the fixed 300-GET warmup, two decoded image samples and current local three-month booking windows precede the two name PATCHes. The live-read budget and final full-fingerprint budget are separate from the original immutable 900-second business window. Both configurations bind the same sources, targets and runtime; a resume cannot reset the business window.

The controller stops the original app through its exact ASG, stops Connect, applies the journaled row/counter reset with binlog enabled, and starts one replacement app using the same template. It verifies fresh post-reset CDC health before closing both writers. Stable zero capacity includes pending scaling activity and instance checks. The service producer then holds MySQL read locks, verifies current inventory, unchanged domain/DDL/account rows and owned inventory, and passes the unchanged snapshot source-evidence validator. This one-for-one restart is separate from the R7 scale-out observation.

Complete success requires `public/supervisor.json` state `SOURCE_R4_VERIFIED_AND_RESET` and the producer's unchanged `public/service-verified-and-reset.json` state `SERVICE_VERIFIED_AND_RESET`. A source-close receipt by itself does not satisfy this gate. Success leaves the source writers stopped and does not create a snapshot.

On failure the operator retains the existing resources and exports closed evidence under `CDC_EVIDENCE_DIR/<run>-<operation>-<lease>/supervisor/public/`. It does not create new teardown tfvars or cancel unknown SSM commands. The workflow preserves only this public subdirectory. When credentials still allow it, the operator conditionally publishes `recovery.json` at `data-bootstrap/<run>/<dataset>-r4/<operation>/recovery-<sha>.json` and writes its exact key/VersionId/SHA/bytes to `recovery-reference.json`.

An explicit resume keeps the original operation fields, selects `stage=resume`, and adds `resume` with `originalOperationSha256`, `supervisorJournalHeadSha256`, `controllerJournalHeadSha256`, `liveConfigurationSha256`, `cdcConfigurationSha256`, and `recovery` containing that exact immutable object reference. A configuration or controller head may be null only when the preserved recovery proves it was never created. The supervisor retrieves that exact S3 version and reconstructs the owned journal; it never selects a latest artifact. An ambiguous prior submission must be resolved from its recorded identity before any next command.

B uses performance mode with ASG min/desired/max=1 and no capacity surge during refresh. A separate asg-probe action implements the proposed one-hour observation; the baseline Terraform capacity remains one. It requires the same retained run, approved execution deadline, exact current service manifest/readiness and this closed b_operation:

    {"operationId":"<unique operation>","serviceRelease":"<current release>","serviceManifestSha256":"<sha>","readinessVersionId":"<version>","readinessSha256":"<sha>","detailPath":"/api/v1/accommodations/<approved id>"}

The controller reads the exact ASG/LT/baseline identity from current Terraform and AWS. The probe verifies its attached app-compute policy's default version, bytes and exact cleanup permissions before scale-out. It protects the baseline from scale-in, permits one additional instance with the same pinned template, observes app readiness/ALB entry and baseline latency/RDS metrics, then terminates only its own added instance with desired-capacity decrement. Pending launch cleanup retains baseline protection until stable 1/1/1 capacity and exact health are verified, then restores the original protection. AWS offers no atomic ASG comparison-and-set, so the tool uses a single lease with observations before/after mutations and reports that limitation. Scale-in protection does not prevent health replacement or manual termination.

The operation deadline is at most one hour and never exceeds either original resource expiry or the approved common deadline; 15 minutes are reserved for cleanup. Ambiguous ownership, resource drift, lease loss or timeout leaves explicit cleanup-required evidence and may retain baseline protection. The config/journal are versioned at data-bootstrap/<run>/asg-probe/<operationId>-<fence>/ and retained as a GitHub artifact even on workflow failure. A cleanup-only retry reuses the same operation fields and adds resume={configuration:{key,versionId,sha256,bytes},receipt:{key,versionId,sha256,bytes}} for one prior exact execution, under a new lease. It cannot start another scale-out. Successful cleanup-only execution reports b_asg_cleanup_complete=true and b_asg_probe_complete=false; the original failed observation remains failed in its immutable receipt. This observation does not complete the separate full inventory/retention R7 measurements or authorize an arbitrary load pool.

Preparation, service and snapshot wrappers use sibling namespaces. Never add operational wrappers or tool archives inside the finite SQL datasets/<datasetId>/ inventory.

## Snapshot creation and retirement

Creation approval is distinct from restore approval. Initially approved_b_snapshot_creation_identifier is empty, so no B snapshot creation policy exists. After R4 source verification, review one canonical airbob-dataset-b-* name, set only this creation approval, and review the exact controller policy and SSM contract changes. Keep approved_rds_snapshot_identifier at its previously promoted value.

Package the eight exact host helpers locally:

    python3 infra/aws/scripts/growth_b_snapshot_controller.py package-tools \
      --dataset-id "$DATASET" --run-id "$SOURCE_RUN" --operation-id "$OPERATION" \
      --output "$NEW_PRIVATE_OUTPUT"

The package receipt contains archive SHA/bytes, eight source SHAs and its immutable sibling object key. Publish the archive through the existing dataset publisher role with conditional creation and versioned SHA readback. Bind its exact VersionId in the host manifest, validate offline, then publish the manifest last:

    datasets/<datasetId>-aws-snapshots/operations/<runId>/<operationId>/manifest-<sha256>.json

The schema is defined by growth_b_snapshot_host.validate_manifest. Common fields bind operation, operationId, dataset/run/account/region, MySQL, snapshot name, application commit/image, final awsPreparation, consumerTools, eight toolSources, deadline and evidence. Create adds the exact source RDS identity and retained private/config hashes, plus exact restore, prepared-fingerprint and service-reset evidence refs.

Run snapshot-create with retained run_id, original dataset_release, manifest dataset_manifest_version_id and this b_operation:

    {"operationId":"<operation>","manifestSha256":"<exact manifest SHA>"}

The controller adds only a deadline-limited versioned S3 read policy to the retained host role. The host holds MySQL READ locks and publishes an immutable request and fresh lock ACKs. The controller requires the same active lease, host SSM invocation, approved name and source before one CreateDBSnapshot. It does not adopt or overwrite a conflicting snapshot.

After verifying the available snapshot and whole source fingerprint, the host issues a 60-second deletion admission. The controller installs STOP and rechecks admission after STOP and immediately before recording retirement. Expiration leaves STOP installed and resources retained. A new snapshot-retire operation with exact existing provenance and original source evidence obtains a fresh full source check/admission under a new lease.

Successful create/retire writes data-bootstrap/<sourceRun>/b-source-retirement.json, after which source reactivation is rejected. Snapshot actions do not delete RDS. The existing down action verifies the exact retirement/source/snapshot, removes only its own temporary read policies and uses the unchanged lease, OCI authority, Terraform state, TTL and destroy gates. Interrupted operations retain data resources.

The Lab role has no CancelCommand grant. Failure cleanup never claims cancellation: it records the original error separately from status/access-cleanup failures and observes the exact SSM invocation. A running or unobservable host retains its deadline-bound temporary read policy and all data resources. A later cleanup-access/down may remove only the same unchanged policy after its immutable dispatch proof shows a terminal host invocation, or after the original DateLessThan deadline has elapsed. The host retains its existing lease/deadline guards; expiration of access is not itself a claim that the host process terminated. Inspect controller-cleanup.json and use a fresh lease for the recorded recovery step.

## Actual snapshot restore and preparation

After available snapshot and immutable provenance review, separately promote that exact snapshot in approved_rds_snapshot_identifier. This changes restore permission only after the snapshot exists.

Run snapshot-restore with database_bootstrap=snapshot, policy=isolated-read, source run/resource ID, exact snapshot identifier, provenance VersionId, original application/bundle tuple and:

    {"provenanceSha256":"<sha>","provenanceKey":"data-bootstrap/<sourceRun>/<datasetId>-snapshot/<operationId>/snapshot-provenance.json"}

Omit provenanceKey only for a separately published canonical dataset-bucket provenance object. The controller requires the source absent and regional RDS inventory empty immediately before creating the sole target. It publishes a source-absence admission and minimal exact CloudTrail restore-event projection. RDS creation does not claim restored SQL/preparation verification.

Publish a new host manifest with operation=prepare, new run, same provenance/final preparation wrapper and exact admission/restore-event refs. Run snapshot-prepare using the retained target run and closed operationId/manifestSha256 shape. The host verifies the actual new RDS resource/UUID and whole snapshot fingerprint, then runs canonical credentials/current FREE preparation without SQL import. Its exact data-only-preparation.json projection admits the same normal service stages above.

A preparation retry uses a new operation ID, prior exact FAILED_RESOURCES_RETAINED operation receipt and restore-config SHA. It retains original verified baseline and source/event bindings. Preparation leaves the temporary application stopped and deploymentReady=false; normal-service and R4 results remain separate.

The snapshot host's per-operation runtime and the service bootstrap runtime have distinct paths. For a restored target, service bootstrap extracts the exact sealed runtime into the missing canonical `bootstrap-runtime` path and repeats current host qualification. An existing or partially created path must pass its byte checks; the service stage does not replace it or repeat SQL import.

After `services` dependencies, bootstrap and application, run `services` with the closed `snapshot-verify` stage. This separate target operation verifies normal logins, owned reads, global search, image decoding, local booking windows and the fixed warmup on the restored target. Package its exact source archive with `growth_b_snapshot_service_verify.py package-sources --output <new directory>`. Its `b_operation` requires `schemaVersion:1`, kind `global-b-aws-snapshot-target-service-operation`, stage `snapshot-verify`, operationId, runId, datasetId, serviceRelease, executionCommit, sourceArchiveSha256, exact manifest/readiness object references, and `targetPreparation:{manifest:<exact prepare-host manifest reference>,hostReceipt:<exact successful host receipt reference>}`. Every object reference contains key, VersionId as `versionId`, SHA256 as `sha256`, and actual byte count as `bytes`.

The target adapter follows the immutable preparation receipt to the actual restored-baseline, new prepared fingerprint, provenance, admission and CloudTrail event. It requires the current target's credentials and UUID. App runtime, cache-disabled configuration, ASG identity and lease observations bracket the reads. The operation preserves the app and CDC lifecycle and uses the same original resource and approved execution deadlines.

Completion requires `SNAPSHOT_TARGET_SERVICE_READS_AND_WARMUP_VERIFIED` with exact evidence references. The normal-read result carries its own target-service kind; it does not assert another domain mutation, source reset or snapshot creation. Closed public results and recovery are preserved under `SNAPSHOT_SERVICE_EVIDENCE_DIR/<run>-<operation>-<lease>/verifier/public/` and published with exact version readback in `data-bootstrap/<run>/<dataset>-snapshot-service/<operation>/`. An explicit resume supplies only its exact `recovery` object reference in the operation's `resume` field.
