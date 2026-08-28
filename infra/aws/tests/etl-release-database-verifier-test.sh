#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

repo_root=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
verifier="$repo_root/infra/aws/scripts/verify-etl-release-database.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-v2-db-verifier-test.XXXXXX")
fixture_password='verifier-test-password-do-not-log'
cleanup() { local status=$?; trap - EXIT; [[ "${AIRBOB_KEEP_TEST_TMP:-false}" == true ]] && printf 'kept: %s\n' "$temp_dir" >&2 || rm -rf "$temp_dir"; exit "$status"; }
trap cleanup EXIT
fail() { printf 'ETL release database verifier test failed: %s\n' "$1" >&2; exit 1; }
sha256_file() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }
append_field() {
  local file=$1 value=$2 length=${#2} escaped
  printf -v escaped '\\x%02x\\x%02x\\x%02x\\x%02x' $(((length>>24)&255)) $(((length>>16)&255)) $(((length>>8)&255)) $((length&255))
  printf '%b%s' "$escaped" "$value" >> "$file"
}
combined_hash() {
  local prefix=$1 row_hash=$2 inventory_hash=$3 output id digest
  output="$temp_dir/$prefix.bin"
  : > "$output"
  if [[ "$prefix" == final ]]; then
    ids=(final-accommodation final-daily-revenue final-inventory final-member final-payment final-payment-transaction final-reservation final-review final-review-summary final-wishlist final-wishlist-accommodation)
  else
    ids=(base-accommodation base-member base-payment base-payment-transaction base-reservation base-review base-wishlist base-wishlist-accommodation)
  fi
  for id in "${ids[@]}"; do
    digest=$row_hash
    [[ "$id" != final-inventory ]] || digest=$inventory_hash
    append_field "$output" "$id"; append_field "$output" "$digest"
  done
  sha256_file "$output"
}
ordered_ids_hash() {
  local label=$1 output="$temp_dir/search-$1.bin" id
  shift
  : > "$output"
  for id in "$@"; do append_field "$output" "$id"; done
  sha256_file "$output"
}

printf 'fffffffe' | xxd -r -p > "$temp_dir/one-row.bin"
row_hash=$(sha256_file "$temp_dir/one-row.bin")
: > "$temp_dir/empty.bin"
empty_hash=$(sha256_file "$temp_dir/empty.bin")
printf '000000013000000004302e3030' | xxd -r -p > "$temp_dir/review-result.bin"
review_hash=$(sha256_file "$temp_dir/review-result.bin")
search_broad_hash=$(ordered_ids_hash broad 1 2 3)
search_medium_hash=$(ordered_ids_hash medium 1 2)
search_narrow_hash=$(ordered_ids_hash narrow 1)
final_world=$(combined_hash final "$row_hash" "$empty_hash")
base_world=$(combined_hash base "$row_hash" "$empty_hash")

write_repository() {
  local root=$1
  local benchmark_dir="$root/src/main/resources/benchmark"
  mkdir -p "$root/scripts" "$benchmark_dir"
  git -C "$root" init -q
  git -C "$root" config user.name Test
  git -C "$root" config user.email test@example.invalid
  printf "%s\n" "SELECT 'production-contract-marker';" > "$root/scripts/verify-production-seed.sql"
  jq -nS '{profileVersion:"production-skew-v1",provenance:{generatorVersion:"production-skew-generator-v1",prngAlgorithm:"sha256-splitmix64-counter-v1",seedDerivation:"length-prefixed(profile-version, global-seed, relation-domain, stable-external-key, counter)",globalSeed:20260826,anchor:"2026-07-31T15:00:00Z",timezone:"Asia/Seoul"},targets:{accommodations:{rowBudget:50000,tolerance:{absoluteRows:0,relativePercent:0}},members:{rowBudget:200000,tolerance:{absoluteRows:0,relativePercent:0}},reservations:{rowBudget:2500000,tolerance:{absoluteRows:0,relativePercent:0}},reviews:{rowBudget:1000000,tolerance:{absoluteRows:0,relativePercent:0}},activeWishlists:{rowBudget:400000,tolerance:{absoluteRows:0,relativePercent:0}},wishlistLinks:{rowBudget:1500000,tolerance:{absoluteRows:0,relativePercent:0}}}}' \
    > "$benchmark_dir/production-skew-v1.json"
  jq -nS '{profileVersion:"production-skew-large-v1",provenance:{generatorVersion:"production-skew-generator-v1",prngAlgorithm:"sha256-splitmix64-counter-v1",seedDerivation:"length-prefixed(profile-version, global-seed, relation-domain, stable-external-key, counter)",globalSeed:20260826,anchor:"2026-07-31T15:00:00Z",timezone:"Asia/Seoul"},targets:{accommodations:{rowBudget:200000,tolerance:{absoluteRows:0,relativePercent:0}},members:{rowBudget:800000,tolerance:{absoluteRows:0,relativePercent:0}},reservations:{rowBudget:10000000,tolerance:{absoluteRows:0,relativePercent:0}},reviews:{rowBudget:4000000,tolerance:{absoluteRows:0,relativePercent:0}},activeWishlists:{rowBudget:1600000,tolerance:{absoluteRows:0,relativePercent:0}},wishlistLinks:{rowBudget:6000000,tolerance:{absoluteRows:0,relativePercent:0}}}}' \
    > "$benchmark_dir/production-skew-large-v1.json"
  git -C "$root" add scripts/verify-production-seed.sql src/main/resources/benchmark
  git -C "$root" commit -qm contract
}

rebind_target_fingerprint() {
  local output=$1
  node -e '
    const fs=require("fs"),crypto=require("crypto"); const p=process.argv[1],m=JSON.parse(fs.readFileSync(p));
    const h=crypto.createHash("sha256"),u=v=>{const b=Buffer.from(v==null?"":String(v));const n=Buffer.alloc(4);n.writeInt32BE(b.length);h.update(n);h.update(b)};
    const hx=x=>{if(Object.is(x,-0))return "-0x0.0p0";if(x===0)return "0x0.0p0";const s=x<0?"-":"";x=Math.abs(x);const e=Math.floor(Math.log2(x));let r=x/(2**e)-1,d="";for(let i=0;i<13;i++){r*=16;const n=Math.floor(r);d+=n.toString(16);r-=n}d=d.replace(/0+$/,"")||"0";return `${s}0x1.${d}p${e}`};
    const qc=q=>q.kind==="REVIEW_SUMMARY_V1"?[q.kind,q.accommodationId].join("\u001f"):q.kind==="WISHLIST_PAGE_V1"?[q.kind,q.memberId,q.size,q.lastId??"<null>",q.lastCreatedAt??"<null>",q.accommodationId??"<null>",q.totalActiveRows].join("\u001f"):q.kind==="REVENUE_RANGE_V1"?[q.kind,q.from,q.to,q.dayBoundary].join("\u001f"):[q.kind,q.destination,q.minPrice,q.maxPrice,q.adultOccupancy,q.childOccupancy,q.infantOccupancy,q.petOccupancy,hx(q.topLeftLat),hx(q.topLeftLng),hx(q.bottomRightLat),hx(q.bottomRightLng),q.page].join("\u001f");
    for(const c of [...m.capsules].sort((a,b)=>a.capsuleId.localeCompare(b.capsuleId))){u(c.capsuleId);for(const t of [...c.targets].sort((a,b)=>a.id.localeCompare(b.id))){u(t.id);u(t.expectedRows);for(const r of t.resourceIds)u(r);u(t.query?qc(t.query):null);u(t.expectedResultHash);u(t.account?.memberId);u(t.account?.email);u(t.account?.role);u(t.account?.status)}}
    m.targetFingerprint=h.digest("hex");fs.writeFileSync(p,JSON.stringify(m,null,2)+"\n");
  ' "$output"
}

write_manifest() {
  local output=$1 spec_file=$2 spec_sha profile budgets
  spec_sha=$(sha256_file "$spec_file")
  profile=$(jq -er '.profileVersion' "$spec_file")
  budgets=$(jq -c '{accommodations:.targets.accommodations.rowBudget,members:.targets.members.rowBudget,reservations:.targets.reservations.rowBudget,reviews:.targets.reviews.rowBudget,activeWishlists:.targets.activeWishlists.rowBudget,wishlistLinks:.targets.wishlistLinks.rowBudget}' "$spec_file")
  jq -nS --slurpfile base "$repo_root/infra/aws/tests/fixtures/benchmark-dataset-v2.json" --arg finalWorld "$final_world" --arg baseWorld "$base_world" --arg inventory "$empty_hash" --arg reviewHash "$review_hash" --arg emptyHash "$empty_hash" --arg searchBroadHash "$search_broad_hash" --arg searchMediumHash "$search_medium_hash" --arg searchNarrowHash "$search_narrow_hash" --arg specSha "$spec_sha" --arg profile "$profile" --argjson budgets "$budgets" '
    def account($id;$email;$role):{memberId:$id,email:$email,role:$role,status:"ACTIVE"};
    def review($id;$accommodation):{id:$id,expectedRows:0,resourceIds:[$accommodation],query:{kind:"REVIEW_SUMMARY_V1",accommodationId:$accommodation},expectedResultHash:$reviewHash};
    def wishlist($id;$member):{id:$id,expectedRows:0,resourceIds:[$member],query:{kind:"WISHLIST_PAGE_V1",memberId:$member,size:50,lastId:(if $id=="wishlist-hot-deep" then 1 else null end),lastCreatedAt:(if $id=="wishlist-hot-deep" then "2026-01-01T00:00:00" else null end),accommodationId:null,totalActiveRows:0},expectedResultHash:$emptyHash,account:account($member;("wishlist-"+($member|tostring)+"@airbob.cloud");"MEMBER")};
    def revenue($id;$day):{id:$id,expectedRows:0,resourceIds:[],query:{kind:"REVENUE_RANGE_V1",from:$day,to:$day,dayBoundary:"UTC"},expectedResultHash:$emptyHash,account:account(99;"revenue@airbob.cloud";"ADMIN")};
    def search($id;$rows;$resources;$minimum;$maximum;$hash):{id:$id,expectedRows:$rows,resourceIds:$resources,query:{kind:"ACCOMMODATION_SEARCH_V1",destination:"",minPrice:$minimum,maxPrice:$maximum,adultOccupancy:1,childOccupancy:0,infantOccupancy:0,petOccupancy:0,topLeftLat:38,topLeftLng:126,bottomRightLat:37,bottomRightLng:127,page:0},expectedResultHash:$hash};
    [
        review("review-hot";1),review("review-median";2),review("review-cold";3),review("review-empty";4),
        wishlist("wishlist-hot";1),wishlist("wishlist-median";2),wishlist("wishlist-cold";3),wishlist("wishlist-empty";4),wishlist("wishlist-hot-deep";1),
        revenue("revenue-recent-1d";"2026-07-31"),revenue("revenue-recent-7d";"2026-07-25"),revenue("revenue-medium";"2026-07-01"),revenue("revenue-broad";"2025-08-01"),revenue("revenue-empty";"2026-08-02"),revenue("revenue-refund-boundary";"2026-01-01")
    ] as $targets |
    [search("search-broad";3;[1];0;300;$searchBroadHash),search("search-medium";2;[1];0;200;$searchMediumHash),search("search-narrow";1;[1];0;100;$searchNarrowHash),search("search-no-hit";0;[];999;999;$emptyHash)] as $searchTargets |
    $base[0] |
    .world.tableRows={accommodation:$budgets.accommodations,accommodation_inventory_day:0,accommodation_review_summary:1,daily_revenue_stats:1,member:$budgets.members,payment:1,payment_transaction:1,reservation:$budgets.reservations,review:$budgets.reviews,wishlist:$budgets.activeWishlists,wishlist_accommodation:$budgets.wishlistLinks} |
    .world.scopeRanges={
      accommodation:{id:"accommodation",minimumId:1,maximumId:$budgets.accommodations,rowCount:$budgets.accommodations},
      member:{id:"member",minimumId:1,maximumId:$budgets.members,rowCount:$budgets.members},
      payment:{id:"payment",minimumId:1,maximumId:1,rowCount:1},
      "payment-transaction":{id:"payment-transaction",minimumId:1,maximumId:1,rowCount:1},
      reservation:{id:"reservation",minimumId:1,maximumId:$budgets.reservations,rowCount:$budgets.reservations},
      review:{id:"review",minimumId:1,maximumId:$budgets.reviews,rowCount:$budgets.reviews},
      wishlist:{id:"wishlist",minimumId:1,maximumId:$budgets.activeWishlists,rowCount:$budgets.activeWishlists},
      "wishlist-accommodation":{id:"wishlist-accommodation",minimumId:1,maximumId:$budgets.wishlistLinks,rowCount:$budgets.wishlistLinks}
    } |
    .world.fingerprints={"final-world":$finalWorld,"base-world":$baseWorld,"final-inventory":$inventory} |
    .world.provenance.profileVersion=$profile |
    .world.provenance.specSha256=$specSha |
    .capsules=[.capsules[]|select(.capsuleId=="read-model-v2" or .capsuleId=="index-query-v1")] |
    (.capsules[]|select(.capsuleId=="read-model-v2").targets)=$targets |
    (.capsules[]|select(.capsuleId=="read-model-v2").accountPool)={capacity:5,emails:["wishlist-1@airbob.cloud","wishlist-2@airbob.cloud","wishlist-3@airbob.cloud","wishlist-4@airbob.cloud","revenue@airbob.cloud"]} |
    (.capsules[]|select(.capsuleId=="index-query-v1").targets)=$searchTargets |
    (.capsules[]|select(.capsuleId=="index-query-v1").distributions)=[{id:"search-selectivity",axis:"SELECTIVITY",shape:"SELECTIVITY_BUCKETS",buckets:[0,1,2,3],parameters:{}}] |
    .targetFingerprint=("0"*64)
  ' > "$output"
  rebind_target_fingerprint "$output"
}

write_verification_output() {
  local output=$1 drift=${2:-0}
  cat > "$output" <<EOF
format_version	2
review_summary_missing_count	$drift
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
orphan_total	0
foreign_key_checks_global	1
foreign_key_checks_session	1
EOF
}

write_release() {
  local root=$1 repository=$2 commit=$3 target_fingerprint spec_sha spec_path path digest
  mkdir -p "$root"
  printf '%s\n' '{}' > "$root/benchmark-fixture.json"
  spec_path=src/main/resources/benchmark/production-skew-v1.json
  git -C "$repository" show "$commit:$spec_path" > "$root/production-skew-v1.json"
  spec_sha=$(sha256_file "$root/production-skew-v1.json")
  write_manifest "$root/benchmark-dataset-v2.json" "$root/production-skew-v1.json"
  target_fingerprint=$(jq -r '.targetFingerprint' "$root/benchmark-dataset-v2.json")
  write_verification_output "$temp_dir/verification.tsv"
  cat > "$root/database-fingerprint.tsv" <<EOF
dataset_final_world_fingerprint	$final_world
dataset_base_world_fingerprint	$base_world
dataset_distribution_fingerprint	dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd
dataset_target_fingerprint	$target_fingerprint
dataset_inventory_fingerprint	$empty_hash
EOF
  cat "$temp_dir/verification.tsv" >> "$root/database-fingerprint.tsv"
  printf 'format=airbob-production-seed-provenance-v2\netl_head=%s\n' "$commit" > "$root/PROVENANCE.txt"
  : > "$root/etl-code.sha256"
  for path in scripts/verify-production-seed.sql \
    src/main/resources/benchmark/production-skew-v1.json \
    src/main/resources/benchmark/production-skew-large-v1.json
  do
    digest=$(git -C "$repository" show "$commit:$path" | { if command -v sha256sum >/dev/null 2>&1; then sha256sum; else shasum -a 256; fi; } | awk '{print $1}')
    printf '%s  %s\n' "$digest" "$path" >> "$root/etl-code.sha256"
  done
  printf '%s\n' 'format=airbob-production-seed-release-v2' 'production_spec=production-skew-v1.json' \
    "production_spec_sha256=$spec_sha" > "$root/release-metadata.txt"
}

rewrite_release_as_large_profile() {
  local root=$1 repository=$2 commit=$3
  local next_spec="$root/production-skew-v1.next" spec_sha
  git -C "$repository" show "$commit:src/main/resources/benchmark/production-skew-large-v1.json" \
    > "$root/production-skew-large-v1.json"
  rm -f "$root/production-skew-v1.json"
  spec_sha=$(sha256_file "$root/production-skew-large-v1.json")
  jq --arg specSha "$spec_sha" --slurpfile spec "$root/production-skew-large-v1.json" '
    .world.provenance.profileVersion="production-skew-large-v1" |
    .world.provenance.specSha256=$specSha |
    .world.tableRows.accommodation=$spec[0].targets.accommodations.rowBudget |
    .world.tableRows.member=$spec[0].targets.members.rowBudget |
    .world.tableRows.reservation=$spec[0].targets.reservations.rowBudget |
    .world.tableRows.review=$spec[0].targets.reviews.rowBudget |
    .world.tableRows.wishlist=$spec[0].targets.activeWishlists.rowBudget |
    .world.tableRows.wishlist_accommodation=$spec[0].targets.wishlistLinks.rowBudget |
    .world.scopeRanges.accommodation.maximumId=$spec[0].targets.accommodations.rowBudget |
    .world.scopeRanges.accommodation.rowCount=$spec[0].targets.accommodations.rowBudget |
    .world.scopeRanges.member.maximumId=$spec[0].targets.members.rowBudget |
    .world.scopeRanges.member.rowCount=$spec[0].targets.members.rowBudget |
    .world.scopeRanges.reservation.maximumId=$spec[0].targets.reservations.rowBudget |
    .world.scopeRanges.reservation.rowCount=$spec[0].targets.reservations.rowBudget |
    .world.scopeRanges.review.maximumId=$spec[0].targets.reviews.rowBudget |
    .world.scopeRanges.review.rowCount=$spec[0].targets.reviews.rowBudget |
    .world.scopeRanges.wishlist.maximumId=$spec[0].targets.activeWishlists.rowBudget |
    .world.scopeRanges.wishlist.rowCount=$spec[0].targets.activeWishlists.rowBudget |
    .world.scopeRanges["wishlist-accommodation"].maximumId=$spec[0].targets.wishlistLinks.rowBudget |
    .world.scopeRanges["wishlist-accommodation"].rowCount=$spec[0].targets.wishlistLinks.rowBudget
  ' "$root/benchmark-dataset-v2.json" > "$next_spec"
  mv "$next_spec" "$root/benchmark-dataset-v2.json"
  printf '%s\n' 'format=airbob-production-seed-release-v2' 'production_spec=production-skew-large-v1.json' \
    "production_spec_sha256=$spec_sha" > "$root/release-metadata.txt"
}

write_fake_mysql() {
  mkdir -p "$temp_dir/bin"
  cp /bin/bash "$temp_dir/bin/bash-copy"
  # apply_patch owns repository files; this generated executable is test runtime state.
  printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail' \
    '[[ "${MYSQL_PWD:-}" == verifier-test-password-do-not-log ]] || exit 91' \
    'query=$(cat)' \
    'printf "%s\n-- query end --\n" "$query" >> "$FAKE_QUERY_LOG"' \
    'case "$query" in' \
    '  *"@@server_uuid"*) if [[ "$query" == *"DATABASE()"* ]]; then printf "%s\t%s\t+00:00\n" 00112233-4455-6677-8899-aabbccddeeff "$AIRBOB_DATASET_DB_NAME"; else printf "%s\t1\t1\n" 00112233-4455-6677-8899-aabbccddeeff; fi ;;' \
    '  *"production-contract-marker"*) count=0; [[ ! -f "$FAKE_VERIFY_COUNTER" ]] || count=$(cat "$FAKE_VERIFY_COUNTER"); count=$((count+1)); printf "%s\n" "$count" > "$FAKE_VERIFY_COUNTER"; if [[ "${FAKE_VERIFY_DRIFT:-false}" == true && "$count" -gt 1 ]]; then sed "s/review_summary_missing_count.0/review_summary_missing_count.1/" "$FAKE_VERIFICATION"; else cat "$FAKE_VERIFICATION"; fi ;;' \
    '  *"airbob_fingerprint_count:final-inventory"*) printf "0\n" ;;' \
    '  *"airbob_fingerprint_count:final-accommodation"*) jq -r ".world.tableRows.accommodation" "$FAKE_MANIFEST" ;;' \
    '  *"airbob_fingerprint_count:final-member"*) jq -r ".world.tableRows.member" "$FAKE_MANIFEST" ;;' \
    '  *"airbob_fingerprint_count:final-reservation"*) jq -r ".world.tableRows.reservation" "$FAKE_MANIFEST" ;;' \
    '  *"airbob_fingerprint_count:final-review-summary"*) printf "1\n" ;;' \
    '  *"airbob_fingerprint_count:final-review"*) jq -r ".world.tableRows.review" "$FAKE_MANIFEST" ;;' \
    '  *"airbob_fingerprint_count:final-wishlist-accommodation"*) jq -r ".world.tableRows.wishlist_accommodation" "$FAKE_MANIFEST" ;;' \
    '  *"airbob_fingerprint_count:final-wishlist"*) jq -r ".world.tableRows.wishlist" "$FAKE_MANIFEST" ;;' \
    '  *"airbob_fingerprint_count:base-accommodation"*) jq -r ".world.scopeRanges.accommodation.rowCount" "$FAKE_MANIFEST" ;;' \
    '  *"airbob_fingerprint_count:base-member"*) jq -r ".world.scopeRanges.member.rowCount" "$FAKE_MANIFEST" ;;' \
    '  *"airbob_fingerprint_count:base-reservation"*) jq -r ".world.scopeRanges.reservation.rowCount" "$FAKE_MANIFEST" ;;' \
    '  *"airbob_fingerprint_count:base-review"*) jq -r ".world.scopeRanges.review.rowCount" "$FAKE_MANIFEST" ;;' \
    '  *"airbob_fingerprint_count:base-wishlist-accommodation"*) jq -r ".world.scopeRanges[\"wishlist-accommodation\"].rowCount" "$FAKE_MANIFEST" ;;' \
    '  *"airbob_fingerprint_count:base-wishlist"*) jq -r ".world.scopeRanges.wishlist.rowCount" "$FAKE_MANIFEST" ;;' \
    '  *"airbob_fingerprint_count:"*) printf "1\n" ;;' \
    '  *"airbob_fingerprint:final-inventory"*) : ;;' \
    '  *"airbob_fingerprint:"*) printf "FFFFFFFE\n" ;;' \
    '  *"airbob_target_account:"*) printf "1\n" ;;' \
    '  *"airbob_target_result:review-"*) if [[ "${FAKE_TARGET_DRIFT:-false}" == true ]]; then printf "000000013100000004302e3030\n"; else printf "000000013000000004302e3030\n"; fi ;;' \
    '  *"airbob_target_result:"*) : ;;' \
    '  *"airbob_search_stats:search-broad"*) if [[ "${FAKE_SEARCH_ADDRESS_OCCUPANCY_DRIFT:-false}" == true ]]; then printf "2:1\n"; else printf "3:1\n"; fi ;;' \
    '  *"airbob_search_stats:search-medium"*) printf "2:1\n" ;;' \
    '  *"airbob_search_stats:search-narrow"*) printf "1:1\n" ;;' \
    '  *"airbob_search_stats:search-no-hit"*) printf "0:<null>\n" ;;' \
    '  *"airbob_search_result:search-broad"*) if [[ "${FAKE_SEARCH_ADDRESS_OCCUPANCY_DRIFT:-false}" == true ]]; then printf "00000001310000000132\n"; else printf "000000013100000001320000000133\n"; fi ;;' \
    '  *"airbob_search_result:search-medium"*) printf "00000001310000000132\n" ;;' \
    '  *"airbob_search_result:search-narrow"*) printf "0000000131\n" ;;' \
    '  *"airbob_search_result:search-no-hit"*) : ;;' \
    '  *"FROM review WHERE accommodation_id="*) printf "0\n" ;;' \
    '  *"FROM wishlist WHERE member_id="*) printf "0\n" ;;' \
    '  *"SELECT COUNT(*) FROM (SELECT w.id"*) printf "0\n" ;;' \
    '  *"SELECT COUNT(*) FROM (SELECT bucket_date"*) printf "0\n" ;;' \
    '  *) printf "unexpected query: %s\n" "$query" >&2; exit 93 ;;' \
    'esac' > "$temp_dir/bin/mysql"
  chmod 700 "$temp_dir/bin/mysql"
}

