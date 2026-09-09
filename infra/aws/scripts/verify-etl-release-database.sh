#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

usage() { printf 'usage: %s ETL_RELEASE_DIR\n' "${0##*/}" >&2; exit 64; }
fail() { printf 'ETL release database verification failed: %s\n' "$1" >&2; exit 1; }
[[ "$#" -eq 1 ]] || usage
release_dir=$1

required_environment=(AIRBOB_DATASET_ETL_REPOSITORY AIRBOB_DATASET_DB_HOST AIRBOB_DATASET_DB_PORT AIRBOB_DATASET_DB_USER AIRBOB_DATASET_DB_PASSWORD AIRBOB_DATASET_DB_NAME)
for name in "${required_environment[@]}"; do [[ -n "${!name:-}" ]] || fail "missing required environment: $name"; done
database_password=$AIRBOB_DATASET_DB_PASSWORD
unset AIRBOB_DATASET_DB_PASSWORD
[[ "${AIRBOB_DATASET_DB_QUIESCED:-}" == true ]] || fail 'AIRBOB_DATASET_DB_QUIESCED=true is required'
verification_mode=${AIRBOB_DATASET_DB_VERIFICATION_MODE:-read-only-restore}
case "$verification_mode" in
  read-only-restore)
    [[ "$AIRBOB_DATASET_DB_NAME" == airbobdb ]] || fail 'database name must be airbobdb'
    ;;
  isolated-temporary-schema)
    [[ "$AIRBOB_DATASET_DB_NAME" =~ ^airbob_verify_[0-9]{14}_[0-9a-f]{12}_[0-9]{1,10}$ \
      && ${#AIRBOB_DATASET_DB_NAME} -le 64 ]] || fail 'temporary verification schema name is invalid'
    ;;
  *) fail 'database verification mode is invalid' ;;
esac
[[ "$AIRBOB_DATASET_DB_HOST" =~ ^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$ ]] || fail 'database host is invalid'
[[ "$AIRBOB_DATASET_DB_PORT" =~ ^[0-9]{1,5}$ ]] || fail 'database port is invalid'
((10#$AIRBOB_DATASET_DB_PORT >= 1 && 10#$AIRBOB_DATASET_DB_PORT <= 65535)) || fail 'database port is invalid'
[[ "$AIRBOB_DATASET_DB_USER" =~ ^[A-Za-z][A-Za-z0-9_]{0,31}$ ]] || fail 'database user is invalid'
for command_name in git jq mysql awk grep sort cmp od tr tail wc mktemp xxd; do
  command -v "$command_name" >/dev/null 2>&1 || fail "required command is unavailable: $command_name"
done

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else fail 'a SHA-256 implementation is required'; fi
}
sha256_stream() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 | awk '{print $1}'
  else fail 'a SHA-256 implementation is required'; fi
}
has_final_newline() { [[ -s "$1" && "$(tail -c 1 "$1" | od -An -t x1 | tr -d '[:space:]')" == 0a ]]; }
require_file() { [[ -f "$1" && ! -L "$1" ]] || fail "required release artifact is missing or unsafe: ${1##*/}"; }

[[ -d "$release_dir" && ! -L "$release_dir" ]] || fail 'ETL release directory is missing or unsafe'
release_dir=$(CDPATH= cd -P -- "$release_dir" && pwd -P)
etl_repository=$AIRBOB_DATASET_ETL_REPOSITORY
[[ -d "$etl_repository" && ! -L "$etl_repository" ]] || fail 'ETL repository is missing or unsafe'
etl_repository=$(CDPATH= cd -P -- "$etl_repository" && pwd -P)
[[ "$(git -C "$etl_repository" rev-parse --is-inside-work-tree 2>/dev/null)" == true ]] || fail 'ETL repository is not a Git work tree'

expected_profile=''
if [[ ${AIRBOB_DATASET_RELEASE_PROFILE+set} == set ]]; then
  [[ -n ${AIRBOB_DATASET_RELEASE_PROFILE//[[:space:]]/} ]] || fail 'expected release profile is blank'
  expected_profile=$AIRBOB_DATASET_RELEASE_PROFILE
  case "$expected_profile" in
    production-skew-v1|production-skew-large-v1) ;;
    *) fail 'expected release profile is unsupported' ;;
  esac
fi
required_release_files=(
  PROVENANCE.txt
  benchmark-fixture.json
  benchmark-dataset-v2.json
  database-fingerprint.tsv
  etl-code.sha256
)
if [[ "$verification_mode" == read-only-restore ]]; then
  required_release_files+=(release-metadata.txt)
fi
for file in "${required_release_files[@]}"; do
  require_file "$release_dir/$file"
done
manifest_profile=$(jq -er '.world.provenance.profileVersion | select(type=="string")' "$release_dir/benchmark-dataset-v2.json") \
  || fail 'manifest production profile is missing'
case "$manifest_profile" in
  production-skew-v1)
    production_spec_name=production-skew-v1.json
    expected_budgets='{"accommodations":50000,"activeWishlists":400000,"members":200000,"reservations":2500000,"reviews":1000000,"wishlistLinks":1500000}'
    ;;
  production-skew-large-v1)
    production_spec_name=production-skew-large-v1.json
    expected_budgets='{"accommodations":200000,"activeWishlists":1600000,"members":800000,"reservations":10000000,"reviews":4000000,"wishlistLinks":6000000}'
    ;;
  *) fail 'manifest selects an unsupported production profile' ;;
