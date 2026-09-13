#!/usr/bin/env bash
# The controller delivers these exact reviewed bytes; all diagnostics stay private.
set -euo pipefail
umask 077
export LC_ALL=C
fail() { printf '%s\n' 'B snapshot host bootstrap failed; private evidence retained.' >&2; exit 1; }
[[ $# == 1 && -f "$1" && ! -L "$1" ]] || fail
context=$1
run_id=$(jq -er '.runId' "$context")
dataset=$(jq -er '.datasetId' "$context")
operation_id=$(jq -er '.operationId' "$context")
mode=$(jq -er '.operation' "$context")
[[ "$run_id" =~ ^lab-[a-z0-9][a-z0-9-]{0,27}$ && "$run_id" != *--* && "$run_id" != *- ]] || fail
[[ "$dataset" =~ ^global-growth-b-[0-9a-f]{16}$ && "$operation_id" =~ ^[a-z0-9][a-z0-9-]{2,47}$ ]] || fail
[[ "$operation_id" != *--* && "$operation_id" != *- && "$mode" =~ ^(create|prepare|retire)$ ]] || fail
[[ "$(uname -s):$(uname -m)" == Linux:x86_64 ]] || fail
root="/opt/airbob/global-b/$run_id"
stage="$root/snapshot-$operation_id"
[[ ! -L "$root" && ! -e "$stage" ]] || fail
[[ "$mode" == retire || ! -e "$root/STOP" ]] || fail
[[ "$mode" == prepare || -f "$root/restore-config.json" ]] || fail
install -d -m 700 "$root"
mkdir -m 700 "$stage"
cp -- "$context" "$stage/context.json"
context="$stage/context.json"
exec > "$stage/bootstrap.log" 2>&1
trap 'printf "%s\n" "B snapshot bootstrap failed; no source deletion was authorized." >&2' ERR
for tool in curl sha256sum unzip tar jq stat setsid flock timeout; do command -v "$tool" >/dev/null || fail; done

deadline=$(jq -er '.deadlineEpoch' "$context")
[[ "$deadline" =~ ^[0-9]+$ && "$deadline" -gt "$(date +%s)" && "$deadline" -le "$(( $(date +%s) + 18000 ))" ]] || fail
aws_version=$(jq -er '.awsCli.version' "$context")
aws_sha=$(jq -er '.awsCli.archiveSha256' "$context")
[[ "$aws_version" == 2.34.64 && "$aws_sha" =~ ^[0-9a-f]{64}$ ]] || fail
if [[ ! -x "$root/aws-bin/aws" ]]; then
  [[ "$mode" == prepare && ! -e "$root/aws-cli" && ! -e "$root/aws-bin" ]] || fail
  curl --fail --silent --show-error --location --retry 3 --max-time 300 \
    -o "$stage/awscli.zip" "https://awscli.amazonaws.com/awscli-exe-linux-x86_64-$aws_version.zip"
  printf '%s  %s\n' "$aws_sha" "$stage/awscli.zip" | sha256sum --check --status
  unzip -q "$stage/awscli.zip" -d "$stage/aws-installer"
  "$stage/aws-installer/aws/install" --install-dir "$root/aws-cli" --bin-dir "$root/aws-bin" >/dev/null
  rm -rf -- "$stage/aws-installer" "$stage/awscli.zip"
fi
aws="$root/aws-bin/aws"
[[ "$("$aws" --version 2>&1)" == aws-cli/2.34.64\ * ]] || fail
export PATH="$root/aws-bin:$PATH" AWS_REGION=ap-northeast-2 AWS_DEFAULT_REGION=ap-northeast-2
export AWS_MAX_ATTEMPTS=1 AWS_RETRY_MODE=standard AWS_CLI_AUTO_PROMPT=off AWS_PAGER=''
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN AWS_SECURITY_TOKEN AWS_PROFILE AWS_DEFAULT_PROFILE
unset AWS_ENDPOINT_URL AWS_ENDPOINT_URL_S3 AWS_ENDPOINT_URL_STS AWS_ENDPOINT_URL_RDS AWS_ENDPOINT_URL_DYNAMODB
caller=$("$aws" --region "$AWS_REGION" sts get-caller-identity --query Arn --output text)
[[ "$caller" == "arn:aws:sts::942632789808:assumed-role/airbob-lab-host-$run_id-debezium/$(jq -er '.hostInstanceId' "$context")" ]] || fail

assert_lease() {
  [[ "$mode" == retire || ! -e "$root/STOP" ]] || fail
  [[ "$(date +%s)" -lt "$deadline" ]] || fail
  "$aws" --region "$AWS_REGION" --cli-connect-timeout 5 --cli-read-timeout 15 dynamodb get-item \
    --table-name airbob-performance-lab-orchestration-lease --consistent-read \
    --key '{"LockName":{"S":"airbob-performance-lab"}}' --output json > "$stage/lease-observation.json"
  jq -e --slurpfile context "$context" --argjson now "$(date +%s)" '
    $context[0].lease as $lease | .Item |
    .Owner.S == $lease.owner and .RunId.S == $lease.runId and .Command.S == $lease.command and
    .FencingToken.N == ($lease.fencingToken|tostring) and
    (.ExpiresAt.N|tonumber) > $now and (.CommandDeadline.N|tonumber) > $now' "$stage/lease-observation.json" >/dev/null || fail
}

download() {
  local ref=$1 destination=$2 bucket=$3 key version digest bytes remaining
  assert_lease
  key=$(jq -er '.key' "$ref"); version=$(jq -er '.versionId' "$ref")
  digest=$(jq -er '.sha256' "$ref"); bytes=$(jq -er '.bytes' "$ref")
  [[ "$key" =~ ^[A-Za-z0-9_./-]+$ && "/$key/" != */../* && "$digest" =~ ^[0-9a-f]{64}$ && "$bytes" =~ ^[1-9][0-9]*$ ]] || fail
  [[ "$version" =~ ^[A-Za-z0-9._~+/=-]+$ && "$version" != null && "$version" != None ]] || fail
  if [[ -e "$destination" ]]; then
    [[ -f "$destination" && ! -L "$destination" && "$(stat -c %s "$destination")" == "$bytes" ]] || fail
    printf '%s  %s\n' "$digest" "$destination" | sha256sum --check --status
    "$aws" --region "$AWS_REGION" s3api head-object --bucket "$bucket" --key "$key" --version-id "$version" \
      --output json > "$stage/download-head.json"
    [[ "$(jq -er '.VersionId' "$stage/download-head.json")" == "$version" && "$(jq -er '.ContentLength' "$stage/download-head.json")" == "$bytes" ]] || fail
    return
  fi
  remaining=$(( deadline - $(date +%s) ))
  timeout --signal=TERM "$remaining" "$aws" --region "$AWS_REGION" s3api get-object --bucket "$bucket" \
    --key "$key" --version-id "$version" "$destination" --output json > "$stage/download.json"
  [[ "$(jq -er '.VersionId' "$stage/download.json")" == "$version" && "$(stat -c %s "$destination")" == "$bytes" ]] || fail
  printf '%s  %s\n' "$digest" "$destination" | sha256sum --check --status
  assert_lease
}

digest=$(jq -er '.manifest.sha256' "$context")
[[ "$(jq -er '.manifest.key' "$context")" == "datasets/$dataset-aws-snapshots/operations/$run_id/$operation_id/manifest-$digest.json" ]] || fail
jq '.manifest' "$context" > "$stage/manifest-ref.json"
download "$stage/manifest-ref.json" "$stage/aws-snapshot-host.json" airbob-performance-lab-dataset-942632789808
manifest="$stage/aws-snapshot-host.json"
jq -e --slurpfile context "$context" '.schemaVersion == 1 and .kind == "global-growth-b-snapshot-host" and
  .operation == $context[0].operation and .datasetId == $context[0].datasetId and .runId == $context[0].runId and
  .operationId == $context[0].operationId and .toolSources == $context[0].toolSources and
  (.toolSources|keys) == ["growth_b_aws_contract.py","growth_b_aws_restore.py","growth_b_contract.py","growth_b_inventory.py", "growth_b_prepare.py","growth_b_runtime.py","growth_b_snapshot.py","growth_b_snapshot_host.py"]' "$manifest" >/dev/null || fail
jq '.awsPreparation' "$manifest" > "$stage/preparation-ref.json"
[[ "$(jq -er '.key' "$stage/preparation-ref.json")" == "datasets/$dataset-aws-preparation/aws-preparation-$(jq -er '.sha256' "$stage/preparation-ref.json").json" ]] || fail
download "$stage/preparation-ref.json" "$stage/aws-preparation.json" airbob-performance-lab-dataset-942632789808
preparation="$stage/aws-preparation.json"
[[ "$(jq -er '.scope' "$preparation")" == final-b-rds ]] || fail

extract_regular() {
  local archive=$1 destination=$2 maximum=$3 member expanded
  [[ ! -e "$destination" && "$maximum" =~ ^[1-9][0-9]*$ && "$maximum" -le 4294967296 ]] || fail
  tar -tzf "$archive" > "$stage/members"
  [[ "$(wc -l < "$stage/members")" -le 50000 && "$(sort -u "$stage/members" | wc -l)" -eq "$(wc -l < "$stage/members")" ]] || fail
  while IFS= read -r member; do
    [[ "$member" =~ ^[A-Za-z0-9_./+-]+$ && "$member" != /* && "/$member/" != */../* ]] || fail
  done < "$stage/members"
  tar -tvzf "$archive" > "$stage/types"
  [[ -z "$(awk 'substr($0,1,1)!="-" && substr($0,1,1)!="d" {print "unsafe"}' "$stage/types")" ]] || fail
  expanded=$(awk '{total+=$3} END {printf "%.0f",total}' "$stage/types")
  [[ "$expanded" -le "$maximum" ]] || fail
  mkdir -m 700 "$destination"
  tar --extract --gzip --file "$archive" --directory "$destination" --no-same-owner --no-same-permissions
}

if [[ ! -d "$root/toolchain" ]]; then
  [[ "$mode" == prepare ]] || fail
  required=$(jq -er '.storage.minimumStagingFreeBytes' "$preparation")
  available=$(df -PB1 "$root" | awk 'NR==2 {print $4}')
  [[ "$required" =~ ^[1-9][0-9]*$ && "$required" -ge 4294967296 && "$required" -lt 21474836480 && "$available" -ge "$required" ]] || fail
  for name in toolchain toolchainManifest; do
    jq --arg name "$name" '.files[$name]' "$preparation" > "$stage/toolchain-ref.json"
    [[ "$(jq -er '.key' "$stage/toolchain-ref.json")" == "datasets/$dataset-aws-preparation/files/"* ]] || fail
    if [[ "$name" == toolchain ]]; then destination="$root/toolchain.tar.gz"; else destination="$root/toolchain.json"; fi
    download "$stage/toolchain-ref.json" "$destination" airbob-performance-lab-dataset-942632789808
  done
  extract_regular "$root/toolchain.tar.gz" "$root/toolchain" "$(jq -er '.toolchain.unpackedBytes' "$preparation")"
fi
[[ -f "$root/toolchain.json" && ! -L "$root/toolchain.json" && ! -L "$root/toolchain" ]] || fail
printf '%s  %s\n' "$(jq -er '.files.toolchainManifest.sha256' "$preparation")" "$root/toolchain.json" | sha256sum --check --status
jq -e '.schemaVersion == 1 and (.files|type=="object") and (.files|length>0) and all(.files|to_entries[];
  (.key|test("^[A-Za-z0-9_./+-]+$")) and (.key|startswith("/")|not) and (.key|split("/")|index("..")|not) and
  (.value.sha256|test("^[0-9a-f]{64}$")))' "$root/toolchain.json" >/dev/null || fail
[[ -z "$(find "$root/toolchain" -type l -print -quit)" ]] || fail
jq -r '.files|keys[]' "$root/toolchain.json" | sort > "$stage/toolchain-expected"
(cd "$root/toolchain" && find . -type f | sed 's@^./@@' | sort) > "$stage/toolchain-actual"
cmp -s "$stage/toolchain-expected" "$stage/toolchain-actual" || fail
jq -r '.files|to_entries[]|"\(.value.sha256)  \(.key)"' "$root/toolchain.json" > "$stage/toolchain-checks"
(cd "$root/toolchain" && sha256sum --check --status "$stage/toolchain-checks")
export JAVA_HOME="$root/toolchain/jdk"
export PATH="$root/toolchain/python/bin:$JAVA_HOME/bin:$root/toolchain/mysql/bin:$PATH"
unset PYTHONPATH PYTHONHOME JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS JDK_JAVAC_OPTIONS CLASSPATH AIRBOB_ETL_BENCHMARK_PASSWORD
jq '.consumerTools' "$manifest" > "$stage/tools-ref.json"
[[ "$(jq -er '.key' "$stage/tools-ref.json")" == "datasets/$dataset-aws-snapshots/operations/$run_id/$operation_id/files/$(jq -er '.sha256' "$stage/tools-ref.json")-consumer-tools.tar.gz" ]] || fail
download "$stage/tools-ref.json" "$stage/consumer-tools.tar.gz" airbob-performance-lab-dataset-942632789808
extract_regular "$stage/consumer-tools.tar.gz" "$stage/tools" 4194304
[[ "$(find "$stage/tools" -type f | wc -l)" -eq 8 && "$(wc -l < "$stage/members")" -eq 8 ]] || fail
jq -r '.toolSources|to_entries[]|"\(.value)  \(.key)"' "$manifest" > "$stage/tool-checks"
(cd "$stage/tools" && sha256sum --check --status "$stage/tool-checks")
"$root/toolchain/python/bin/python3" "$stage/tools/growth_b_snapshot_host.py" validate --manifest "$manifest" --sha256 "$digest" \
  --dataset-id "$dataset" --run-id "$run_id" --operation-id "$operation_id"
assert_lease
if [[ "$mode" == prepare ]]; then
  [[ -z "$(docker ps -q)" ]] || fail
  redis_image=$(jq -er '.redisImage' "$context")
  [[ "$redis_image" =~ ^942632789808\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/[a-z0-9/_-]+@sha256:[0-9a-f]{64}$ ]] || fail
  export DOCKER_CONFIG="$stage/docker-client"; install -d -m 700 "$DOCKER_CONFIG"
  "$aws" --region "$AWS_REGION" ecr get-login-password | docker login --username AWS --password-stdin \
    942632789808.dkr.ecr.ap-northeast-2.amazonaws.com >/dev/null 2>&1
  timeout --signal=TERM "$(( deadline - $(date +%s) ))" docker pull --quiet "$redis_image" >/dev/null 2>&1
fi
assert_lease
exec 9> "$root/control.lock"
flock -x 9
[[ "$mode" == retire || ! -e "$root/STOP" ]] || fail
[[ ! -e "$root/process-group" ]] || fail
setsid "$root/toolchain/python/bin/python3" "$stage/tools/growth_b_snapshot_host.py" host --manifest "$manifest" --sha256 "$digest" \
  --dataset-id "$dataset" --run-id "$run_id" --operation-id "$operation_id" \
  --context "$context" --root "$root" --output "$stage" > "$stage/host.log" 2>&1 &
child=$!
start_ticks=$(awk '{print $22}' "/proc/$child/stat")
printf '%s %s\n' "$child" "$start_ticks" > "$root/process-group"
flock -u 9
trap 'kill -INT -- "-$child" 2>/dev/null || true; wait "$child" || true; exit 130' HUP INT TERM
status=0; wait "$child" || status=$?
trap - HUP INT TERM
flock -x 9
read -r recorded_pid recorded_ticks < "$root/process-group"
[[ "$recorded_pid" == "$child" && "$recorded_ticks" == "$start_ticks" ]] || fail
rm -f -- "$root/process-group"
flock -u 9
[[ "$status" -eq 0 ]] || fail
assert_lease
printf '%s\n' 'B snapshot host operation complete; controller retains every persistent-resource decision.'
