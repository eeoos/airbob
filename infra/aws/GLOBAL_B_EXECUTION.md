# Global B AWS execution

This path uses MySQL 8.4.11 / Flyway V28 and the sealed B application and native Elasticsearch bytes. It is separate from the existing MySQL 8.0 / V27 dataset path. Offline tests validate contracts; they do not establish that a cloud operation completed.

## Review and execution identity

Use the protected main branch of aws-performance-lab.yml after the AWS-only code review. Every manual mutation requires expected_execution_commit equal to both the dispatch SHA and checked-out HEAD. An arbitrary checkout ref is not accepted. Prepare, services and snapshot host operations use the existing six-hour OIDC session, 18,000-second command deadline, 20,700-second lease deadline and cleanup margins. Existing resource TTLs are preserved.

Before the first paid B dispatch, approve one absolute UTC deadline and pass its integer epoch as approved_execution_deadline_epoch (APPROVED_EXECUTION_DEADLINE_EPOCH for the CLI). Reuse that exact value for the small run, final run, snapshot target and every retained service/snapshot operation. New B runs cap expiresAt at min(now + ttl_hours, approved deadline), require at least six hours remaining before lease acquisition, and store the approved deadline in the immutable operator manifest. Continuations require the same deadline and original resource expiry; they cannot extend either value. Legacy operations and down/expired cleanup keep their existing behavior. For a total 24-hour approval, the common deadline is fixed once before the first paid dispatch; do not calculate a new 24-hour deadline for each run. Resource deletion can still finish after expiry if the scheduled workflow queues or teardown fails; the deadline is not a billing cutoff.

An approval network plan uses review-only lease/TTL values. Actual execution acquires a fresh lease and resource identity. A full B service plan is a later gate: it requires the actual RDS resource ID/MySQL UUID, successful preparation receipts and published immutable image digests. A network plan does not establish service readiness.

The reviewed image inputs contain the baseline application/config bundle and nine infrastructure images. Normal services additionally require the separate B Debezium image. Its exact five manifest fields are image, buildCommit, pluginVersion=3.0.8.Final, pluginJarSha256=6de35d7c20ca1d00e6d9d8ae0e033203e487bf29e335a096dcaf38d4e0316f59, and connectVersion=3.7.0. The host separately verifies container digest, image revision, plugin JAR bytes, connector-plugins version and Kafka Connect root version. Existing release.json, ordinary commit tags and V27 receipts retain their meaning.

## SQL preparation and normal services

1. Use action prepare, database_bootstrap=dump, mode=performance, policy=isolated-read, dns_mode=direct-only, load_generator_enabled=false. Bind dataset_release, dataset_manifest_version_id, b_preparation_sha256, bundle_commit, bundle_manifest_version_id and app_image_digest. This creates the preparation host and RDS without an application ASG/ALB. The six preparation helpers and content-addressed wrapper are verified on the host.
2. Preserve the actual small rehearsal receipt, then complete down and verify the empty Lab state. Publish the final preparation wrapper in the sibling preparation prefix with that successful same-tool small receipt, then perform final prepare under a new run/RDS identity with the same approved execution deadline. The controller cannot switch the small run's dataset on its existing RDS. Preparation-only retries retain the selected final dataset and RDS identity. The sealed SQL inventory remains unchanged.
3. Publish a service manifest with the actual RDS resource ID/MySQL UUID, preparation receipt/config/fingerprint hashes, B Debezium provenance and native search transport. Its appRuntimeBinding is an exact key/VersionId/SHA/bytes reference to files/app-runtime-binding.json in the selected service sibling prefix. The nine service helpers include growth_b_app_runtime.py; the six preparation helpers remain unchanged. Use action services, the retained run_id, original dataset/application tuple, selected service manifest VersionId and b_operation:

       {"serviceRelease":"<release>","serviceManifestSha256":"<sha>","stage":"dependencies"}

   After actual native S3 restore, bind its receipt in a new immutable service release and select stage=bootstrap. Then select stage=application, adding readinessVersionId and readinessSha256 from bootstrap.
4. Application admission enables the normal aws profile, inventory readiness, schedulers and Kafka consumers, with separate general/cache Redis. It verifies MySQL TLS identity, exact application image and actual container JAR, and native ES fingerprint. The sealed application.appJarSha256 retains its preparation/search meaning. The reviewed main479 ECR JAR has the same 1277 source file entries including loader/META-INF and adds only the pinned API reference page plus two empty directory entries. The runtime validator verifies both complete inventories and its own source hash; it does not claim whole-JAR equality. Readiness and the runtime revision bind this proof, and startup independently verifies the actual image JAR SHA before launching. External payment, Slack, Google and image-write integrations remain disabled for the lab.
5. Perform separate R4 account/API/search/image/reservable-date checks and real domain API → outbox → Kafka → consumer → ES observation. Connector RUNNING and heartbeat alone are dependency checks. The source reset receipt must prove the domain mutation, full reset, zero test outbox rows, unchanged ownership, all writers/CDC stopped and exactly three representative accounts.

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