esac
if [[ -n "$expected_profile" ]]; then
  [[ "$manifest_profile" == "$expected_profile" ]] || fail 'release profile differs from the expected profile fence'
fi
require_file "$release_dir/$production_spec_name"
if [[ "$verification_mode" == read-only-restore ]]; then
  metadata_spec=$(awk -F= '$1=="production_spec"{count++;value=substr($0,index($0,"=")+1)} END{if(count==1)print value}' "$release_dir/release-metadata.txt")
  [[ "$metadata_spec" == "$production_spec_name" ]] || fail 'release metadata profile/spec binding is invalid'
  metadata_spec_sha=$(awk -F= '$1=="production_spec_sha256"{count++;value=substr($0,index($0,"=")+1)} END{if(count==1)print value}' "$release_dir/release-metadata.txt")
  [[ "$metadata_spec_sha" == "$(sha256_file "$release_dir/$production_spec_name")" ]] \
    || fail 'release metadata production spec digest is invalid'
fi
jq -e --arg profile "$manifest_profile" --argjson budgets "$expected_budgets" '
  .profileVersion==$profile and .provenance.generatorVersion=="production-skew-generator-v1" and
  .provenance.prngAlgorithm=="sha256-splitmix64-counter-v1" and
  .provenance.seedDerivation=="length-prefixed(profile-version, global-seed, relation-domain, stable-external-key, counter)" and
  .provenance.globalSeed==20260826 and .provenance.anchor=="2026-07-31T15:00:00Z" and .provenance.timezone=="Asia/Seoul" and
  (.targets|{accommodations:.accommodations.rowBudget,members:.members.rowBudget,reservations:.reservations.rowBudget,reviews:.reviews.rowBudget,activeWishlists:.activeWishlists.rowBudget,wishlistLinks:.wishlistLinks.rowBudget})==$budgets and
  ([.targets[]|select(.rowBudget!=null)|.tolerance]|all(.absoluteRows==0 and .relativePercent==0))
' "$release_dir/$production_spec_name" >/dev/null || fail 'production distribution profile contract failed'
script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
semantic_validator="$script_dir/validate-benchmark-dataset-v2.jq"
require_file "$semantic_validator"
jq -e -f "$semantic_validator" "$release_dir/benchmark-dataset-v2.json" >/dev/null \
  || fail 'benchmark-dataset-v2 semantics failed before manifest-derived SQL'
jq -e '.world.provenance.verificationPassed == true' \
  "$release_dir/benchmark-dataset-v2.json" >/dev/null \
  || fail 'ETL release requires verified production fingerprints'
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-v2-database-verifier.XXXXXX") || fail 'cannot create workspace'
cleanup() { unset MYSQL_PWD database_password; rm -rf "$work_dir"; }
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

source_etl_commit=$(awk -F= '$1=="etl_head"{count++;value=$2} END{if(count==1)print value}' "$release_dir/PROVENANCE.txt")
[[ "$source_etl_commit" =~ ^[0-9a-f]{40}$ ]] || fail 'ETL provenance commit is invalid'
[[ "$(git -C "$etl_repository" rev-parse --verify "$source_etl_commit^{commit}" 2>/dev/null)" == "$source_etl_commit" ]] || fail 'ETL provenance commit is unavailable'
has_final_newline "$release_dir/etl-code.sha256" || fail 'ETL code inventory is malformed'
inventory_digest() {
  local target=$1
  awk -v target="$target" '
    substr($0,67)==target {
      count++
      digest=substr($0,1,64)
      if (length($0)!=(66+length(target)) || substr($0,65,2)!="  ") invalid=1
    }
    END {if(count==1 && !invalid) print digest}
  ' "$release_dir/etl-code.sha256"
}
contract_path=scripts/verify-production-seed.sql
expected_contract_sha=$(inventory_digest "$contract_path")
[[ "$expected_contract_sha" =~ ^[0-9a-f]{64}$ ]] || fail 'production verifier is absent from code inventory'
verification_contract="$work_dir/verify-production-seed.sql"
git -C "$etl_repository" show "$source_etl_commit:$contract_path" > "$verification_contract" 2>/dev/null || fail 'cannot extract provenance verifier'
[[ "$(sha256_file "$verification_contract")" == "$expected_contract_sha" ]] || fail 'provenance verifier digest drifted'
printf '%s  %s\n' "$expected_contract_sha" "$contract_path" > "$work_dir/verifier-contract-inventory.sha256"
verifier_contract_inventory_sha256=$(sha256_file "$work_dir/verifier-contract-inventory.sha256")

production_spec_path="src/main/resources/benchmark/$production_spec_name"
expected_production_spec_sha=$(inventory_digest "$production_spec_path")
[[ "$expected_production_spec_sha" =~ ^[0-9a-f]{64}$ ]] \
  || fail 'production distribution spec is absent from code inventory'