run_verifier() {
  local output=$1; shift
  : > "$temp_dir/verify-counter"
  env PATH="$temp_dir/bin:$PATH" AIRBOB_DATASET_ETL_REPOSITORY="$temp_dir/etl" \
    AIRBOB_DATASET_DB_HOST=127.0.0.1 AIRBOB_DATASET_DB_PORT=3307 AIRBOB_DATASET_DB_USER=verify \
    AIRBOB_DATASET_DB_PASSWORD="$fixture_password" AIRBOB_DATASET_DB_NAME=airbobdb AIRBOB_DATASET_DB_QUIESCED=true \
    FAKE_VERIFICATION="$temp_dir/verification.tsv" FAKE_VERIFY_COUNTER="$temp_dir/verify-counter" FAKE_QUERY_LOG="$temp_dir/mysql-queries.log" \
    FAKE_MANIFEST="$temp_dir/release/benchmark-dataset-v2.json" \
    "$@" "$verifier" "$temp_dir/release" > "$output"
}
run_isolated_verifier() {
  local output=$1; shift
  : > "$temp_dir/verify-counter"
  env PATH="$temp_dir/bin:$PATH" AIRBOB_DATASET_ETL_REPOSITORY="$temp_dir/etl" \
    AIRBOB_DATASET_DB_HOST=127.0.0.1 AIRBOB_DATASET_DB_PORT=3307 AIRBOB_DATASET_DB_USER=verify \
    AIRBOB_DATASET_DB_PASSWORD="$fixture_password" \
    AIRBOB_DATASET_DB_NAME=airbob_verify_20260827010203_abcdef123456_1234 \
    AIRBOB_DATASET_DB_QUIESCED=true AIRBOB_DATASET_DB_VERIFICATION_MODE=isolated-temporary-schema \
    FAKE_VERIFICATION="$temp_dir/verification.tsv" FAKE_VERIFY_COUNTER="$temp_dir/verify-counter" FAKE_QUERY_LOG="$temp_dir/mysql-queries.log" \
    FAKE_MANIFEST="$temp_dir/release/benchmark-dataset-v2.json" \
    "$@" "$verifier" "$temp_dir/release" > "$output"
}
expect_failure() {
  local label=$1; shift
  if "$@" > "$temp_dir/$label.out" 2> "$temp_dir/$label.err"; then fail "expected rejection: $label"; fi
  grep -Fq "$fixture_password" "$temp_dir/$label.out" "$temp_dir/$label.err" && fail "secret leaked: $label"
  return 0
}

