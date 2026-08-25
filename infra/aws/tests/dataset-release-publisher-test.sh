#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
publisher="$repo_root/infra/aws/scripts/publish-dataset-release.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-dataset-publisher-test.XXXXXX")

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  rm -rf "$temp_dir"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

[[ -x "$publisher" && ! -L "$publisher" ]] \
  || fail 'dataset release publisher is missing or unsafe'
command -v jq >/dev/null 2>&1 || fail 'jq is required'

fixture="$temp_dir/repo"
fake_bin="$temp_dir/bin"
fake_s3="$temp_dir/s3"
call_log="$temp_dir/aws-calls.log"
mkdir -p "$fixture/infra/aws/scripts" "$fake_bin" "$fake_s3"
cp "$publisher" "$fixture/infra/aws/scripts/publish-dataset-release.sh"
cp "$repo_root/infra/aws/toolchain.env" "$fixture/infra/aws/toolchain.env"

cat > "$fixture/infra/aws/scripts/verify-dataset-release.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 3 ]]
root=$1
release=$2
kind=$3
[[ -f "$root/manifest.json" ]]
jq -e --arg release "$release" --arg kind "$kind" \
  '.datasetRelease == $release and .releaseKind == $kind' \
  "$root/manifest.json" >/dev/null
[[ -f "$root/mysql/airbob.sql.zst" ]]
[[ -f "$root/mysql/sha256.txt" ]]
[[ -f "$root/benchmark/manifest.json" ]]
if [[ "$(jq -r '.search.enabled' "$root/manifest.json")" == true ]]; then
  [[ -f "$root/elasticsearch/snapshot-reference.json" ]]
fi
printf '%s\n' 'dataset release verified'
EOF
chmod 700 "$fixture/infra/aws/scripts/verify-dataset-release.sh"

cat > "$fake_bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_CALL_LOG:?}"

key_from_s3_uri() {
  local uri=$1
  printf '%s\n' "${uri#s3://${FAKE_BUCKET:?}/}"
}