committed_production_spec="$work_dir/$production_spec_name"
git -C "$etl_repository" show "$source_etl_commit:$production_spec_path" \
  > "$committed_production_spec" 2>/dev/null \
  || fail 'cannot extract provenance production distribution spec'
[[ "$(sha256_file "$committed_production_spec")" == "$expected_production_spec_sha" ]] \
  || fail 'provenance production distribution spec digest drifted'
[[ "$(sha256_file "$release_dir/$production_spec_name")" == "$expected_production_spec_sha" ]] \
  || fail 'production distribution spec differs from provenance commit'
cmp -s "$release_dir/$production_spec_name" "$committed_production_spec" \
  || fail 'production distribution spec differs from provenance commit'

mysql_base=(mysql --protocol=TCP --default-character-set=utf8mb4 "--init-command=SET SESSION time_zone='+00:00'" --host="$AIRBOB_DATASET_DB_HOST" --port="$AIRBOB_DATASET_DB_PORT" --user="$AIRBOB_DATASET_DB_USER" --batch --raw --skip-column-names "$AIRBOB_DATASET_DB_NAME")
mysql_file() {
  local label=$1 input=$2 output=$3
  MYSQL_PWD="$database_password" "${mysql_base[@]}" < "$input" > "$output" 2>/dev/null || fail "database query failed: $label"
}
mysql_text() {
  local label=$1 sql=$2 output=$3
  printf '%s\n' "$sql" > "$work_dir/query.sql"
  mysql_file "$label" "$work_dir/query.sql" "$output"
}
mysql_scalar() {
  local label=$1 sql=$2 output
  output="$work_dir/scalar-$label.tsv"
  mysql_text "$label" "$sql" "$output"
  [[ "$(wc -l < "$output" | tr -d '[:space:]')" == 1 ]] || fail "scalar query was not singular: $label"
  sed -n '1p' "$output"
}
mysql_hash() {
  local label=$1 sql=$2
  printf '%s\n' "$sql" > "$work_dir/hash-query.sql"
  MYSQL_PWD="$database_password" "${mysql_base[@]}" < "$work_dir/hash-query.sql" 2>/dev/null \
    | tr -d '\n' | xxd -r -p | sha256_stream || fail "canonical hash query failed: $label"
}

if [[ "$verification_mode" == read-only-restore ]]; then
  server_query='SELECT LOWER(@@server_uuid),@@GLOBAL.read_only,@@GLOBAL.super_read_only;'
else
  server_query='SELECT LOWER(@@server_uuid),DATABASE(),@@SESSION.time_zone;'
fi
server_before="$work_dir/server-before.tsv"
mysql_text server-before "$server_query" "$server_before"
if [[ "$verification_mode" == read-only-restore ]]; then
  IFS=$'\t' read -r database_server_uuid read_only super_read_only extra < "$server_before"
  [[ -z "${extra:-}" \
    && "$database_server_uuid" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ \
    && "$read_only" == 1 && "$super_read_only" == 1 ]] \
    || fail 'database is not a stable read-only restore'
else
  IFS=$'\t' read -r database_server_uuid selected_database session_timezone extra < "$server_before"
  [[ -z "${extra:-}" \
    && "$database_server_uuid" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ \
    && "$selected_database" == "$AIRBOB_DATASET_DB_NAME" \
    && "$session_timezone" == '+00:00' ]] \
    || fail 'database is not the isolated UTC temporary verification schema'
fi

legacy_hex=$(od -An -v -tx1 "$release_dir/benchmark-fixture.json" | tr -d ' \n')
verification_sql="$work_dir/verification.sql"
{
  printf "SET @manifest_json = CONVERT(UNHEX('%s') USING utf8mb4);\n" "$legacy_hex"
  cat "$verification_contract"
} > "$verification_sql"
verification_one="$work_dir/verification-one.tsv"
verification_two="$work_dir/verification-two.tsv"
mysql_file production-verification-one "$verification_sql" "$verification_one"
mysql_file production-verification-two "$verification_sql" "$verification_two"
cmp -s "$verification_one" "$verification_two" || fail 'production verification changed between two read-only passes'
has_final_newline "$verification_one" || fail 'production verification output is malformed'

source_tail="$work_dir/source-verification-tail.tsv"
awk 'found || $1=="format_version"{found=1;print} END{if(!found)exit 1}' "$release_dir/database-fingerprint.tsv" > "$source_tail" || fail 'shipped fingerprint lacks canonical verifier output'
cmp -s "$source_tail" "$verification_one" || fail 'live production verification differs from shipped canonical output'

metric_value() { awk -F $'\t' -v target="$1" '$1==target{count++;value=$2} END{if(count==1)print value}' "$verification_one"; }
zero_metrics=(review_summary_missing_count review_summary_stale_count review_summary_extra_count review_summary_symmetric_mismatch_count wishlist_accommodation_count_mismatch_count wishlist_representative_mismatch_count wishlist_denormalized_symmetric_mismatch_count daily_revenue_stats_missing_count daily_revenue_stats_stale_count daily_revenue_stats_extra_count daily_revenue_stats_symmetric_mismatch_count accommodation_inventory_day_row_count orphan_total)
for metric in "${zero_metrics[@]}"; do [[ "$(metric_value "$metric")" == 0 ]] || fail "semantic restore gate failed: $metric"; done
[[ "$(metric_value foreign_key_checks_global)" == 1 && "$(metric_value foreign_key_checks_session)" == 1 ]] || fail 'foreign-key enforcement is disabled'

