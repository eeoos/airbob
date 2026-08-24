#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

usage() {
  printf 'usage: %s RELEASE_DIR EXPECTED_RELEASE EXPECTED_KIND DATASET_BUCKET [SNAPSHOT_RECEIPT]\n' \
    "${0##*/}" >&2
  exit 64
}

[[ $# -eq 4 || $# -eq 5 ]] || usage
release_dir=$1
expected_release=$2
expected_kind=$3
dataset_bucket=$4
snapshot_receipt=${5:-}

[[ "$expected_release" =~ ^[a-z0-9][a-z0-9._-]{2,63}$ ]] \
  || fail 'invalid expected dataset release'
case "$expected_kind" in
  pipeline-rehearsal|evidence) ;;
  *) fail 'invalid expected release kind' ;;
esac
[[ "$dataset_bucket" =~ ^airbob-performance-lab-dataset-[0-9]{12}$ ]] \
  || fail 'dataset bucket is outside the approved foundation boundary'
[[ -d "$release_dir" && ! -L "$release_dir" ]] \
  || fail 'dataset release directory is missing or unsafe'
release_dir=$(CDPATH= cd -P -- "$release_dir" && pwd -P)

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
toolchain_contract="$repo_root/infra/aws/toolchain.env"
validator="$script_dir/verify-dataset-release.sh"
[[ -f "$toolchain_contract" && ! -L "$toolchain_contract" ]] \
  || fail 'toolchain contract is missing or unsafe'
[[ -x "$validator" && ! -L "$validator" ]] \
  || fail 'dataset release validator is missing or unsafe'
# shellcheck disable=SC1090
source "$toolchain_contract"

for required_command in aws jq cmp find sort; do
  command -v "$required_command" >/dev/null 2>&1 \
    || fail "$required_command is required to publish a dataset release"
done
[[ "${AWS_REGION:-}" == "$AIRBOB_AWS_REGION" ]] \
  || fail "AWS_REGION must equal $AIRBOB_AWS_REGION"

identity_file=$(mktemp "${TMPDIR:-/tmp}/airbob-dataset-identity.XXXXXX")
temp_dir=''
cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  [[ -z "$temp_dir" || ! -d "$temp_dir" ]] || rm -rf -- "$temp_dir"
  [[ ! -f "$identity_file" ]] || rm -f -- "$identity_file"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

aws sts get-caller-identity --output json --no-cli-pager > "$identity_file" \
  || fail 'unable to identify the AWS dataset publisher caller'
jq -se '
  length == 1 and
  (.[0] | type == "object") and
  (.[0].Account | type == "string" and test("^[0-9]{12}$")) and
  (.[0].Arn | type == "string")
' "$identity_file" >/dev/null || fail 'AWS returned an invalid caller identity'
account_id=$(jq -r '.Account' "$identity_file")
caller_arn=$(jq -r '.Arn' "$identity_file")
[[ "$account_id" == "$AIRBOB_AWS_ACCOUNT_ID" ]] \
  || fail 'active AWS account does not match the dataset foundation'
[[ "$dataset_bucket" == "airbob-performance-lab-dataset-$account_id" ]] \
  || fail 'dataset bucket does not belong to the active account'
[[ "$caller_arn" =~ ^arn:aws:sts::${account_id}:assumed-role/airbob-dataset-publisher/[A-Za-z0-9+=,.@_-]{2,64}$ ]] \
  || fail 'caller must assume the airbob-dataset-publisher role'

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-dataset-publish.XXXXXX")
chmod 700 "$temp_dir"

publisher_role_name=airbob-dataset-publisher
publisher_policy_name=airbob-dataset-publisher
attached_policies_file="$temp_dir/attached-role-policies.json"
inline_policies_file="$temp_dir/inline-role-policies.json"
publisher_role_file="$temp_dir/publisher-role.json"
publisher_policy_file="$temp_dir/publisher-role-policy.json"

aws iam list-attached-role-policies \
  --role-name "$publisher_role_name" \
  --output json \
  --no-cli-pager > "$attached_policies_file" \
  || fail 'unable to inspect managed policies attached to the dataset publisher role'
