#!/usr/bin/env bash
# Shared byte contract for local restore, the operator preflight and AWS SSM.
# Do not use shell printf %a: x86 long double differs from Java Double.toHexString.
set -euo pipefail
umask 077
export LC_ALL=C
fail() { printf 'target fingerprint: %s\n' "$1" >&2; exit 1; }
[[ "$#" -eq 1 && -f "$1" && ! -L "$1" ]] || fail 'expected one regular dataset manifest'
manifest=$1
jq -e '
  def integer: type == "number" and floor == . and . >= 0;
  def query:
    if . == null then true
    elif .kind == "REVIEW_SUMMARY_V1" then .accommodationId | integer
    elif .kind == "WISHLIST_PAGE_V1" then
      ([.memberId,.size,.totalActiveRows] | all(.[]; integer)) and
      ([.lastId,.accommodationId] | all(.[]; . == null or integer)) and
      (.lastCreatedAt == null or (.lastCreatedAt | type == "string"))
    elif .kind == "REVENUE_RANGE_V1" then [.from,.to,.dayBoundary] | all(.[]; type == "string")
    elif .kind == "ACCOMMODATION_SEARCH_V1" then
      (.destination | type == "string") and
      ([.minPrice,.maxPrice,.adultOccupancy,.childOccupancy,.infantOccupancy,.petOccupancy,.page] | all(.[]; integer)) and
      ([.topLeftLat,.topLeftLng,.bottomRightLat,.bottomRightLng] | all(.[]; type == "number" and isfinite))
    else false end;
  (.capsules | type == "array" and length > 0) and
  all(.capsules[];
    (.capsuleId | type == "string" and length > 0) and
    (.targets | type == "array") and
    all(.targets[];
      (.id | type == "string" and length > 0) and (.expectedRows | integer) and
      (.resourceIds | type == "array") and all(.resourceIds[]; integer) and
      (.expectedResultHash == null or (.expectedResultHash | type == "string")) and
      (.query | query) and
      (.account == null or
        ((.account.memberId | integer) and
         ([.account.email,.account.role,.account.status] | all(.[]; type == "string"))))))
' "$manifest" >/dev/null || fail 'invalid canonical target input'
work_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-target-fingerprint.XXXXXX")
trap 'rm -rf "$work_dir"' EXIT
append_length_prefixed() {
  local output=$1 value=$2 length=${#2} unsigned escaped
  unsigned=$length
  printf -v escaped '\\x%02x\\x%02x\\x%02x\\x%02x' $(((unsigned>>24)&255)) $(((unsigned>>16)&255)) $(((unsigned>>8)&255)) $((unsigned&255))
  printf '%b%s' "$escaped" "$value" >> "$output"
}
java_double_hex() {
  jq -nr --argjson value "$1" '
    def hex_digit($value):
      "0123456789abcdef"[$value:($value + 1)];
    def fraction_hex($value):
      reduce range(0; 13) as $unused
        ({remainder: $value, digits: ""};
          (.remainder * 16) as $scaled
          | ($scaled | floor) as $digit
          | .remainder = ($scaled - $digit)
          | .digits += hex_digit($digit))
      | .digits
      | sub("0+$"; "")
      | if length == 0 then "0" else . end;
    ($value | fabs) as $absolute
    | (if copysign(1; $value) < 0 then "-" else "" end) as $sign
    | if $absolute == 0 then
        $sign + "0x0.0p0"
      elif $absolute < ldexp(1; -1022) then
        $sign + "0x0." + fraction_hex(ldexp($absolute; 1022)) + "p-1022"
      else
        ($absolute | frexp) as $parts
        | $sign + "0x1." + fraction_hex(($parts[0] * 2) - 1)
          + "p" + (($parts[1] - 1) | tostring)
      end
  '
}
join_unit_fields() {
  local delimiter=$'\x1f' first=true field
  for field in "$@"; do
    if [[ "$first" == true ]]; then first=false; else printf '%s' "$delimiter"; fi
    printf '%s' "$field"
  done
}
query_nullable() {
  jq -r --arg field "$1" 'if .query[$field]==null then "<null>" else (.query[$field]|tostring) end' <<< "$2"
}
query_canonical() {
  local target=$1 kind
  kind=$(jq -r '.query.kind // empty' <<< "$target")
  case "$kind" in
    REVIEW_SUMMARY_V1) join_unit_fields "$kind" "$(jq -r '.query.accommodationId' <<< "$target")" ;;
    WISHLIST_PAGE_V1)
      join_unit_fields "$kind" "$(jq -r '.query.memberId' <<< "$target")" \
        "$(jq -r '.query.size' <<< "$target")" "$(query_nullable lastId "$target")" \
        "$(query_nullable lastCreatedAt "$target")" "$(query_nullable accommodationId "$target")" \
        "$(jq -r '.query.totalActiveRows' <<< "$target")" ;;
    REVENUE_RANGE_V1) join_unit_fields "$kind" "$(jq -r '.query.from' <<< "$target")" "$(jq -r '.query.to' <<< "$target")" "$(jq -r '.query.dayBoundary' <<< "$target")" ;;
    ACCOMMODATION_SEARCH_V1)
      join_unit_fields "$kind" "$(jq -r '.query.destination' <<< "$target")" \
        "$(jq -r '.query.minPrice' <<< "$target")" "$(jq -r '.query.maxPrice' <<< "$target")" \
        "$(jq -r '.query.adultOccupancy' <<< "$target")" "$(jq -r '.query.childOccupancy' <<< "$target")" \
        "$(jq -r '.query.infantOccupancy' <<< "$target")" "$(jq -r '.query.petOccupancy' <<< "$target")" \
        "$(java_double_hex "$(jq -r '.query.topLeftLat' <<< "$target")")" \
        "$(java_double_hex "$(jq -r '.query.topLeftLng' <<< "$target")")" \
        "$(java_double_hex "$(jq -r '.query.bottomRightLat' <<< "$target")")" \
        "$(java_double_hex "$(jq -r '.query.bottomRightLng' <<< "$target")")" \
        "$(jq -r '.query.page' <<< "$target")" ;;
    '') printf '' ;;
    *) fail "unsupported target query kind: $kind" ;;
  esac
}

fingerprint_stream="$work_dir/target.bin"
: > "$fingerprint_stream"
jq -c '.capsules|sort_by(.capsuleId)[]' "$manifest" > "$work_dir/capsules"
while IFS= read -r capsule; do
  append_length_prefixed "$fingerprint_stream" "$(jq -r '.capsuleId' <<< "$capsule")"
  jq -c '.targets|sort_by(.id)[]' <<< "$capsule" > "$work_dir/targets"
  while IFS= read -r target; do
    append_length_prefixed "$fingerprint_stream" "$(jq -r '.id' <<< "$target")"
    append_length_prefixed "$fingerprint_stream" "$(jq -r '.expectedRows|tostring' <<< "$target")"
    while IFS= read -r resource; do
      append_length_prefixed "$fingerprint_stream" "$resource"
    done < <(jq -r '.resourceIds[]|tostring' <<< "$target")
    canonical=$(query_canonical "$target") || fail 'query canonicalization failed'
    append_length_prefixed "$fingerprint_stream" "$canonical"
    for field in expectedResultHash account.memberId account.email account.role account.status; do
      append_length_prefixed "$fingerprint_stream" "$(jq -r ".$field // empty" <<< "$target")"
    done
  done < "$work_dir/targets"
done < "$work_dir/capsules"
if command -v sha256sum >/dev/null 2>&1; then sha256sum "$fingerprint_stream"
else shasum -a 256 "$fingerprint_stream"; fi | awk '{print $1}'