[[ -x "$verifier" ]] || fail 'verifier is missing'
for command_name in git jq node xxd; do command -v "$command_name" >/dev/null || fail "$command_name is required"; done
bash -n "$verifier"
write_repository "$temp_dir/etl"
commit=$(git -C "$temp_dir/etl" rev-parse HEAD)
write_release "$temp_dir/release" "$temp_dir/etl" "$commit"
write_fake_mysql
run_verifier "$temp_dir/receipt.json"
for selector_sql in \
  'join address addr on addr.id=a.address_id' \
  'join occupancy_policy op on op.id=a.occupancy_policy_id' \
  "a.status='PUBLISHED'" \
  'a.base_price between 0 and 2147483647' \
  'addr.latitude between -90 and 90' \
  'addr.longitude between -180 and 180' \
  'op.max_occupancy>=1' \
  'addr.latitude<=38' \
  'addr.latitude>=37' \
  'addr.longitude>=126' \
  'addr.longitude<=127' \
  'op.infant_occupancy>=0' \
  'op.pet_occupancy>=0' \
  'min(a.id)' \
  'order by a.id asc'
do
  grep -Fq "$selector_sql" "$temp_dir/mysql-queries.log" || fail "search verifier omitted selector SQL: $selector_sql"
done
[[ "$(grep -Fc 'airbob_search_stats:search-broad' "$temp_dir/mysql-queries.log")" == 2 ]] \
  || fail 'search stats were not verified in both passes'