jq -se '
  length == 1 and
  (.[0] | type == "object") and
  (.[0].AttachedPolicies | type == "array" and length == 0) and
  ((.[0].IsTruncated // false) == false)
' "$attached_policies_file" >/dev/null \
  || fail 'dataset publisher role must not have attached managed policies'

aws iam list-role-policies \
  --role-name "$publisher_role_name" \
  --output json \
  --no-cli-pager > "$inline_policies_file" \
  || fail 'unable to list inline policies on the dataset publisher role'
jq -se --arg policy "$publisher_policy_name" '
  length == 1 and
  (.[0] | type == "object") and
  .[0].PolicyNames == [$policy] and
  ((.[0].IsTruncated // false) == false)
' "$inline_policies_file" >/dev/null \
  || fail 'dataset publisher role must have exactly its approved inline policy'

aws iam get-role \
  --role-name "$publisher_role_name" \
  --output json \
  --no-cli-pager > "$publisher_role_file" \
  || fail 'unable to inspect the dataset publisher role trust policy'
jq -se \
  --arg role "$publisher_role_name" \
  --arg principal "$AIRBOB_DATASET_PUBLISHER_PRINCIPAL_ARN" '
    def values:
      if type == "array" then . else [.] end;
    length == 1 and
    (.[0] | type == "object") and
    (.[0].Role | type == "object") and
    .[0].Role.RoleName == $role and
    (.[0].Role.AssumeRolePolicyDocument | type == "object") and
    .[0].Role.AssumeRolePolicyDocument.Version == "2012-10-17" and
    (.[0].Role.AssumeRolePolicyDocument.Statement | type == "array" and length == 1) and
    (.[0].Role.AssumeRolePolicyDocument.Statement[0] | keys | sort) ==
      ["Action", "Condition", "Effect", "Principal", "Sid"] and
    .[0].Role.AssumeRolePolicyDocument.Statement[0].Sid == "ApprovedLocalPrincipals" and
    .[0].Role.AssumeRolePolicyDocument.Statement[0].Effect == "Allow" and
    .[0].Role.AssumeRolePolicyDocument.Statement[0].Action == "sts:AssumeRole" and
    (.[0].Role.AssumeRolePolicyDocument.Statement[0].Principal | keys) == ["AWS"] and
    (.[0].Role.AssumeRolePolicyDocument.Statement[0].Principal.AWS | values) == [$principal] and
    .[0].Role.AssumeRolePolicyDocument.Statement[0].Condition == {
      "Bool": {"aws:MultiFactorAuthPresent": "true"}
    }
  ' "$publisher_role_file" >/dev/null \
  || fail 'dataset publisher role trust differs from the reviewed local MFA principal'

aws iam get-role-policy \
  --role-name "$publisher_role_name" \
  --policy-name "$publisher_policy_name" \
  --output json \
  --no-cli-pager > "$publisher_policy_file" \
  || fail 'unable to inspect the dataset publisher inline policy'

dataset_write_resource="arn:aws:s3:::$dataset_bucket/datasets/*"
disabled_snapshot_write_resource="arn:aws:s3:::$dataset_bucket/elasticsearch/releases/__disabled__/*"
disabled_snapshot_seal_resource="arn:aws:s3:::$dataset_bucket/elasticsearch/seals/__disabled__.json"
snapshot_read_resource="arn:aws:s3:::$dataset_bucket/elasticsearch/releases/*"
snapshot_seal_read_resource="arn:aws:s3:::$dataset_bucket/elasticsearch/seals/*"
dataset_bucket_resource="arn:aws:s3:::$dataset_bucket"
publisher_role_resource="arn:aws:iam::$account_id:role/$publisher_role_name"
lease_table_resource="arn:aws:dynamodb:$AIRBOB_AWS_REGION:$account_id:table/airbob-performance-lab-orchestration-lease"
disabled_snapshot_lock="airbob-dataset-snapshot/__disabled__"
jq -se \
  --arg role "$publisher_role_name" \
  --arg policy "$publisher_policy_name" \
  --arg datasetResource "$dataset_write_resource" \
  --arg disabledSnapshotResource "$disabled_snapshot_write_resource" \
  --arg disabledSnapshotSealResource "$disabled_snapshot_seal_resource" \
  --arg snapshotReadResource "$snapshot_read_resource" \
  --arg snapshotSealReadResource "$snapshot_seal_read_resource" \
  --arg datasetBucketResource "$dataset_bucket_resource" \
  --arg publisherRoleResource "$publisher_role_resource" \
  --arg leaseTableResource "$lease_table_resource" \
  --arg disabledSnapshotLock "$disabled_snapshot_lock" '
    def values:
      if type == "array" then . else [.] end;
    def explicit_action:
      type == "string" and (test("[*?]") | not);
    def read_only_s3_action:
      ascii_downcase as $action |
      $action == "s3:getbucketlocation" or
      $action == "s3:listbucket" or
      $action == "s3:listbucketversions" or
      $action == "s3:listbucketmultipartuploads" or
      $action == "s3:getobject" or
      $action == "s3:getobjectversion" or
      $action == "s3:listmultipartuploadparts";
    def s3_mutation_action:
      type == "string" and
      (ascii_downcase | startswith("s3:")) and
      (read_only_s3_action | not);
    def approved_action:
      ascii_downcase as $action |
      ($action == "iam:getrole" or
       $action == "iam:getrolepolicy" or
       $action == "iam:listattachedrolepolicies" or
       $action == "iam:listrolepolicies" or
       $action == "dynamodb:getitem" or
       $action == "dynamodb:updateitem" or
       ($action | startswith("s3:")));
    length == 1 and
    .[0].RoleName == $role and
    .[0].PolicyName == $policy and
    (.[0].PolicyDocument | type == "object") and
    (.[0].PolicyDocument.Statement | type == "array") and
    ([.[0].PolicyDocument.Statement[] |
      select(.Sid == "ReadPublishedDatasetBytes")
    ] | length == 1) and
    ([.[0].PolicyDocument.Statement[] |
      select(.Sid == "ReadPublishedDatasetBytes")
    ][0] |
      (keys | sort) == ["Action", "Effect", "Resource", "Sid"] and
      .Effect == "Allow" and
      (.Action | values | sort) == ["s3:GetObject", "s3:GetObjectVersion"] and
      (.Resource | values | sort) == ([$datasetResource, $snapshotReadResource, $snapshotSealReadResource] | sort)
    ) and
    all(.[0].PolicyDocument.Statement[];
      if (.Effect | type == "string" and ascii_downcase == "allow") then
        (has("NotAction") | not) and
        (has("NotResource") | not) and
        (.Action | type == "string" or type == "array") and
        all(.Action | values[]; explicit_action and approved_action) and
        if any(.Action | values[]; ascii_downcase | startswith("iam:")) then
          (.Resource | type == "string" or type == "array") and
          all(.Resource | values[]; . == $publisherRoleResource)
        elif any(.Action | values[]; ascii_downcase | startswith("dynamodb:")) then
          (.Resource | type == "string" or type == "array") and
          all(.Resource | values[]; . == $leaseTableResource) and
          .Condition["ForAllValues:StringEquals"]["dynamodb:LeadingKeys"] ==
            [$disabledSnapshotLock]
        elif any(.Action | values[]; s3_mutation_action) then
          (.Resource | type == "string" or type == "array") and
          all(.Resource | values[];
            type == "string" and
            (. == $datasetResource or
             . == $disabledSnapshotResource or
             . == $disabledSnapshotSealResource)
          )
        elif any(.Action | values[]; ascii_downcase | startswith("s3:")) then
          (.Resource | type == "string" or type == "array") and
          all(.Resource | values[];
            . == $datasetBucketResource or
            . == $datasetResource or
            . == $snapshotReadResource or
            . == $snapshotSealReadResource or
            . == $disabledSnapshotResource or
            . == $disabledSnapshotSealResource
          )
        else false
        end
      else
        true
      end
    )
  ' "$publisher_policy_file" >/dev/null \
  || fail 'dataset publisher snapshot writer permission must be revoked before publication'

bucket_location_file="$temp_dir/bucket-location.json"
if ! aws s3api get-bucket-location \
  --bucket "$dataset_bucket" \
  --output json \
  --no-cli-pager > "$bucket_location_file"
then
  rm -f -- "$bucket_location_file"
  fail 'unable to verify the dataset bucket region'
fi
bucket_region=$(jq -er '.LocationConstraint' "$bucket_location_file") \
  || {
    rm -f -- "$bucket_location_file"
    fail 'dataset bucket returned an invalid region'
  }
rm -f -- "$bucket_location_file"
[[ "$bucket_region" == "$AIRBOB_AWS_REGION" ]] \
  || fail 'dataset bucket is outside the approved AWS region'

stage_dir="$temp_dir/release"
mkdir -m 700 "$stage_dir"

manifest_source="$release_dir/manifest.json"
[[ -f "$manifest_source" && ! -L "$manifest_source" ]] \
  || fail 'dataset manifest is missing or unsafe'
jq -se \
  --arg release "$expected_release" \
  --arg kind "$expected_kind" '
    length == 1 and
    .[0].datasetRelease == $release and
    .[0].releaseKind == $kind and
    (.[0].search.enabled == true or .[0].search.enabled == false)
  ' "$manifest_source" >/dev/null \
  || fail 'dataset manifest does not match the requested release tuple'
search_enabled=$(jq -r '.search.enabled' "$manifest_source")

expected_entries="$temp_dir/expected-local-entries.txt"
expected_files="$temp_dir/expected-local-files.txt"
case "$search_enabled" in
  false)
    [[ -z "$snapshot_receipt" ]] \
      || fail 'snapshot receipt is forbidden when search is disabled'
    printf '%s\n' \
      benchmark \
      benchmark/manifest.json \
      manifest.json \
      mysql \
      mysql/airbob.sql.zst \
      mysql/sha256.txt \
      | LC_ALL=C sort > "$expected_entries"
    printf '%s\n' \
      benchmark/manifest.json \
      manifest.json \
      mysql/airbob.sql.zst \
      mysql/sha256.txt \
      > "$expected_files"
    ;;
  true)
    [[ -n "$snapshot_receipt" ]] \
      || fail 'snapshot receipt is required when search is enabled'
    [[ -f "$snapshot_receipt" && ! -L "$snapshot_receipt" ]] \
      || fail 'snapshot receipt is missing or unsafe'
    printf '%s\n' \
      benchmark \
      benchmark/manifest.json \
      elasticsearch \
      elasticsearch/snapshot-reference.json \
      manifest.json \
      mysql \
      mysql/airbob.sql.zst \
      mysql/sha256.txt \
      | LC_ALL=C sort > "$expected_entries"
    printf '%s\n' \
      benchmark/manifest.json \
      elasticsearch/snapshot-reference.json \
      manifest.json \
      mysql/airbob.sql.zst \
      mysql/sha256.txt \
      > "$expected_files"
    ;;
  *) fail 'dataset search contract is invalid' ;;
esac

actual_entries="$temp_dir/actual-local-entries.txt"
while IFS= read -r entry; do
  printf '%s\n' "${entry#"$release_dir"/}"
done < <(find "$release_dir" -mindepth 1 -print | LC_ALL=C sort) \
  > "$actual_entries"
cmp -s "$expected_entries" "$actual_entries" \
  || fail 'dataset release must contain the exact approved inventory'

mkdir -m 700 "$stage_dir/benchmark" "$stage_dir/mysql"
[[ "$search_enabled" != true ]] || mkdir -m 700 "$stage_dir/elasticsearch"
while IFS= read -r relative_path; do
  source_path="$release_dir/$relative_path"
  staged_path="$stage_dir/$relative_path"
  [[ -f "$source_path" && ! -L "$source_path" ]] \
    || fail 'dataset release contains a missing or unsafe artifact'
  cp "$source_path" "$staged_path"
  chmod 600 "$staged_path"
done < "$expected_files"

"$validator" "$stage_dir" "$expected_release" "$expected_kind" >/dev/null \
  || fail 'dataset release validation failed in private staging'

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    fail 'a SHA-256 implementation is required'
  fi
}