# ETL projects temporal and DOUBLE values through MySQL CAST AS CHAR before JDBC reads them. The
# verifier mirrors that database-canonical text and fingerprints each row as -2 followed by signed
# int32 length + bytes for every field.
text_field() { printf "IF(%s IS NULL,'FFFFFFFF',CONCAT(LPAD(HEX(OCTET_LENGTH(CAST(%s AS CHAR))),8,'0'),HEX(CAST(%s AS CHAR))))" "$1" "$1" "$1"; }
binary_field() { printf "IF(%s IS NULL,'FFFFFFFF',CONCAT(LPAD(HEX(OCTET_LENGTH(%s)),8,'0'),HEX(%s)))" "$1" "$1" "$1"; }
row_hex() {
  local expression="'FFFFFFFE'" field kind
  while [[ "$#" -gt 0 ]]; do
    field=$1; kind=$2; shift 2
    if [[ "$kind" == binary ]]; then expression="$expression,$(binary_field "$field")"; else expression="$expression,$(text_field "$field")"; fi
  done
  printf 'CONCAT(%s)' "$expression"
}
result_field() { printf "IF(%s IS NULL,'00000000',CONCAT(LPAD(HEX(OCTET_LENGTH(CAST(%s AS CHAR))),8,'0'),HEX(CAST(%s AS CHAR))))" "$1" "$1" "$1"; }

fingerprint_result="$work_dir/fingerprints.tsv"
: > "$fingerprint_result"
fingerprint_table() {
  local id=$1 table=$2 predicate=$3 order=$4 expected_rows=$5
  shift 5
  local row_expression actual_rows digest expected_digest
  row_expression=$(row_hex "$@")
  actual_rows=$(mysql_scalar "rows-${id//-/_}" "/* airbob_fingerprint_count:$id */ SELECT COUNT(*) FROM $table WHERE $predicate;")
  [[ "$actual_rows" == "$expected_rows" ]] || fail "fingerprint scope row count drifted: $id"
  digest=$(mysql_hash "$id" "/* airbob_fingerprint:$id */ SELECT $row_expression FROM $table WHERE $predicate ORDER BY $order;")
  expected_digest=$(jq -er --arg id "$id" \
    '.world.fingerprints[$id] | select(type=="string" and test("^[0-9a-f]{64}$"))' \
    "$manifest") || fail "manifest fingerprint component is missing or malformed: $id"
  [[ "$digest" == "$expected_digest" ]] \
    || fail "fingerprint component differs from restored canonical rows: $id"
  printf '%s\t%s\n' "$id" "$digest" >> "$fingerprint_result"
}
manifest="$release_dir/benchmark-dataset-v2.json"
table_rows() { jq -er --arg key "$1" '.world.tableRows[$key] | select(type=="number" and floor==. and .>=0)' "$manifest"; }
scope_value() { jq -er --arg key "$1" --arg field "$2" '.world.scopeRanges[$key][$field]' "$manifest"; }
jq -e '
  (.world.scopeRanges|keys)==["accommodation","member","payment","payment-transaction","reservation","review","wishlist","wishlist-accommodation"] and
  all(.world.scopeRanges|to_entries[];.key==.value.id and .value.minimumId>0 and .value.maximumId>=.value.minimumId and .value.rowCount==(.value.maximumId-.value.minimumId+1))
' "$manifest" >/dev/null || fail 'base scopes are not exact contiguous ranges'
jq -e --argjson budgets "$expected_budgets" '
  (.world.scopeRanges | {
    accommodations:.accommodation.rowCount,
    members:.member.rowCount,
    reservations:.reservation.rowCount,
    reviews:.review.rowCount,
    activeWishlists:.wishlist.rowCount,
    wishlistLinks:.["wishlist-accommodation"].rowCount
  }) as $baseRows |
  (.world.tableRows | {
    accommodations:.accommodation,
    members:.member,
    reservations:.reservation,
    reviews:.review,
    activeWishlists:.wishlist,
    wishlistLinks:.wishlist_accommodation
  }) as $finalRows |
  ($baseRows==$budgets) and
  all($budgets|to_entries[];$finalRows[.key]>=.value)
' "$manifest" >/dev/null || fail 'manifest rows do not satisfy the selected production profile budgets'