[[ "$(grep -Fc 'airbob_search_result:search-broad' "$temp_dir/mysql-queries.log")" == 2 ]] \
  || fail 'ordered search IDs were not hashed in both passes'
jq -e --arg final "$final_world" --arg base "$base_world" --arg inventory "$empty_hash" --arg target "$(jq -r '.targetFingerprint' "$temp_dir/release/benchmark-dataset-v2.json")" --arg assertion "$(jq -r '.world.provenance.assertionSha256' "$temp_dir/release/benchmark-dataset-v2.json")" --arg spec "$(sha256_file "$temp_dir/release/production-skew-v1.json")" '
  (keys|sort)==(["schemaVersion","sourceEtlCommit","databaseServerUuid","verifierContractInventorySha256","databaseFingerprintSha256","verificationOutputSha256","finalWorldFingerprintSha256","baseWorldFingerprintSha256","distributionEvidenceSha256","distributionAssertionSha256","distributionSpecSha256","targetFingerprintSha256","inventoryFingerprintSha256"]|sort) and
  .schemaVersion==2 and .finalWorldFingerprintSha256==$final and .baseWorldFingerprintSha256==$base and .inventoryFingerprintSha256==$inventory and .targetFingerprintSha256==$target and .distributionAssertionSha256==$assertion and .distributionSpecSha256==$spec
