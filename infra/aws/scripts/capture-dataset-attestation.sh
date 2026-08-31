#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

usage() { printf 'usage: %s ETL_RELEASE_DIR OUTPUT_JSON\n' "${0##*/}" >&2; exit 64; }
fail() { printf 'dataset attestation capture failed: %s\n' "$1" >&2; exit 1; }
[[ "$#" -eq 2 ]] || usage
release_dir=$1
output_json=$2

required_environment=(AIRBOB_DATASET_ETL_REPOSITORY AIRBOB_DATASET_DB_HOST AIRBOB_DATASET_DB_PORT AIRBOB_DATASET_DB_USER AIRBOB_DATASET_DB_PASSWORD AIRBOB_DATASET_DB_RESTORE_USER AIRBOB_DATASET_DB_RESTORE_PASSWORD AIRBOB_DATASET_DB_NAME)
for name in "${required_environment[@]}"; do [[ -n "${!name:-}" ]] || fail "missing required database environment: $name"; done
database_password=$AIRBOB_DATASET_DB_PASSWORD
restore_user=$AIRBOB_DATASET_DB_RESTORE_USER
restore_password=$AIRBOB_DATASET_DB_RESTORE_PASSWORD
unset AIRBOB_DATASET_DB_PASSWORD AIRBOB_DATASET_DB_RESTORE_PASSWORD
[[ "$AIRBOB_DATASET_DB_NAME" == airbobdb ]] || fail 'database name must be airbobdb'
[[ "$AIRBOB_DATASET_DB_HOST" =~ ^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$ ]] || fail 'database host is invalid'
[[ "$AIRBOB_DATASET_DB_PORT" =~ ^[0-9]{1,5}$ ]] || fail 'database port is invalid'
((10#$AIRBOB_DATASET_DB_PORT >= 1 && 10#$AIRBOB_DATASET_DB_PORT <= 65535)) || fail 'database port is invalid'
[[ "$AIRBOB_DATASET_DB_USER" =~ ^[A-Za-z][A-Za-z0-9_]{0,31}$ && "$restore_user" =~ ^[A-Za-z][A-Za-z0-9_]{0,31}$ ]] || fail 'database user is invalid'
[[ "$restore_user" != "$AIRBOB_DATASET_DB_USER" && "$restore_password" != "$database_password" ]] || fail 'restore and attestation credentials must be distinct'
[[ "${AIRBOB_DATASET_DB_QUIESCED:-}" == true ]] || fail 'AIRBOB_DATASET_DB_QUIESCED=true is required'
for command_name in jq mysql sort find awk tail od tr mktemp chmod gzip date cmp; do
  command -v "$command_name" >/dev/null 2>&1 || fail "required command is unavailable: $command_name"
done
sha256_file() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'; else fail 'SHA-256 is required'; fi; }
has_final_newline() { [[ -s "$1" && "$(tail -c 1 "$1" | od -An -t x1 | tr -d '[:space:]')" == 0a ]]; }
require_file() { [[ -f "$1" && ! -L "$1" ]] || fail "artifact is missing or unsafe: ${1##*/}"; }

[[ -d "$release_dir" && ! -L "$release_dir" ]] || fail 'ETL release directory is missing or unsafe'
release_dir=$(CDPATH= cd -P -- "$release_dir" && pwd -P)
output_parent=${output_json%/*}; output_name=${output_json##*/}; [[ "$output_parent" != "$output_json" ]] || output_parent=.
[[ -n "$output_name" && "$output_name" != . && "$output_name" != .. && -d "$output_parent" && ! -L "$output_parent" ]] || fail 'output path is unsafe'
[[ ! -e "$output_json" && ! -L "$output_json" ]] || fail 'attestation output already exists'
output_parent=$(CDPATH= cd -P -- "$output_parent" && pwd -P)
case "$output_parent/" in "$release_dir/"*) fail 'attestation output must be outside the source release' ;; esac
output_json="$output_parent/$output_name"

require_file "$release_dir/release-metadata.txt"
metadata_value() { awk -F= -v target="$1" '$1==target{count++;value=substr($0,index($0,"=")+1)} END{if(count==1)print value}' "$release_dir/release-metadata.txt"; }
production_spec_name=$(metadata_value production_spec)
case "$production_spec_name" in
  production-skew-v1.json)
    profile_version=production-skew-v1
    expected_budgets='{"accommodations":50000,"activeWishlists":400000,"members":200000,"reservations":2500000,"reviews":1000000,"wishlistLinks":1500000}'
    ;;
  production-skew-large-v1.json)
    profile_version=production-skew-large-v1
    expected_budgets='{"accommodations":200000,"activeWishlists":1600000,"members":800000,"reservations":10000000,"reviews":4000000,"wishlistLinks":6000000}'
    ;;
  *) fail 'release metadata selects an unsupported production profile' ;;
