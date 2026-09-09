#!/usr/bin/env bash
set -euo pipefail
umask 077

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
producer_source="$repo_root/infra/aws/scripts/produce-elasticsearch-snapshot.sh"
dataset_runbook="$repo_root/infra/aws/datasets/README.md"
[[ -f "$dataset_runbook" && ! -L "$dataset_runbook" ]] \
  || { printf '%s\n' 'dataset snapshot runbook is missing or unsafe' >&2; exit 1; }
grep -Fq 'duration_seconds = 3600' "$dataset_runbook" \
  || { printf '%s\n' 'dataset snapshot runbook omits the role-chaining duration' >&2; exit 1; }
grep -Fq '3,300 seconds (55 minutes)' "$dataset_runbook" \
  || { printf '%s\n' 'dataset snapshot runbook omits the credential headroom contract' >&2; exit 1; }
grep -Fq '300-second cleanup reserve' "$dataset_runbook" \
  || { printf '%s\n' 'dataset snapshot runbook omits the cleanup reserve' >&2; exit 1; }
grep -Fq 'aws sts get-caller-identity --profile admin-eeoos' "$dataset_runbook" \
  || { printf '%s\n' 'dataset snapshot runbook omits the AWS-free publisher preflight' >&2; exit 1; }
! grep -Fxq 'aws sts get-caller-identity' "$dataset_runbook" \
  || { printf '%s\n' 'dataset snapshot runbook resolves publisher credentials before lineage' >&2; exit 1; }
grep -Fq 'lineage verification without any AWS call' "$dataset_runbook" \
  || { printf '%s\n' 'dataset snapshot runbook omits deferred publisher credential resolution' >&2; exit 1; }
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-es-snapshot-producer-test.XXXXXX")
producer_root="$temp_dir/producer/infra/aws/scripts"
mkdir -p "$producer_root"
cp "$producer_source" "$producer_root/produce-elasticsearch-snapshot.sh"
cp "$repo_root/infra/aws/scripts/orchestration-lease.sh" "$producer_root/orchestration-lease.sh"
cp "$repo_root/infra/aws/scripts/validate-benchmark-dataset-v2.jq" "$producer_root/validate-benchmark-dataset-v2.jq"
producer="$producer_root/produce-elasticsearch-snapshot.sh"
fixture_password='mysql-producer-password-do-not-log'
access_key='ASIAABCDEFGHIJKLMNOP'
secret_key='temporary-secret-key-do-not-log'
session_token='temporary-session-token-do-not-log'

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  [[ "${AIRBOB_KEEP_TEST_TMP:-false}" == true ]] || rm -rf "$temp_dir"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  printf 'Elasticsearch snapshot producer test failed: %s\n' "$1" >&2
  exit 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

stat_mode() {
  if stat -f '%Lp' "$1" >/dev/null 2>&1; then
    stat -f '%Lp' "$1"
  else
    stat -c '%a' "$1"
  fi
}

write_checksums() {
  local root=$1
  local file_name

  : > "$root/SHA256SUMS"
  for file_name in \
    PROVENANCE.txt \
    airbob-production-seed.sql.gz \
    backend-migrations.sha256 \
    benchmark-dataset-v2.json \
    benchmark-fixture.json \
    database-fingerprint.tsv \
    etl-code.sha256 \
    generation-qualification-v1.json \
    production-skew-v1.json \
    release-metadata.txt \
    source-calibration-v1.json \
    source.sha256 \
    traffic-v1.json; do
    printf '%s  %s\n' "$(sha256_file "$root/$file_name")" "$file_name" \
      >> "$root/SHA256SUMS"
  done
}

write_database_fingerprint() {
  cat > "$1" <<'EOF'
dataset_final_world_fingerprint	eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee
dataset_base_world_fingerprint	0000000000000000000000000000000000000000000000000000000000000000
dataset_distribution_fingerprint	dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
dataset_target_fingerprint	ace11b7713606a877a12bed71a7c52aebca77851a169c6e25176c137fb77d9ac
dataset_inventory_fingerprint	ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff
foreign_key_checks_global	1
foreign_key_checks_session	1
orphan_total	0
review_summary_missing_count	0
review_summary_stale_count	0
review_summary_extra_count	0
review_summary_symmetric_mismatch_count	0
wishlist_accommodation_count_mismatch_count	0
wishlist_representative_mismatch_count	0
wishlist_denormalized_symmetric_mismatch_count	0
daily_revenue_stats_missing_count	0
daily_revenue_stats_stale_count	0
daily_revenue_stats_extra_count	0
daily_revenue_stats_symmetric_mismatch_count	0
accommodation_inventory_day_row_count	0
traffic_admin_account_count	3
traffic_admin_role_mismatch_count	0
traffic_cohort_account_count	27
traffic_cohort_distinct_email_count	27
traffic_cohort_mismatch_count	0
traffic_manifest_dataset_version_match	1
traffic_payment_graph_invalid_count	0
traffic_public_detail_anchor_mismatch_count	0
traffic_public_detail_last_window_mismatch_count	0
traffic_public_detail_overlap_count	0
traffic_public_review_image_mismatch_count	0
traffic_public_review_mismatch_count	0
traffic_review_summary_mismatch_count	0
traffic_settlement_target_mismatch_count	0
traffic_viewer_state_mismatch_count	0
traffic_wishlist_denormalization_mismatch_count	0
EOF
}

write_etl_repository() {
  local repository=$1
  mkdir -p "$repository/scripts"
  git -C "$repository" init -q
  git -C "$repository" config user.name 'Airbob Test'
  git -C "$repository" config user.email 'airbob-test@example.invalid'
  printf '%s\n' "SELECT 'production-contract-marker';" \
    > "$repository/scripts/verify-production-seed.sql"
  printf '%s\n' "SELECT 'traffic-contract-marker';" \
    > "$repository/scripts/verify-traffic-v1.sql"
  git -C "$repository" add scripts/verify-production-seed.sql scripts/verify-traffic-v1.sql
  git -C "$repository" commit -qm 'fixture verifier contracts'
}

write_release() {
  local root=$1
  local repository=$2
  local commit=$3
  local file_name
  local benchmark_dataset_sha calibration_sha spec_sha qualification_sha traffic_sha fingerprint_sha

  mkdir -p "$root"
  printf 'format=airbob-production-seed-provenance-v1\netl_head=%s\n' "$commit" \
    > "$root/PROVENANCE.txt"
  printf '%s\n' 'canonical gzip bytes' | gzip -n > "$root/airbob-production-seed.sql.gz"
  printf '%s\n' 'backend migrations' > "$root/backend-migrations.sha256"
  printf '%s\n' '{"datasetVersion":"nplus1-v1"}' > "$root/benchmark-fixture.json"
  printf '%s\n' '{"calibrationVersion":"source-calibration-v1"}' > "$root/source-calibration-v1.json"
  jq -nS '{profileVersion:"production-skew-v1",provenance:{generatorVersion:"production-skew-generator-v1",prngAlgorithm:"sha256-splitmix64-counter-v1",seedDerivation:"length-prefixed(profile-version, global-seed, relation-domain, stable-external-key, counter)",globalSeed:20260826,anchor:"2026-07-31T15:00:00Z",timezone:"Asia/Seoul"},targets:{accommodations:{rowBudget:50000,tolerance:{absoluteRows:0,relativePercent:0}},members:{rowBudget:200000,tolerance:{absoluteRows:0,relativePercent:0}},reservations:{rowBudget:2500000,tolerance:{absoluteRows:0,relativePercent:0}},reviews:{rowBudget:1000000,tolerance:{absoluteRows:0,relativePercent:0}},activeWishlists:{rowBudget:400000,tolerance:{absoluteRows:0,relativePercent:0}},wishlistLinks:{rowBudget:1500000,tolerance:{absoluteRows:0,relativePercent:0}}}}' > "$root/production-skew-v1.json"
  jq -nS '{version:"generation-qualification-v1",canonicalScale:true,generatedBudgets:{accommodations:50000,activeWishlists:400000,members:200000,reservations:2500000,reviews:1000000,wishlistLinks:1500000}}' > "$root/generation-qualification-v1.json"
  calibration_sha=$(sha256_file "$root/source-calibration-v1.json")
  spec_sha=$(sha256_file "$root/production-skew-v1.json")
  jq --arg calibration "$calibration_sha" --arg spec "$spec_sha" '
    .world.provenance.calibrationSha256=$calibration|.world.provenance.specSha256=$spec|
    .world.provenance.sourceInventorySha256=("a"*64)
  ' "$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v2.json" > "$root/benchmark-dataset-v2.json"
  benchmark_dataset_sha=$(sha256_file "$root/benchmark-dataset-v2.json")
  write_database_fingerprint "$root/database-fingerprint.tsv"
  : > "$root/etl-code.sha256"
  printf '%s  %s\n' "$('printf' '%s\n' verifier | sha256sum | awk '{print $1}')" scripts/verify-production-seed.sql > "$root/etl-code.sha256"
  qualification_sha=$(sha256_file "$root/generation-qualification-v1.json")
  fingerprint_sha=$(sha256_file "$root/database-fingerprint.tsv")
  cat > "$root/release-metadata.txt" <<EOF
format=airbob-production-seed-release-v2
release_id=production-seed-20260817t000000z
dump=airbob-production-seed.sql.gz
dump_sha256=$(sha256_file "$root/airbob-production-seed.sql.gz")
manifest=benchmark-fixture.json
manifest_sha256=$(sha256_file "$root/benchmark-fixture.json")
benchmark_dataset_manifest=benchmark-dataset-v2.json
benchmark_dataset_manifest_sha256=$benchmark_dataset_sha
benchmark_dataset_version=benchmark-dataset-v2
world_version=world-v2
production_spec=production-skew-v1.json
production_spec_sha256=$spec_sha
source_calibration=source-calibration-v1.json
source_calibration_sha256=$calibration_sha
source_catalog_inventory_fingerprint=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
generation_qualification=generation-qualification-v1.json
generation_qualification_sha256=$qualification_sha
canonical_scale=true
configured_batch_size=1000
jvm_max_heap_bytes=12884901888
traffic_manifest=traffic-v1.json
traffic_manifest_sha256=TRAFFIC_SHA
traffic_dataset_version=traffic-v1
traffic_dataset_run_id=20260817T001530Z-12345678
traffic_flyway_version=27
traffic_migration_digest=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
fingerprint=database-fingerprint.tsv
fingerprint_sha256=$fingerprint_sha
final_world_fingerprint=$(jq -r '.world.fingerprints["final-world"]' "$root/benchmark-dataset-v2.json")
base_world_fingerprint=$(jq -r '.world.fingerprints["base-world"]' "$root/benchmark-dataset-v2.json")
distribution_fingerprint=$(jq -r '.world.provenance.assertionSha256' "$root/benchmark-dataset-v2.json")
target_fingerprint=$(jq -r '.targetFingerprint' "$root/benchmark-dataset-v2.json")
inventory_fingerprint=$(jq -r '.world.fingerprints["final-inventory"]' "$root/benchmark-dataset-v2.json")
etl_code_inventory=etl-code.sha256
etl_code_inventory_sha256=$(sha256_file "$root/etl-code.sha256")
source_inventory=source.sha256
source_inventory_sha256=SOURCE_SHA
backend_migration_inventory=backend-migrations.sha256
backend_migration_inventory_sha256=$(sha256_file "$root/backend-migrations.sha256")
provenance=PROVENANCE.txt
provenance_sha256=$(sha256_file "$root/PROVENANCE.txt")
required_rows=201
recovery=reset-flyway-v1-v27-etl-reseed-before-traffic
EOF
  printf '%s\n' 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd  source.csv' \
    > "$root/source.sha256"
  jq -nS '
    {
      datasetVersion:"traffic-v1",
      datasetRunId:"20260817T001530Z-12345678",
      cohorts:[range(0; 27) as $index |
        {accounts:[{email:("traffic-" + ($index | tostring) + "@airbob.cloud")}]}]
    }
  ' > "$root/traffic-v1.json"

  traffic_sha=$(sha256_file "$root/traffic-v1.json")
  sed "s/TRAFFIC_SHA/$traffic_sha/;s/SOURCE_SHA/$(sha256_file "$root/source.sha256")/" \
    "$root/release-metadata.txt" > "$root/release-metadata.next"
  mv "$root/release-metadata.next" "$root/release-metadata.txt"

  write_checksums "$root"
}