' "$temp_dir/receipt.json" >/dev/null || fail 'receipt does not bind recomputed fingerprints'

mv "$temp_dir/release/release-metadata.txt" "$temp_dir/release-metadata.saved"
run_isolated_verifier "$temp_dir/isolated-receipt.json"
mv "$temp_dir/release-metadata.saved" "$temp_dir/release/release-metadata.txt"
jq -e '.schemaVersion==2 and .databaseServerUuid=="00112233-4455-6677-8899-aabbccddeeff"' \
  "$temp_dir/isolated-receipt.json" >/dev/null || fail 'isolated temporary schema receipt is invalid'

rewrite_release_as_large_profile "$temp_dir/release" "$temp_dir/etl" "$commit"
run_isolated_verifier "$temp_dir/isolated-large-receipt.json"
jq -e '.schemaVersion==2 and .databaseServerUuid=="00112233-4455-6677-8899-aabbccddeeff"' \
  "$temp_dir/isolated-large-receipt.json" >/dev/null || fail 'large isolated verifier should derive profile from manifest'

expect_failure mismatched-expected-profile run_isolated_verifier \
  "$temp_dir/mismatched-profile.json" AIRBOB_DATASET_RELEASE_PROFILE=production-skew-v1
expect_failure unsupported-expected-profile run_isolated_verifier \
  "$temp_dir/unsupported-profile.json" AIRBOB_DATASET_RELEASE_PROFILE=production-skew-huge-v1