esac

release_files=(PROVENANCE.txt SHA256SUMS airbob-production-seed.sql.gz backend-migrations.sha256 benchmark-dataset-v2.json benchmark-fixture.json database-fingerprint.tsv etl-code.sha256 generation-qualification-v1.json "$production_spec_name" release-metadata.txt source-calibration-v1.json source.sha256 traffic-v1.json)
checksummed_files=(PROVENANCE.txt airbob-production-seed.sql.gz backend-migrations.sha256 benchmark-dataset-v2.json benchmark-fixture.json database-fingerprint.tsv etl-code.sha256 generation-qualification-v1.json "$production_spec_name" release-metadata.txt source-calibration-v1.json source.sha256 traffic-v1.json)
actual_inventory=$(find "$release_dir" -mindepth 1 -maxdepth 1 -exec basename {} \; | sort)
expected_inventory=$(printf '%s\n' "${release_files[@]}" | sort)
[[ "$actual_inventory" == "$expected_inventory" ]] || fail 'ETL release inventory is not exact v2'
for file in "${release_files[@]}"; do require_file "$release_dir/$file"; done
gzip -t "$release_dir/airbob-production-seed.sql.gz" >/dev/null 2>&1 || fail 'database dump is not valid gzip'

checksum_file="$release_dir/SHA256SUMS"
has_final_newline "$checksum_file" || fail 'SHA256SUMS is not newline terminated'
[[ "$(wc -l < "$checksum_file" | tr -d '[:space:]')" == 13 ]] || fail 'SHA256SUMS must contain thirteen entries'
checksum_index=0
while IFS= read -r line; do
  expected_name=${checksummed_files[$checksum_index]}
  [[ "$line" =~ ^([0-9a-f]{64})\ \ ([A-Za-z0-9._-]+)$ && "${BASH_REMATCH[2]}" == "$expected_name" ]] || fail 'SHA256SUMS is malformed or reordered'
  [[ "$(sha256_file "$release_dir/$expected_name")" == "${BASH_REMATCH[1]}" ]] || fail "source checksum mismatch: $expected_name"
  checksum_index=$((checksum_index + 1))
done < "$checksum_file"
checksum_digest() { awk -v target="$1" '$2==target{count++;value=$1} END{if(count==1)print value}' "$checksum_file"; }
source_release_payload_sha256=$(sha256_file "$checksum_file")
source_dump_sha256=$(checksum_digest airbob-production-seed.sql.gz)
source_database_fingerprint_sha256=$(checksum_digest database-fingerprint.tsv)
source_distribution_spec_sha256=$(checksum_digest "$production_spec_name")
source_distribution_assertion_sha256=$(jq -er \
  '.world.provenance.assertionSha256 | select(test("^[0-9a-f]{64}$"))' \
  "$release_dir/benchmark-dataset-v2.json") \
  || fail 'manifest distribution assertion seal is missing'

[[ "$(wc -l < "$release_dir/release-metadata.txt" | tr -d '[:space:]')" == 43 && "$(metadata_value format)" == airbob-production-seed-release-v2 && \
   "$(metadata_value benchmark_dataset_manifest)" == benchmark-dataset-v2.json && "$(metadata_value benchmark_dataset_version)" == benchmark-dataset-v2 && \
   "$(metadata_value world_version)" == world-v2 && "$(metadata_value source_calibration)" == source-calibration-v1.json && \
   "$(metadata_value production_spec)" == "$production_spec_name" && "$(metadata_value generation_qualification)" == generation-qualification-v1.json ]] || fail 'release metadata is not exact v2'