receipt_stage=''
snapshot_base_path=''
expected_snapshot_inventory_sha=''
expected_snapshot_inventory_count=''
expected_snapshot_inventory_bytes=''
if [[ "$search_enabled" == true ]]; then
  receipt_stage="$temp_dir/snapshot-receipt.json"
  cp "$snapshot_receipt" "$receipt_stage"
  chmod 600 "$receipt_stage"
  [[ "$(wc -c < "$receipt_stage" | tr -d '[:space:]')" -le 1048576 ]] \
    || fail 'snapshot receipt exceeds the approved size'
  reference="$stage_dir/elasticsearch/snapshot-reference.json"
  reference_sha=$(sha256_file "$reference")
  jq -se \
    --arg release "$expected_release" \
    --arg bucket "$dataset_bucket" \
    --arg referenceSha "$reference_sha" \
    --slurpfile manifest "$stage_dir/manifest.json" \
    --slurpfile reference "$reference" '
      def exact_keys($wanted): (keys | sort) == ($wanted | sort);
      def sha256: type == "string" and test("^[0-9a-f]{64}$");
      def image_digest: type == "string" and test("^sha256:[0-9a-f]{64}$");
      def timestamp: type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$");
      def safe_name: type == "string" and test("^[A-Za-z0-9._+/@:-]{1,128}$");
      length == 1 and
      (.[0] | exact_keys([
        "schemaVersion","datasetRelease","datasetRunId","sourceReleasePayloadSha256",
        "createdAt","producer","repository","snapshot","validation"
      ])) and
      .[0].schemaVersion == 1 and
      .[0].datasetRelease == $release and
      .[0].datasetRunId == $manifest[0].datasetRunId and
      (.[0].datasetRunId | type == "string" and test("^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")) and
      .[0].sourceReleasePayloadSha256 == $manifest[0].source.canonicalPayloadSha256 and
      (.[0].sourceReleasePayloadSha256 | sha256) and
      (.[0].createdAt | timestamp) and
      (.[0].producer | exact_keys(["elasticsearchVersion","imageDigest","client"])) and
      .[0].producer.elasticsearchVersion == $reference[0].elasticsearchVersion and
      .[0].producer.elasticsearchVersion == $manifest[0].search.elasticsearchVersion and
      (.[0].producer.imageDigest | image_digest) and
      .[0].producer.imageDigest == $reference[0].imageDigest and
      .[0].producer.imageDigest == $manifest[0].search.imageDigest and
      .[0].producer.client == "airbob_dataset_producer" and
      (.[0].repository | exact_keys(["bucket","basePath","writerName","readerName","verificationNodeCount","inventory"])) and
      .[0].repository.bucket == $bucket and
      .[0].repository.bucket == $reference[0].bucket and
      .[0].repository.basePath == $reference[0].basePath and
      .[0].repository.basePath == ("elasticsearch/releases/" + $release) and
      .[0].repository.writerName == "airbob-dataset-producer" and
      .[0].repository.readerName == "airbob-dataset-readonly" and
      .[0].repository.readerName == $reference[0].repository and
      (.[0].repository.verificationNodeCount | type == "number" and floor == . and . > 0) and
      (.[0].repository.inventory | exact_keys(["algorithm","sha256","entryCount","totalVersionBytes"])) and
      .[0].repository.inventory.algorithm == "s3-list-object-versions-v1" and
      (.[0].repository.inventory.sha256 | sha256) and
      (.[0].repository.inventory.entryCount | type == "number" and floor == . and . > 0) and
      (.[0].repository.inventory.totalVersionBytes | type == "number" and floor == . and . >= 0) and
      (.[0].snapshot | exact_keys(["name","uuid","state","version","indices","includeGlobalState","totalShards","successfulShards","failedShards","metadataSha256"])) and
      .[0].snapshot.name == $reference[0].snapshot and
      (.[0].snapshot.uuid | safe_name) and
      .[0].snapshot.state == "SUCCESS" and
      .[0].snapshot.version == $reference[0].elasticsearchVersion and
      .[0].snapshot.indices == [$reference[0].index] and
      .[0].snapshot.includeGlobalState == false and
      (.[0].snapshot.totalShards | type == "number" and floor == . and . > 0) and
      .[0].snapshot.successfulShards == .[0].snapshot.totalShards and
      .[0].snapshot.failedShards == 0 and
      (.[0].snapshot.metadataSha256 | sha256) and
      (.[0].validation | exact_keys(["snapshotReferenceSha256","documentCount","mappingSha256","dbIdsSha256","esIdsSha256","contentFingerprintSha256"])) and
      .[0].validation.snapshotReferenceSha256 == $referenceSha and
      .[0].validation.documentCount == $reference[0].documentCount and
      .[0].validation.documentCount == $manifest[0].search.documentCount and
      .[0].validation.mappingSha256 == $reference[0].mappingSha256 and
      .[0].validation.mappingSha256 == $manifest[0].search.mappingSha256 and
      .[0].validation.dbIdsSha256 == $reference[0].dbIdsSha256 and
      .[0].validation.dbIdsSha256 == $manifest[0].search.databaseAccommodationIdsSha256 and
      .[0].validation.esIdsSha256 == $reference[0].esIdsSha256 and
      .[0].validation.esIdsSha256 == $manifest[0].search.elasticsearchAccommodationIdsSha256 and
      .[0].validation.dbIdsSha256 == .[0].validation.esIdsSha256 and
      .[0].validation.contentFingerprintSha256 == $reference[0].contentFingerprintSha256 and
      .[0].validation.contentFingerprintSha256 == $manifest[0].search.contentFingerprintSha256 and
      (.[0].validation.mappingSha256 | sha256) and
      (.[0].validation.dbIdsSha256 | sha256) and
      (.[0].validation.esIdsSha256 | sha256) and
      (.[0].validation.contentFingerprintSha256 | sha256)
    ' "$receipt_stage" >/dev/null \
    || fail 'snapshot receipt does not match the dataset search contract'
  snapshot_base_path=$(jq -r '.repository.basePath' "$receipt_stage")
  expected_snapshot_inventory_sha=$(jq -r '.repository.inventory.sha256' "$receipt_stage")
  expected_snapshot_inventory_count=$(jq -r '.repository.inventory.entryCount' "$receipt_stage")
  expected_snapshot_inventory_bytes=$(jq -r '.repository.inventory.totalVersionBytes' "$receipt_stage")

  seal_key="elasticsearch/seals/$expected_release.json"
  seal_head_response="$temp_dir/snapshot-seal-head.json"
  if ! aws s3api head-object \
    --bucket "$dataset_bucket" \
    --key "$seal_key" \
    --output json \
    --no-cli-pager > "$seal_head_response"
  then
    fail 'immutable snapshot seal is missing or unreadable'
  fi
  seal_version_id=$(jq -er '
    select(type == "object") |
    .VersionId |
    select(type == "string" and . != "null" and length > 0 and test("^[^[:cntrl:]]+$"))
  ' "$seal_head_response") || fail 'snapshot seal has no immutable version id'
  jq -se '
    length == 1 and
    (.[0] | type == "object") and
    .[0].ServerSideEncryption == "AES256" and
    .[0].ContentType == "application/json" and
    (.[0].ContentLength | type == "number" and floor == . and . > 0 and . <= 1048576)
  ' "$seal_head_response" >/dev/null \
    || fail 'snapshot seal metadata is invalid'

  seal_readback="$temp_dir/snapshot-seal.json"
  seal_get_response="$temp_dir/snapshot-seal-get.json"
  if ! aws s3api get-object \
    --bucket "$dataset_bucket" \
    --key "$seal_key" \
    --version-id "$seal_version_id" \
    --output json \
    --no-cli-pager \
    "$seal_readback" > "$seal_get_response"
  then
    fail 'immutable snapshot seal version could not be read'
  fi
  jq -se --arg versionId "$seal_version_id" '
    length == 1 and
    (.[0] | type == "object") and
    .[0].VersionId == $versionId and
    .[0].ServerSideEncryption == "AES256" and
    .[0].ContentType == "application/json"
  ' "$seal_get_response" >/dev/null \
    || fail 'snapshot seal version readback metadata is invalid'

  receipt_sha=$(sha256_file "$receipt_stage")
  receipt_created_at=$(jq -r '.createdAt' "$receipt_stage")
  snapshot_name=$(jq -r '.snapshot.name' "$receipt_stage")
  jq -se \
    --arg release "$expected_release" \
    --arg snapshot "$snapshot_name" \
    --arg referenceSha "$reference_sha" \
    --arg receiptSha "$receipt_sha" \
    --arg createdAt "$receipt_created_at" '
      length == 1 and
      (.[0] | type == "object") and
      (.[0] | keys | sort) == ([
        "createdAt", "datasetRelease", "snapshot",
        "snapshotReceiptSha256", "snapshotReferenceSha256", "schemaVersion"
      ] | sort) and
      .[0].schemaVersion == 1 and
      .[0].datasetRelease == $release and
      .[0].snapshot == $snapshot and
      .[0].snapshotReferenceSha256 == $referenceSha and
      .[0].snapshotReceiptSha256 == $receiptSha and
      .[0].createdAt == $createdAt and
      (.[0].createdAt | fromdateiso8601 | type == "number")
    ' "$seal_readback" >/dev/null \
    || fail 'snapshot seal does not bind the staged reference and receipt'
fi

verify_snapshot_inventory() {
  local label=$1
  local raw="$temp_dir/snapshot-versions-$label.raw.jsonl"
  local response="$temp_dir/snapshot-versions-$label.page.json"
  local canonical="$temp_dir/snapshot-inventory-$label.jsonl"
  local actual_sha actual_count actual_bytes key_marker='' version_marker=''
  local truncated next_key next_version
  : > "$raw"
  while :; do
    local -a arguments=(
      s3api list-object-versions
      --bucket "$dataset_bucket"
      --prefix "$snapshot_base_path/"
      --output json
      --no-paginate
      --no-cli-pager
    )
    if [[ -n "$key_marker" ]]; then
      arguments+=(--key-marker "$key_marker")
      [[ -z "$version_marker" ]] || arguments+=(--version-id-marker "$version_marker")
    fi
    aws "${arguments[@]}" > "$response" \
      || fail 'unable to inventory the Elasticsearch snapshot repository'
    jq -e --arg prefix "$snapshot_base_path/" '
      (.IsTruncated | type == "boolean") and
      ((.Versions // []) | type == "array") and
      ((.DeleteMarkers // []) | type == "array") and
      all((.Versions // [])[];
        (.Key | type == "string" and startswith($prefix) and length > ($prefix | length)) and
        (.VersionId | type == "string" and length > 0) and
        (.IsLatest | type == "boolean") and
        (.Size | type == "number" and floor == . and . >= 0) and
        (.ETag | type == "string" and length > 0) and
        ((.ChecksumAlgorithm // []) | type == "array" and all(.[]; type == "string"))
      ) and
      all((.DeleteMarkers // [])[];
        (.Key | type == "string" and startswith($prefix) and length > ($prefix | length)) and
        (.VersionId | type == "string" and length > 0) and
        (.IsLatest | type == "boolean")
      )
    ' "$response" >/dev/null \
      || fail 'Elasticsearch snapshot repository returned an invalid version inventory'
    jq -c '
      (.Versions // [])[] | {
        kind:"version", key:.Key, versionId:.VersionId, isLatest:.IsLatest,
        size:.Size, eTag:.ETag,
        checksumAlgorithms: ((.ChecksumAlgorithm // []) | sort)
      }
    ' "$response" >> "$raw"
    jq -c '
      (.DeleteMarkers // [])[] | {
        kind:"delete-marker", key:.Key, versionId:.VersionId, isLatest:.IsLatest
      }
    ' "$response" >> "$raw"
    truncated=$(jq -r '.IsTruncated' "$response")
    [[ "$truncated" == true ]] || break
    next_key=$(jq -er '.NextKeyMarker | select(type == "string" and length > 0)' "$response") \
      || fail 'truncated snapshot inventory omitted the next key marker'
    next_version=$(jq -r '.NextVersionIdMarker // ""' "$response")
    [[ -z "$next_version" || "$next_version" =~ ^[^[:cntrl:]]+$ ]] \
      || fail 'truncated snapshot inventory returned an invalid version marker'
    [[ "$next_key|$next_version" != "$key_marker|$version_marker" ]] \
      || fail 'snapshot inventory pagination did not advance'
    key_marker=$next_key
    version_marker=$next_version
  done
  jq -cS -s 'sort_by(.key, .kind, .versionId)[]' "$raw" > "$canonical"
  actual_sha=$(sha256_file "$canonical")
  actual_count=$(wc -l < "$canonical" | tr -d '[:space:]')
  actual_bytes=$(jq -s '[.[] | select(.kind == "version") | .size] | add // 0' "$canonical")
  [[ "$actual_sha" == "$expected_snapshot_inventory_sha" ]] \
    || fail 'Elasticsearch snapshot repository inventory digest changed'
  [[ "$actual_count" == "$expected_snapshot_inventory_count" ]] \
    || fail 'Elasticsearch snapshot repository inventory count changed'
  [[ "$actual_bytes" == "$expected_snapshot_inventory_bytes" ]] \
    || fail 'Elasticsearch snapshot repository inventory byte count changed'
}

dataset_prefix="datasets/$expected_release"
marker_key="$dataset_prefix/manifest.json"
payload_files=(
  mysql/airbob.sql.zst
  mysql/sha256.txt
  benchmark/manifest.json
)
[[ "$search_enabled" != true ]] \
  || payload_files+=(elasticsearch/snapshot-reference.json)

expected_remote_payload="$temp_dir/expected-remote-payload.txt"
for relative_path in "${payload_files[@]}"; do
  printf '%s/%s\n' "$dataset_prefix" "$relative_path"
done | LC_ALL=C sort > "$expected_remote_payload"
expected_remote_complete="$temp_dir/expected-remote-complete.txt"
{
  cat "$expected_remote_payload"
  printf '%s\n' "$marker_key"
} | LC_ALL=C sort > "$expected_remote_complete"

list_remote_keys() {
  local destination=$1
  local response="$temp_dir/dataset-list.json"
  aws s3api list-objects-v2 \
    --bucket "$dataset_bucket" \
    --prefix "$dataset_prefix/" \
    --output json \
    --no-cli-pager > "$response" \
    || fail 'unable to list the dataset release prefix'
  jq -se --arg prefix "$dataset_prefix/" '
    length == 1 and
    ((.[0].Contents // []) | type == "array") and
    all(.[0].Contents[]?; .Key | type == "string" and startswith($prefix))
  ' "$response" >/dev/null || fail 'dataset release prefix returned an invalid inventory'
  jq -r '.Contents[]?.Key' "$response" | LC_ALL=C sort > "$destination"
}

remote_copy_index=0
compare_remote_object() {
  local relative_path=$1
  local key="$dataset_prefix/$relative_path"
  local remote_copy
  remote_copy_index=$((remote_copy_index + 1))
  remote_copy="$temp_dir/remote-object-$remote_copy_index"
  aws s3api get-object \
    --bucket "$dataset_bucket" \
    --key "$key" \
    "$remote_copy" \
    --no-cli-pager >/dev/null \
    || fail 'unable to read an immutable dataset object'
  cmp -s "$stage_dir/$relative_path" "$remote_copy" \
    || fail 'immutable dataset key already exists with different bytes'
}

verify_remote_complete() {
  local actual="$temp_dir/actual-remote-complete.txt"
  list_remote_keys "$actual"
  cmp -s "$expected_remote_complete" "$actual" \
    || fail 'completed dataset release has extra or missing keys'
  for relative_path in "${payload_files[@]}" manifest.json; do
    compare_remote_object "$relative_path"
  done
}

emit_result() {
  printf 'dataset_manifest=s3://%s/%s\n' "$dataset_bucket" "$marker_key"
  printf 'manifest_sha256=%s\n' "$(sha256_file "$stage_dir/manifest.json")"
}

complete_if_marker_exists() {
  if aws s3api head-object \
    --bucket "$dataset_bucket" \
    --key "$marker_key" \
    --no-cli-pager >/dev/null 2>&1
  then
    verify_remote_complete
    emit_result
    exit 0
  fi
}

[[ "$search_enabled" != true ]] || verify_snapshot_inventory initial
complete_if_marker_exists

actual_remote="$temp_dir/actual-remote-incomplete.txt"
list_remote_keys "$actual_remote"
if grep -Fxq -- "$marker_key" "$actual_remote"; then
  verify_remote_complete
  emit_result
  exit 0
fi
while IFS= read -r remote_key; do
  [[ -z "$remote_key" ]] && continue
  grep -Fxq -- "$remote_key" "$expected_remote_payload" \
    || fail 'incomplete dataset release contains an unexpected key'
  compare_remote_object "${remote_key#"$dataset_prefix"/}"
done < "$actual_remote"

publish_immutable() {
  local relative_path=$1
  local local_path="$stage_dir/$relative_path"
  local key="$dataset_prefix/$relative_path"
  local remote_copy
  remote_copy_index=$((remote_copy_index + 1))
  remote_copy="$temp_dir/raced-object-$remote_copy_index"

  if ! aws s3 cp \
    "$local_path" \
    "s3://$dataset_bucket/$key" \
    --no-overwrite \
    --sse AES256 \
    --only-show-errors \
    --no-progress
  then
    aws s3api get-object \
      --bucket "$dataset_bucket" \
      --key "$key" \
      "$remote_copy" \
      --no-cli-pager >/dev/null \
      || fail 'dataset publication lost an immutable write race'
    cmp -s "$local_path" "$remote_copy" \
      || fail 'dataset publication raced with different bytes'
  fi
  aws s3api get-object \
    --bucket "$dataset_bucket" \
    --key "$key" \
    "$remote_copy" \
    --no-cli-pager >/dev/null \
    || fail 'published dataset object is not readable'
  cmp -s "$local_path" "$remote_copy" \
    || fail 'published dataset object failed byte verification'
}

for relative_path in "${payload_files[@]}"; do
  complete_if_marker_exists
  publish_immutable "$relative_path"
done

complete_if_marker_exists
[[ "$search_enabled" != true ]] || verify_snapshot_inventory final
complete_if_marker_exists
actual_remote="$temp_dir/actual-remote-before-marker.txt"
list_remote_keys "$actual_remote"
if grep -Fxq -- "$marker_key" "$actual_remote"; then
  verify_remote_complete
  emit_result
  exit 0
fi
cmp -s "$expected_remote_payload" "$actual_remote" \
  || fail 'dataset payload has extra or missing keys before completion'
publish_immutable manifest.json
verify_remote_complete
emit_result