cp "$temp_dir/release/release-metadata.txt" "$temp_dir/release-metadata.canonical"
sed 's/production_spec=production-skew-large-v1.json/production_spec=production-skew-v1.json/' \
  "$temp_dir/release-metadata.canonical" > "$temp_dir/release/release-metadata.txt"
expect_failure mixed-metadata-spec run_verifier "$temp_dir/mixed-metadata-spec.json"
mv "$temp_dir/release-metadata.canonical" "$temp_dir/release/release-metadata.txt"

canonical_large_spec="$temp_dir/canonical-large-spec.json"
canonical_large_manifest="$temp_dir/canonical-large-manifest.json"
canonical_large_metadata="$temp_dir/canonical-large-metadata.txt"
cp "$temp_dir/release/production-skew-large-v1.json" "$canonical_large_spec"
cp "$temp_dir/release/benchmark-dataset-v2.json" "$canonical_large_manifest"
cp "$temp_dir/release/release-metadata.txt" "$canonical_large_metadata"
jq '.releaseLocalNonBudgetMarker="fully-rebound-mutation"' "$canonical_large_spec" \
  > "$temp_dir/release/production-skew-large-v1.json"
rebound_spec_sha=$(sha256_file "$temp_dir/release/production-skew-large-v1.json")
jq --arg specSha "$rebound_spec_sha" '.world.provenance.specSha256=$specSha' \
  "$canonical_large_manifest" > "$temp_dir/release/benchmark-dataset-v2.json"
