#!/usr/bin/env bash
set -euo pipefail

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
capture="$repo_root/infra/aws/scripts/capture-dataset-attestation.sh"
verifier="$repo_root/infra/aws/scripts/verify-etl-release-database.sh"

fail() { printf 'dataset attestation test failed: %s\n' "$1" >&2; exit 1; }
assert_contains() { grep -Fq -- "$2" "$1" || fail "missing contract: $2"; }

[[ -x "$capture" && -x "$verifier" ]] || fail 'attestation scripts are missing'
bash -n "$capture"
bash -n "$verifier"

for contract in \
  'benchmark-dataset-v2.json' 'source-calibration-v1.json' 'production-skew-v1.json' \
  'generation-qualification-v1.json' 'SHA256SUMS must contain thirteen entries' \
  'release metadata is not exact v2' 'benchmark dataset semantics failed before restore' \
  'restore target is not the existing empty airbobdb schema' 'SET GLOBAL super_read_only=ON' \
  "time_zone_id NOT REGEXP '^[A-Za-z][A-Za-z0-9._+-]*(/[A-Za-z0-9._+-]+)*$'" \
  'published accommodation timezone contract is invalid' \
  'semantic DB receipt changed between two read-only passes' 'schema changed during capture' \
  'AIRBOB_DATASET_RELEASE_PROFILE="$profile_version"' \
  'table counts changed during capture' 'source release changed during capture' \
  'mktemp "$output_parent/.${output_name}.tmp.XXXXXX"' \
  'schemaVersion:4' 'databaseRestoreMethod:"gzip-to-empty-airbobdb-v2"' \
  'finalWorldFingerprintSha256' 'baseWorldFingerprintSha256' 'distributionEvidenceSha256' \
  'distributionAssertionSha256' 'distributionSpecSha256' \
  'targetFingerprintSha256' 'inventoryFingerprintSha256'; do
  assert_contains "$capture" "$contract"
done

first_verify=$(grep -n 'lineage_verifier.*release_dir.*lineage_one' "$capture" | head -1 | cut -d: -f1)
second_verify=$(grep -n 'lineage_verifier.*release_dir.*lineage_two' "$capture" | head -1 | cut -d: -f1)
output_link=$(grep -n 'ln "$output_temp" "$output_json"' "$capture" | head -1 | cut -d: -f1)
output_temp_unlink=$(grep -n 'rm -f "$output_temp"' "$capture" | tail -1 | cut -d: -f1)
output_temp_clear=$(grep -n "output_temp=''" "$capture" | tail -1 | cut -d: -f1)
[[ -n "$first_verify" && -n "$second_verify" && -n "$output_link" \
  && -n "$output_temp_unlink" && -n "$output_temp_clear" \
  && "$first_verify" -lt "$second_verify" && "$second_verify" -lt "$output_link" \
  && "$output_link" -lt "$output_temp_unlink" && "$output_temp_unlink" -lt "$output_temp_clear" ]] \
  || fail 'attestation is published before both semantic verification passes'
if grep -Fq 'output_temp="$work_dir/' "$capture"; then
  fail 'attestation temporary output can cross filesystems before hard-link promotion'
fi

dump_import=$(grep -n 'gzip -dc "$private_dump"' "$capture" | head -1 | cut -d: -f1)
read_only_transition=$(grep -n "restore_mysql enable-super-read-only 'SET GLOBAL super_read_only=ON;'" "$capture" | head -1 | cut -d: -f1)
import_failure=$(grep -n "fail 'exact dump import failed'" "$capture" | tail -1 | cut -d: -f1)
[[ -n "$dump_import" && -n "$read_only_transition" && -n "$import_failure" \
  && "$dump_import" -lt "$read_only_transition" && "$read_only_transition" -lt "$import_failure" ]] \
  || fail 'failed import can return before the restore target is made super-read-only'
assert_contains "$capture" 'dump_import_succeeded=false'
assert_contains "$capture" 'dump_import_succeeded=true'

# Exact schema-4 receipt shape rejects a copied/literal-only old receipt.
good=$(jq -nS '{schemaVersion:4,sourceReleasePayloadSha256:("1"*64),sourceDumpSha256:("2"*64),restoredDumpSha256:("2"*64),databaseRestoreMethod:"gzip-to-empty-airbobdb-v2",sourceDatabaseFingerprintSha256:("3"*64),sourceEtlCommit:("a"*40),databaseServerUuid:"00112233-4455-6677-8899-aabbccddeeff",verifierContractInventorySha256:("4"*64),databaseFingerprintSha256:("3"*64),verificationOutputSha256:("5"*64),finalWorldFingerprintSha256:("6"*64),baseWorldFingerprintSha256:("7"*64),distributionEvidenceSha256:("8"*64),distributionAssertionSha256:("d"*64),distributionSpecSha256:("e"*64),targetFingerprintSha256:("9"*64),inventoryFingerprintSha256:("0"*64),flywayVersion:"27",flywayHistoryRows:27,migrationChecksumSha256:("b"*64),schemaFingerprintSha256:("c"*64),outboxState:"empty",expectedTableRows:{accommodation_inventory_day:0,flyway_schema_history:27,outbox:0},capturedAt:"2026-08-17T00:00:00Z"}')
jq -e '
  (keys|sort)==(["schemaVersion","sourceReleasePayloadSha256","sourceDumpSha256","restoredDumpSha256","databaseRestoreMethod","sourceDatabaseFingerprintSha256","sourceEtlCommit","databaseServerUuid","verifierContractInventorySha256","databaseFingerprintSha256","verificationOutputSha256","finalWorldFingerprintSha256","baseWorldFingerprintSha256","distributionEvidenceSha256","distributionAssertionSha256","distributionSpecSha256","targetFingerprintSha256","inventoryFingerprintSha256","flywayVersion","flywayHistoryRows","migrationChecksumSha256","schemaFingerprintSha256","outboxState","expectedTableRows","capturedAt"]|sort) and .schemaVersion==4 and .sourceDumpSha256==.restoredDumpSha256 and .distributionAssertionSha256==("d"*64) and .distributionSpecSha256==("e"*64)
' <<< "$good" >/dev/null || fail 'schema-4 fixture is invalid'
for mutation in \
  'del(.distributionAssertionSha256)' \
  'del(.distributionSpecSha256)' \
  '.distributionAssertionSha256=("f"*64)' \
  '.distributionSpecSha256=("f"*64)'; do
  if jq "$mutation" <<< "$good" | jq -e '
    (keys|sort)==(["schemaVersion","sourceReleasePayloadSha256","sourceDumpSha256","restoredDumpSha256","databaseRestoreMethod","sourceDatabaseFingerprintSha256","sourceEtlCommit","databaseServerUuid","verifierContractInventorySha256","databaseFingerprintSha256","verificationOutputSha256","finalWorldFingerprintSha256","baseWorldFingerprintSha256","distributionEvidenceSha256","distributionAssertionSha256","distributionSpecSha256","targetFingerprintSha256","inventoryFingerprintSha256","flywayVersion","flywayHistoryRows","migrationChecksumSha256","schemaFingerprintSha256","outboxState","expectedTableRows","capturedAt"]|sort) and
    .schemaVersion==4 and .distributionAssertionSha256==("d"*64) and
    .distributionSpecSha256==("e"*64)
  ' >/dev/null 2>&1; then
    fail "missing or drifted distribution proof passed: $mutation"
  fi
done

printf '%s\n' 'dataset attestation tests passed'