accommodation_fields=(a.id text a.base_price text a.created_at text a.member_id text a.description text a.name text a.thumbnail_url text a.type text a.check_in_time text a.check_out_time text a.time_zone_id text a.status text a.currency text a.accommodation_uid binary a.address_id text a.occupancy_policy_id text)
address_fields=(a.id text a.latitude text a.longitude text a.postal_code text a.city text a.country text a.detail text a.district text a.street text a.state text)
occupancy_policy_fields=(o.id text o.infant_occupancy text o.max_occupancy text o.pet_occupancy text)
accommodation_image_fields=(ai.id text ai.accommodation_id text ai.image_url text)
accommodation_amenity_fields=(aa.id text aa.accommodation_id text aa.amenity_code text aa.count text)
member_fields=(m.id text m.created_at text m.email text m.nickname text m.role text m.status text m.thumbnail_image_url text)
reservation_fields=(r.id text r.reservation_uid binary r.accommodation_id text r.guest_id text r.check_in_date text r.check_out_date text r.check_in_at text r.check_out_at text r.time_zone_id text r.guest_count text r.total_price text r.discount_amount text r.status text r.message text r.reservation_code text r.created_at text r.expires_at text r.currency text)
review_fields=(r.id text r.rating text r.accommodation_id text r.created_at text r.member_id text r.content text r.status text)
review_image_fields=(ri.id text ri.review_id text ri.image_url text)
wishlist_fields=(w.id text w.name text w.created_at text w.member_id text w.status text w.accommodation_count text w.representative_accommodation_id text)
wishlist_link_fields=(wa.id text wa.memo text wa.created_at text wa.wishlist_id text wa.accommodation_id text)
payment_fields=(p.id text p.payment_uid binary p.payment_key text p.order_id text p.amount text p.method text p.approved_at text p.created_at text p.reservation_id text p.status text p.balance_amount text)
transaction_fields=(pt.id text pt.reservation_id text pt.payment_id text pt.transaction_type text pt.status text pt.amount text pt.payment_key text pt.order_id text pt.method text pt.failure_code text pt.cancel_amount text pt.cancel_reason text pt.transaction_key text pt.canceled_at text pt.created_at text)

fingerprint_table final-accommodation 'accommodation a' '1=1' a.id "$(table_rows accommodation)" "${accommodation_fields[@]}"
fingerprint_table final-address 'address a' '1=1' a.id "$(table_rows address)" "${address_fields[@]}"
fingerprint_table final-occupancy-policy 'occupancy_policy o' '1=1' o.id "$(table_rows occupancy_policy)" "${occupancy_policy_fields[@]}"
fingerprint_table final-accommodation-image 'accommodation_image ai' '1=1' ai.id "$(table_rows accommodation_image)" "${accommodation_image_fields[@]}"
fingerprint_table final-accommodation-amenity 'accommodation_amenity aa' '1=1' aa.id "$(table_rows accommodation_amenity)" "${accommodation_amenity_fields[@]}"
fingerprint_table final-member 'member m' '1=1' m.id "$(table_rows member)" "${member_fields[@]}"
fingerprint_table final-reservation 'reservation r' '1=1' r.id "$(table_rows reservation)" "${reservation_fields[@]}"
fingerprint_table final-review 'review r' '1=1' r.id "$(table_rows review)" "${review_fields[@]}"
fingerprint_table final-review-image 'review_image ri' '1=1' ri.id "$(table_rows review_image)" "${review_image_fields[@]}"
fingerprint_table final-wishlist 'wishlist w' '1=1' w.id "$(table_rows wishlist)" "${wishlist_fields[@]}"
fingerprint_table final-wishlist-accommodation 'wishlist_accommodation wa' '1=1' wa.id "$(table_rows wishlist_accommodation)" "${wishlist_link_fields[@]}"
fingerprint_table final-payment 'payment p' '1=1' p.id "$(table_rows payment)" "${payment_fields[@]}"
fingerprint_table final-payment-transaction 'payment_transaction pt' '1=1' pt.id "$(table_rows payment_transaction)" "${transaction_fields[@]}"
fingerprint_table final-review-summary 'accommodation_review_summary s' '1=1' s.accommodation_id "$(table_rows accommodation_review_summary)" s.accommodation_id text s.total_review_count text s.rating_sum text s.average_rating text
fingerprint_table final-daily-revenue 'daily_revenue_stats s' '1=1' 's.stat_date,s.accommodation_id' "$(table_rows daily_revenue_stats)" s.stat_date text s.accommodation_id text s.gross_amount text s.refund_amount text s.net_amount text s.payment_count text s.refund_count text
fingerprint_table final-inventory 'accommodation_inventory_day i' '1=1' 'i.accommodation_id,i.stay_date' "$(table_rows accommodation_inventory_day)" i.accommodation_id text i.stay_date text i.state text i.reservation_id text i.hold_expires_at text