sed "s/^production_spec_sha256=.*/production_spec_sha256=$rebound_spec_sha/" \
  "$canonical_large_metadata" > "$temp_dir/release/release-metadata.txt"
expect_failure fully-rebound-same-profile-spec run_verifier "$temp_dir/rebound-spec.json"
grep -Fq 'production distribution spec differs from provenance commit' \
  "$temp_dir/fully-rebound-same-profile-spec.err" \
  || fail 'fully rebound spec mutation was not rejected by the provenance pin'
cp "$canonical_large_spec" "$temp_dir/release/production-skew-large-v1.json"
cp "$canonical_large_manifest" "$temp_dir/release/benchmark-dataset-v2.json"
cp "$canonical_large_metadata" "$temp_dir/release/release-metadata.txt"

canonical_code_inventory="$temp_dir/canonical-etl-code.sha256"
cp "$temp_dir/release/etl-code.sha256" "$canonical_code_inventory"
grep -F '  src/main/resources/benchmark/production-skew-large-v1.json' \
  "$canonical_code_inventory" >> "$temp_dir/release/etl-code.sha256"
expect_failure duplicate-selected-spec-inventory run_isolated_verifier \
  "$temp_dir/duplicate-spec-inventory.json"
cp "$canonical_code_inventory" "$temp_dir/release/etl-code.sha256"

expect_failure unsafe-temporary-schema run_isolated_verifier \
  "$temp_dir/unsafe-temporary.json" AIRBOB_DATASET_DB_NAME=airbobdb