case " $* " in
  *' sts get-caller-identity '*)
    jq -n \
      --arg account "${FAKE_ACCOUNT:-942632789808}" \
      --arg arn "${FAKE_ARN:-arn:aws:sts::942632789808:assumed-role/airbob-dataset-publisher/test-session}" \
      '{Account:$account,Arn:$arn,UserId:"fixture"}'
    ;;
  *' iam list-attached-role-policies '*)
    case "${FAKE_ATTACHED_POLICY_MODE:-approved}" in
      approved) printf '%s\n' '{"AttachedPolicies":[],"IsTruncated":false}' ;;
      one) printf '%s\n' '{"AttachedPolicies":[{"PolicyName":"extra","PolicyArn":"arn:aws:iam::aws:policy/ReadOnlyAccess"}],"IsTruncated":false}' ;;
      truncated) printf '%s\n' '{"AttachedPolicies":[],"IsTruncated":true}' ;;
      *) exit 96 ;;
    esac
    ;;
  *' iam list-role-policies '*)
    case "${FAKE_INLINE_POLICY_MODE:-approved}" in
      approved) printf '%s\n' '{"PolicyNames":["airbob-dataset-publisher"],"IsTruncated":false}' ;;
      missing) printf '%s\n' '{"PolicyNames":[],"IsTruncated":false}' ;;
      extra) printf '%s\n' '{"PolicyNames":["airbob-dataset-publisher","extra"],"IsTruncated":false}' ;;
      truncated) printf '%s\n' '{"PolicyNames":["airbob-dataset-publisher"],"IsTruncated":true}' ;;
      *) exit 97 ;;
    esac
    ;;
  *' iam get-role '*)
    jq -n \
      --arg principal 'arn:aws:iam::942632789808:user/admin-eeoos' \
      --arg trustMode "${FAKE_TRUST_MODE:-approved}" '
      {
        Role:{
          RoleName:"airbob-dataset-publisher",
          AssumeRolePolicyDocument:{
            Version:"2012-10-17",
            Statement:[{
              Sid:"ApprovedLocalPrincipals",
              Effect:"Allow",
              Principal:{AWS:$principal},
              Action:"sts:AssumeRole",
              Condition:{Bool:{"aws:MultiFactorAuthPresent":"true"}}
            }]
          }
        }
      }
      | if $trustMode == "broadened" then
          .Role.AssumeRolePolicyDocument.Statement[0].Principal.AWS = [
            $principal,
            "arn:aws:iam::942632789808:user/dev-eeoos"
          ]
        else . end
    '
    ;;
  *' iam get-role-policy '*)
    writer_release=${FAKE_WRITER_RELEASE:-__disabled__}
    jq -n \
      --arg bucket "${FAKE_BUCKET:?}" \
      --arg release "$writer_release" \
      --arg policyMode "${FAKE_POLICY_MODE:-approved}" '
      {
        RoleName:"airbob-dataset-publisher",
        PolicyName:"airbob-dataset-publisher",
        PolicyDocument:{
          Version:"2012-10-17",
          Statement:[
            {
              Sid:"ReadPublishedDatasetBytes",
              Effect:"Allow",
              Action:["s3:GetObject","s3:GetObjectVersion"],
              Resource:[
                ("arn:aws:s3:::" + $bucket + "/datasets/*"),
                ("arn:aws:s3:::" + $bucket + "/elasticsearch/releases/*"),
                ("arn:aws:s3:::" + $bucket + "/elasticsearch/seals/*")
              ]
            },
            {
              Sid:"WriteImmutableDatasetRelease",
              Effect:"Allow",
              Action:"s3:PutObject",
              Resource:("arn:aws:s3:::" + $bucket + "/datasets/*")
            },
            {
              Sid:"WriteElasticsearchSnapshotRepository",
              Effect:"Allow",
              Action:["s3:PutObject","s3:PutObjectAcl"],
              Resource:("arn:aws:s3:::" + $bucket + "/elasticsearch/releases/" + $release + "/*")
            },
            {
              Sid:"ManageElasticsearchSnapshotRepository",
              Effect:"Allow",
              Action:["s3:AbortMultipartUpload","s3:DeleteObject"],
              Resource:("arn:aws:s3:::" + $bucket + "/elasticsearch/releases/" + $release + "/*")
            },
            {
              Sid:"SealElasticsearchSnapshotRelease",
              Effect:"Allow",
              Action:"s3:PutObject",
              Resource:("arn:aws:s3:::" + $bucket + "/elasticsearch/seals/" + $release + ".json")
            }
          ]
        }
      }
      | if $policyMode == "wildcard-action" then
          .PolicyDocument.Statement += [{
            Sid:"DriftedWildcardWriter",
            Effect:"Allow",
            Action:"s3:Put*",
            Resource:("arn:aws:s3:::" + $bucket + "/elasticsearch/releases/rehearsal-v20/*")
          }]
        elif $policyMode == "not-action" then
          .PolicyDocument.Statement += [{
            Sid:"DriftedNotActionWriter",
            Effect:"Allow",
            NotAction:["s3:GetObject","s3:ListBucket"],
            Resource:("arn:aws:s3:::" + $bucket + "/elasticsearch/releases/rehearsal-v20/*")
          }]
        elif $policyMode == "extra-non-s3" then
          .PolicyDocument.Statement += [{
            Sid:"DriftedRoleEscalation",
            Effect:"Allow",
            Action:"sts:AssumeRole",
            Resource:"*"
          }]
        elif $policyMode == "active-seal-only" then
          (.PolicyDocument.Statement[] | select(.Sid == "SealElasticsearchSnapshotRelease").Resource) =
            ("arn:aws:s3:::" + $bucket + "/elasticsearch/seals/rehearsal-v20.json")
        elif $policyMode == "missing-seal-read" then
          (.PolicyDocument.Statement[] | select(.Sid == "ReadPublishedDatasetBytes").Resource) -=
            [("arn:aws:s3:::" + $bucket + "/elasticsearch/seals/*")]
        elif $policyMode == "broad-seal-read" then
          (.PolicyDocument.Statement[] | select(.Sid == "ReadPublishedDatasetBytes").Resource) +=
            [("arn:aws:s3:::" + $bucket + "/elasticsearch/*")]
        else . end
    '
    ;;
  *' s3api get-bucket-location '*)
    jq -n --arg region "${FAKE_BUCKET_REGION:-ap-northeast-2}" \
      '{LocationConstraint:$region}'
    ;;
  *' s3api head-object '*)
    key=''
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --key) key=$2; shift 2 ;;
        --bucket) shift 2 ;;
        --no-cli-pager) shift ;;
        *) shift ;;
      esac
    done
    if [[ "$key" == */manifest.json && -n "${FAKE_COMPLETION_RACE_SOURCE:-}" ]]; then
      count_file="${FAKE_S3_ROOT:?}/.marker-head-count"
      count=0
      [[ ! -f "$count_file" ]] || count=$(<"$count_file")
      count=$((count + 1))
      printf '%s\n' "$count" > "$count_file"
      if [[ "$count" -eq "${FAKE_COMPLETION_RACE_AT:-0}" ]]; then
        prefix="${key%/manifest.json}"
        mkdir -p "${FAKE_S3_ROOT:?}/$prefix"
        cp -R "${FAKE_COMPLETION_RACE_SOURCE}/." "${FAKE_S3_ROOT:?}/$prefix/"
      fi
    fi
    [[ -f "${FAKE_S3_ROOT:?}/$key" ]] || exit 1
    if [[ "$key" == elasticsearch/seals/*.json ]]; then
      content_length=$(wc -c < "${FAKE_S3_ROOT:?}/$key" | tr -d '[:space:]')
      jq -n \
        --arg versionId "${FAKE_SEAL_VERSION_ID-seal-version-1}" \
        --arg encryption "${FAKE_SEAL_ENCRYPTION-AES256}" \
        --arg contentType "${FAKE_SEAL_CONTENT_TYPE-application/json}" \
        --argjson contentLength "$content_length" \
        '{VersionId:$versionId,ServerSideEncryption:$encryption,ContentType:$contentType,ContentLength:$contentLength}'
    fi
    ;;
  *' s3api get-object '*)
    key=''
    destination=''
    version_id=''
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --key) key=$2; shift 2 ;;
        --version-id) version_id=$2; shift 2 ;;
        --bucket) shift 2 ;;
        --output) shift 2 ;;
        --no-cli-pager) shift ;;
        --*) shift ;;
        *) destination=$1; shift ;;
      esac
    done
    [[ -n "$key" && -n "$destination" ]]
    cp "${FAKE_S3_ROOT:?}/$key" "$destination"
    if [[ "$key" == elasticsearch/seals/*.json ]]; then
      [[ "$version_id" == "${FAKE_SEAL_VERSION_ID-seal-version-1}" ]] || exit 1
      jq -n \
        --arg versionId "$version_id" \
        --arg encryption "${FAKE_SEAL_ENCRYPTION-AES256}" \
        --arg contentType "${FAKE_SEAL_CONTENT_TYPE-application/json}" \
        '{VersionId:$versionId,ServerSideEncryption:$encryption,ContentType:$contentType}'
    fi
    ;;
  *' s3api list-objects-v2 '*)
    prefix=''
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --prefix) prefix=$2; shift 2 ;;
        --bucket|--query|--output) shift 2 ;;
        --no-cli-pager) shift ;;
        *) shift ;;
      esac
    done
    if [[ -d "${FAKE_S3_ROOT:?}/$prefix" ]]; then
      find "${FAKE_S3_ROOT:?}/$prefix" -type f ! -name '.marker-head-count' -print \
        | LC_ALL=C sort \
        | while IFS= read -r path; do printf '%s\n' "${path#${FAKE_S3_ROOT}/}"; done \
        | jq -Rsc '{Contents:(split("\n") | map(select(length > 0)) | map({Key:.}))}'
    else
      printf '%s\n' '{"Contents":[]}'
    fi
    ;;
  *' s3api list-object-versions '*)
    key_marker=''
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --key-marker) key_marker=$2; shift 2 ;;
        --bucket|--prefix|--version-id-marker|--output) shift 2 ;;
        --no-paginate|--no-cli-pager) shift ;;
        *) shift ;;
      esac
    done
    count_file="${FAKE_S3_ROOT:?}/.version-list-count"
    count=0
    [[ ! -f "$count_file" ]] || count=$(<"$count_file")
    count=$((count + 1))
    printf '%s\n' "$count" > "$count_file"
    if [[ "$count" -ge 3 && -n "${FAKE_VERSION_INVENTORY_DRIFT:-}" ]]; then
      command cat "$FAKE_VERSION_INVENTORY_DRIFT"
    elif [[ -n "$key_marker" ]]; then
      command cat "${FAKE_VERSION_INVENTORY_SECOND:?}"
    else
      command cat "${FAKE_VERSION_INVENTORY:?}"
    fi
    ;;
  *' s3 cp '*)
    shift 2
    source_path=$1
    destination=$2
    shift 2
    no_overwrite=false
    encryption=''
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --no-overwrite) no_overwrite=true; shift ;;
        --sse) encryption=$2; shift 2 ;;
        --only-show-errors|--no-progress) shift ;;
        *) printf 'unexpected fake S3 copy argument: %s\n' "$1" >&2; exit 1 ;;
      esac
    done
    [[ "$no_overwrite" == true && "$encryption" == AES256 ]] || exit 1
    key=$(key_from_s3_uri "$destination")
    target="${FAKE_S3_ROOT:?}/$key"
    if [[ "$key" == "${FAKE_CP_RACE_KEY:-}" && ! -f "${FAKE_S3_ROOT}/.cp-race-fired" ]]; then
      : > "${FAKE_S3_ROOT}/.cp-race-fired"
      mkdir -p "${target%/*}"
      if [[ "${FAKE_CP_RACE_BYTES:-same}" == same ]]; then
        cp "$source_path" "$target"
      else
        printf '%s\n' 'racing-different-bytes' > "$target"
      fi
      exit 1
    fi
    [[ ! -e "$target" ]] || exit 1
    mkdir -p "${target%/*}"
    cp "$source_path" "$target"
    ;;
  *)
    printf 'unexpected fake AWS call: %s\n' "$*" >&2
    exit 1
    ;;
esac
EOF
chmod 700 "$fake_bin/aws"

bucket=airbob-performance-lab-dataset-942632789808
release=rehearsal-v20
prefix="datasets/$release"
secret_fixture='publisher-secret-must-not-leak'

version_inventory="$temp_dir/version-inventory.json"
version_inventory_second="$temp_dir/version-inventory-second.json"
version_inventory_drift="$temp_dir/version-inventory-drift.json"
cat > "$version_inventory" <<'JSON'
{
  "IsTruncated": true,
  "NextKeyMarker": "elasticsearch/releases/rehearsal-v20/index-0",
  "NextVersionIdMarker": "version-1",
  "Versions": [{
    "Key": "elasticsearch/releases/rehearsal-v20/index-0",
    "VersionId": "version-1",
    "IsLatest": true,
    "Size": 4,
    "ETag": "\"abcd\"",
    "ChecksumAlgorithm": ["SHA256"]
  }],
  "DeleteMarkers": []
}
JSON
cat > "$version_inventory_second" <<'JSON'
{
  "IsTruncated": false,
  "Versions": [{
    "Key": "elasticsearch/releases/rehearsal-v20/snap-airbob-rehearsal-v20.dat",
    "VersionId": "version-2",
    "IsLatest": true,
    "Size": 5,
    "ETag": "\"efgh\"",
    "ChecksumAlgorithm": []
  }],
  "DeleteMarkers": []
}
JSON
jq '.IsTruncated = false | del(.NextKeyMarker, .NextVersionIdMarker) | .Versions[0].VersionId = "version-drift"' \
  "$version_inventory" > "$version_inventory_drift"

canonical_inventory() {
  jq -cS '
    ([.Versions[]? | {
      kind:"version", key:.Key, versionId:.VersionId, isLatest:.IsLatest,
      size:.Size, eTag:.ETag,
      checksumAlgorithms: ((.ChecksumAlgorithm // []) | sort)
    }] + [.DeleteMarkers[]? | {
      kind:"delete-marker", key:.Key, versionId:.VersionId, isLatest:.IsLatest
    }]) | sort_by(.key, .kind, .versionId)[]
  ' "$1"
}

inventory_jsonl="$temp_dir/inventory.jsonl"
inventory_unsorted="$temp_dir/inventory-unsorted.jsonl"
canonical_inventory "$version_inventory" > "$inventory_unsorted"
canonical_inventory "$version_inventory_second" >> "$inventory_unsorted"
jq -cS -s 'sort_by(.key, .kind, .versionId)[]' "$inventory_unsorted" > "$inventory_jsonl"
inventory_sha=$(sha256_file "$inventory_jsonl")

make_release() {
  local root=$1
  local search_enabled=$2
  local dump_sha snapshot_reference_sha
  mkdir -p "$root/mysql" "$root/benchmark"
  printf '%s\n' 'canonical-dump-bytes' > "$root/mysql/airbob.sql.zst"
  dump_sha=$(sha256_file "$root/mysql/airbob.sql.zst")
  printf '%s  airbob.sql.zst\n' "$dump_sha" > "$root/mysql/sha256.txt"
  printf '%s\n' '{"datasetVersion":"fixture-v1"}' > "$root/benchmark/manifest.json"

  if [[ "$search_enabled" == true ]]; then
    mkdir -p "$root/elasticsearch"
    jq -n '
      {
        schemaVersion:2,
        repository:"airbob-dataset-readonly",
        bucket:"airbob-performance-lab-dataset-942632789808",
        basePath:"elasticsearch/releases/rehearsal-v20",
        snapshot:"airbob-rehearsal-v20",
        logicalAlias:"accommodations",
        snapshotIndex:"accommodations-v20260817001530",
        elasticsearchVersion:"8.18.8",
        imageDigest:("sha256:" + ("a" * 64)),
        documentCount:10,
        mappingSha256:("b" * 64),
        dbIdsSha256:("c" * 64),
        esIdsSha256:("c" * 64),
        dbDocumentIdentityPairsSha256:("9" * 64),
        esDocumentIdentityPairsSha256:("9" * 64),
        contentFingerprintSha256:("d" * 64)
      }
    ' > "$root/elasticsearch/snapshot-reference.json"
    jq -n '
      {
        datasetRelease:"rehearsal-v20",
        datasetRunId:"20260817T001530Z-12345678",
        releaseKind:"evidence",
        source:{canonicalPayloadSha256:("f" * 64)},
        search:{
          enabled:true,
          snapshotReferenceKey:"elasticsearch/snapshot-reference.json",
          repository:"airbob-dataset-readonly",
          elasticsearchVersion:"8.18.8",
          imageDigest:("sha256:" + ("a" * 64)),
          requiredPlugins:["analysis-nori","repository-s3"],
          logicalAlias:"accommodations",
          snapshotIndex:"accommodations-v20260817001530",
          documentCount:10,
          mappingSha256:("b" * 64),
          databaseAccommodationIdsSha256:("c" * 64),
          elasticsearchAccommodationIdsSha256:("c" * 64),
          databaseDocumentIdentityPairsSha256:("9" * 64),
          elasticsearchDocumentIdentityPairsSha256:("9" * 64),
          contentFingerprintSha256:("d" * 64)
        }
      }
    ' > "$root/manifest.json"
    snapshot_reference_sha=$(sha256_file "$root/elasticsearch/snapshot-reference.json")
    jq -n \
      --arg inventorySha "$inventory_sha" \
      --arg referenceSha "$snapshot_reference_sha" '
      {
        schemaVersion:2,
        datasetRelease:"rehearsal-v20",
        datasetRunId:"20260817T001530Z-12345678",
        sourceReleasePayloadSha256:("f" * 64),
        createdAt:"2026-08-17T00:00:00Z",
        producer:{
          elasticsearchVersion:"8.18.8",
          imageDigest:("sha256:" + ("a" * 64)),
          client:"airbob_dataset_producer"
        },
        repository:{
          bucket:"airbob-performance-lab-dataset-942632789808",
          basePath:"elasticsearch/releases/rehearsal-v20",
          writerName:"airbob-dataset-producer",
          readerName:"airbob-dataset-readonly",
          verificationNodeCount:1,
          inventory:{
            algorithm:"s3-list-object-versions-v1",
            sha256:$inventorySha,
            entryCount:2,
            totalVersionBytes:9
          }
        },
        snapshot:{
          name:"airbob-rehearsal-v20",
          uuid:"snapshot-uuid",
          state:"SUCCESS",
          version:"8.18.8",
          indices:["accommodations-v20260817001530"],
          includeGlobalState:false,
          totalShards:1,
          successfulShards:1,
          failedShards:0,
          metadataSha256:("e" * 64)
        },
        validation:{
          snapshotReferenceSha256:$referenceSha,
          documentCount:10,
          mappingSha256:("b" * 64),
          dbIdsSha256:("c" * 64),
          esIdsSha256:("c" * 64),
          dbDocumentIdentityPairsSha256:("9" * 64),
          esDocumentIdentityPairsSha256:("9" * 64),
          contentFingerprintSha256:("d" * 64)
        }
      }
    ' > "$root/snapshot-receipt.json"
  else
    jq -n '{datasetRelease:"rehearsal-v20",releaseKind:"pipeline-rehearsal",search:{enabled:false}}' \
      > "$root/manifest.json"
  fi
}

reset_fake_s3() {
  if [[ -f "$call_log" ]] && grep -Fq -- "$secret_fixture" "$call_log"; then
    fail 'publisher replayed a secret into an AWS command'
  fi
  rm -rf "$fake_s3"
  mkdir -p "$fake_s3"
  : > "$call_log"
}

install_snapshot_seal() {
  local source=$1
  local key="elasticsearch/seals/$release.json"
  mkdir -p "$fake_s3/${key%/*}"
  cp "$source" "$fake_s3/$key"
}

run_publisher() {
  env \
    PATH="$fake_bin:$(dirname "$(command -v jq)"):/usr/bin:/bin:/sbin" \
    AWS_REGION="${PUBLISH_AWS_REGION:-ap-northeast-2}" \
    AIRBOB_TEST_SECRET="$secret_fixture" \
    FAKE_CALL_LOG="$call_log" \
    FAKE_S3_ROOT="$fake_s3" \
    FAKE_BUCKET="$bucket" \
    FAKE_VERSION_INVENTORY="$version_inventory" \
    FAKE_VERSION_INVENTORY_SECOND="$version_inventory_second" \
    FAKE_ACCOUNT="${FAKE_ACCOUNT:-942632789808}" \
    FAKE_ARN="${FAKE_ARN:-arn:aws:sts::942632789808:assumed-role/airbob-dataset-publisher/test-session}" \
    FAKE_BUCKET_REGION="${FAKE_BUCKET_REGION:-ap-northeast-2}" \
    FAKE_WRITER_RELEASE="${FAKE_WRITER_RELEASE:-__disabled__}" \
    FAKE_POLICY_MODE="${FAKE_POLICY_MODE:-approved}" \
    FAKE_TRUST_MODE="${FAKE_TRUST_MODE:-approved}" \
    FAKE_ATTACHED_POLICY_MODE="${FAKE_ATTACHED_POLICY_MODE:-approved}" \
    FAKE_INLINE_POLICY_MODE="${FAKE_INLINE_POLICY_MODE:-approved}" \
    FAKE_CP_RACE_KEY="${FAKE_CP_RACE_KEY:-}" \
    FAKE_CP_RACE_BYTES="${FAKE_CP_RACE_BYTES:-same}" \
    FAKE_COMPLETION_RACE_SOURCE="${FAKE_COMPLETION_RACE_SOURCE:-}" \
    FAKE_COMPLETION_RACE_AT="${FAKE_COMPLETION_RACE_AT:-0}" \
    FAKE_VERSION_INVENTORY_DRIFT="${FAKE_VERSION_INVENTORY_DRIFT:-}" \
    FAKE_SEAL_VERSION_ID="${FAKE_SEAL_VERSION_ID-seal-version-1}" \
    FAKE_SEAL_ENCRYPTION="${FAKE_SEAL_ENCRYPTION-AES256}" \
    FAKE_SEAL_CONTENT_TYPE="${FAKE_SEAL_CONTENT_TYPE-application/json}" \
    "$fixture/infra/aws/scripts/publish-dataset-release.sh" "$@"
}

expect_failure() {
  local label=$1
  shift
  if "$@" >"$temp_dir/$label.out" 2>&1; then
    fail "expected failure: $label"
  fi
  if grep -Fq -- "$secret_fixture" "$temp_dir/$label.out"; then
    fail "publisher leaked a secret while failing: $label"
  fi
}

assert_remote_inventory() {
  local expected=$1
  local actual
  actual=$(find "$fake_s3/$prefix" -type f -print | sed "s#^$fake_s3/##" | LC_ALL=C sort)
  [[ "$actual" == "$expected" ]] || fail 'remote dataset inventory is not exact'
}

assert_no_dataset_writes() {
  if grep -Eq -- "^aws s3 cp .*s3://$bucket/datasets/|^aws s3api put-object .*--key datasets/" "$call_log"; then
    fail 'publisher wrote a dataset object before validating the snapshot seal'
  fi
}

rehearsal="$temp_dir/rehearsal"
make_release "$rehearsal" false
expected_rehearsal_inventory=$(printf '%s\n' \
  "$prefix/benchmark/manifest.json" \
  "$prefix/manifest.json" \
  "$prefix/mysql/airbob.sql.zst" \
  "$prefix/mysql/sha256.txt" | LC_ALL=C sort)

reset_fake_s3
happy_output=$(run_publisher "$rehearsal" "$release" pipeline-rehearsal "$bucket")
[[ "$happy_output" == *"dataset_manifest=s3://$bucket/$prefix/manifest.json"* ]] \
  || fail 'happy publication did not return the manifest URI'
[[ "$happy_output" == *"manifest_sha256=$(sha256_file "$rehearsal/manifest.json")"* ]] \
  || fail 'happy publication did not return the manifest SHA-256'
assert_remote_inventory "$expected_rehearsal_inventory"

before_idempotent=$(grep -Fc 'aws s3 cp ' "$call_log" || true)
run_publisher "$rehearsal" "$release" pipeline-rehearsal "$bucket" >/dev/null
assert_remote_inventory "$expected_rehearsal_inventory"
after_idempotent=$(grep -Fc 'aws s3 cp ' "$call_log" || true)
[[ "$before_idempotent" == "$after_idempotent" ]] \
  || fail 'idempotent publication wrote to a completed release'

reset_fake_s3
mkdir -p "$fake_s3/$prefix/mysql"
cp "$rehearsal/mysql/airbob.sql.zst" "$fake_s3/$prefix/mysql/airbob.sql.zst"
run_publisher "$rehearsal" "$release" pipeline-rehearsal "$bucket" >/dev/null
assert_remote_inventory "$expected_rehearsal_inventory"

reset_fake_s3
mkdir -p "$fake_s3/$prefix/mysql"
printf '%s\n' 'different-existing-dump' > "$fake_s3/$prefix/mysql/airbob.sql.zst"
expect_failure partial-mismatch run_publisher \
  "$rehearsal" "$release" pipeline-rehearsal "$bucket"
[[ ! -e "$fake_s3/$prefix/manifest.json" ]] \
  || fail 'publisher completed a release over mismatched partial bytes'

reset_fake_s3
FAKE_CP_RACE_KEY="$prefix/mysql/sha256.txt" FAKE_CP_RACE_BYTES=same \
  run_publisher "$rehearsal" "$release" pipeline-rehearsal "$bucket" >/dev/null
assert_remote_inventory "$expected_rehearsal_inventory"

reset_fake_s3
FAKE_CP_RACE_KEY="$prefix/mysql/sha256.txt" FAKE_CP_RACE_BYTES=different \
  expect_failure immutable-race-different run_publisher \
    "$rehearsal" "$release" pipeline-rehearsal "$bucket"
[[ ! -e "$fake_s3/$prefix/manifest.json" ]] \
  || fail 'publisher completed a release after a different-byte write race'

reset_fake_s3
FAKE_COMPLETION_RACE_SOURCE="$rehearsal" FAKE_COMPLETION_RACE_AT=3 \
  run_publisher "$rehearsal" "$release" pipeline-rehearsal "$bucket" >/dev/null
assert_remote_inventory "$expected_rehearsal_inventory"

reset_fake_s3
mkdir -p "$fake_s3/$prefix"
cp -R "$rehearsal/." "$fake_s3/$prefix/"
printf '%s\n' 'corrupt-completion-marker' > "$fake_s3/$prefix/manifest.json"
expect_failure corrupt-completion run_publisher \
  "$rehearsal" "$release" pipeline-rehearsal "$bucket"
if grep -Fq ' s3 cp ' "$call_log"; then
  fail 'publisher tried to repair a completed release'
fi

reset_fake_s3
mkdir -p "$fake_s3/$prefix/unexpected"
printf '%s\n' extra > "$fake_s3/$prefix/unexpected/object"
expect_failure remote-extra run_publisher \
  "$rehearsal" "$release" pipeline-rehearsal "$bucket"

reset_fake_s3
mkdir -p "$fake_s3/$prefix"
cp -R "$rehearsal/." "$fake_s3/$prefix/"
rm "$fake_s3/$prefix/benchmark/manifest.json"
expect_failure completed-missing run_publisher \
  "$rehearsal" "$release" pipeline-rehearsal "$bucket"
if grep -Fq ' s3 cp ' "$call_log"; then
  fail 'publisher tried to repair a completed release with a missing payload'
fi

local_extra="$temp_dir/local-extra"
cp -R "$rehearsal" "$local_extra"
printf '%s\n' extra > "$local_extra/unexpected.txt"
reset_fake_s3
expect_failure local-extra run_publisher \
  "$local_extra" "$release" pipeline-rehearsal "$bucket"

FAKE_ACCOUNT=111111111111 expect_failure wrong-account run_publisher \
  "$rehearsal" "$release" pipeline-rehearsal "$bucket"
FAKE_ARN=arn:aws:sts::942632789808:assumed-role/airbob-lab-operator/test \
  expect_failure wrong-role run_publisher \
    "$rehearsal" "$release" pipeline-rehearsal "$bucket"
reset_fake_s3
FAKE_WRITER_RELEASE="$release" expect_failure active-snapshot-writer run_publisher \
  "$rehearsal" "$release" pipeline-rehearsal "$bucket"
if grep -Eq '^aws s3(api)? ' "$call_log"; then
  fail 'publisher contacted S3 while a snapshot writer grant was active'
fi
for unsafe_policy_mode in wildcard-action not-action extra-non-s3; do
  reset_fake_s3
  FAKE_POLICY_MODE="$unsafe_policy_mode" \
    expect_failure "unsafe-policy-$unsafe_policy_mode" run_publisher \
      "$rehearsal" "$release" pipeline-rehearsal "$bucket"
  if grep -Eq '^aws s3(api)? ' "$call_log"; then
    fail 'publisher contacted S3 while an unsafe policy could retain writer access'
  fi
done
for unsafe_policy_mode in missing-seal-read broad-seal-read; do
  reset_fake_s3
  FAKE_POLICY_MODE="$unsafe_policy_mode" \
    expect_failure "unsafe-policy-$unsafe_policy_mode" run_publisher \
      "$rehearsal" "$release" pipeline-rehearsal "$bucket"
  if grep -Eq '^aws s3(api)? ' "$call_log"; then
    fail 'publisher contacted S3 without the exact read-only snapshot seal boundary'
  fi
done
reset_fake_s3
FAKE_POLICY_MODE=active-seal-only \
  expect_failure active-snapshot-seal run_publisher \
    "$rehearsal" "$release" pipeline-rehearsal "$bucket"
if grep -Eq '^aws s3(api)? ' "$call_log"; then
  fail 'publisher contacted S3 while a real snapshot seal grant was active'
fi
reset_fake_s3
FAKE_TRUST_MODE=broadened expect_failure broadened-role-trust run_publisher \
  "$rehearsal" "$release" pipeline-rehearsal "$bucket"
if grep -Eq '^aws s3(api)? ' "$call_log"; then
  fail 'publisher contacted S3 while unreviewed principals could assume its role'
fi
for attached_policy_mode in one truncated; do
  reset_fake_s3
  FAKE_ATTACHED_POLICY_MODE="$attached_policy_mode" \
    expect_failure "attached-policy-$attached_policy_mode" run_publisher \
      "$rehearsal" "$release" pipeline-rehearsal "$bucket"
  if grep -Eq '^aws s3(api)? ' "$call_log"; then
    fail 'publisher contacted S3 while managed-policy inspection was unsafe'
  fi
done
for inline_policy_mode in missing extra truncated; do
  reset_fake_s3
  FAKE_INLINE_POLICY_MODE="$inline_policy_mode" \
    expect_failure "inline-policy-$inline_policy_mode" run_publisher \
      "$rehearsal" "$release" pipeline-rehearsal "$bucket"
  if grep -Eq '^aws s3(api)? ' "$call_log"; then
    fail 'publisher contacted S3 while inline-policy inspection was unsafe'
  fi
done
PUBLISH_AWS_REGION=us-east-1 expect_failure wrong-region run_publisher \
  "$rehearsal" "$release" pipeline-rehearsal "$bucket"
expect_failure wrong-bucket run_publisher "$rehearsal" "$release" pipeline-rehearsal \
  airbob-performance-lab-dataset-111111111111
FAKE_BUCKET_REGION=us-east-1 expect_failure wrong-bucket-region run_publisher \
  "$rehearsal" "$release" pipeline-rehearsal "$bucket"
expect_failure receipt-for-disabled-search run_publisher \
  "$rehearsal" "$release" pipeline-rehearsal "$bucket" "$rehearsal/manifest.json"

search_release="$temp_dir/search-release"
make_release "$search_release" true
receipt="$temp_dir/snapshot-receipt.json"
mv "$search_release/snapshot-receipt.json" "$receipt"
snapshot_seal="$temp_dir/snapshot-seal.json"
snapshot_reference_sha=$(sha256_file "$search_release/elasticsearch/snapshot-reference.json")
snapshot_receipt_sha=$(sha256_file "$receipt")
snapshot_created_at=$(jq -r '.createdAt' "$receipt")
snapshot_name=$(jq -r '.snapshot.name' "$receipt")
jq -nS \
  --arg release "$release" \
  --arg snapshot "$snapshot_name" \
  --arg referenceSha "$snapshot_reference_sha" \
  --arg receiptSha "$snapshot_receipt_sha" \
  --arg createdAt "$snapshot_created_at" '
  {
    schemaVersion:1,
    datasetRelease:$release,
    snapshot:$snapshot,
    snapshotReferenceSha256:$referenceSha,
    snapshotReceiptSha256:$receiptSha,
    createdAt:$createdAt
  }
' > "$snapshot_seal"
expected_search_inventory=$(printf '%s\n' \
  "$prefix/benchmark/manifest.json" \
  "$prefix/elasticsearch/snapshot-reference.json" \
  "$prefix/manifest.json" \
  "$prefix/mysql/airbob.sql.zst" \
  "$prefix/mysql/sha256.txt" | LC_ALL=C sort)

reset_fake_s3
expect_failure missing-search-receipt run_publisher \
  "$search_release" "$release" evidence "$bucket"

reset_fake_s3
expect_failure missing-snapshot-seal run_publisher \
  "$search_release" "$release" evidence "$bucket" "$receipt"
assert_no_dataset_writes

reference_mismatch_seal="$temp_dir/reference-mismatch-seal.json"
jq '.snapshotReferenceSha256 = ("0" * 64)' "$snapshot_seal" > "$reference_mismatch_seal"
reset_fake_s3
install_snapshot_seal "$reference_mismatch_seal"
expect_failure snapshot-seal-reference-mismatch run_publisher \
  "$search_release" "$release" evidence "$bucket" "$receipt"
assert_no_dataset_writes

receipt_mismatch_seal="$temp_dir/receipt-mismatch-seal.json"
jq '.snapshotReceiptSha256 = ("0" * 64)' "$snapshot_seal" > "$receipt_mismatch_seal"
reset_fake_s3
install_snapshot_seal "$receipt_mismatch_seal"
expect_failure snapshot-seal-receipt-mismatch run_publisher \
  "$search_release" "$release" evidence "$bucket" "$receipt"
assert_no_dataset_writes

reset_fake_s3
install_snapshot_seal "$snapshot_seal"
FAKE_SEAL_VERSION_ID=null \
  expect_failure unversioned-snapshot-seal run_publisher \
    "$search_release" "$release" evidence "$bucket" "$receipt"
assert_no_dataset_writes

reset_fake_s3
install_snapshot_seal "$snapshot_seal"
run_publisher "$search_release" "$release" evidence "$bucket" "$receipt" >/dev/null
assert_remote_inventory "$expected_search_inventory"
grep -Eq -- "^aws s3api get-object .*--key elasticsearch/seals/$release.json .*--version-id seal-version-1" "$call_log" \
  || fail 'search publication did not read the exact immutable seal version'
[[ "$(<"$fake_s3/.version-list-count")" -eq 4 ]] \
  || fail 'search publication did not check snapshot inventory twice'
[[ "$(grep -Fc -- '--no-paginate' "$call_log")" -eq 4 ]] \
  || fail 'search publication did not use explicit snapshot inventory pages'
[[ "$(grep -Fc -- '--key-marker' "$call_log")" -eq 2 ]] \
  || fail 'search publication did not traverse every snapshot inventory page'

tampered_receipt="$temp_dir/tampered-receipt.json"
jq '.validation.documentCount = 11' "$receipt" > "$tampered_receipt"
reset_fake_s3
expect_failure mismatched-search-receipt run_publisher \
  "$search_release" "$release" evidence "$bucket" "$tampered_receipt"

mismatched_document_identity_receipt="$temp_dir/mismatched-document-identity-receipt.json"
jq '.validation.esDocumentIdentityPairsSha256 = ("8" * 64)' \
  "$receipt" > "$mismatched_document_identity_receipt"
reset_fake_s3
expect_failure mismatched-document-identity-receipt run_publisher \
  "$search_release" "$release" evidence "$bucket" "$mismatched_document_identity_receipt"

missing_document_identity_receipt="$temp_dir/missing-document-identity-receipt.json"
jq 'del(.validation.dbDocumentIdentityPairsSha256)' \
  "$receipt" > "$missing_document_identity_receipt"
reset_fake_s3
expect_failure missing-document-identity-receipt-schema run_publisher \
  "$search_release" "$release" evidence "$bucket" "$missing_document_identity_receipt"

wrong_source_receipt="$temp_dir/wrong-source-receipt.json"
jq '.sourceReleasePayloadSha256 = ("e" * 64)' "$receipt" > "$wrong_source_receipt"
reset_fake_s3
expect_failure mismatched-source-release run_publisher \
  "$search_release" "$release" evidence "$bucket" "$wrong_source_receipt"

reset_fake_s3
install_snapshot_seal "$snapshot_seal"
FAKE_VERSION_INVENTORY_DRIFT="$version_inventory_drift" \
  expect_failure snapshot-inventory-drift run_publisher \
    "$search_release" "$release" evidence "$bucket" "$receipt"
[[ ! -e "$fake_s3/$prefix/manifest.json" ]] \
  || fail 'publisher completed a release after snapshot inventory drift'

if grep -Fq -- "$secret_fixture" "$call_log"; then
  fail 'publisher replayed a secret into an AWS command'
fi
if grep -Eq -- 's3[[:space:]]+rm|delete-object|delete-objects' "$publisher" "$call_log"; then
  fail 'dataset publisher must never delete or clean up remote data'
fi

printf '%s\n' 'dataset release publisher test passed'