base_fingerprint() {
  local scope=$1 id=$2 table=$3 alias_id=$4 extra_predicate=$5 order=$6
  shift 6
  local minimum maximum rows predicate
  minimum=$(scope_value "$scope" minimumId); maximum=$(scope_value "$scope" maximumId); rows=$(scope_value "$scope" rowCount)
  predicate="$alias_id BETWEEN $minimum AND $maximum"
  [[ "$extra_predicate" == 1=1 ]] || predicate="$predicate AND $extra_predicate"
  fingerprint_table "$id" "$table" "$predicate" "$order" "$rows" "$@"
}
base_fingerprint accommodation base-accommodation 'accommodation a' a.id '1=1' a.id "${accommodation_fields[@]}"
base_fingerprint member base-member 'member m' m.id '1=1' m.id "${member_fields[@]}"
base_fingerprint reservation base-reservation 'reservation r' r.id '1=1' r.id "${reservation_fields[@]}"
base_fingerprint review base-review 'review r' r.id '1=1' r.id "${review_fields[@]}"
base_fingerprint wishlist base-wishlist 'wishlist w' w.id "w.status='ACTIVE'" w.id "${wishlist_fields[@]}"
base_fingerprint wishlist-accommodation base-wishlist-accommodation 'wishlist_accommodation wa' wa.id '1=1' wa.id "${wishlist_link_fields[@]}"
base_fingerprint payment base-payment 'payment p' p.id '1=1' p.id "${payment_fields[@]}"
base_fingerprint payment-transaction base-payment-transaction 'payment_transaction pt' pt.id '1=1' pt.id "${transaction_fields[@]}"