grep -Fq 'then "<null>"' "$verifier" || fail 'wishlist canonical null fields are not literal <null>'
canonical_manifest="$temp_dir/canonical-manifest.json"
cp "$temp_dir/release/benchmark-dataset-v2.json" "$canonical_manifest"
jq '.targetFingerprint=("0"*64)' "$canonical_manifest" > "$temp_dir/release/benchmark-dataset-v2.json"
expect_failure empty-null-target-fingerprint run_verifier "$temp_dir/empty-null.json"
cp "$canonical_manifest" "$temp_dir/release/benchmark-dataset-v2.json"

jq '(.capsules[0].targets[]|select(.id=="review-hot").expectedResultHash)=("9"*64)' \
  "$canonical_manifest" > "$temp_dir/release/benchmark-dataset-v2.json"
rebind_target_fingerprint "$temp_dir/release/benchmark-dataset-v2.json"
expect_failure rebound-target-hash run_isolated_verifier "$temp_dir/rebound-target.json"
cp "$canonical_manifest" "$temp_dir/release/benchmark-dataset-v2.json"

jq '.world.fingerprints["final-world"]=("5"*64) |
    .world.fingerprints["base-world"]=("6"*64) |
    .world.fingerprints["final-inventory"]=("7"*64)' \
  "$canonical_manifest" > "$temp_dir/release/benchmark-dataset-v2.json"
expect_failure rebound-world-fingerprints run_isolated_verifier "$temp_dir/rebound-world.json"
cp "$canonical_manifest" "$temp_dir/release/benchmark-dataset-v2.json"

expect_failure one-row-target-drift run_verifier "$temp_dir/target-drift.json" FAKE_TARGET_DRIFT=true
expect_failure isolated-snapshot-drift run_isolated_verifier \
  "$temp_dir/isolated-drift.json" FAKE_TARGET_DRIFT=true
expect_failure search-address-occupancy-drift run_isolated_verifier \
  "$temp_dir/search-drift.json" FAKE_SEARCH_ADDRESS_OCCUPANCY_DRIFT=true
expect_failure second-pass-drift run_verifier "$temp_dir/pass-drift.json" FAKE_VERIFY_DRIFT=true

safe_manifest="$temp_dir/safe-manifest.json"
cp "$temp_dir/release/benchmark-dataset-v2.json" "$safe_manifest"

jq '.world.scopeRanges.accommodation.maximumId=199999 |
    .world.scopeRanges.accommodation.rowCount=199999' \
  "$safe_manifest" > "$temp_dir/release/benchmark-dataset-v2.json"
expect_failure underfilled-base-scope run_isolated_verifier "$temp_dir/underfilled-base.json"
grep -Fq 'manifest rows do not satisfy the selected production profile budgets' \
  "$temp_dir/underfilled-base-scope.err" \
  || fail 'underfilled base scope was not rejected by the profile budget gate'
cp "$safe_manifest" "$temp_dir/release/benchmark-dataset-v2.json"

jq '.world.tableRows.accommodation=199999' \
  "$safe_manifest" > "$temp_dir/release/benchmark-dataset-v2.json"
expect_failure underfilled-final-table run_isolated_verifier "$temp_dir/underfilled-final.json"
grep -Fq 'manifest rows do not satisfy the selected production profile budgets' \
  "$temp_dir/underfilled-final-table.err" \
  || fail 'underfilled final table was not rejected by the profile budget gate'
cp "$safe_manifest" "$temp_dir/release/benchmark-dataset-v2.json"

jq '(.capsules[0].targets[]|select(.id=="wishlist-hot").account.email)="bad\u0027@airbob.cloud"' \
  "$safe_manifest" > "$temp_dir/release/benchmark-dataset-v2.json"
expect_failure quote-bearing-account run_verifier "$temp_dir/quote-bearing.json"
cp "$safe_manifest" "$temp_dir/release/benchmark-dataset-v2.json"

bad_scope="$temp_dir/release/benchmark-dataset-v2.next"
jq '.world.scopeRanges.accommodation.rowCount=2' "$temp_dir/release/benchmark-dataset-v2.json" > "$bad_scope"
mv "$bad_scope" "$temp_dir/release/benchmark-dataset-v2.json"
expect_failure noncontiguous-base-scope run_verifier "$temp_dir/scope-drift.json"

grep -Fq -- '--default-character-set=utf8mb4' "$verifier" || fail 'MySQL canonical hash session is not utf8mb4'
non_ascii="$temp_dir/non-ascii.bin"
: > "$non_ascii"
append_field "$non_ascii" '한글'
node_hash=$(node -e 'const c=require("crypto"),b=Buffer.from("한글"),n=Buffer.alloc(4);n.writeInt32BE(b.length);console.log(c.createHash("sha256").update(n).update(b).digest("hex"))')
[[ "$(sha256_file "$non_ascii")" == "$node_hash" ]] || fail 'UTF-8 byte-length prefix drifted for non-ASCII text'

printf '%s\n' 'ETL release database verifier test passed'