[[ "$(metadata_value production_spec_sha256)" == "$source_distribution_spec_sha256" ]] \
  || fail 'release metadata production spec digest is not checksum-bound'

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
semantic_validator="$script_dir/validate-benchmark-dataset-v2.jq"
lineage_verifier="$script_dir/verify-etl-release-database.sh"
require_file "$semantic_validator"
[[ -x "$lineage_verifier" && ! -L "$lineage_verifier" ]] || fail 'database verifier is unavailable'
jq -e -f "$semantic_validator" "$release_dir/benchmark-dataset-v2.json" >/dev/null || fail 'benchmark dataset semantics failed before restore'
jq -e --arg profile "$profile_version" --arg calibration "$(checksum_digest source-calibration-v1.json)" --arg spec "$(checksum_digest "$production_spec_name")" '
  .datasetVersion=="benchmark-dataset-v2" and .world.version=="world-v2" and
  .world.provenance.profileVersion==$profile and
  .world.provenance.calibrationSha256==$calibration and .world.provenance.specSha256==$spec and
  .world.tableRows.accommodation_inventory_day==0 and
  (.world.scopeRanges|keys)==["accommodation","member","payment","payment-transaction","reservation","review","wishlist","wishlist-accommodation"] and
  all(.world.scopeRanges|to_entries[];.key==.value.id and .value.rowCount==(.value.maximumId-.value.minimumId+1))
' "$release_dir/benchmark-dataset-v2.json" >/dev/null || fail 'manifest provenance or base scopes drifted'
jq -e --arg profile "$profile_version" --argjson budgets "$expected_budgets" '
  .profileVersion==$profile and .provenance.generatorVersion=="production-skew-generator-v1" and
  .provenance.prngAlgorithm=="sha256-splitmix64-counter-v1" and
  .provenance.seedDerivation=="length-prefixed(profile-version, global-seed, relation-domain, stable-external-key, counter)" and
  .provenance.globalSeed==20260826 and .provenance.anchor=="2026-07-31T15:00:00Z" and .provenance.timezone=="Asia/Seoul" and
  (.targets|{accommodations:.accommodations.rowBudget,members:.members.rowBudget,reservations:.reservations.rowBudget,reviews:.reviews.rowBudget,activeWishlists:.activeWishlists.rowBudget,wishlistLinks:.wishlistLinks.rowBudget})==$budgets and
  ([.targets[]|select(.rowBudget!=null)|.tolerance]|all(.absoluteRows==0 and .relativePercent==0))
' "$release_dir/$production_spec_name" >/dev/null || fail 'production distribution profile contract failed'
jq -e --argjson budgets "$expected_budgets" '
  .version=="generation-qualification-v1" and .canonicalScale==true and .generatedBudgets==$budgets
' "$release_dir/generation-qualification-v1.json" >/dev/null || fail 'generation qualification profile budgets drifted'

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-v2-attestation.XXXXXX") || fail 'cannot create workspace'
output_temp=''
cleanup() { unset MYSQL_PWD database_password restore_password restore_user; [[ -z "$output_temp" ]] || rm -f "$output_temp"; rm -rf "$work_dir"; }
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