write_attestation() {
  local release=$1
  local output=$2
  local commit=$3
  local release_sha dump_sha fingerprint_sha contract_inventory_sha expected_rows
  release_sha=$(sha256_file "$release/SHA256SUMS")
  dump_sha=$(awk '$2 == "airbob-production-seed.sql.gz" { print $1 }' "$release/SHA256SUMS")
  fingerprint_sha=$(awk '$2 == "database-fingerprint.tsv" { print $1 }' "$release/SHA256SUMS")
  awk '$2 == "scripts/verify-production-seed.sql" || $2 == "scripts/verify-traffic-v1.sql"' \
    "$release/etl-code.sha256" > "$temp_dir/verifier-contract-inventory.sha256"
  contract_inventory_sha=$(sha256_file "$temp_dir/verifier-contract-inventory.sha256")
  expected_rows=$(jq -c '.world.tableRows+{flyway_schema_history:27,outbox:0}' "$release/benchmark-dataset-v2.json")
  jq -nS \
    --arg releaseSha "$release_sha" \
    --arg dumpSha "$dump_sha" \
    --arg fingerprintSha "$fingerprint_sha" \
    --arg sourceEtlCommit "$commit" \
    --arg verifierContractInventorySha256 "$contract_inventory_sha" \
    --arg finalWorld "$(jq -r '.world.fingerprints["final-world"]' "$release/benchmark-dataset-v2.json")" \
    --arg baseWorld "$(jq -r '.world.fingerprints["base-world"]' "$release/benchmark-dataset-v2.json")" \
    --arg distributionAssertion "$(jq -r '.world.provenance.assertionSha256' "$release/benchmark-dataset-v2.json")" \
    --arg distributionSpec "$(sha256_file "$release/production-skew-v1.json")" \
    --arg target "$(jq -r '.targetFingerprint' "$release/benchmark-dataset-v2.json")" \
    --arg inventory "$(jq -r '.world.fingerprints["final-inventory"]' "$release/benchmark-dataset-v2.json")" \
    --argjson expectedRows "$expected_rows" '
    {
      schemaVersion: 4,
      databaseRestoreMethod: "gzip-to-empty-airbobdb-v2",
      sourceReleasePayloadSha256: $releaseSha,
      sourceDumpSha256: $dumpSha,
      restoredDumpSha256: $dumpSha,
      sourceDatabaseFingerprintSha256: $fingerprintSha,
      sourceEtlCommit: $sourceEtlCommit,
      databaseServerUuid: "00112233-4455-6677-8899-aabbccddeeff",
      verifierContractInventorySha256: $verifierContractInventorySha256,
      databaseFingerprintSha256: $fingerprintSha,
      verificationOutputSha256: ("8"*64),
      finalWorldFingerprintSha256: $finalWorld,
      baseWorldFingerprintSha256: $baseWorld,
      distributionEvidenceSha256: ("d"*64),
      distributionAssertionSha256: $distributionAssertion,
      distributionSpecSha256: $distributionSpec,
      targetFingerprintSha256: $target,
      inventoryFingerprintSha256: $inventory,
      flywayVersion: "27",
      flywayHistoryRows: 27,
      migrationChecksumSha256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      schemaFingerprintSha256: "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      outboxState: "empty",
      expectedTableRows: $expectedRows,
      capturedAt: "2026-08-17T03:04:05Z"
    }
  ' > "$output"
}

write_image_release() {
  local output=$1
  local digest='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  local registry='942632789808.dkr.ecr.ap-northeast-2.amazonaws.com'
  jq -n --arg digest "$digest" --arg registry "$registry" '
    {
      schemaVersion: 1,
      kind: "infra",
      gitCommit: "0123456789abcdef0123456789abcdef01234567",
      images: {
        DEBEZIUM_IMAGE: ($registry + "/airbob-infra/debezium@sha256:" + $digest),
        ELASTICSEARCH_EXPORTER_IMAGE: ($registry + "/airbob-infra/elasticsearch-exporter@sha256:" + $digest),
        ELASTICSEARCH_IMAGE: ($registry + "/airbob-infra/elasticsearch@sha256:" + $digest),
        GRAFANA_IMAGE: ($registry + "/airbob-infra/grafana@sha256:" + $digest),
        KAFKA_IMAGE: ($registry + "/airbob-infra/kafka@sha256:" + $digest),
        NODE_EXPORTER_IMAGE: ($registry + "/airbob-infra/node-exporter@sha256:" + $digest),
        PROMETHEUS_IMAGE: ($registry + "/airbob-infra/prometheus@sha256:" + $digest),
        REDIS_EXPORTER_IMAGE: ($registry + "/airbob-infra/redis-exporter@sha256:" + $digest),
        REDIS_IMAGE: ($registry + "/airbob-infra/redis@sha256:" + $digest)
      }
    }
  ' > "$output"
}

write_fake_aws() {
  cat > "$fake_bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws' >> "${FAKE_AWS_LOG:?}"
printf ' <%s>' "$@" >> "$FAKE_AWS_LOG"
printf '\n' >> "$FAKE_AWS_LOG"

while [[ "${1:-}" == --cli-connect-timeout || "${1:-}" == --cli-read-timeout ]]; do
  [[ "$#" -ge 2 ]] || exit 1
  shift 2
done

if [[ "$1 $2" == 'sts get-caller-identity' ]]; then
  printf '%s\n' 'caller-identity' >> "${FAKE_EVENT_LOG:?}"
  if [[ "${FAKE_WRONG_ROLE:-false}" == true ]]; then
    printf '%s\n' '{"UserId":"AIDA","Account":"942632789808","Arn":"arn:aws:iam::942632789808:user/admin-eeoos"}'
  else
    printf '%s\n' '{"UserId":"AROA:local","Account":"942632789808","Arn":"arn:aws:sts::942632789808:assumed-role/airbob-dataset-publisher/local"}'
  fi
  exit
fi

if [[ "$1 $2" == 'configure export-credentials' ]]; then
  printf '%s\n' 'credential-export' >> "${FAKE_EVENT_LOG:?}"
  credential_lifetime_seconds=${FAKE_CREDENTIAL_LIFETIME_SECONDS:-3590}
  expiration=$(jq -nr --argjson lifetime "$credential_lifetime_seconds" \
    'now + $lifetime | todateiso8601')
  [[ "${FAKE_EXPIRED_CREDENTIALS:-false}" == true ]] && expiration='2020-01-01T00:00:00Z'
  jq -n \
    --arg access "${FAKE_ACCESS_KEY:?}" \
    --arg secret "${FAKE_SECRET_KEY:?}" \
    --arg token "${FAKE_SESSION_TOKEN:?}" \
    --arg expiration "$expiration" \
    '{Version:1,AccessKeyId:$access,SecretAccessKey:$secret,SessionToken:$token,Expiration:$expiration}'
  exit
fi

if [[ "$1 $2" == 'dynamodb update-item' ]]; then
  if [[ "$*" == *'if_not_exists(#token, :zero) + :one'* ]]; then
    printf '%s\n' 'lease-acquire' >> "${FAKE_EVENT_LOG:?}"
    [[ "${FAKE_LEASE_ACQUIRE_FAILURE:-false}" != true ]] || exit 1
    printf '%s\n' 7
  elif [[ "$*" == *'SET #heartbeat = :now, #expires = :expires'* ]]; then
    [[ -z "${FAKE_HEARTBEAT_ATTEMPT_MARKER:-}" ]] \
      || : > "$FAKE_HEARTBEAT_ATTEMPT_MARKER"
    [[ "${FAKE_HEARTBEAT_FAILURE:-false}" != true ]] || exit 1
  elif [[ "$*" == *'SET #owner = :released'* ]]; then
    printf '%s\n' 'lease-release' >> "${FAKE_EVENT_LOG:?}"
    [[ "${FAKE_LEASE_RELEASE_FAILURE:-false}" != true ]] || exit 1
  fi
  exit
fi

if [[ "$1 $2" == 'dynamodb get-item' ]]; then
  printf '%s\n' 'lease-assert' >> "${FAKE_EVENT_LOG:?}"
  if [[ "${FAKE_FENCE_AFTER_OUTPUT_LINKS:-false}" == true ]]; then
    linked_output_count=$(awk '/^link </ { count++ } END { print count + 0 }' \
      "$FAKE_EVENT_LOG")
    if [[ "$linked_output_count" -ge 2 ]]; then
      printf '%s\n' 'lease-assert-fenced-after-output-links' >> "$FAKE_EVENT_LOG"
      printf '%s\n' $'dataset-publisher/local\t8\tsnapshot-20260817-12345678\tdataset-snapshot\t1900000000\t1900000000\t4102444800\t4102444800'
      exit
    fi
  fi
  printf '%s\n' $'dataset-publisher/local\t7\tsnapshot-20260817-12345678\tdataset-snapshot\t1900000000\t1900000000\t4102444800\t4102444800'
  exit
fi

if [[ "$1 $2" == 's3api put-object' ]]; then
  body=''
  bucket=''
  key=''
  if_none_match=''
  encryption=''
  content_type=''
  previous=''
  for argument in "$@"; do
    case "$previous" in
      --body) body=$argument ;;
      --bucket) bucket=$argument ;;
      --key) key=$argument ;;
      --if-none-match) if_none_match=$argument ;;
      --server-side-encryption) encryption=$argument ;;
      --content-type) content_type=$argument ;;
    esac
    previous=$argument
  done
  printf '%s\n' 'seal-put' >> "${FAKE_EVENT_LOG:?}"
  [[ "$bucket" == airbob-performance-lab-dataset-942632789808 \
    && "$key" == elasticsearch/seals/rehearsal-v20.json \
    && "$if_none_match" == '*' \
    && "$encryption" == AES256 \
    && "$content_type" == application/json \
    && -f "$body" ]] || exit 96
  [[ ! -e "${FAKE_SEAL_OBJECT:?}" ]] || exit 1
  if [[ "${FAKE_SEAL_FAILURE:-false}" == true ]]; then
    exit 1
  elif [[ "${FAKE_SEAL_REMOTE_MISMATCH:-false}" == true ]]; then
    printf '%s\n' '{"schemaVersion":1,"datasetRelease":"another-release"}' \
      > "$FAKE_SEAL_OBJECT"
    exit 1
  fi
  cp "$body" "$FAKE_SEAL_OBJECT"
  [[ "${FAKE_SEAL_APPLIED_RESPONSE_LOST:-false}" != true ]] || exit 1
  if [[ "${FAKE_SEAL_RESPONSE_UNCONFIRMED:-false}" == true ]]; then
    printf '%s\n' '{"ETag":"seal-etag"}'
    exit
  fi
  printf '%s\n' '{"ETag":"seal-etag","ServerSideEncryption":"AES256","VersionId":"seal-version-1"}'
  exit
fi