append_length_prefixed() {
  local output=$1 value=$2 length=${#2} unsigned escaped
  unsigned=$length
  printf -v escaped '\\x%02x\\x%02x\\x%02x\\x%02x' $(((unsigned>>24)&255)) $(((unsigned>>16)&255)) $(((unsigned>>8)&255)) $((unsigned&255))
  printf '%b%s' "$escaped" "$value" >> "$output"
}
combined_fingerprint() {
  local prefix=$1 output="$work_dir/combined.bin" id digest
  : > "$output"
  while IFS=$'\t' read -r id digest; do append_length_prefixed "$output" "$id"; append_length_prefixed "$output" "$digest"; done \
    < <(awk -F $'\t' -v prefix="$prefix" 'index($1,prefix)==1' "$fingerprint_result" | sort)
  sha256_file "$output"
}
final_world=$(combined_fingerprint final-)
base_world=$(combined_fingerprint base-)
inventory_fingerprint=$(awk -F $'\t' '$1=="final-inventory"{print $2}' "$fingerprint_result")
[[ "$final_world" == "$(jq -r '.world.fingerprints["final-world"]' "$manifest")" ]] || fail 'final-world fingerprint differs from restored canonical rows'
[[ "$base_world" == "$(jq -r '.world.fingerprints["base-world"]' "$manifest")" ]] || fail 'base-world fingerprint differs from restored contiguous scopes'
[[ "$inventory_fingerprint" == "$(jq -r '.world.fingerprints["final-inventory"]' "$manifest")" ]] || fail 'inventory fingerprint differs from restored rows'

verify_read_model_targets() {
  local receipt=$1 target id kind expected_rows expected_hash actual_rows actual_hash member_id size cursor_id cursor_time from to data_sql data_hash account_count created_at_field
  local read_model_count=0 search_count=0 adult child infant pet total_occupancy top_left_lat top_left_lng bottom_right_lat bottom_right_lng minimum_price maximum_price search_scope search_stats representative_id expected_representative
  : > "$receipt"
  while IFS= read -r target; do
    id=$(jq -r '.id' <<< "$target"); kind=$(jq -r '.query.kind // empty' <<< "$target")
    if [[ "$kind" != REVIEW_SUMMARY_V1 && "$kind" != WISHLIST_PAGE_V1 && "$kind" != REVENUE_RANGE_V1 && "$kind" != ACCOMMODATION_SEARCH_V1 ]]; then continue; fi
    expected_rows=$(jq -r '.expectedRows' <<< "$target"); expected_hash=$(jq -r '.expectedResultHash' <<< "$target")
    if jq -e 'has("account")' <<< "$target" >/dev/null; then
      member_id=$(jq -r '.account.memberId' <<< "$target")
      account_count=$(mysql_scalar "account-${id}" "/* airbob_target_account:$id */ SELECT COUNT(*) FROM member WHERE id=$member_id AND email='$(jq -r '.account.email' <<< "$target")' AND role='$(jq -r '.account.role' <<< "$target")' AND status='$(jq -r '.account.status' <<< "$target")';")
      [[ "$account_count" == 1 ]] || fail "target account drifted: $id"
    fi
    case "$kind" in
      REVIEW_SUMMARY_V1)
        read_model_count=$((read_model_count + 1))
        accommodation_id=$(jq -r '.query.accommodationId' <<< "$target")
        actual_rows=$(mysql_scalar "review-rows-${id}" "SELECT COUNT(*) FROM review WHERE accommodation_id=$accommodation_id AND status='PUBLISHED';")
        data_sql="/* airbob_target_result:$id */ SELECT CONCAT($(result_field 'COUNT(*)'),$(result_field 'CAST(CAST(COALESCE(AVG(rating),0) AS DECIMAL(20,2)) AS CHAR)')) FROM review WHERE accommodation_id=$accommodation_id AND status='PUBLISHED';"
        ;;
      WISHLIST_PAGE_V1)
        read_model_count=$((read_model_count + 1))
        member_id=$(jq -r '.query.memberId' <<< "$target"); size=$(jq -r '.query.size' <<< "$target")
        cursor_id=$(jq -r '.query.lastId // empty' <<< "$target"); cursor_time=$(jq -r '.query.lastCreatedAt // empty' <<< "$target")
        [[ "$(jq -r '.query.accommodationId // empty' <<< "$target")" == '' ]] || fail 'v2 wishlist attestation does not support accommodation-filter targets'
        total_active=$(mysql_scalar "wishlist-total-${id}" "SELECT COUNT(*) FROM wishlist WHERE member_id=$member_id AND status='ACTIVE';")
        [[ "$total_active" == "$(jq -r '.query.totalActiveRows' <<< "$target")" ]] || fail "wishlist totalActiveRows drifted: $id"
        predicate="w.member_id=$member_id AND w.status='ACTIVE'"
        [[ -z "$cursor_id" ]] || predicate="$predicate AND (w.created_at<'$cursor_time' OR (w.created_at='$cursor_time' AND w.id<$cursor_id))"
        actual_rows=$(mysql_scalar "wishlist-rows-${id}" "SELECT COUNT(*) FROM (SELECT w.id FROM wishlist w WHERE $predicate ORDER BY w.created_at DESC,w.id DESC LIMIT $size) target_rows;")
        created_at_field=$(result_field "DATE_FORMAT(w.created_at,'%Y-%m-%dT%H:%i:%s.%f')")
        data_sql="/* airbob_target_result:$id */ SELECT CONCAT($(result_field 'w.id'),$(result_field 'w.name'),$created_at_field,$(result_field 'w.accommodation_count'),$(result_field 'a.thumbnail_url')) FROM wishlist w LEFT JOIN accommodation a ON a.id=w.representative_accommodation_id WHERE $predicate ORDER BY w.created_at DESC,w.id DESC LIMIT $size;"
        ;;
      REVENUE_RANGE_V1)
        read_model_count=$((read_model_count + 1))
        from=$(jq -r '.query.from' <<< "$target"); to=$(jq -r '.query.to' <<< "$target")
        ledger="(SELECT DATE(pt.created_at) bucket_date,COALESCE(pt.amount,0) gross,0 refund,1 gcount,0 rcount FROM payment_transaction pt WHERE pt.transaction_type='CONFIRM' AND DATE(pt.created_at) BETWEEN '$from' AND '$to' UNION ALL SELECT DATE(COALESCE(pt.canceled_at,pt.created_at)),0,COALESCE(pt.cancel_amount,0),0,1 FROM payment_transaction pt WHERE pt.transaction_type IN ('CANCEL','PARTIAL_CANCEL') AND DATE(COALESCE(pt.canceled_at,pt.created_at)) BETWEEN '$from' AND '$to')"
        actual_rows=$(mysql_scalar "revenue-rows-${id}" "SELECT COUNT(*) FROM (SELECT bucket_date FROM $ledger t GROUP BY bucket_date) target_rows;")
        data_sql="/* airbob_target_result:$id */ SELECT CONCAT($(result_field 't.bucket_date'),$(result_field 'SUM(t.gross)'),$(result_field 'SUM(t.refund)'),$(result_field 'SUM(t.gross)-SUM(t.refund)'),$(result_field 'SUM(t.gcount)'),$(result_field 'SUM(t.rcount)')) FROM $ledger t GROUP BY t.bucket_date ORDER BY t.bucket_date;"
        ;;
      ACCOMMODATION_SEARCH_V1)
        search_count=$((search_count + 1))
        adult=$(jq -r '.query.adultOccupancy' <<< "$target"); child=$(jq -r '.query.childOccupancy' <<< "$target")
        infant=$(jq -r '.query.infantOccupancy' <<< "$target"); pet=$(jq -r '.query.petOccupancy' <<< "$target")
        total_occupancy=$((adult + child))
        top_left_lat=$(jq -r '.query.topLeftLat' <<< "$target"); top_left_lng=$(jq -r '.query.topLeftLng' <<< "$target")
        bottom_right_lat=$(jq -r '.query.bottomRightLat' <<< "$target"); bottom_right_lng=$(jq -r '.query.bottomRightLng' <<< "$target")
        minimum_price=$(jq -r '.query.minPrice' <<< "$target"); maximum_price=$(jq -r '.query.maxPrice' <<< "$target")
        search_scope="from accommodation a join address addr on addr.id=a.address_id join occupancy_policy op on op.id=a.occupancy_policy_id where a.status='PUBLISHED' and a.base_price between 0 and 2147483647 and addr.latitude between -90 and 90 and addr.longitude between -180 and 180 and op.max_occupancy>=1 and addr.latitude<=$top_left_lat and addr.latitude>=$bottom_right_lat and addr.longitude>=$top_left_lng and addr.longitude<=$bottom_right_lng and a.base_price between $minimum_price and $maximum_price and op.max_occupancy>=$total_occupancy and ($infant=0 or op.infant_occupancy>=$infant) and ($pet=0 or op.pet_occupancy>=$pet)"
        search_stats=$(mysql_scalar "search-stats-${id}" "/* airbob_search_stats:$id */ select concat(count(*),':',coalesce(cast(min(a.id) as char),'<null>')) $search_scope;")
        actual_rows=${search_stats%%:*}; representative_id=${search_stats#*:}
        expected_representative=$(jq -r 'if (.resourceIds|length)==0 then "<null>" else (.resourceIds[0]|tostring) end' <<< "$target")
        [[ "$representative_id" == "$expected_representative" ]] || fail "search target representative resource drifted: $id"
        data_sql="/* airbob_search_result:$id */ select concat($(result_field 'a.id')) $search_scope order by a.id asc;"
        ;;
    esac
    [[ "$actual_rows" == "$expected_rows" ]] || fail "target expectedRows drifted: $id"
    actual_hash=$(mysql_hash "target-$id" "$data_sql")
    [[ "$actual_hash" == "$expected_hash" ]] || fail "target expectedResultHash drifted: $id"
    printf '%s\t%s\t%s\n' "$id" "$actual_rows" "$actual_hash" >> "$receipt"
  done < <(jq -c '.capsules[]|select(.capsuleId=="read-model-v2" or .capsuleId=="index-query-v1").targets[]' "$manifest")
  [[ "$read_model_count" == 15 ]] || fail 'read-model-v2 must contain exactly fifteen live-verifiable targets'
  [[ "$search_count" == 4 ]] || fail 'index-query-v1 must contain exactly four live-verifiable search targets'
  [[ "$(wc -l < "$receipt" | tr -d '[:space:]')" == 19 ]] || fail 'live target receipt cardinality is invalid'

  target_fingerprint=$("$script_dir/compute-target-fingerprint.sh" "$manifest") \
    || fail 'target fingerprint calculation failed'
  [[ "$target_fingerprint" == "$(jq -r '.targetFingerprint' "$manifest")" ]] || fail 'targetFingerprint does not bind live-verified targets'
  printf 'targetFingerprint\t%s\n' "$target_fingerprint" >> "$receipt"
}