restore_mysql() {
  local label=$1 sql=$2 output=$3
  printf '%s\n' "$sql" | MYSQL_PWD="$restore_password" mysql --protocol=TCP --default-character-set=utf8mb4 --host="$AIRBOB_DATASET_DB_HOST" --port="$AIRBOB_DATASET_DB_PORT" --user="$restore_user" --batch --raw --skip-column-names "$AIRBOB_DATASET_DB_NAME" > "$output" 2>/dev/null || fail "restore query failed: $label"
}
restore_target="$work_dir/restore-target.tsv"
restore_mysql empty-target "SELECT LOWER(@@server_uuid),(SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='airbobdb'),(SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='airbobdb' AND TABLE_TYPE='BASE TABLE'),(SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA='airbobdb'),(SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA='airbobdb'),(SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA='airbobdb'),(SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='airbobdb');" "$restore_target"
IFS=$'\t' read -r restored_server schema_count table_count view_count routine_count event_count trigger_count extra < "$restore_target"
[[ -z "${extra:-}" && "$restored_server" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ && "$schema_count" == 1 && "$table_count" == 0 && "$view_count" == 0 && "$routine_count" == 0 && "$event_count" == 0 && "$trigger_count" == 0 ]] || fail 'restore target is not the existing empty airbobdb schema'
private_dump="$work_dir/airbob-production-seed.sql.gz"
cp "$release_dir/airbob-production-seed.sql.gz" "$private_dump"; chmod 600 "$private_dump"
[[ "$(sha256_file "$private_dump")" == "$source_dump_sha256" ]] || fail 'private dump copy drifted'
dump_import_succeeded=false
if gzip -dc "$private_dump" 2>/dev/null \
  | MYSQL_PWD="$restore_password" mysql --protocol=TCP --default-character-set=utf8mb4 \
    --host="$AIRBOB_DATASET_DB_HOST" --port="$AIRBOB_DATASET_DB_PORT" --user="$restore_user" \
    --batch --raw --skip-column-names "$AIRBOB_DATASET_DB_NAME" >/dev/null 2>/dev/null; then
  dump_import_succeeded=true
fi
read_only_output="$work_dir/read-only.out"
restore_mysql enable-super-read-only 'SET GLOBAL super_read_only=ON;' "$read_only_output"
[[ ! -s "$read_only_output" && "$(sha256_file "$private_dump")" == "$source_dump_sha256" ]] || fail 'read-only transition or dump stability failed'
[[ "$dump_import_succeeded" == true ]] || fail 'exact dump import failed'
invalid_published_timezone_file="$work_dir/invalid-published-timezone-count.txt"
restore_mysql published-timezone-contract "
  SELECT COUNT(*)
  FROM accommodation
  WHERE status = 'PUBLISHED'
    AND (
      time_zone_id IS NULL
      OR TRIM(time_zone_id) = ''
      OR time_zone_id NOT REGEXP '^[A-Za-z][A-Za-z0-9._+-]*(/[A-Za-z0-9._+-]+)*$'
    );
" "$invalid_published_timezone_file"
[[ "$(wc -l < "$invalid_published_timezone_file" | tr -d '[:space:]')" == 1 \
  && "$(sed -n '1p' "$invalid_published_timezone_file")" == 0 ]] \
  || fail 'published accommodation timezone contract is invalid'
unset restore_password restore_user AIRBOB_DATASET_DB_RESTORE_PASSWORD AIRBOB_DATASET_DB_RESTORE_USER

lineage_one="$work_dir/lineage-one.json"
lineage_two="$work_dir/lineage-two.json"
AIRBOB_DATASET_RELEASE_PROFILE="$profile_version" AIRBOB_DATASET_DB_PASSWORD="$database_password" \
  "$lineage_verifier" "$release_dir" > "$lineage_one" || fail 'live DB semantic verification failed'
AIRBOB_DATASET_RELEASE_PROFILE="$profile_version" AIRBOB_DATASET_DB_PASSWORD="$database_password" \
  "$lineage_verifier" "$release_dir" > "$lineage_two" || fail 'second live DB semantic verification failed'
cmp -s "$lineage_one" "$lineage_two" || fail 'semantic DB receipt changed between two read-only passes'
jq -e --arg assertion "$source_distribution_assertion_sha256" \
  --arg spec "$source_distribution_spec_sha256" '
  (keys|sort)==(["schemaVersion","sourceEtlCommit","databaseServerUuid","verifierContractInventorySha256","databaseFingerprintSha256","verificationOutputSha256","finalWorldFingerprintSha256","baseWorldFingerprintSha256","distributionEvidenceSha256","distributionAssertionSha256","distributionSpecSha256","targetFingerprintSha256","inventoryFingerprintSha256"]|sort) and
  .schemaVersion==2 and
  (.distributionAssertionSha256|type=="string" and test("^[0-9a-f]{64}$")) and
  (.distributionSpecSha256|type=="string" and test("^[0-9a-f]{64}$")) and
  .distributionAssertionSha256==$assertion and .distributionSpecSha256==$spec