if [[ "$1 $2" == 's3api get-object' ]]; then
  key=''
  version_id=''
  previous=''
  for argument in "$@"; do
    case "$previous" in
      --key) key=$argument ;;
      --version-id) version_id=$argument ;;
    esac
    previous=$argument
  done
  output=${!#}
  if [[ -n "$version_id" ]]; then
    printf '%s\n' 'seal-get-version' >> "${FAKE_EVENT_LOG:?}"
  else
    printf '%s\n' 'seal-get-latest' >> "${FAKE_EVENT_LOG:?}"
  fi
  [[ "${FAKE_SEAL_GET_FAILURE:-false}" != true ]] || exit 97
  [[ "$key" == elasticsearch/seals/rehearsal-v20.json \
    && ( -z "$version_id" || "$version_id" == seal-version-1 ) \
    && -f "${FAKE_SEAL_OBJECT:?}" ]] || exit 97
  cp "$FAKE_SEAL_OBJECT" "$output"
  printf '%s\n' '{"ContentType":"application/json","ServerSideEncryption":"AES256","VersionId":"seal-version-1"}'
  exit
fi

if [[ "$1 $2" == 's3api list-object-versions' ]]; then
  key_marker=''
  previous=''
  for argument in "$@"; do
    if [[ "$previous" == --key-marker ]]; then key_marker=$argument; fi
    previous=$argument
  done
  invocation=0
  [[ ! -f "${FAKE_AWS_STATE:?}" ]] || invocation=$(cat "$FAKE_AWS_STATE")
  invocation=$((invocation + 1))
  printf '%s\n' "$invocation" > "$FAKE_AWS_STATE"
  if [[ "$invocation" -eq 1 && "${FAKE_PREFIX_OCCUPIED:-false}" != true ]]; then
    printf '%s\n' '{"IsTruncated":false,"Versions":[],"DeleteMarkers":[]}'
  elif [[ -z "$key_marker" ]]; then
    prefix='elasticsearch/releases/rehearsal-v20/'
    jq -n --arg prefix "$prefix" '
      {
        IsTruncated: true,
        NextKeyMarker: ($prefix + "index-0"),
        Versions: [
          {Key: ($prefix + "index-0"), VersionId: "v2", IsLatest: true, Size: 20, ETag: "etag-2", ChecksumAlgorithm: ["SHA256", "CRC32"]},
          {Key: ($prefix + "index-0"), VersionId: "v1", IsLatest: false, Size: 10, ETag: "etag-1"}
        ],
        DeleteMarkers: []
      }
    '
  else
    prefix='elasticsearch/releases/rehearsal-v20/'
    jq -n --arg prefix "$prefix" '
      {
        IsTruncated: false,
        Versions: [
          {Key: ($prefix + "snap-airbob-rehearsal-v20.dat"), VersionId: "v3", IsLatest: true, Size: 5, ETag: "etag-3", ChecksumAlgorithm: []}
        ],
        DeleteMarkers: [
          {Key: ($prefix + "pending-master.dat"), VersionId: "d1", IsLatest: true}
        ]
      }
    '
  fi
  exit
fi

printf 'unexpected fake AWS call: %s\n' "$*" >&2
exit 1
EOF
  chmod 700 "$fake_bin/aws"
}

write_fake_docker() {
  cat > "$fake_bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker' >> "${FAKE_DOCKER_LOG:?}"
printf ' <%s>' "$@" >> "$FAKE_DOCKER_LOG"
printf '\n' >> "$FAKE_DOCKER_LOG"

if [[ "$1" == inspect ]]; then
  if [[ "$*" == *'{{json .Config.Env}}'* ]]; then
    printf '["s3.client.airbob_dataset_producer.region=ap-northeast-2"]\n'
  else
    printf 'true|%s\n' "${FAKE_ES_IMAGE_REF:?}"
  fi
  exit
fi
if [[ "$1 $2" == 'image inspect' ]]; then
  printf '["%s"]\n' "${FAKE_ES_IMAGE_REF:?}"
  exit
fi
if [[ "$1" == exec ]]; then
  key=${!#}
  if [[ "$*" == *'elasticsearch-keystore list'* ]]; then
    [[ "${FAKE_KEYSTORE_OCCUPIED:-false}" != true ]] \
      || printf '%s\n' 's3.client.airbob_dataset_producer.access_key'
    exit
  fi
  if [[ "$*" == *'elasticsearch-keystore add '* ]]; then
    value=''
    IFS= read -r value || [[ -n "$value" ]]
    printf 'keystore-add <%s> <%s>\n' "$key" "$value" >> "${FAKE_DOCKER_SECRET_LOG:?}"
  fi
  exit
fi

printf 'unexpected fake Docker call: %s\n' "$*" >&2
exit 1
EOF
  chmod 700 "$fake_bin/docker"
}

write_fake_mysql() {
  cat > "$fake_bin/mysql" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "${MYSQL_PWD:-}" == "${FAKE_MYSQL_PASSWORD:?}" ]] || exit 91
[[ -z "${AIRBOB_DATASET_DB_PASSWORD:-}" ]] || exit 92
printf 'mysql' >> "${FAKE_MYSQL_LOG:?}"
printf ' <%s>' "$@" >> "$FAKE_MYSQL_LOG"
printf '\n' >> "$FAKE_MYSQL_LOG"
query=''
for argument in "$@"; do
  case "$argument" in --execute=*) query=${argument#--execute=} ;; esac
done
[[ -n "$query" ]] || query=$(cat)
case "$query" in
  *'@@server_uuid'*) printf '%s\t%s\t%s\n' '00112233-4455-6677-8899-aabbccddeeff' 1 1 ;;
  *'production-contract-marker'*'traffic-contract-marker'*)
    cat "${FAKE_DATABASE_FINGERPRINT:?}"
    ;;
  'SELECT COUNT(*) FROM accommodation') printf '%s\n' 50000 ;;
  'SELECT COUNT(*) FROM outbox') printf '%s\n' 0 ;;
  *'BIN_TO_UUID(accommodation_uid)'*)
    printf '%s\t%s\n' \
      '11111111-1111-1111-1111-111111111111' 1 \
      '22222222-2222-2222-2222-222222222222' 2
    ;;
  *"SELECT id FROM accommodation WHERE status = 'PUBLISHED' ORDER BY id"*)
    printf '%s\n' 1 2
    ;;
  *) printf 'unexpected fake MySQL query: %s\n' "$query" >&2; exit 93 ;;
esac
EOF
  chmod 700 "$fake_bin/mysql"
}

write_fake_lineage_verifier() {
  cat > "$producer_root/verify-etl-release-database.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$#" -eq 1 && -d "$1" && "${AIRBOB_DATASET_DB_PASSWORD:-}" == "${FAKE_MYSQL_PASSWORD:?}" \
  && "${AIRBOB_DATASET_RELEASE_PROFILE:-}" == production-skew-v1 ]]
printf '%s\n' 'semantic-lineage-verifier' >> "${FAKE_MYSQL_LOG:?}"
jq '{schemaVersion:2,sourceEtlCommit,databaseServerUuid,verifierContractInventorySha256,
  databaseFingerprintSha256,verificationOutputSha256,finalWorldFingerprintSha256,
  baseWorldFingerprintSha256,distributionEvidenceSha256,distributionAssertionSha256,
  distributionSpecSha256,targetFingerprintSha256,
  inventoryFingerprintSha256}' "${FAKE_CANONICAL_ATTESTATION:?}"
printf '%s\n' 'lineage-verified' >> "${FAKE_EVENT_LOG:?}"
EOF
  chmod 700 "$producer_root/verify-etl-release-database.sh"
}

write_fake_curl() {
  cat > "$fake_bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
method=GET
body=''
url=''
write_out=''
connect_timeout=''
max_time=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --request) method=$2; shift 2 ;;
    --data-binary) body=$2; shift 2 ;;
    --connect-timeout) connect_timeout=$2; shift 2 ;;
    --max-time) max_time=$2; shift 2 ;;
    --header|--output) shift 2 ;;
    --write-out) write_out=$2; shift 2 ;;
    --fail|--silent|--show-error) shift ;;
    http://*) url=$1; shift ;;
    *) printf 'unexpected fake curl argument: %s\n' "$1" >&2; exit 94 ;;
  esac
done
[[ "$connect_timeout" =~ ^[1-9][0-9]*$ && "$max_time" =~ ^[1-9][0-9]*$ ]] \
  || { printf '%s\n' 'fake curl requires finite connect and request timeouts' >&2; exit 96; }
printf 'curl <%s> <%s> BODY <%s> CONNECT <%s> MAX <%s>\n' \
  "$method" "$url" "$body" "$connect_timeout" "$max_time" >> "${FAKE_CURL_LOG:?}"

if [[ "${FAKE_CURL_TIMEOUT:-false}" == true && "$method $url" == "GET ${FAKE_ES_URL}/" ]]; then
  exit 28
fi

if [[ -n "$write_out" ]]; then
  if [[ "${FAKE_REPOSITORY_OCCUPIED:-false}" == true && "$url" == *'/_snapshot/airbob-dataset-producer' ]]; then
    printf '%s' 200
    exit
  fi
  printf '%s' 404
  exit
fi

