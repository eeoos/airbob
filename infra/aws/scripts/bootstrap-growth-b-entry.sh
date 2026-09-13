#!/usr/bin/env bash
# Public trusted entry delivered with an exact SHA by the reviewed SSM document.
set -euo pipefail
umask 077
export LC_ALL=C

fail() { printf '%s\n' 'B preparation bootstrap failed; inspect the private host receipt.' >&2; exit 1; }
[[ $# == 1 ]] || fail
context=$1
[[ -f "$context" && ! -L "$context" ]] || fail
run_id=$(jq -er '.runId' "$context")
dataset_id=$(jq -er '.datasetId' "$context")
[[ "$run_id" =~ ^lab-[a-z0-9][a-z0-9-]{0,27}$ && "$run_id" != *--* && "$run_id" != *- ]] || fail
[[ "$dataset_id" =~ ^global-growth-b-[0-9a-f]{16}$ && "$(uname -s):$(uname -m)" == Linux:x86_64 ]] || fail
root="/opt/airbob/global-b/$run_id"
[[ ! -e "$root/STOP" && ! -e "$root/started" ]] || fail
install -d -m 700 "$root"
(set -C; printf '%s\n' started > "$root/started") || fail
cp -- "$context" "$root/context.json"

aws_version=$(jq -er '.awsCli.version' "$context")
aws_sha=$(jq -er '.awsCli.archiveSha256' "$context")
[[ "$aws_version" == 2.34.64 && "$aws_sha" =~ ^[0-9a-f]{64}$ ]] || fail
for tool in curl sha256sum unzip tar jq stat setsid flock; do command -v "$tool" >/dev/null || fail; done
curl --fail --silent --show-error --location --retry 3 --max-time 300 \
  -o "$root/awscli.zip" "https://awscli.amazonaws.com/awscli-exe-linux-x86_64-$aws_version.zip"
printf '%s  %s\n' "$aws_sha" "$root/awscli.zip" | sha256sum --check --status
unzip -q "$root/awscli.zip" -d "$root/aws-installer"
"$root/aws-installer/aws/install" --install-dir "$root/aws-cli" --bin-dir "$root/aws-bin" >/dev/null
aws="$root/aws-bin/aws"
[[ "$("$aws" --version 2>&1)" == aws-cli/2.34.64\ * ]] || fail
export PATH="$root/aws-bin:$PATH" AWS_REGION=ap-northeast-2 AWS_MAX_ATTEMPTS=2 AWS_RETRY_MODE=standard AWS_CLI_AUTO_PROMPT=off
rm -rf -- "$root/aws-installer" "$root/awscli.zip"
caller=$("$aws" --region "$AWS_REGION" sts get-caller-identity --query Arn --output text)
[[ "$caller" == arn:aws:sts::942632789808:assumed-role/airbob-lab-host-"$run_id"-debezium/* ]] || fail

assert_lease() {
  [[ ! -e "$root/STOP" ]] || fail
  "$aws" --region "$AWS_REGION" --cli-connect-timeout 5 --cli-read-timeout 15 dynamodb get-item \
    --table-name airbob-performance-lab-orchestration-lease --consistent-read \
    --key '{"LockName":{"S":"airbob-performance-lab"}}' --output json > "$root/lease-observation.json"
  jq -e --slurpfile context "$context" --argjson now "$(date +%s)" '
    $context[0].lease as $lease | .Item |
    .Owner.S == $lease.owner and .RunId.S == $lease.runId and .Command.S == $lease.command and
    .FencingToken.N == ($lease.fencingToken|tostring) and
    (.ExpiresAt.N|tonumber) > $now and (.CommandDeadline.N|tonumber) > $now
  ' "$root/lease-observation.json" >/dev/null || fail
}
assert_lease
version=$(jq -er '.manifestVersionId' "$context")
"$aws" --region "$AWS_REGION" s3api get-object --bucket airbob-performance-lab-dataset-942632789808 \
  --key "datasets/$dataset_id-aws-preparation/aws-preparation-$(jq -er '.manifestSha256' "$context").json" --version-id "$version" "$root/aws-preparation.json" \
  --output json > "$root/manifest-object.json"
[[ "$(jq -er '.VersionId' "$root/manifest-object.json")" == "$version" ]] || fail
printf '%s  %s\n' "$(jq -er '.manifestSha256' "$context")" "$root/aws-preparation.json" | sha256sum --check --status
manifest="$root/aws-preparation.json"
[[ "$(jq -er '.kind' "$manifest")" == global-growth-b-aws-data-only-preparation ]] || fail
available=$(df -PB1 "$root" | awk 'NR==2 {print $4}')
required=$(jq -er '.storage.minimumStagingFreeBytes' "$manifest")
[[ "$available" =~ ^[0-9]+$ && "$required" =~ ^[0-9]+$ && "$available" -ge "$required" ]] || fail

download() {
  local name=$1 file=$2 key selected_version digest bytes
  assert_lease
  key=$(jq -er --arg name "$name" '.files[$name].key' "$manifest")
  selected_version=$(jq -er --arg name "$name" '.files[$name].versionId' "$manifest")
  digest=$(jq -er --arg name "$name" '.files[$name].sha256' "$manifest")
  bytes=$(jq -er --arg name "$name" '.files[$name].bytes' "$manifest")
  [[ "$key" == "datasets/$dataset_id-aws-preparation/files/"* && "$digest" =~ ^[0-9a-f]{64}$ && "$bytes" =~ ^[1-9][0-9]*$ ]] || fail
  "$aws" --region "$AWS_REGION" s3api get-object --bucket airbob-performance-lab-dataset-942632789808 \
    --key "$key" --version-id "$selected_version" "$root/$file" --output json > "$root/download-result.json"
  [[ "$(jq -er '.VersionId' "$root/download-result.json")" == "$selected_version" && "$(stat -c %s "$root/$file")" == "$bytes" ]] || fail
  printf '%s  %s\n' "$digest" "$root/$file" | sha256sum --check --status
}

extract_regular_archive() {
  local archive=$1 destination=$2 maximum=$3 member expanded
  tar -tzf "$archive" > "$root/archive-members"
  [[ "$(wc -l < "$root/archive-members")" -le 50000 ]] || fail
  [[ "$(sort -u "$root/archive-members" | wc -l)" -eq "$(wc -l < "$root/archive-members")" ]] || fail
  while IFS= read -r member; do
    [[ "$member" =~ ^[A-Za-z0-9_./+-]+$ && "$member" != /* && "/$member/" != */../* ]] || fail
  done < "$root/archive-members"
  tar -tvzf "$archive" > "$root/archive-types"
  # Closed regular-file/directory bundles avoid bootstrap symlink traversal.
  [[ -z "$(awk 'substr($0,1,1)!="-" && substr($0,1,1)!="d" {print "unsafe"}' "$root/archive-types")" ]] || fail
  expanded=$(awk '{total+=$3} END {printf "%.0f",total}' "$root/archive-types")
  [[ "$expanded" -le "$maximum" ]] || fail
  mkdir -m 700 "$destination"
  tar --extract --gzip --file "$archive" --directory "$destination" --no-same-owner --no-same-permissions
}

download toolchain toolchain.tar.gz
download toolchainManifest toolchain.json
extract_regular_archive "$root/toolchain.tar.gz" "$root/toolchain" "$(jq -er '.toolchain.unpackedBytes' "$manifest")"
jq -e '.schemaVersion == 1 and (.files|type=="object") and (.files|length>0) and
  (.files as $files | all(["python/bin/python3","jdk/bin/java","jdk/bin/javac","jdk/bin/keytool","mysql/bin/mysql"][]; $files[.] != null)) and
  all(.files|to_entries[]; (.key|test("^[A-Za-z0-9_./+-]+$")) and (.key|startswith("/")|not) and
    (.key|split("/")|index("..")|not) and
    (.value.sha256|test("^[0-9a-f]{64}$")))' "$root/toolchain.json" >/dev/null || fail
jq -r '.files|keys[]' "$root/toolchain.json" | sort > "$root/toolchain-expected"
(cd "$root/toolchain" && find . -type f | sed 's@^./@@' | sort) > "$root/toolchain-actual"
cmp -s "$root/toolchain-expected" "$root/toolchain-actual" || fail
jq -r '.files|to_entries[]|"\(.value.sha256)  \(.key)"' "$root/toolchain.json" > "$root/toolchain-checks"
(cd "$root/toolchain" && sha256sum --check --status "$root/toolchain-checks")
download consumerTools consumer-tools.tar.gz
extract_regular_archive "$root/consumer-tools.tar.gz" "$root/consumer-tools" 1048576
jq -e --slurpfile context "$context" '.toolSources == $context[0].toolSources' "$manifest" >/dev/null || fail
jq -r '.toolSources|to_entries[]|"\(.value)  \(.key)"' "$context" > "$root/consumer-checks"
(cd "$root/consumer-tools" && sha256sum --check --status "$root/consumer-checks")
[[ "$(find "$root/consumer-tools" -type f | wc -l)" -eq 6 ]] || fail
export JAVA_HOME="$root/toolchain/jdk"
export PATH="$root/toolchain/python/bin:$JAVA_HOME/bin:$root/toolchain/mysql/bin:$PATH"
# Imports must preserve the sealed toolchain, including in child Python processes.
export PYTHONDONTWRITEBYTECODE=1
unset PYTHONPATH PYTHONHOME JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS JDK_JAVAC_OPTIONS CLASSPATH AIRBOB_ETL_BENCHMARK_PASSWORD
assert_lease
redis_image=$(jq -er '.redisImage' "$context")
[[ "$redis_image" =~ ^942632789808\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/[a-z0-9/_-]+@sha256:[0-9a-f]{64}$ ]] || fail
export DOCKER_CONFIG="$root/docker-client"
install -d -m 700 "$DOCKER_CONFIG"
"$aws" --region "$AWS_REGION" ecr get-login-password | docker login --username AWS --password-stdin \
  942632789808.dkr.ecr.ap-northeast-2.amazonaws.com >/dev/null 2>&1
docker pull --quiet "$redis_image" >/dev/null 2>&1
[[ -z "$(docker ps -aq)" ]] || fail
assert_lease
exec 9> "$root/control.lock"
flock -x 9
[[ ! -e "$root/STOP" ]] || fail
setsid "$root/toolchain/python/bin/python3" -B "$root/consumer-tools/growth_b_prepare.py" host \
  --manifest "$manifest" --sha256 "$(jq -er '.manifestSha256' "$context")" --dataset-id "$dataset_id" \
  --context "$context" --root "$root" --aws "$aws" > "$root/preparation.log" 2>&1 &
child=$!
start_ticks=$(awk '{print $22}' "/proc/$child/stat")
printf '%s %s\n' "$child" "$start_ticks" > "$root/process-group"
flock -u 9
trap 'kill -INT -- "-$child" 2>/dev/null || true; wait "$child" || true; exit 130' HUP INT TERM
wait "$child" || fail
trap - HUP INT TERM
rm -f -- "$root/process-group"
assert_lease
standalone_key="data-bootstrap/$run_id/$dataset_id-standalone-rds.json"
"$aws" --region "$AWS_REGION" s3api put-object --bucket airbob-performance-lab-evidence-942632789808 \
  --key "$standalone_key" --body "$root/execute/restore-receipt.json" --tagging Retention=summary \
  --if-none-match '*' --output json > "$root/standalone-publication.json"
jq --arg key "$standalone_key" --arg version "$(jq -er '.VersionId' "$root/standalone-publication.json")" \
  --arg digest "$(sha256sum "$root/execute/restore-receipt.json" | awk '{print $1}')" \
  --argjson bytes "$(stat -c %s "$root/execute/restore-receipt.json")" \
  '. + {standaloneReceiptObject:{key:$key,versionId:$version,sha256:$digest,bytes:$bytes}}' \
  "$root/preparation-receipt.json" > "$root/public-receipt.json"
assert_lease
"$aws" --region "$AWS_REGION" s3api put-object --bucket airbob-performance-lab-evidence-942632789808 \
  --key "data-bootstrap/$run_id/$dataset_id.json" --body "$root/public-receipt.json" \
  --tagging Retention=summary --if-none-match '*' >/dev/null
printf '%s\n' 'B data-only preparation completed; deploymentReady=false. Private account files remain on the host.'