' "$lineage_one" >/dev/null || fail 'database verifier receipt schema is invalid'
[[ "$(jq -r '.databaseServerUuid' "$lineage_one")" == "$restored_server" && "$(jq -r '.databaseFingerprintSha256' "$lineage_one")" == "$source_database_fingerprint_sha256" ]] || fail 'database verifier receipt does not bind the restore target'

mysql_query() {
  local label=$1 sql=$2 output=$3
  printf '%s\n' "$sql" | MYSQL_PWD="$database_password" mysql --protocol=TCP --default-character-set=utf8mb4 --host="$AIRBOB_DATASET_DB_HOST" --port="$AIRBOB_DATASET_DB_PORT" --user="$AIRBOB_DATASET_DB_USER" --batch --raw --skip-column-names "$AIRBOB_DATASET_DB_NAME" > "$output" 2>/dev/null || fail "attestation query failed: $label"
}
migration_sql="SELECT installed_rank,COALESCE(version,'<NULL>'),description,type,script,COALESCE(checksum,'<NULL>'),success FROM flyway_schema_history ORDER BY installed_rank;"
migration_one="$work_dir/migrations-one.tsv"; migration_two="$work_dir/migrations-two.tsv"
mysql_query migrations-one "$migration_sql" "$migration_one"; mysql_query migrations-two "$migration_sql" "$migration_two"
cmp -s "$migration_one" "$migration_two" || fail 'Flyway history changed during capture'
[[ "$(wc -l < "$migration_one" | tr -d '[:space:]')" == 27 ]] || fail 'Flyway history is not V1-V27 exact'
migration_checksum_sha256=$(sha256_file "$migration_one")

schema_sql="SELECT 'COLUMN',HEX(TABLE_NAME),HEX(COLUMN_NAME),HEX(CAST(ORDINAL_POSITION AS CHAR)),HEX(COLUMN_NAME),HEX(COLUMN_TYPE),HEX(IS_NULLABLE),COALESCE(HEX(CAST(COLUMN_DEFAULT AS CHAR)),'<NULL>'),HEX(EXTRA),COALESCE(HEX(COLLATION_NAME),'<NULL>'),COALESCE(HEX(CHARACTER_SET_NAME),'<NULL>'),COALESCE(HEX(GENERATION_EXPRESSION),'<NULL>') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='airbobdb' UNION ALL SELECT 'INDEX',HEX(TABLE_NAME),HEX(INDEX_NAME),HEX(CAST(SEQ_IN_INDEX AS CHAR)),COALESCE(HEX(COLUMN_NAME),'<NULL>'),HEX(CAST(NON_UNIQUE AS CHAR)),COALESCE(HEX(COLLATION),'<NULL>'),COALESCE(HEX(CAST(SUB_PART AS CHAR)),'<NULL>'),HEX(NULLABLE),HEX(INDEX_TYPE),HEX(IS_VISIBLE),COALESCE(HEX(EXPRESSION),'<NULL>') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='airbobdb' UNION ALL SELECT 'CONSTRAINT',HEX(tc.TABLE_NAME),HEX(tc.CONSTRAINT_NAME),COALESCE(HEX(CAST(kcu.ORDINAL_POSITION AS CHAR)),'<NULL>'),COALESCE(HEX(kcu.COLUMN_NAME),'<NULL>'),HEX(tc.CONSTRAINT_TYPE),COALESCE(HEX(CAST(kcu.POSITION_IN_UNIQUE_CONSTRAINT AS CHAR)),'<NULL>'),COALESCE(HEX(kcu.REFERENCED_TABLE_SCHEMA),'<NULL>'),COALESCE(HEX(kcu.REFERENCED_TABLE_NAME),'<NULL>'),COALESCE(HEX(kcu.REFERENCED_COLUMN_NAME),'<NULL>'),HEX(tc.ENFORCED),'<NULL>' FROM information_schema.TABLE_CONSTRAINTS tc LEFT JOIN information_schema.KEY_COLUMN_USAGE kcu ON kcu.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND kcu.TABLE_NAME=tc.TABLE_NAME AND kcu.CONSTRAINT_NAME=tc.CONSTRAINT_NAME WHERE tc.CONSTRAINT_SCHEMA='airbobdb' UNION ALL SELECT 'REFERENTIAL',HEX(TABLE_NAME),HEX(CONSTRAINT_NAME),'<NULL>','<NULL>',HEX(UNIQUE_CONSTRAINT_SCHEMA),HEX(UNIQUE_CONSTRAINT_NAME),HEX(MATCH_OPTION),HEX(UPDATE_RULE),HEX(DELETE_RULE),HEX(REFERENCED_TABLE_NAME),'<NULL>' FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='airbobdb' UNION ALL SELECT 'CHECK',HEX(tc.TABLE_NAME),HEX(cc.CONSTRAINT_NAME),'<NULL>','<NULL>',HEX(cc.CHECK_CLAUSE),HEX(tc.ENFORCED),'<NULL>','<NULL>','<NULL>','<NULL>','<NULL>' FROM information_schema.CHECK_CONSTRAINTS cc JOIN information_schema.TABLE_CONSTRAINTS tc ON tc.CONSTRAINT_SCHEMA=cc.CONSTRAINT_SCHEMA AND tc.CONSTRAINT_NAME=cc.CONSTRAINT_NAME AND tc.CONSTRAINT_TYPE='CHECK' WHERE cc.CONSTRAINT_SCHEMA='airbobdb';"
schema_one_raw="$work_dir/schema-one.raw"; schema_two_raw="$work_dir/schema-two.raw"; schema_one="$work_dir/schema-one.tsv"; schema_two="$work_dir/schema-two.tsv"
mysql_query schema-one "$schema_sql" "$schema_one_raw"; mysql_query schema-two "$schema_sql" "$schema_two_raw"
sort "$schema_one_raw" > "$schema_one"; sort "$schema_two_raw" > "$schema_two"; cmp -s "$schema_one" "$schema_two" || fail 'schema changed during capture'
schema_fingerprint_sha256=$(sha256_file "$schema_one")