case "$method $url" in
  "GET ${FAKE_ES_URL}/")
    printf '%s\n' '{"version":{"number":"8.18.8"}}'
    ;;
  "GET ${FAKE_ES_URL}/_nodes/plugins?filter_path=nodes.*.modules.name,nodes.*.plugins.name")
    printf '%s\n' '{"nodes":{"node-1":{"modules":[{"name":"repository-s3"}],"plugins":[{"name":"analysis-nori"}]}}}'
    ;;
  "GET ${FAKE_ES_URL}/_cluster/health?wait_for_no_relocating_shards=true&wait_for_no_initializing_shards=true&timeout=30s")
    printf '%s\n' '{"timed_out":false,"relocating_shards":0,"initializing_shards":0}'
    ;;
  "GET ${FAKE_ES_URL}/_alias/accommodations")
    if [[ "${FAKE_ALIAS_MULTIPLE_TARGETS:-false}" == true ]]; then
      printf '%s\n' '{"accommodations-v20260817001530":{"aliases":{"accommodations":{"is_write_index":true}}},"accommodations-v20260817001531":{"aliases":{"accommodations":{"is_write_index":false}}}}'
    elif [[ "${FAKE_ALIAS_NOT_WRITE:-false}" == true ]]; then
      printf '%s\n' '{"accommodations-v20260817001530":{"aliases":{"accommodations":{"is_write_index":false}}}}'
    else
      printf '%s\n' '{"accommodations-v20260817001530":{"aliases":{"accommodations":{"is_write_index":true}}}}'
    fi
    ;;
  "GET ${FAKE_ES_URL}/accommodations-v20260817001530/_settings?flat_settings=true&filter_path=*.settings.index.blocks.write")
    if [[ "${FAKE_SOURCE_WRITE_BLOCKED:-false}" == true ]]; then
      printf '%s\n' '{"accommodations-v20260817001530":{"settings":{"index.blocks.write":"true"}}}'
    else
      printf '%s\n' '{"accommodations-v20260817001530":{"settings":{}}}'
    fi
    ;;
  "POST ${FAKE_ES_URL}/_nodes/reload_secure_settings")
    printf '%s\n' '{"nodes":{"node-1":{"reload_exception":null}}}'
    ;;
  "PUT ${FAKE_ES_URL}/accommodations-v20260817001530/_settings"|"PUT ${FAKE_ES_URL}/airbob-verify-rehearsal-v20/_settings")
    printf '%s\n' '{"acknowledged":true}'
    ;;
  "GET ${FAKE_ES_URL}/accommodations-v20260817001530/_count"|"GET ${FAKE_ES_URL}/airbob-verify-rehearsal-v20/_count")
    printf '%s\n' '{"count":2,"_shards":{"failed":0}}'
    ;;
  "GET ${FAKE_ES_URL}/accommodations-v20260817001530/_mapping")
    printf '%s\n' '{"accommodations-v20260817001530":{"mappings":{"properties":{"accommodationId":{"type":"long"},"name":{"type":"keyword"}}}}}'
    ;;
  "GET ${FAKE_ES_URL}/airbob-verify-rehearsal-v20/_mapping")
    printf '%s\n' '{"airbob-verify-rehearsal-v20":{"mappings":{"properties":{"accommodationId":{"type":"long"},"name":{"type":"keyword"}}}}}'
    ;;
  "POST ${FAKE_ES_URL}/accommodations-v20260817001530/_search?scroll=2m"|"POST ${FAKE_ES_URL}/airbob-verify-rehearsal-v20/_search?scroll=2m")
    initial=0
    [[ ! -f "${FAKE_CURL_STATE:?}" ]] || initial=$(cat "$FAKE_CURL_STATE")
    initial=$((initial + 1))
    printf '%s\n' "$initial" > "$FAKE_CURL_STATE"
    name_two='beta'
    if [[ "${FAKE_ES_DRIFT:-false}" == true && "$initial" -ge 3 ]]; then
      name_two='changed'
    fi
    first_document_id='11111111-1111-1111-1111-111111111111'
    if [[ "${FAKE_WRONG_DOCUMENT_ID:-false}" == true ]]; then
      first_document_id='99999999-9999-9999-9999-999999999999'
    fi
    jq -n \
      --arg firstDocumentId "$first_document_id" \
      --arg nameTwo "$name_two" \
      --arg scroll "scroll-$initial" '
      {
        _scroll_id: $scroll,
        timed_out: false,
        _shards: {failed: 0},
        hits: {hits: [
          {_id: $firstDocumentId, _source: {accommodationId: 1, name: "alpha"}},
          {_id: "22222222-2222-2222-2222-222222222222", _source: {accommodationId: 2, name: $nameTwo}}
        ]}
      }
    '
    ;;
  "POST ${FAKE_ES_URL}/_search/scroll")
    printf '%s\n' '{"_scroll_id":"scroll-done","timed_out":false,"_shards":{"failed":0},"hits":{"hits":[]}}'
    ;;
  "DELETE ${FAKE_ES_URL}/_search/scroll")
    printf '%s\n' '{"succeeded":true,"num_freed":1}'
    ;;
  "PUT ${FAKE_ES_URL}/_snapshot/airbob-dataset-producer"|"PUT ${FAKE_ES_URL}/_snapshot/airbob-dataset-readonly")
    printf '%s\n' '{"acknowledged":true}'
    ;;
  "POST ${FAKE_ES_URL}/_snapshot/airbob-dataset-producer/_verify"|"POST ${FAKE_ES_URL}/_snapshot/airbob-dataset-readonly/_verify")
    printf '%s\n' '{"nodes":{"node-1":{"name":"node-1"}}}'
    ;;
  "PUT ${FAKE_ES_URL}/_snapshot/airbob-dataset-producer/airbob-rehearsal-v20?wait_for_completion=true")
    if [[ "${FAKE_HEARTBEAT_FAILURE:-false}" == true ]]; then
      : > "${FAKE_SNAPSHOT_PUT_MARKER:?}"
      heartbeat_wait=0
      while [[ ! -e "${FAKE_HEARTBEAT_ATTEMPT_MARKER:?}" && "$heartbeat_wait" -lt 100 ]]; do
        /bin/sleep 0.01
        heartbeat_wait=$((heartbeat_wait + 1))
      done
      [[ -e "$FAKE_HEARTBEAT_ATTEMPT_MARKER" ]] || exit 97
      /bin/sleep 1
    fi
    snapshot_state='SUCCESS'
    successful_shards=1
    snapshot_index_version='8.18.0-8.18.8'
    snapshot_index_version_id=8525000
    if [[ "${FAKE_SNAPSHOT_FAIL:-false}" == true ]]; then
      snapshot_state='PARTIAL'
      successful_shards=0
    fi
    [[ "${FAKE_SNAPSHOT_INDEX_VERSION_DRIFT:-false}" != true ]] \
      || snapshot_index_version='8.18.0-8.18.7'
    [[ "${FAKE_SNAPSHOT_INDEX_VERSION_ID_DRIFT:-false}" != true ]] \
      || snapshot_index_version_id=8524000
    jq -n \
      --arg state "$snapshot_state" \
      --argjson successful "$successful_shards" \
      --arg snapshotIndexVersion "$snapshot_index_version" \
      --argjson snapshotIndexVersionId "$snapshot_index_version_id" \
      --arg runId "${FAKE_DATASET_RUN_ID:?}" \
      --arg sourceSha "${FAKE_SOURCE_PAYLOAD_SHA:?}" '
      {snapshot:{
        snapshot:"airbob-rehearsal-v20",uuid:"uuid-1",state:$state,
        version:$snapshotIndexVersion,version_id:$snapshotIndexVersionId,
        indices:["accommodations-v20260817001530"],include_global_state:false,feature_states:[],
        metadata:{
          datasetRelease:"rehearsal-v20",datasetRunId:$runId,
          sourceReleasePayloadSha256:$sourceSha,
          imageDigest:("sha256:" + ("a" * 64))
        },
        start_time:"2026-08-17T04:00:00.000Z",end_time:"2026-08-17T04:00:01.000Z",
        shards:{total:1,successful:$successful,failed:(1 - $successful)}
      }}'
    ;;
  "DELETE ${FAKE_ES_URL}/_snapshot/airbob-dataset-producer"|"DELETE ${FAKE_ES_URL}/_snapshot/airbob-dataset-readonly")
    printf '%s\n' '{"acknowledged":true}'
    ;;
  "GET ${FAKE_ES_URL}/_snapshot/airbob-dataset-readonly/airbob-rehearsal-v20")
    metadata_release='rehearsal-v20'
    snapshot_index_version='8.18.0-8.18.8'
    snapshot_index_version_id=8525000
    [[ "${FAKE_SNAPSHOT_METADATA_DRIFT:-false}" != true ]] || metadata_release='other-v20'
    [[ "${FAKE_SNAPSHOT_INDEX_VERSION_DRIFT:-false}" != true ]] \
      || snapshot_index_version='8.18.0-8.18.7'
    [[ "${FAKE_SNAPSHOT_INDEX_VERSION_ID_DRIFT:-false}" != true ]] \
      || snapshot_index_version_id=8524000
    jq -n \
      --arg metadataRelease "$metadata_release" \
      --arg snapshotIndexVersion "$snapshot_index_version" \
      --argjson snapshotIndexVersionId "$snapshot_index_version_id" \
      --arg runId "${FAKE_DATASET_RUN_ID:?}" \
      --arg sourceSha "${FAKE_SOURCE_PAYLOAD_SHA:?}" '
      {
        snapshots:[{
          snapshot:"airbob-rehearsal-v20",
          uuid:"uuid-1",
          repository:"airbob-dataset-readonly",
          version:$snapshotIndexVersion,
          version_id:$snapshotIndexVersionId,
          indices:["accommodations-v20260817001530"],
          include_global_state:false,
          feature_states:[],
          state:"SUCCESS",
          metadata:{
            datasetRelease:$metadataRelease,
            datasetRunId:$runId,
            sourceReleasePayloadSha256:$sourceSha,
            imageDigest:"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          },
          start_time:"2026-08-17T04:00:00.000Z",
          end_time:"2026-08-17T04:00:01.000Z",
          shards:{total:1,successful:1,failed:0}
        }],
        total:1,
        remaining:0
      }
    '
    ;;
  "POST ${FAKE_ES_URL}/_snapshot/airbob-dataset-readonly/airbob-rehearsal-v20/_restore?wait_for_completion=true")
    printf '%s\n' '{"snapshot":{"snapshot":"airbob-rehearsal-v20","indices":["airbob-verify-rehearsal-v20"],"shards":{"total":1,"successful":1,"failed":0}}}'
    ;;
  "DELETE ${FAKE_ES_URL}/airbob-verify-rehearsal-v20")
    printf '%s\n' '{"acknowledged":true}'
    ;;
  *) printf 'unexpected fake curl call: %s %s\n' "$method" "$url" >&2; exit 95 ;;
esac
EOF
  chmod 700 "$fake_bin/curl"
}

write_fake_sleep() {
  cat > "$fake_bin/sleep" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${FAKE_HEARTBEAT_FAILURE:-false}" == true && "$#" -eq 1 && "$1" == 60 ]]; then
  marker_wait=0
  while [[ ! -e "${FAKE_SNAPSHOT_PUT_MARKER:?}" && "$marker_wait" -lt 100 ]]; do
    /bin/sleep 0.01
    marker_wait=$((marker_wait + 1))
  done
  [[ -e "$FAKE_SNAPSHOT_PUT_MARKER" ]] || exit 98
  exit 0
fi
exec /bin/sleep "$@"
EOF
  chmod 700 "$fake_bin/sleep"
}

write_fake_ln() {
  cat > "$fake_bin/ln" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$#" -eq 2 ]] || exit 99
printf 'link <%s>\n' "${2##*/}" >> "${FAKE_EVENT_LOG:?}"
exec /bin/ln "$@"
EOF
  chmod 700 "$fake_bin/ln"
}

run_producer() {
  local reference=$1
  local receipt=$2
  local producer_release=${PRODUCER_RELEASE:-$release}
  shift 2
  : > "$aws_log"
  : > "$docker_log"
  : > "$docker_secret_log"
  : > "$mysql_log"
  : > "$curl_log"
  : > "$event_log"
  rm -f "$aws_state" "$curl_state" \
    "$heartbeat_snapshot_marker" "$heartbeat_attempt_marker" "$seal_object"
  env \
    PATH="$fake_bin:$PATH" \
    AIRBOB_REGION=ap-northeast-2 \
    AIRBOB_AWS_ACCOUNT_ID=942632789808 \
    AIRBOB_DATASET_ETL_REPOSITORY="$etl_repository" \
    AIRBOB_DATASET_DB_HOST=127.0.0.1 \
    AIRBOB_DATASET_DB_PORT=3307 \
    AIRBOB_DATASET_DB_USER=airbob_snapshot \
    AIRBOB_DATASET_DB_PASSWORD="$fixture_password" \
    AIRBOB_DATASET_DB_NAME=airbobdb \
    AIRBOB_DATASET_DB_QUIESCED=true \
    AIRBOB_DATASET_ES_URL=http://127.0.0.1:9200 \
    AIRBOB_DATASET_ES_CONTAINER=elasticsearch \
    AIRBOB_DATASET_ES_QUIESCED=true \
    FAKE_AWS_LOG="$aws_log" \
    FAKE_AWS_STATE="$aws_state" \
    FAKE_EVENT_LOG="$event_log" \
    FAKE_SEAL_OBJECT="$seal_object" \
    FAKE_ACCESS_KEY="$access_key" \
    FAKE_SECRET_KEY="$secret_key" \
    FAKE_SESSION_TOKEN="$session_token" \
    FAKE_DOCKER_LOG="$docker_log" \
    FAKE_DOCKER_SECRET_LOG="$docker_secret_log" \
    FAKE_ES_IMAGE_REF="$elasticsearch_image_ref" \
    FAKE_MYSQL_LOG="$mysql_log" \
    FAKE_MYSQL_PASSWORD="$fixture_password" \
    FAKE_CANONICAL_ATTESTATION="$attestation" \
    FAKE_DATABASE_FINGERPRINT="$producer_release/database-fingerprint.tsv" \
    FAKE_CURL_TIMEOUT="${FAKE_CURL_TIMEOUT:-false}" \
    FAKE_CURL_LOG="$curl_log" \
    FAKE_CURL_STATE="$curl_state" \
    FAKE_SNAPSHOT_PUT_MARKER="$heartbeat_snapshot_marker" \
    FAKE_HEARTBEAT_ATTEMPT_MARKER="$heartbeat_attempt_marker" \
    FAKE_ES_URL=http://127.0.0.1:9200 \
    FAKE_DATASET_RUN_ID=20260817T001530Z-12345678 \
    FAKE_SOURCE_PAYLOAD_SHA="$(sha256_file "$producer_release/SHA256SUMS")" \
    "$@" \
    "$producer" "$producer_release" "${PRODUCER_ATTESTATION:-$attestation}" \
    "${PRODUCER_IMAGE_RELEASE:-$image_release}" rehearsal-v20 "$reference" "$receipt"
}

expect_failure() {
  local label=$1
  shift
  if "$@" > "$temp_dir/$label.stdout" 2> "$temp_dir/$label.stderr"; then
    fail "expected rejection: $label"
  fi
  local credential
  for credential in "$fixture_password" "$access_key" "$secret_key" "$session_token"; do
    ! grep -Fq -- "$credential" "$temp_dir/$label.stdout" "$temp_dir/$label.stderr" \
      || fail "secret leaked for rejection: $label"
  done
}

fake_bin="$temp_dir/bin"
mkdir -p "$fake_bin"
release="$temp_dir/etl-release"
etl_repository="$temp_dir/etl-repository"
attestation="$temp_dir/attestation.json"
image_release="$temp_dir/infra-images.json"
reference="$temp_dir/snapshot-reference.json"
receipt="$temp_dir/snapshot-receipt.json"
aws_log="$temp_dir/aws.log"
aws_state="$temp_dir/aws.state"
docker_log="$temp_dir/docker.log"
docker_secret_log="$temp_dir/docker-secrets.log"
mysql_log="$temp_dir/mysql.log"
curl_log="$temp_dir/curl.log"
curl_state="$temp_dir/curl.state"
heartbeat_snapshot_marker="$temp_dir/heartbeat-snapshot-put.marker"
heartbeat_attempt_marker="$temp_dir/heartbeat-attempt.marker"
event_log="$temp_dir/events.log"
seal_object="$temp_dir/snapshot-seal.json"
elasticsearch_image_ref='942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/elasticsearch@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'