target_receipt_one="$work_dir/target-receipt-one.tsv"
target_receipt_two="$work_dir/target-receipt-two.tsv"
verify_read_model_targets "$target_receipt_one"
verify_read_model_targets "$target_receipt_two"
cmp -s "$target_receipt_one" "$target_receipt_two" || fail 'read-model targets changed between two read-only passes'
target_fingerprint=$(awk -F $'\t' '$1=="targetFingerprint"{print $2}' "$target_receipt_one")

server_after="$work_dir/server-after.tsv"
mysql_text server-after "$server_query" "$server_after"
cmp -s "$server_before" "$server_after" || fail 'database identity or read-only state changed during verification'

database_fingerprint_sha256=$(sha256_file "$release_dir/database-fingerprint.tsv")
verification_output_sha256=$(sha256_file "$verification_one")
distribution_evidence="$work_dir/distribution-evidence.tsv"
grep -E '^(review_summary_|wishlist_|daily_revenue_|accommodation_inventory_day_row_count|orphan_total|foreign_key_checks_)' "$verification_one" > "$distribution_evidence"
distribution_evidence_sha256=$(sha256_file "$distribution_evidence")
distribution_assertion_sha256=$(jq -er '.world.provenance.assertionSha256 | select(test("^[0-9a-f]{64}$"))' "$manifest") \
  || fail 'distribution assertion seal is missing from the manifest'
distribution_spec_sha256=$(sha256_file "$release_dir/$production_spec_name")
[[ "$distribution_spec_sha256" == "$(jq -r '.world.provenance.specSha256' "$manifest")" ]] \
  || fail 'production distribution spec differs from the manifest proof chain'
jq -nS \
  --arg sourceEtlCommit "$source_etl_commit" --arg databaseServerUuid "$database_server_uuid" \
  --arg verifierContractInventorySha256 "$verifier_contract_inventory_sha256" \
  --arg databaseFingerprintSha256 "$database_fingerprint_sha256" --arg verificationOutputSha256 "$verification_output_sha256" \
  --arg finalWorldFingerprintSha256 "$final_world" --arg baseWorldFingerprintSha256 "$base_world" \
  --arg distributionEvidenceSha256 "$distribution_evidence_sha256" \
  --arg distributionAssertionSha256 "$distribution_assertion_sha256" --arg distributionSpecSha256 "$distribution_spec_sha256" \
  --arg targetFingerprintSha256 "$target_fingerprint" \
  --arg inventoryFingerprintSha256 "$inventory_fingerprint" '
  {schemaVersion:2,sourceEtlCommit:$sourceEtlCommit,databaseServerUuid:$databaseServerUuid,
   verifierContractInventorySha256:$verifierContractInventorySha256,databaseFingerprintSha256:$databaseFingerprintSha256,
   verificationOutputSha256:$verificationOutputSha256,finalWorldFingerprintSha256:$finalWorldFingerprintSha256,
   baseWorldFingerprintSha256:$baseWorldFingerprintSha256,distributionEvidenceSha256:$distributionEvidenceSha256,
   distributionAssertionSha256:$distributionAssertionSha256,distributionSpecSha256:$distributionSpecSha256,
   targetFingerprintSha256:$targetFingerprintSha256,inventoryFingerprintSha256:$inventoryFingerprintSha256}
'