tables_one="$work_dir/tables-one.tsv"; tables_two="$work_dir/tables-two.tsv"
table_sql="SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='airbobdb' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;"
mysql_query tables-one "$table_sql" "$tables_one"; mysql_query tables-two "$table_sql" "$tables_two"; cmp -s "$tables_one" "$tables_two" || fail 'table inventory changed during capture'
rows_one="$work_dir/rows-one.tsv"; rows_two="$work_dir/rows-two.tsv"; : > "$rows_one"; : > "$rows_two"
while IFS= read -r table; do
  [[ "$table" =~ ^[a-z][a-z0-9_]{0,63}$ ]] || fail 'unsafe table name returned'
  printf '%s\t%s\n' "$table" "$(mysql_query row-one "SELECT COUNT(*) FROM \`$table\`;" "$work_dir/count"; cat "$work_dir/count")" >> "$rows_one"
  printf '%s\t%s\n' "$table" "$(mysql_query row-two "SELECT COUNT(*) FROM \`$table\`;" "$work_dir/count"; cat "$work_dir/count")" >> "$rows_two"
done < "$tables_one"
cmp -s "$rows_one" "$rows_two" || fail 'table counts changed during capture'
expected_rows=$(jq -Rn '[inputs|split("\t")|{key:.[0],value:(.[1]|tonumber)}]|from_entries' < "$rows_one")
[[ "$(jq -r '.accommodation_inventory_day' <<< "$expected_rows")" == 0 && "$(jq -r '.outbox' <<< "$expected_rows")" == 0 && "$(jq -r '.flyway_schema_history' <<< "$expected_rows")" == 27 ]] || fail 'inventory, outbox, or Flyway row invariant failed'

server_after="$work_dir/server-after.tsv"
mysql_query server-after 'SELECT LOWER(@@server_uuid),@@GLOBAL.read_only,@@GLOBAL.super_read_only;' "$server_after"
[[ "$(cat "$server_after")" == "$restored_server"$'\t1\t1' ]] || fail 'server identity or read-only state changed'
[[ "$(sha256_file "$checksum_file")" == "$source_release_payload_sha256" && "$(sha256_file "$release_dir/database-fingerprint.tsv")" == "$source_database_fingerprint_sha256" ]] || fail 'source release changed during capture'

captured_at=${AIRBOB_DATASET_CAPTURED_AT:-$(date -u '+%Y-%m-%dT%H:%M:%SZ')}
jq -en --arg captured "$captured_at" '$captured|fromdateiso8601' >/dev/null || fail 'capture timestamp is invalid'
output_temp=$(mktemp "$output_parent/.${output_name}.tmp.XXXXXX") \
  || fail 'cannot create same-filesystem attestation temporary output'
chmod 600 "$output_temp"
jq -nS \
  --arg sourceReleasePayloadSha256 "$source_release_payload_sha256" --arg sourceDumpSha256 "$source_dump_sha256" \
  --arg sourceDatabaseFingerprintSha256 "$source_database_fingerprint_sha256" \
  --arg sourceEtlCommit "$(jq -r '.sourceEtlCommit' "$lineage_one")" --arg databaseServerUuid "$restored_server" \
  --arg verifierContractInventorySha256 "$(jq -r '.verifierContractInventorySha256' "$lineage_one")" \
  --arg databaseFingerprintSha256 "$(jq -r '.databaseFingerprintSha256' "$lineage_one")" \
  --arg verificationOutputSha256 "$(jq -r '.verificationOutputSha256' "$lineage_one")" \
  --arg finalWorldFingerprintSha256 "$(jq -r '.finalWorldFingerprintSha256' "$lineage_one")" \
  --arg baseWorldFingerprintSha256 "$(jq -r '.baseWorldFingerprintSha256' "$lineage_one")" \
  --arg distributionEvidenceSha256 "$(jq -r '.distributionEvidenceSha256' "$lineage_one")" \
  --arg distributionAssertionSha256 "$(jq -r '.distributionAssertionSha256' "$lineage_one")" \
  --arg distributionSpecSha256 "$(jq -r '.distributionSpecSha256' "$lineage_one")" \
  --arg targetFingerprintSha256 "$(jq -r '.targetFingerprintSha256' "$lineage_one")" \
  --arg inventoryFingerprintSha256 "$(jq -r '.inventoryFingerprintSha256' "$lineage_one")" \
  --arg migrationChecksumSha256 "$migration_checksum_sha256" --arg schemaFingerprintSha256 "$schema_fingerprint_sha256" \
  --arg capturedAt "$captured_at" --argjson expectedTableRows "$expected_rows" '
  {schemaVersion:4,sourceReleasePayloadSha256:$sourceReleasePayloadSha256,sourceDumpSha256:$sourceDumpSha256,
   restoredDumpSha256:$sourceDumpSha256,databaseRestoreMethod:"gzip-to-empty-airbobdb-v2",
   sourceDatabaseFingerprintSha256:$sourceDatabaseFingerprintSha256,sourceEtlCommit:$sourceEtlCommit,
   databaseServerUuid:$databaseServerUuid,verifierContractInventorySha256:$verifierContractInventorySha256,
   databaseFingerprintSha256:$databaseFingerprintSha256,verificationOutputSha256:$verificationOutputSha256,
   finalWorldFingerprintSha256:$finalWorldFingerprintSha256,baseWorldFingerprintSha256:$baseWorldFingerprintSha256,
   distributionEvidenceSha256:$distributionEvidenceSha256,
   distributionAssertionSha256:$distributionAssertionSha256,distributionSpecSha256:$distributionSpecSha256,
   targetFingerprintSha256:$targetFingerprintSha256,
   inventoryFingerprintSha256:$inventoryFingerprintSha256,flywayVersion:"27",flywayHistoryRows:27,
   migrationChecksumSha256:$migrationChecksumSha256,schemaFingerprintSha256:$schemaFingerprintSha256,
   outboxState:"empty",expectedTableRows:$expectedTableRows,capturedAt:$capturedAt}
' > "$output_temp"
ln "$output_temp" "$output_json" 2>/dev/null || fail 'attestation output already exists or cannot be created'
if ! rm -f "$output_temp"; then
  rm -f "$output_json" 2>/dev/null || true
  fail 'attestation temporary output cannot be removed after promotion'
fi
output_temp=''
trap - EXIT HUP INT TERM
cleanup
printf '%s\n' 'dataset attestation captured'