mkdir -p "$etl_repository"
source_etl_commit=0123456789abcdef0123456789abcdef01234567
write_release "$release" "$etl_repository" "$source_etl_commit"
write_attestation "$release" "$attestation" "$source_etl_commit"
write_image_release "$image_release"
write_fake_aws
write_fake_docker
write_fake_mysql
write_fake_lineage_verifier
write_fake_curl
write_fake_sleep
write_fake_ln

[[ -x "$producer" && ! -L "$producer" ]] || fail 'producer script is missing or unsafe'
/bin/bash -n "$producer"

clock_override_reference="$temp_dir/clock-override-reference.json"
clock_override_receipt="$temp_dir/clock-override-receipt.json"
expect_failure test-clock-override run_producer \
  "$clock_override_reference" "$clock_override_receipt" AIRBOB_NOW_EPOCH=1900000000
[[ ! -s "$aws_log" && ! -s "$curl_log" && ! -s "$docker_log" && ! -s "$mysql_log" ]] \
  || fail 'test clock override reached AWS or local data services'
[[ ! -e "$clock_override_reference" && ! -e "$clock_override_receipt" ]] \
  || fail 'test clock override wrote snapshot outputs'

mismatched_benchmark_dataset_release="$temp_dir/mismatched-benchmark-dataset-release"
mismatched_benchmark_dataset_attestation="$temp_dir/mismatched-benchmark-dataset-attestation.json"
cp -R "$release" "$mismatched_benchmark_dataset_release"
sed 's/^benchmark_dataset_manifest_sha256=.*/benchmark_dataset_manifest_sha256=ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff/' \
  "$mismatched_benchmark_dataset_release/release-metadata.txt" \
  > "$mismatched_benchmark_dataset_release/release-metadata.next"
mv "$mismatched_benchmark_dataset_release/release-metadata.next" \
  "$mismatched_benchmark_dataset_release/release-metadata.txt"
write_checksums "$mismatched_benchmark_dataset_release"
write_attestation "$mismatched_benchmark_dataset_release" \
  "$mismatched_benchmark_dataset_attestation" "$source_etl_commit"
mismatched_benchmark_dataset_reference="$temp_dir/mismatched-benchmark-dataset-reference.json"
mismatched_benchmark_dataset_receipt="$temp_dir/mismatched-benchmark-dataset-receipt.json"
PRODUCER_RELEASE="$mismatched_benchmark_dataset_release" \
PRODUCER_ATTESTATION="$mismatched_benchmark_dataset_attestation" \
  expect_failure mismatched-benchmark-dataset-sha run_producer \
    "$mismatched_benchmark_dataset_reference" "$mismatched_benchmark_dataset_receipt"
grep -Fq 'benchmark dataset manifest digest does not match the source release' \
  "$temp_dir/mismatched-benchmark-dataset-sha.stderr" \
  || fail 'mismatched benchmark dataset SHA did not reach the exact metadata binding gate'
[[ ! -s "$aws_log" && ! -s "$curl_log" && ! -s "$docker_log" && ! -s "$mysql_log" ]] \
  || fail 'mismatched benchmark dataset SHA reached AWS or local data services'

run_producer "$reference" "$receipt" >/dev/null
[[ -f "$seal_object" ]] || fail 'successful snapshot did not publish its immutable seal'
lineage_verified_line=$(grep -n -m1 -F 'lineage-verified' "$event_log" | cut -d: -f1)
caller_identity_line=$(grep -n -m1 -F 'caller-identity' "$event_log" | cut -d: -f1)
credential_export_line=$(grep -n -m1 -F 'credential-export' "$event_log" | cut -d: -f1)
lease_acquire_event_line=$(grep -n -m1 -F 'lease-acquire' "$event_log" | cut -d: -f1)
[[ -n "$lineage_verified_line" && -n "$caller_identity_line" \
  && -n "$credential_export_line" && -n "$lease_acquire_event_line" \
  && "$lineage_verified_line" -lt "$caller_identity_line" \
  && "$caller_identity_line" -lt "$credential_export_line" \
  && "$credential_export_line" -lt "$lease_acquire_event_line" ]] \
  || fail 'publisher credentials or snapshot lease started before live lineage verification'

jq -e '
  (keys | sort) == ([
    "basePath", "bucket", "contentFingerprintSha256", "dbDocumentIdentityPairsSha256",
    "dbIdsSha256", "documentCount", "elasticsearchVersion", "esDocumentIdentityPairsSha256",
    "esIdsSha256", "imageDigest", "logicalAlias", "mappingSha256", "repository",
    "schemaVersion", "snapshot", "snapshotIndex"
  ] | sort) and
  .schemaVersion == 2 and
  .repository == "airbob-dataset-readonly" and
  .bucket == "airbob-performance-lab-dataset-942632789808" and
  .basePath == "elasticsearch/releases/rehearsal-v20" and
  .snapshot == "airbob-rehearsal-v20" and
  .logicalAlias == "accommodations" and
  .snapshotIndex == "accommodations-v20260817001530" and
  .elasticsearchVersion == "8.18.8" and
  .imageDigest == "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" and
  .documentCount == 2 and
  .mappingSha256 == "3b3da175955517975363408a2d42b15388d787b89100b2034291eeb54c37853b" and
  .dbIdsSha256 == "a6e2b7a040683432de03a18fd8a1939a2fdf82585b364bfc874bdd4095c4cae1" and
  .esIdsSha256 == "a6e2b7a040683432de03a18fd8a1939a2fdf82585b364bfc874bdd4095c4cae1" and
  .dbDocumentIdentityPairsSha256 == "668f78e773ff929c20a45d855b2466f187091620e6f5a57ee7b966d8bde9da37" and
  .esDocumentIdentityPairsSha256 == "668f78e773ff929c20a45d855b2466f187091620e6f5a57ee7b966d8bde9da37" and
  .contentFingerprintSha256 == "4f216b11588cf17caa70276c213d599282b8580d1da9b07f71f763a087617f9b"
' "$reference" >/dev/null || fail 'snapshot reference violates its exact contract'

reference_sha=$(sha256_file "$reference")
source_payload_sha=$(sha256_file "$release/SHA256SUMS")
expected_snapshot_metadata="$temp_dir/expected-snapshot-metadata.json"
jq -cS -n --arg sourcePayloadSha "$source_payload_sha" '
  {
    snapshot:"airbob-rehearsal-v20",
    uuid:"uuid-1",
    repository:"airbob-dataset-readonly",
    version:"8.18.0-8.18.8",
    version_id:8525000,
    indices:["accommodations-v20260817001530"],
    include_global_state:false,
    feature_states:[],
    state:"SUCCESS",
    metadata:{
      datasetRelease:"rehearsal-v20",
      datasetRunId:"20260817T001530Z-12345678",
      sourceReleasePayloadSha256:$sourcePayloadSha,
      imageDigest:"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    },
    start_time:"2026-08-17T04:00:00.000Z",
    end_time:"2026-08-17T04:00:01.000Z",
    shards:{total:1,successful:1,failed:0}
  }
' > "$expected_snapshot_metadata"
expected_snapshot_metadata_sha=$(sha256_file "$expected_snapshot_metadata")
jq -e \
  --arg referenceSha "$reference_sha" \
  --arg sourcePayloadSha "$source_payload_sha" \
  --arg expectedSnapshotMetadataSha "$expected_snapshot_metadata_sha" '
  (keys | sort) == ([
    "createdAt", "datasetRelease", "datasetRunId", "producer", "repository", "schemaVersion",
    "snapshot", "sourceReleasePayloadSha256", "validation"
  ] | sort) and
  .schemaVersion == 2 and
  .datasetRelease == "rehearsal-v20" and
  .datasetRunId == "20260817T001530Z-12345678" and
  .sourceReleasePayloadSha256 == $sourcePayloadSha and
  (.createdAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
  (.producer | keys | sort) == (["client", "elasticsearchVersion", "imageDigest"] | sort) and
  .producer.client == "airbob_dataset_producer" and
  .producer.elasticsearchVersion == "8.18.8" and
  .producer.imageDigest == "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" and
  (.repository | keys | sort) == (["basePath", "bucket", "inventory", "readerName", "verificationNodeCount", "writerName"] | sort) and
  .repository.bucket == "airbob-performance-lab-dataset-942632789808" and
  .repository.basePath == "elasticsearch/releases/rehearsal-v20" and
  .repository.writerName == "airbob-dataset-producer" and
  .repository.readerName == "airbob-dataset-readonly" and
  .repository.verificationNodeCount == 1 and
  (.repository.inventory | keys | sort) == (["algorithm", "entryCount", "sha256", "totalVersionBytes"] | sort) and
  .repository.inventory.algorithm == "s3-list-object-versions-v1" and
  .repository.inventory.entryCount == 4 and
  .repository.inventory.totalVersionBytes == 35 and
  .repository.inventory.sha256 == "596f72d63e7322f5ca54ec745729f42d040aac4483739c6e2d22097ebbe55ab8" and
  (.snapshot | keys | sort) == (["failedShards", "includeGlobalState", "indices", "metadataSha256", "name", "state", "successfulShards", "totalShards", "uuid", "version"] | sort) and
  .snapshot.name == "airbob-rehearsal-v20" and
  .snapshot.uuid == "uuid-1" and
  .snapshot.state == "SUCCESS" and
  .snapshot.version == "8.18.0-8.18.8" and
  .snapshot.indices == ["accommodations-v20260817001530"] and
  .snapshot.includeGlobalState == false and
  .snapshot.totalShards == 1 and
  .snapshot.successfulShards == 1 and
  .snapshot.failedShards == 0 and
  .snapshot.metadataSha256 == $expectedSnapshotMetadataSha and
  (.validation | keys | sort) == (["contentFingerprintSha256", "dbDocumentIdentityPairsSha256", "dbIdsSha256", "documentCount", "esDocumentIdentityPairsSha256", "esIdsSha256", "mappingSha256", "snapshotReferenceSha256"] | sort) and
  .validation.snapshotReferenceSha256 == $referenceSha and
  .validation.documentCount == 2 and
  .validation.mappingSha256 == "3b3da175955517975363408a2d42b15388d787b89100b2034291eeb54c37853b" and
  .validation.dbIdsSha256 == "a6e2b7a040683432de03a18fd8a1939a2fdf82585b364bfc874bdd4095c4cae1" and
  .validation.esIdsSha256 == "a6e2b7a040683432de03a18fd8a1939a2fdf82585b364bfc874bdd4095c4cae1" and
  .validation.dbDocumentIdentityPairsSha256 == "668f78e773ff929c20a45d855b2466f187091620e6f5a57ee7b966d8bde9da37" and
  .validation.esDocumentIdentityPairsSha256 == "668f78e773ff929c20a45d855b2466f187091620e6f5a57ee7b966d8bde9da37" and
  .validation.contentFingerprintSha256 == "4f216b11588cf17caa70276c213d599282b8580d1da9b07f71f763a087617f9b"
' "$receipt" >/dev/null || fail 'snapshot receipt violates its exact contract'

receipt_sha=$(sha256_file "$receipt")
receipt_created_at=$(jq -r '.createdAt' "$receipt")
expected_seal="$temp_dir/expected-snapshot-seal.json"
jq -nS \
  --arg referenceSha "$reference_sha" \
  --arg receiptSha "$receipt_sha" \
  --arg createdAt "$receipt_created_at" '
  {
    schemaVersion:1,
    datasetRelease:"rehearsal-v20",
    snapshot:"airbob-rehearsal-v20",
    snapshotReferenceSha256:$referenceSha,
    snapshotReceiptSha256:$receiptSha,
    createdAt:$createdAt
  }
' > "$expected_seal"
cmp -s "$expected_seal" "$seal_object" \
  || fail 'snapshot seal upload bytes do not match the exact fixture contract'
jq -se \
  --arg referenceSha "$reference_sha" \
  --arg receiptSha "$receipt_sha" \
  --arg createdAt "$receipt_created_at" '
  length == 1 and
  (.[0] |
    (keys | sort) == ([
      "createdAt", "datasetRelease", "snapshot",
      "snapshotReceiptSha256", "snapshotReferenceSha256", "schemaVersion"
    ] | sort) and
    .schemaVersion == 1 and
    .datasetRelease == "rehearsal-v20" and
    .snapshot == "airbob-rehearsal-v20" and
    .snapshotReferenceSha256 == $referenceSha and
    .snapshotReceiptSha256 == $receiptSha and
    .createdAt == $createdAt and
    (.createdAt | fromdateiso8601 | type == "number"))
' "$seal_object" >/dev/null || fail 'snapshot seal violates its exact contract'
grep -Fq -- '<s3api> <put-object>' "$aws_log" \
  || fail 'snapshot seal was not uploaded with the S3 object API'
grep -Fq -- '<--key> <elasticsearch/seals/rehearsal-v20.json>' "$aws_log" \
  || fail 'snapshot seal did not use the exact sibling key'
grep -Fq -- '<--if-none-match> <*> ' "$aws_log" \
  || fail 'snapshot seal upload was not conditional'
grep -Fq -- '<--server-side-encryption> <AES256>' "$aws_log" \
  || fail 'snapshot seal upload omitted AES256 encryption'
grep -Fq -- '<--content-type> <application/json>' "$aws_log" \
  || fail 'snapshot seal upload omitted its JSON content type'
grep -Fq -- '<s3api> <get-object>' "$aws_log" \
  || fail 'snapshot seal was not read back for byte verification'
reference_link_line=$(grep -nF 'link <snapshot-reference.json>' "$event_log" | cut -d: -f1)
receipt_link_line=$(grep -nF 'link <snapshot-receipt.json>' "$event_log" | cut -d: -f1)
seal_put_line=$(grep -nF 'seal-put' "$event_log" | cut -d: -f1)
final_preseal_assert_line=$(awk -v sealLine="$seal_put_line" '
  NR < sealLine && $0 == "lease-assert" { line = NR }
  END { print line + 0 }
' "$event_log")
[[ -n "$reference_link_line" && -n "$receipt_link_line" && -n "$seal_put_line" \
  && "$reference_link_line" -lt "$receipt_link_line" \
  && "$receipt_link_line" -lt "$final_preseal_assert_line" \
  && "$final_preseal_assert_line" -lt "$seal_put_line" ]] \
  || fail 'snapshot seal was not created after both links and final lease verification'

[[ "$(stat_mode "$reference")" == 600 && "$(stat_mode "$receipt")" == 600 ]] \
  || fail 'snapshot outputs must have mode 0600'

for secret in "$fixture_password" "$access_key" "$secret_key" "$session_token"; do
  ! grep -Fq -- "$secret" "$aws_log" "$docker_log" "$mysql_log" "$curl_log" \
    || fail 'a credential reached an argv or request log'
done
grep -Fq -- "keystore-add <s3.client.airbob_dataset_producer.access_key> <$access_key>" "$docker_secret_log" \
  || fail 'temporary access key was not installed through stdin'
grep -Fq -- "keystore-add <s3.client.airbob_dataset_producer.secret_key> <$secret_key>" "$docker_secret_log" \
  || fail 'temporary secret key was not installed through stdin'
grep -Fq -- "keystore-add <s3.client.airbob_dataset_producer.session_token> <$session_token>" "$docker_secret_log" \
  || fail 'temporary session token was not installed through stdin'

grep -Fq -- '"canned_acl":"bucket-owner-full-control"' "$curl_log" \
  || fail 'writer repository omitted bucket-owner-full-control'
grep -Fq -- '"server_side_encryption":true' "$curl_log" \
  || fail 'writer repository omitted S3 server-side encryption'
grep -Fq -- '"feature_states":["none"]' "$curl_log" \
  || fail 'snapshot included feature state'
grep -Fq -- '<GET> <http://127.0.0.1:9200/_alias/accommodations>' "$curl_log" \
  || fail 'producer did not resolve the managed accommodations write alias'
grep -Fq -- '"indices":"accommodations-v20260817001530"' "$curl_log" \
  || fail 'snapshot did not bind the resolved physical source index'
grep -Fq -- '"include_aliases":false' "$curl_log" \
  || fail 'verification restore retained source aliases'
grep -Fq -- '"partial":false' "$curl_log" || fail 'snapshot allowed partial completion'
grep -Fq -- '"include_global_state":false' "$curl_log" || fail 'snapshot included global state'
grep -Fq -- '"metadata":{"datasetRelease":"rehearsal-v20","datasetRunId":"20260817T001530Z-12345678","sourceReleasePayloadSha256":"' "$curl_log" \
  || fail 'snapshot metadata omitted the release lineage bindings'
grep -Fq -- '"index.blocks.write":false' "$curl_log" \
  || fail 'verification restore retained the frozen source write block'
grep -Fq -- '<DELETE> <http://127.0.0.1:9200/_snapshot/airbob-dataset-producer>' "$curl_log" \
  || fail 'writer repository was not unregistered'
grep -Fq -- '<PUT> <http://127.0.0.1:9200/_snapshot/airbob-dataset-readonly>' "$curl_log" \
  || fail 'read-only repository was not registered'
grep -Fq -- '<PUT> <http://127.0.0.1:9200/accommodations-v20260817001530/_settings> BODY <{"index":{"blocks.write":false}}>' "$curl_log" \
  || fail 'source index was not unfrozen after snapshot creation'
[[ $(grep -c '<remove> <s3.client.airbob_dataset_producer.' "$docker_log") -eq 3 ]] \
  || fail 'temporary Elasticsearch credentials were not removed'

if grep -Eq 's3 rm|delete-object|delete-objects' "$producer" "$aws_log"; then
  fail 'snapshot producer must never delete S3 data'
fi
if grep -Fq 'dynamodb delete-item' "$producer" "$aws_log"; then
  fail 'snapshot producer must retain the lease fencing-token row'
fi
grep -Fq -- '<airbob-performance-lab-orchestration-lease> <--key> <{"LockName":{"S":"airbob-dataset-snapshot/rehearsal-v20"}}>' "$aws_log" \
  || fail 'snapshot producer did not use the exact per-release lease row'
grep -Fq -- '":command":{"S":"dataset-snapshot"}' "$aws_log" \
  || fail 'snapshot producer did not use the dedicated lease command'
lease_acquire_line=$(grep -n -m1 'if_not_exists(#token, :zero) + :one' "$aws_log" | cut -d: -f1)
first_inventory_line=$(grep -n -m1 's3api> <list-object-versions' "$aws_log" | cut -d: -f1)
[[ -n "$lease_acquire_line" && -n "$first_inventory_line" && "$lease_acquire_line" -lt "$first_inventory_line" ]] \
  || fail 'snapshot lease was not acquired before the first S3 repository inventory'
grep -Fq -- '<--no-paginate>' "$aws_log" \
  || fail 'explicit version-inventory continuation must disable AWS CLI auto-pagination'
[[ $(grep -c '<--key-marker>' "$aws_log") -eq 2 ]] \
  || fail 'snapshot inventory did not traverse every explicit version page twice'
grep -Fq -- '{{json .Config.Env}}' "$docker_log" \
  || fail 'producer did not verify the local Elasticsearch S3 client region'
grep -Fq -- 's3.client.airbob_dataset_producer.region=ap-northeast-2' "$repo_root/docker-compose.yml" \
  || fail 'local Elasticsearch does not pin the dataset producer S3 client region'
grep -Fq -- 'image: ${ELASTICSEARCH_IMAGE:-airbob-elasticsearch-local}' "$repo_root/docker-compose.yml" \
  || fail 'local Elasticsearch cannot select the reviewed immutable producer image'

occupied_reference="$temp_dir/occupied-reference.json"
occupied_receipt="$temp_dir/occupied-receipt.json"
expect_failure occupied-prefix run_producer "$occupied_reference" "$occupied_receipt" FAKE_PREFIX_OCCUPIED=true
[[ ! -e "$occupied_reference" && ! -e "$occupied_receipt" ]] \
  || fail 'occupied prefix rejection wrote output files'

expired_reference="$temp_dir/expired-reference.json"
expired_receipt="$temp_dir/expired-receipt.json"
expect_failure expired-credentials run_producer "$expired_reference" "$expired_receipt" FAKE_EXPIRED_CREDENTIALS=true
[[ ! -e "$expired_reference" && ! -e "$expired_receipt" ]] \
  || fail 'expired credential rejection wrote output files'
grep -Fq 'semantic-lineage-verifier' "$mysql_log" \
  || fail 'expired credential rejection did not finish live lineage verification first'
! grep -Fq 'dynamodb update-item' "$aws_log" \
  || fail 'expired credential rejection reached the snapshot lease'
! grep -Fq 's3api' "$aws_log" \
  || fail 'expired credential rejection reached S3'
[[ ! -s "$curl_log" && ! -s "$docker_log" ]] \
  || fail 'expired credential rejection reached Elasticsearch mutation'

insufficient_headroom_reference="$temp_dir/insufficient-headroom-reference.json"
insufficient_headroom_receipt="$temp_dir/insufficient-headroom-receipt.json"
expect_failure insufficient-credential-headroom run_producer \
  "$insufficient_headroom_reference" "$insufficient_headroom_receipt" \
  FAKE_CREDENTIAL_LIFETIME_SECONDS=3290
grep -Fq 'temporary AWS credentials do not have 55 minutes of expiry headroom' \
  "$temp_dir/insufficient-credential-headroom.stderr" \
  || fail 'insufficient credential headroom missed the closed rejection reason'
[[ ! -e "$insufficient_headroom_reference" && ! -e "$insufficient_headroom_receipt" ]] \
  || fail 'insufficient credential headroom wrote output files'
! grep -Fq 'dynamodb update-item' "$aws_log" \
  || fail 'insufficient credential headroom reached the snapshot lease'
! grep -Fq 's3api' "$aws_log" \
  || fail 'insufficient credential headroom reached S3'
grep -Fq 'semantic-lineage-verifier' "$mysql_log" \
  || fail 'insufficient credential headroom did not finish live lineage verification first'
[[ ! -s "$curl_log" && ! -s "$docker_log" ]] \
  || fail 'insufficient credential headroom reached Elasticsearch mutation'

wrong_role_reference="$temp_dir/wrong-role-reference.json"
wrong_role_receipt="$temp_dir/wrong-role-receipt.json"
expect_failure wrong-role run_producer "$wrong_role_reference" "$wrong_role_receipt" FAKE_WRONG_ROLE=true
grep -Fq 'semantic-lineage-verifier' "$mysql_log" \
  || fail 'wrong-role rejection did not finish live lineage verification first'
! grep -Fq 'dynamodb update-item' "$aws_log" \
  || fail 'wrong-role rejection reached the snapshot lease'
! grep -Fq 's3api' "$aws_log" \
  || fail 'wrong-role rejection reached S3'
[[ ! -s "$curl_log" && ! -s "$docker_log" ]] \
  || fail 'wrong-role rejection reached Elasticsearch mutation'

lease_rejected_reference="$temp_dir/lease-rejected-reference.json"
lease_rejected_receipt="$temp_dir/lease-rejected-receipt.json"
expect_failure lease-rejected run_producer \
  "$lease_rejected_reference" "$lease_rejected_receipt" FAKE_LEASE_ACQUIRE_FAILURE=true
[[ ! -e "$lease_rejected_reference" && ! -e "$lease_rejected_receipt" ]] \
  || fail 'rejected lease acquisition wrote output files'
! grep -Fq 's3api' "$aws_log" || fail 'rejected lease acquisition reached S3'
grep -Fq 'semantic-lineage-verifier' "$mysql_log" \
  || fail 'rejected lease acquisition did not finish live lineage verification first'
[[ ! -s "$curl_log" && ! -s "$docker_log" ]] \
  || fail 'rejected lease acquisition reached Elasticsearch mutation'

multi_attestation="$temp_dir/multi-attestation.json"
{
  printf '%s\n' '{"password":"must-not-be-replayed"}'
  cat "$attestation"
} > "$multi_attestation"
multi_attestation_reference="$temp_dir/multi-attestation-reference.json"
multi_attestation_receipt="$temp_dir/multi-attestation-receipt.json"
PRODUCER_ATTESTATION="$multi_attestation" expect_failure multi-attestation \
  run_producer "$multi_attestation_reference" "$multi_attestation_receipt"
[[ ! -s "$aws_log" && ! -s "$curl_log" && ! -s "$docker_log" ]] \
  || fail 'multi-document attestation reached AWS or Elasticsearch mutation'

v2_attestation="$temp_dir/v2-attestation.json"
jq '.schemaVersion = 2' "$attestation" > "$v2_attestation"
v2_attestation_reference="$temp_dir/v2-attestation-reference.json"
v2_attestation_receipt="$temp_dir/v2-attestation-receipt.json"
PRODUCER_ATTESTATION="$v2_attestation" expect_failure v2-attestation \
  run_producer "$v2_attestation_reference" "$v2_attestation_receipt"
[[ ! -s "$aws_log" && ! -s "$curl_log" && ! -s "$docker_log" && ! -s "$mysql_log" ]] \
  || fail 'v2 attestation reached AWS or Elasticsearch mutation'
[[ ! -e "$v2_attestation_reference" && ! -e "$v2_attestation_receipt" ]] \
  || fail 'v2 attestation wrote producer outputs'

for missing_proof in distributionAssertionSha256 distributionSpecSha256; do
  missing_proof_attestation="$temp_dir/missing-$missing_proof-attestation.json"
  missing_proof_reference="$temp_dir/missing-$missing_proof-reference.json"
  missing_proof_receipt="$temp_dir/missing-$missing_proof-receipt.json"
  jq --arg field "$missing_proof" 'del(.[$field])' \
    "$attestation" > "$missing_proof_attestation"
  PRODUCER_ATTESTATION="$missing_proof_attestation" \
    expect_failure "missing-$missing_proof" \
    run_producer "$missing_proof_reference" "$missing_proof_receipt"
  [[ ! -e "$missing_proof_reference" && ! -e "$missing_proof_receipt" ]] \
    || fail 'missing distribution proof wrote producer outputs'
  [[ ! -s "$aws_log" && ! -s "$curl_log" && ! -s "$docker_log" && ! -s "$mysql_log" ]] \
    || fail 'missing distribution proof reached mutation or live verification'
done

wrong_restore_method_attestation="$temp_dir/wrong-restore-method-attestation.json"
jq '.databaseRestoreMethod = "unreviewed-restore-v1"' \
  "$attestation" > "$wrong_restore_method_attestation"
wrong_restore_method_reference="$temp_dir/wrong-restore-method-reference.json"
wrong_restore_method_receipt="$temp_dir/wrong-restore-method-receipt.json"
PRODUCER_ATTESTATION="$wrong_restore_method_attestation" expect_failure wrong-restore-method \
  run_producer "$wrong_restore_method_reference" "$wrong_restore_method_receipt"
[[ ! -s "$aws_log" && ! -s "$curl_log" && ! -s "$docker_log" && ! -s "$mysql_log" ]] \
  || fail 'wrong restore method reached AWS or Elasticsearch mutation'
[[ ! -e "$wrong_restore_method_reference" && ! -e "$wrong_restore_method_receipt" ]] \
  || fail 'wrong restore method wrote producer outputs'

mismatched_restored_dump_attestation="$temp_dir/mismatched-restored-dump-attestation.json"
jq '.restoredDumpSha256 = "9999999999999999999999999999999999999999999999999999999999999999"' \
  "$attestation" > "$mismatched_restored_dump_attestation"
mismatched_restored_dump_reference="$temp_dir/mismatched-restored-dump-reference.json"
mismatched_restored_dump_receipt="$temp_dir/mismatched-restored-dump-receipt.json"
PRODUCER_ATTESTATION="$mismatched_restored_dump_attestation" \
  expect_failure mismatched-restored-dump \
  run_producer "$mismatched_restored_dump_reference" "$mismatched_restored_dump_receipt"
[[ ! -s "$aws_log" && ! -s "$curl_log" && ! -s "$docker_log" && ! -s "$mysql_log" ]] \
  || fail 'mismatched restored dump reached AWS or Elasticsearch mutation'
[[ ! -e "$mismatched_restored_dump_reference" \
  && ! -e "$mismatched_restored_dump_receipt" ]] \
  || fail 'mismatched restored dump wrote producer outputs'

lineage_fields=(
  sourceEtlCommit
  databaseServerUuid
  verifierContractInventorySha256
  databaseFingerprintSha256
  finalWorldFingerprintSha256
  baseWorldFingerprintSha256
  distributionAssertionSha256
  distributionSpecSha256
  targetFingerprintSha256
  inventoryFingerprintSha256
)
lineage_values=(
  ffffffffffffffffffffffffffffffffffffffff
  11112233-4455-6677-8899-aabbccddeeff
  9999999999999999999999999999999999999999999999999999999999999999
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
)
for lineage_index in "${!lineage_fields[@]}"; do
  lineage_field=${lineage_fields[$lineage_index]}
  lineage_value=${lineage_values[$lineage_index]}
  lineage_attestation="$temp_dir/lineage-$lineage_field-attestation.json"
  lineage_reference="$temp_dir/lineage-$lineage_field-reference.json"
  lineage_receipt_output="$temp_dir/lineage-$lineage_field-receipt.json"
  jq --arg field "$lineage_field" --arg value "$lineage_value" \
    '.[$field] = $value' "$attestation" > "$lineage_attestation"
  PRODUCER_ATTESTATION="$lineage_attestation" \
    expect_failure "lineage-$lineage_field" \
    run_producer "$lineage_reference" "$lineage_receipt_output"
  [[ ! -e "$lineage_reference" && ! -e "$lineage_receipt_output" ]] \
    || fail 'lineage mismatch wrote producer outputs'
  ! grep -Fq 's3api' "$aws_log" \
    || fail 'lineage mismatch reached the S3 snapshot repository'
  [[ ! -s "$curl_log" && ! -s "$docker_log" ]] \
    || fail 'lineage mismatch reached Elasticsearch mutation'
  case "$lineage_field" in
    databaseServerUuid|verifierContractInventorySha256)
      [[ -s "$mysql_log" ]] || fail 'live-only lineage mismatch did not reach database verification'
      grep -Fq 'live database lineage differs from the dataset attestation' "$temp_dir/lineage-$lineage_field.stderr" \
        || fail 'live-only lineage mismatch missed the receipt comparison gate'
      [[ ! -s "$aws_log" ]] \
        || fail 'live-only lineage mismatch reached AWS before lineage verification completed'
      ;;
    *)
      [[ ! -s "$mysql_log" ]] || fail 'release-tuple mismatch reached live database verification'
      grep -Fq 'schema-4 semantic attestation does not bind the exact v2 ETL release tuple' "$temp_dir/lineage-$lineage_field.stderr" \
        || fail 'release-tuple mismatch missed the pre-lease semantic gate'
      ;;
  esac
done

multi_image_release="$temp_dir/multi-image-release.json"
{
  printf '%s\n' '{"password":"must-not-be-replayed"}'
  cat "$image_release"
} > "$multi_image_release"
multi_image_reference="$temp_dir/multi-image-reference.json"
multi_image_receipt="$temp_dir/multi-image-receipt.json"
PRODUCER_IMAGE_RELEASE="$multi_image_release" expect_failure multi-image-release \
  run_producer "$multi_image_reference" "$multi_image_receipt"
[[ ! -s "$aws_log" && ! -s "$curl_log" && ! -s "$docker_log" ]] \
  || fail 'multi-document image release reached AWS or Elasticsearch mutation'

occupied_keystore_reference="$temp_dir/occupied-keystore-reference.json"
occupied_keystore_receipt="$temp_dir/occupied-keystore-receipt.json"
expect_failure occupied-keystore run_producer \
  "$occupied_keystore_reference" "$occupied_keystore_receipt" FAKE_KEYSTORE_OCCUPIED=true
! grep -Fq '<remove> <s3.client.airbob_dataset_producer.' "$docker_log" \
  || fail 'keystore ownership rejection removed a caller-owned credential'

occupied_repository_reference="$temp_dir/occupied-repository-reference.json"
occupied_repository_receipt="$temp_dir/occupied-repository-receipt.json"
expect_failure occupied-repository run_producer \
  "$occupied_repository_reference" "$occupied_repository_receipt" FAKE_REPOSITORY_OCCUPIED=true

multiple_alias_reference="$temp_dir/multiple-alias-reference.json"
multiple_alias_receipt="$temp_dir/multiple-alias-receipt.json"
expect_failure multiple-alias-targets run_producer \
  "$multiple_alias_reference" "$multiple_alias_receipt" FAKE_ALIAS_MULTIPLE_TARGETS=true
! grep -Fq -- '<PUT> <http://127.0.0.1:9200/accommodations-v20260817001530/_settings>' "$curl_log" \
  || fail 'ambiguous accommodation alias reached source mutation'

non_write_alias_reference="$temp_dir/non-write-alias-reference.json"
non_write_alias_receipt="$temp_dir/non-write-alias-receipt.json"
expect_failure non-write-alias run_producer \
  "$non_write_alias_reference" "$non_write_alias_receipt" FAKE_ALIAS_NOT_WRITE=true
! grep -Fq -- '<PUT> <http://127.0.0.1:9200/accommodations-v20260817001530/_settings>' "$curl_log" \
  || fail 'non-write accommodation alias reached source mutation'

blocked_source_reference="$temp_dir/blocked-source-reference.json"
blocked_source_receipt="$temp_dir/blocked-source-receipt.json"
expect_failure preblocked-source run_producer \
  "$blocked_source_reference" "$blocked_source_receipt" FAKE_SOURCE_WRITE_BLOCKED=true
! grep -Fq -- '<PUT> <http://127.0.0.1:9200/accommodations-v20260817001530/_settings> BODY <{"index":{"blocks.write":false}}>' "$curl_log" \
  || fail 'source ownership rejection changed a caller-owned write block'

snapshot_failure_reference="$temp_dir/snapshot-failure-reference.json"
snapshot_failure_receipt="$temp_dir/snapshot-failure-receipt.json"
expect_failure snapshot-failure run_producer \
  "$snapshot_failure_reference" "$snapshot_failure_receipt" FAKE_SNAPSHOT_FAIL=true
grep -Fq -- '<PUT> <http://127.0.0.1:9200/accommodations-v20260817001530/_settings> BODY <{"index":{"blocks.write":false}}>' "$curl_log" \
  || fail 'snapshot failure did not unfreeze the source index'
[[ $(grep -c '<remove> <s3.client.airbob_dataset_producer.' "$docker_log") -eq 3 ]] \
  || fail 'snapshot failure did not remove temporary credentials'

snapshot_version_drift_reference="$temp_dir/snapshot-version-drift-reference.json"
snapshot_version_drift_receipt="$temp_dir/snapshot-version-drift-receipt.json"
expect_failure snapshot-index-version-drift run_producer \
  "$snapshot_version_drift_reference" "$snapshot_version_drift_receipt" \
  FAKE_SNAPSHOT_INDEX_VERSION_DRIFT=true
[[ ! -e "$snapshot_version_drift_reference" && ! -e "$snapshot_version_drift_receipt" ]] \
  || fail 'mismatched snapshot index version wrote producer outputs'

snapshot_version_id_drift_reference="$temp_dir/snapshot-version-id-drift-reference.json"
snapshot_version_id_drift_receipt="$temp_dir/snapshot-version-id-drift-receipt.json"
expect_failure snapshot-index-version-id-drift run_producer \
  "$snapshot_version_id_drift_reference" "$snapshot_version_id_drift_receipt" \
  FAKE_SNAPSHOT_INDEX_VERSION_ID_DRIFT=true
[[ ! -e "$snapshot_version_id_drift_reference" && ! -e "$snapshot_version_id_drift_receipt" ]] \
  || fail 'mismatched snapshot index version id wrote producer outputs'

heartbeat_reference="$temp_dir/heartbeat-failure-reference.json"
heartbeat_receipt="$temp_dir/heartbeat-failure-receipt.json"
expect_failure heartbeat-failure run_producer \
  "$heartbeat_reference" "$heartbeat_receipt" FAKE_HEARTBEAT_FAILURE=true
[[ -e "$heartbeat_snapshot_marker" && -e "$heartbeat_attempt_marker" ]] \
  || fail 'accelerated heartbeat did not overlap snapshot creation'
grep -Fq 'SET #heartbeat = :now, #expires = :expires' "$aws_log" \
  || fail 'snapshot heartbeat path was not exercised'
[[ ! -e "$heartbeat_reference" && ! -e "$heartbeat_receipt" ]] \
  || fail 'lost snapshot lease wrote producer outputs'
grep -Fq -- '<DELETE> <http://127.0.0.1:9200/_snapshot/airbob-dataset-producer>' "$curl_log" \
  || fail 'lost lease cleanup did not remove the writer repository'
grep -Fq -- '<PUT> <http://127.0.0.1:9200/accommodations-v20260817001530/_settings> BODY <{"index":{"blocks.write":false}}>' "$curl_log" \
  || fail 'lost lease cleanup did not unfreeze the source index'
[[ $(grep -c '<remove> <s3.client.airbob_dataset_producer.' "$docker_log") -eq 3 ]] \
  || fail 'lost lease cleanup did not remove all temporary credentials'
! grep -Fq 'SET #owner = :released' "$aws_log" \
  || fail 'lost snapshot lease was released after fencing failure'

final_fence_reference="$temp_dir/final-fence-reference.json"
final_fence_receipt="$temp_dir/final-fence-receipt.json"
expect_failure final-fence-after-output-links run_producer \
  "$final_fence_reference" "$final_fence_receipt" \
  FAKE_FENCE_AFTER_OUTPUT_LINKS=true
[[ ! -e "$final_fence_reference" && ! -e "$final_fence_receipt" ]] \
  || fail 'final fenced lease retained its newly linked outputs'
[[ ! -e "$seal_object" ]] \
  || fail 'final fenced lease created a remote seal fixture'
reference_link_line=$(grep -nF 'link <final-fence-reference.json>' "$event_log" | cut -d: -f1)
receipt_link_line=$(grep -nF 'link <final-fence-receipt.json>' "$event_log" | cut -d: -f1)
final_fence_line=$(grep -n -m1 -F 'lease-assert-fenced-after-output-links' \
  "$event_log" | cut -d: -f1)
[[ -n "$reference_link_line" && -n "$receipt_link_line" && -n "$final_fence_line" \
  && "$reference_link_line" -lt "$receipt_link_line" \
  && "$receipt_link_line" -lt "$final_fence_line" ]] \
  || fail 'final fencing token did not change specifically after both output links'
! grep -Fq 'seal-put' "$event_log" \
  || fail 'final fenced lease reached immutable seal creation'
! grep -Fq -- '<s3api> <put-object>' "$aws_log" \
  || fail 'final fenced lease called S3 PutObject'
! grep -Fq 'SET #owner = :released' "$aws_log" \
  || fail 'final fenced lease attempted a conditional release'
! grep -Eq 's3 rm|delete-object|delete-objects' "$aws_log" \
  || fail 'final fenced lease attempted destructive S3 cleanup'

timeout_reference="$temp_dir/timeout-reference.json"
timeout_receipt="$temp_dir/timeout-receipt.json"
expect_failure curl-timeout run_producer \
  "$timeout_reference" "$timeout_receipt" FAKE_CURL_TIMEOUT=true
[[ ! -e "$timeout_reference" && ! -e "$timeout_receipt" ]] \
  || fail 'timed-out Elasticsearch request wrote output files'
grep -Eq 'CONNECT <[1-9][0-9]*> MAX <[1-9][0-9]*>' "$curl_log" \
  || fail 'Elasticsearch request did not carry finite curl timeouts'
grep -Fq 'SET #owner = :released' "$aws_log" \
  || fail 'timed-out Elasticsearch request did not release its clean lease'

drift_reference="$temp_dir/drift-reference.json"
drift_receipt="$temp_dir/drift-receipt.json"
expect_failure source-drift run_producer "$drift_reference" "$drift_receipt" FAKE_ES_DRIFT=true

wrong_document_id_reference="$temp_dir/wrong-document-id-reference.json"
wrong_document_id_receipt="$temp_dir/wrong-document-id-receipt.json"
expect_failure wrong-document-id run_producer \
  "$wrong_document_id_reference" "$wrong_document_id_receipt" FAKE_WRONG_DOCUMENT_ID=true
[[ ! -e "$wrong_document_id_reference" && ! -e "$wrong_document_id_receipt" ]] \
  || fail 'mismatched Elasticsearch _id wrote snapshot outputs'

metadata_drift_reference="$temp_dir/metadata-drift-reference.json"
metadata_drift_receipt="$temp_dir/metadata-drift-receipt.json"
expect_failure snapshot-metadata-drift run_producer \
  "$metadata_drift_reference" "$metadata_drift_receipt" FAKE_SNAPSHOT_METADATA_DRIFT=true

lost_response_reference="$temp_dir/seal-lost-response-reference.json"
lost_response_receipt="$temp_dir/seal-lost-response-receipt.json"
run_producer "$lost_response_reference" "$lost_response_receipt" \
  FAKE_SEAL_APPLIED_RESPONSE_LOST=true >/dev/null
[[ -f "$lost_response_reference" && -f "$lost_response_receipt" && -f "$seal_object" ]] \
  || fail 'server-applied seal with a lost PutObject response did not recover its outputs'
lost_response_reference_sha=$(sha256_file "$lost_response_reference")
lost_response_receipt_sha=$(sha256_file "$lost_response_receipt")
jq -e \
  --arg referenceSha "$lost_response_reference_sha" \
  --arg receiptSha "$lost_response_receipt_sha" '
  .schemaVersion == 1 and
  .snapshotReferenceSha256 == $referenceSha and
  .snapshotReceiptSha256 == $receiptSha
' "$seal_object" >/dev/null \
  || fail 'lost PutObject response recovery accepted a seal for different local outputs'
grep -Fq 'seal-get-latest' "$event_log" \
  || fail 'lost PutObject response did not verify the exact latest seal'
! grep -Fq 'seal-get-version' "$event_log" \
  || fail 'lost PutObject response incorrectly assumed a returned seal version'
grep -Fq 'SET #owner = :released' "$aws_log" \
  || fail 'recovered lost PutObject response did not release its clean lease'

unconfirmed_response_reference="$temp_dir/seal-unconfirmed-response-reference.json"
unconfirmed_response_receipt="$temp_dir/seal-unconfirmed-response-receipt.json"
run_producer "$unconfirmed_response_reference" "$unconfirmed_response_receipt" \
  FAKE_SEAL_RESPONSE_UNCONFIRMED=true >/dev/null
[[ -f "$unconfirmed_response_reference" && -f "$unconfirmed_response_receipt" \
  && -f "$seal_object" ]] \
  || fail 'unconfirmed PutObject response did not recover its exact remote seal'
grep -Fq 'seal-get-latest' "$event_log" \
  || fail 'unconfirmed PutObject response did not verify the exact latest seal'
! grep -Fq 'seal-get-version' "$event_log" \
  || fail 'unconfirmed PutObject response used an unverified version id'

mismatched_seal_reference="$temp_dir/mismatched-seal-reference.json"
mismatched_seal_receipt="$temp_dir/mismatched-seal-receipt.json"
expect_failure mismatched-remote-seal run_producer \
  "$mismatched_seal_reference" "$mismatched_seal_receipt" \
  FAKE_SEAL_REMOTE_MISMATCH=true
[[ ! -e "$mismatched_seal_reference" && ! -e "$mismatched_seal_receipt" ]] \
  || fail 'mismatched remote seal retained newly linked outputs'
[[ -f "$seal_object" ]] \
  || fail 'mismatched remote seal fixture was not created'
grep -Fq 'seal-get-latest' "$event_log" \
  || fail 'mismatched remote seal was not read through the recovery path'
grep -Fq 'SET #owner = :released' "$aws_log" \
  || fail 'mismatched remote seal did not release its clean lease'
! grep -Eq 's3 rm|delete-object|delete-objects' "$aws_log" \
  || fail 'mismatched remote seal attempted destructive S3 cleanup'

seal_failure_reference="$temp_dir/seal-failure-reference.json"
seal_failure_receipt="$temp_dir/seal-failure-receipt.json"
expect_failure seal-upload-failure run_producer \
  "$seal_failure_reference" "$seal_failure_receipt" FAKE_SEAL_FAILURE=true
[[ ! -e "$seal_failure_reference" && ! -e "$seal_failure_receipt" ]] \
  || fail 'failed snapshot seal retained newly linked outputs'
[[ ! -e "$seal_object" ]] \
  || fail 'failed conditional seal upload created a remote object fixture'
grep -Fq 'seal-put' "$event_log" \
  || fail 'seal failure test did not reach the conditional upload'
grep -Fq 'seal-get-latest' "$event_log" \
  || fail 'failed seal upload did not check for an exact server-applied seal'
grep -Fq 'SET #owner = :released' "$aws_log" \
  || fail 'failed seal upload did not release its clean lease'
! grep -Eq 's3 rm|delete-object|delete-objects' "$aws_log" \
  || fail 'failed seal upload attempted destructive S3 cleanup'

readback_failure_reference="$temp_dir/seal-readback-failure-reference.json"
readback_failure_receipt="$temp_dir/seal-readback-failure-receipt.json"
expect_failure seal-readback-failure run_producer \
  "$readback_failure_reference" "$readback_failure_receipt" \
  FAKE_SEAL_GET_FAILURE=true
[[ ! -e "$readback_failure_reference" && ! -e "$readback_failure_receipt" \
  && -f "$seal_object" ]] \
  || fail 'unverified remote seal retained local outputs or lost its remote fixture'
grep -Fq 'seal-get-version' "$event_log" \
  || fail 'confirmed PutObject response did not use version-specific readback'
grep -Fq 'SET #owner = :released' "$aws_log" \
  || fail 'failed seal readback did not release its clean lease'
! grep -Eq 's3 rm|delete-object|delete-objects' "$aws_log" \
  || fail 'failed seal readback attempted destructive S3 cleanup'

release_failure_reference="$temp_dir/release-failure-reference.json"
release_failure_receipt="$temp_dir/release-failure-receipt.json"
expect_failure final-lease-release-failure run_producer \
  "$release_failure_reference" "$release_failure_receipt" \
  FAKE_LEASE_RELEASE_FAILURE=true
[[ -f "$release_failure_reference" && -f "$release_failure_receipt" \
  && -f "$seal_object" ]] \
  || fail 'successful seal did not preserve valid outputs after lease release failure'
release_failure_reference_sha=$(sha256_file "$release_failure_reference")
release_failure_receipt_sha=$(sha256_file "$release_failure_receipt")
jq -e \
  --arg referenceSha "$release_failure_reference_sha" \
  --arg receiptSha "$release_failure_receipt_sha" '
  .schemaVersion == 1 and
  .snapshotReferenceSha256 == $referenceSha and
  .snapshotReceiptSha256 == $receiptSha
' "$seal_object" >/dev/null \
  || fail 'preserved outputs do not match the successfully created seal'
grep -Fq 'warning: dataset snapshot lease release failed' \
  "$temp_dir/final-lease-release-failure.stderr" \
  || fail 'lease release failure did not surface the retained-lease warning'
! grep -Eq 's3 rm|delete-object|delete-objects' "$aws_log" \
  || fail 'post-seal lease failure attempted destructive S3 cleanup'

overwrite_reference="$temp_dir/existing-reference.json"
overwrite_receipt="$temp_dir/existing-receipt.json"
printf '%s\n' 'keep-reference' > "$overwrite_reference"
printf '%s\n' 'keep-receipt' > "$overwrite_receipt"
expect_failure overwrite run_producer "$overwrite_reference" "$overwrite_receipt"
[[ "$(cat "$overwrite_reference")" == keep-reference && "$(cat "$overwrite_receipt")" == keep-receipt ]] \
  || fail 'producer overwrote caller-owned output files'

printf '%s\n' 'Elasticsearch snapshot producer tests passed'
