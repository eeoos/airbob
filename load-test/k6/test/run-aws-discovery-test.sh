#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
runner="$repo_root/load-test/k6/traffic/run-aws-discovery.sh"
toolchain="$repo_root/infra/aws/toolchain.env"
loadgen_bootstrap="$repo_root/infra/aws/lab/templates/load-generator-user-data.sh.tftpl"
service_host_bootstrap="$repo_root/infra/aws/lab/templates/host-user-data.sh.tftpl"
workflow="$repo_root/.github/workflows/aws-performance-lab.yml"
makefile="$repo_root/Makefile"

fail() { printf '%s\n' "$1" >&2; exit 1; }
assert_contains() { grep -Fq -- "$2" "$1" || fail "$1 does not contain: $2"; }
sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

[[ -x "$runner" && ! -L "$runner" ]] || fail "AWS discovery runner is missing or unsafe"
[[ -f "$toolchain" && ! -L "$toolchain" ]] || fail "AWS toolchain contract is missing or unsafe"

assert_contains "$toolchain" 'AIRBOB_K6_VERSION=1.5.0'
assert_contains "$toolchain" 'AIRBOB_K6_LINUX_AMD64_SHA256=5ec7c7800ffedac41b9346c55fa7a4a73b4711b0d05d7226f1b8494748878263'
assert_contains "$loadgen_bootstrap" 'dnf install -y awscli2'
assert_contains "$service_host_bootstrap" '[[ "$service" == debezium ]]'
assert_contains "$service_host_bootstrap" 'dnf install -y mariadb105'
assert_contains "$service_host_bootstrap" 'command -v mysql >/dev/null 2>&1'
assert_contains "$runner" 'TARGET must equal accommodation-detail for the first AWS discovery slice'
assert_contains "$runner" 'releaseKind == "pipeline-rehearsal"'
assert_contains "$runner" 'claimScope:"pipeline-only"'
assert_contains "$runner" 'measurement-inputs/$run_id/$harness_commit'
assert_contains "$runner" 'measurements/$run_id/$run_label'
assert_contains "$runner" 'verify-idle'
assert_contains "$runner" 'enforce-measurement-policy.sh'
assert_contains "$runner" '"$dns_controller" verify aws'
assert_contains "$runner" 'IMAGE_DIGEST'
assert_contains "$runner" 'APP_COMMIT'
assert_contains "$runner" 'EXPECTED_SQL_CALLS_PER_REQUEST'
assert_contains "$runner" 'capture-statement-digests.sql'
assert_contains "$runner" 'app_query_per_request_queries_sum'
assert_contains "$runner" 'api.airbob.cloud'
assert_contains "$runner" "--if-none-match '*'"
assert_contains "$makefile" 'aws-discovery:'
assert_contains "$workflow" "inputs.action == 'measure'"
assert_contains "$workflow" 'run: load-test/k6/traffic/run-aws-discovery.sh'

if grep -Eq 'BENCHMARK_(PASSWORD|SESSION)|SESSION_ID' "$runner"; then
  fail "the public discovery slice must not read or persist authentication secrets"
fi

temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-discovery-test.XXXXXX")
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

fixture_repo="$temp_dir/repo"
mkdir -p \
  "$fixture_repo/load-test/k6/traffic" \
  "$fixture_repo/load-test/k6/lib" \
  "$fixture_repo/load-test/mysql" \
  "$fixture_repo/infra/aws/scripts" \
  "$fixture_repo/infra/aws/lab" \
  "$temp_dir/bin" \
  "$temp_dir/objects"
for path in \
  load-test/k6/traffic/run-aws-discovery.sh \
  load-test/k6/traffic/aggregate-traffic-results.mjs \
  load-test/k6/traffic/guest-read.js \
  load-test/k6/lib/traffic-benchmark.js \
  load-test/k6/lib/benchmark-fixture.js \
  load-test/k6/lib/benchmark-manifest.js \
  load-test/k6/lib/read-model-benchmark.js \
  load-test/mysql/capture-statement-digests.sql; do
  cp "$repo_root/$path" "$fixture_repo/$path"
done
cp "$toolchain" "$fixture_repo/infra/aws/toolchain.env"

cat > "$fixture_repo/infra/aws/scripts/orchestration-lease.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'lease %s\n' "$*" >> "${FAKE_DISCOVERY_LOG:?}"
[[ "$1" != acquire ]] || printf '%s\n' fencing_token=17
EOF
cat > "$fixture_repo/infra/aws/scripts/prepare-terraform-backend.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'backend %s\n' "$*" >> "${FAKE_DISCOVERY_LOG:?}"
EOF
cat > "$fixture_repo/infra/aws/scripts/enforce-measurement-policy.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'policy %s\n' "$*" >> "${FAKE_DISCOVERY_LOG:?}"
EOF
cat > "$fixture_repo/infra/aws/scripts/verify-dataset-release.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'dataset %s\n' "$*" >> "${FAKE_DISCOVERY_LOG:?}"
EOF
cat > "$fixture_repo/infra/aws/scripts/aws-dns-controller.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1:$2" == verify:aws ]]
[[ "${LEASE_COMMAND:?}" == measurement ]]
[[ "${ALB_FENCING_TOKEN:?}" == 41 ]]
printf 'dns %s %s\n' "$1" "$2" >> "${FAKE_DISCOVERY_LOG:?}"
[[ "${FAKE_DNS_FAIL:-false}" != true ]]
EOF
chmod 700 "$fixture_repo/infra/aws/scripts"/*.sh "$fixture_repo/load-test/k6/traffic"/*.sh

git -C "$fixture_repo" init -q
git -C "$fixture_repo" add .
git -C "$fixture_repo" -c user.name=Airbob -c user.email=airbob@example.invalid commit -qm fixture
harness_commit=$(git -C "$fixture_repo" rev-parse HEAD)
app_commit=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
image_digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

cp "$repo_root/load-test/k6/test/fixtures/nplus1-v1.json" "$temp_dir/objects/benchmark.json"
benchmark_sha=$(sha256_file "$temp_dir/objects/benchmark.json")
jq -n --arg sha "$benchmark_sha" '{source:{benchmarkManifestSha256:$sha}}' > "$temp_dir/objects/dataset.json"
dataset_sha=$(sha256_file "$temp_dir/objects/dataset.json")
printf '%s  %s\n' "$(printf 'd%.0s' {1..64})" airbob.sql.zst > "$temp_dir/objects/mysql-sha256.txt"
jq -n --arg run phase3-test --arg release rehearsal-v16 --arg sha "$dataset_sha" \
  '{schemaVersion:1,runId:$run,datasetRelease:$release,releaseKind:"pipeline-rehearsal",datasetManifestSha256:$sha,flywayVersion:"16",outboxState:"empty"}' \
  > "$temp_dir/objects/receipt.json"
jq -n --arg run phase3-test --arg bundle "$harness_commit" --arg digest "$image_digest" \
  --arg release rehearsal-v16 --arg datasetSha "$dataset_sha" \
  '{schemaVersion:1,runId:$run,datasetRelease:$release,policy:"isolated-read",loadGeneratorEnabled:true,bundleCommit:$bundle,imageDigest:$digest,datasetManifestSha256:$datasetSha,fencingToken:41}' \
  > "$temp_dir/objects/operator.json"
cp "$repo_root/infra/aws/lab/tests/fixtures/lab-contract.json" "$temp_dir/objects/lab-contract.json"

mkdir -p "$temp_dir/k6-v1.5.0-linux-amd64"
printf '#!/usr/bin/env bash\nprintf "k6 v1.5.0 (go1.25.0, linux/amd64)\\n"\n' > "$temp_dir/k6-v1.5.0-linux-amd64/k6"
chmod 700 "$temp_dir/k6-v1.5.0-linux-amd64/k6"
tar -czf "$temp_dir/objects/k6.tar.gz" -C "$temp_dir" k6-v1.5.0-linux-amd64
fake_k6_sha=$(sha256_file "$temp_dir/objects/k6.tar.gz")
sed -i.bak "s/^AIRBOB_K6_LINUX_AMD64_SHA256=.*/AIRBOB_K6_LINUX_AMD64_SHA256=$fake_k6_sha/" \
  "$fixture_repo/infra/aws/toolchain.env"
rm -f "$fixture_repo/infra/aws/toolchain.env.bak"
git -C "$fixture_repo" add infra/aws/toolchain.env
git -C "$fixture_repo" -c user.name=Airbob -c user.email=airbob@example.invalid commit -qm toolchain-fixture
harness_commit=$(git -C "$fixture_repo" rev-parse HEAD)
jq --arg bundle "$harness_commit" '.bundleCommit=$bundle' "$temp_dir/objects/operator.json" \
  > "$temp_dir/objects/operator.next"
mv "$temp_dir/objects/operator.next" "$temp_dir/objects/operator.json"

digest=$(printf '1%.0s' {1..64})
write_snapshot() {
  local destination=$1 count=$2 timer=$3 examined=$4 sent=$5
  jq -nc --arg digest "$digest" --arg count "$count" --arg timer "$timer" \
    --arg examined "$examined" --arg sent "$sent" \
    '{schemaName:"airbobdb",digest:$digest,digestText:"SELECT * FROM accommodation WHERE id = ?",count:$count,timerWait:$timer,rowsExamined:$examined,rowsSent:$sent}' \
    > "$destination"
}
write_snapshot "$temp_dir/objects/idle-before.jsonl" 10 1000000000 10 10
cp "$temp_dir/objects/idle-before.jsonl" "$temp_dir/objects/idle-after.jsonl"
cp "$temp_dir/objects/idle-before.jsonl" "$temp_dir/objects/before.jsonl"
write_snapshot "$temp_dir/objects/after.jsonl" 11 1100000000 11 11
printf '%s\n' '{"schemaVersion":1,"startEpochMs":1000,"endEpochMs":11000}' > "$temp_dir/objects/window.json"
printf '%s\n' '{"schemaVersion":1,"flywayVersion":"16"}' > "$temp_dir/objects/flyway-before.json"
cp "$temp_dir/objects/flyway-before.json" "$temp_dir/objects/flyway-after.json"
jq -n --arg sha "$benchmark_sha" --arg commit "$app_commit" '
  {
    schemaVersion:1,
    metadata:{releaseKind:"pipeline-rehearsal",claimScope:"pipeline-only",role:"guest",target:"accommodation-detail",datasetVersion:"nplus1-v1",manifestSha256:$sha,appCommit:$commit,appInstanceCount:1,round:1,runOrder:1,runLabel:"accommodation-detail-r1-o1",endpoint:"/api/v1/accommodations/1",endpointTemplate:"GET /api/v1/accommodations/{accommodationId}"},
    validity:{status:"valid",reasons:[]},
    load:{configuredRatePerSecond:1,duration:"1s",durationSeconds:1,preAllocatedVUs:20,maxVUs:20,iterations:{started:1,completed:1,successful:1,minimumRequired:1,dropped:0},achievedRps:1},
    performance:{errorRate:0,latencyMs:{avg:10,min:10,median:10,p90:10,p95:10,p99:10,max:10}},
    manifestGaps:["pipeline-rehearsal"]
  }
' > "$temp_dir/objects/k6.json"
jq -n '{schemaVersion:1,startEpochMs:1000,endEpochMs:11000,queries:{requestCount:{status:"success",data:{resultType:"matrix",result:[]}},queryCount:{status:"success",data:{resultType:"matrix",result:[]}},hikariPending:{status:"success",data:{resultType:"matrix",result:[]}}}}' \
  > "$temp_dir/objects/prometheus.json"

cat > "$temp_dir/bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_DISCOVERY_LOG:?}"
case " $* " in
  *' sts get-caller-identity '*Account*) printf '%s\n' 942632789808 ;;
  *' sts get-caller-identity '*) printf '%s\n' 'arn:aws:sts::942632789808:assumed-role/airbob-lab-operator/test' ;;
  *' ssm get-parameter '*) cat "${FAKE_OBJECTS:?}/lab-contract.json" ;;
  *' ecr describe-images '*) printf '%s\n' 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' ;;
  *' autoscaling describe-auto-scaling-groups '*) printf '%s\n' 1 ;;
  *' rds describe-db-instances '*) printf '%s\n' 'arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-fixture' ;;
  *' ssm send-command '*)
    case "$*" in
      *'Airbob discovery inspect'*) printf '%s\n' '22222222-2222-2222-2222-222222222222' ;;
      *'Airbob discovery warmup'*) printf '%s\n' '33333333-3333-3333-3333-333333333333' ;;
      *) printf '%s\n' '11111111-1111-1111-1111-111111111111' ;;
    esac
    ;;
  *' ssm wait command-executed '*) ;;
  *' ssm get-command-invocation '*)
    if [[ "${FAKE_FAIL_STAGE:-}" == inspect && "$*" == *22222222-2222-2222-2222-222222222222* ]] ||
      [[ "${FAKE_FAIL_STAGE:-}" == warmup && "$*" == *33333333-3333-3333-3333-333333333333* ]]; then
      printf '%s\n' '{"Status":"Failed","StandardOutputContent":""}'
      exit 0
    fi
    jq -n --arg output $'AIRBOB_DISCOVERY_STAGE_OK\nAIRBOB_DISCOVERY_FLYWAY_BEFORE_OK\nAIRBOB_DISCOVERY_INSPECT_OK\nAIRBOB_DISCOVERY_WARMUP_OK\nAIRBOB_DISCOVERY_IDLE_BEFORE_OK\nAIRBOB_DISCOVERY_IDLE_AFTER_OK\nAIRBOB_DISCOVERY_BASELINE_OK\nAIRBOB_DISCOVERY_MEASURE_OK\nAIRBOB_DISCOVERY_AFTER_OK\nAIRBOB_DISCOVERY_FLYWAY_AFTER_OK\nAIRBOB_DISCOVERY_PROMETHEUS_OK\n' '{Status:"Success",StandardOutputContent:$output}'
    ;;
  *' s3api put-object '*) ;;
  *' s3api get-object '*)
    key=''
    destination=''
    while [[ $# -gt 0 ]]; do
      case "$1" in
        --key) key=$2; shift 2 ;;
        --bucket|--region) shift 2 ;;
        --no-cli-pager) shift ;;
        --*) shift ;;
        *) destination=$1; shift ;;
      esac
    done
    case "$key" in
      runs/*/operator.json) source="${FAKE_OBJECTS:?}/operator.json" ;;
      data-bootstrap/*) source="${FAKE_OBJECTS:?}/receipt.json" ;;
      datasets/*/benchmark/manifest.json) source="${FAKE_OBJECTS:?}/benchmark.json" ;;
      datasets/*/mysql/sha256.txt) source="${FAKE_OBJECTS:?}/mysql-sha256.txt" ;;
      datasets/*/manifest.json) source="${FAKE_OBJECTS:?}/dataset.json" ;;
      measurements/*/*) source="${FAKE_OBJECTS:?}/${key##*/}" ;;
      *) printf 'unexpected fake S3 key: %s\n' "$key" >&2; exit 1 ;;
    esac
    cp "$source" "$destination"
    ;;
  *) printf 'unexpected fake AWS call: %s\n' "$*" >&2; exit 1 ;;
esac
EOF
cat > "$temp_dir/bin/terraform" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'terraform %s\n' "$*" >> "${FAKE_DISCOVERY_LOG:?}"
case " $* " in
  *' init '*) ;;
  *' output -json phase2_contract '*)
    printf '%s\n' '{"run_id":"phase3-test","deployment_phase":"data-ready","services":{"debezium":"i-11111111111111111","kafka":"i-22222222222222222","monitoring":"i-33333333333333333"}}'
    ;;
  *' output -json phase3_contract '*)
    printf '%s\n' '{"release_kind":"pipeline-rehearsal","data_ready":true,"dataset_release":"rehearsal-v16","rds_instance_id":"airbob-phase3-test","rds_endpoint":"fake.abcdefghijkl.ap-northeast-2.rds.amazonaws.com"}'
    ;;
  *' output -json phase4_contract '*)
    printf '%s\n' '{"app_enabled":true,"measurement_policy":"isolated-read","load_generator_enabled":true,"load_generator_instance_id":"i-44444444444444444","capacity":{"desired":1},"auto_scaling_group_name":"airbob-phase3-test-app","alb_arn":"arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-test/0123456789abcdef","alb_dns_name":"airbob-test.ap-northeast-2.elb.amazonaws.com"}'
    ;;
  *) printf 'unexpected fake Terraform call: %s\n' "$*" >&2; exit 1 ;;
esac
EOF
cat > "$temp_dir/bin/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
destination=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) destination=$2; shift 2 ;;
    *) shift ;;
  esac
done
[[ -n "$destination" ]] || exit 1
cp "${FAKE_OBJECTS:?}/k6.tar.gz" "$destination"
EOF
chmod 700 "$temp_dir/bin"/*
: > "$temp_dir/discovery.log"

env PATH="$temp_dir/bin:$PATH" \
  FAKE_DISCOVERY_LOG="$temp_dir/discovery.log" FAKE_OBJECTS="$temp_dir/objects" \
  AWS_REGION=ap-northeast-2 RUN_ID=phase3-test TARGET=accommodation-detail \
  OCI_ORIGIN_IPV4=198.51.100.20 \
  RATE=1 DURATION=1s WARMUP_DURATION=1s MIN_COMPLETED_SAMPLES=1 ROUND=1 RUN_ORDER=1 \
  APP_COMMIT="$app_commit" EXPECTED_SQL_CALLS_PER_REQUEST=1 \
  "$fixture_repo/load-test/k6/traffic/run-aws-discovery.sh" > "$temp_dir/output.log"

aggregate="$fixture_repo/build/k6/traffic/accommodation-detail-r1-o1-aggregate.json"
jq -e '.validity.status == "valid" and .metadata.claimScope == "pipeline-only" and .sql.attribution.observedCalls == 1' \
  "$aggregate" >/dev/null || fail "fake discovery did not produce a valid pipeline-only aggregate"
assert_contains "$temp_dir/discovery.log" 'lease acquire'
assert_contains "$temp_dir/discovery.log" 'measurement-inputs/phase3-test/'
assert_contains "$temp_dir/discovery.log" 'measurements/phase3-test/accommodation-detail-r1-o1/aggregate.json'
assert_contains "$temp_dir/discovery.log" 'policy isolated-read phase3-test'
assert_contains "$temp_dir/discovery.log" 'dns verify aws'
assert_contains "$temp_dir/discovery.log" '--comment Airbob discovery inspect'
assert_contains "$temp_dir/discovery.log" '--comment Airbob discovery warmup'
assert_contains "$temp_dir/discovery.log" '--comment Airbob discovery SQL idle-before'
assert_contains "$temp_dir/discovery.log" '--comment Airbob discovery SQL before'
assert_contains "$temp_dir/discovery.log" '--comment Airbob discovery measure'
assert_contains "$temp_dir/discovery.log" '--comment Airbob discovery SQL after'
assert_contains "$temp_dir/discovery.log" '--comment Airbob discovery Prometheus'
if rg -n 'benchmark-password|SESSION_ID|BENCHMARK_SESSION' "$temp_dir/discovery.log" "$temp_dir/output.log" >/dev/null 2>&1; then
  fail "fake discovery logs contain an authentication secret marker"
fi

: > "$temp_dir/failure.log"
if env PATH="$temp_dir/bin:$PATH" \
  FAKE_DISCOVERY_LOG="$temp_dir/failure.log" FAKE_OBJECTS="$temp_dir/objects" FAKE_DNS_FAIL=true \
  AWS_REGION=ap-northeast-2 RUN_ID=phase3-test TARGET=accommodation-detail \
  OCI_ORIGIN_IPV4=198.51.100.20 \
  RATE=1 DURATION=1s WARMUP_DURATION=1s MIN_COMPLETED_SAMPLES=1 ROUND=1 RUN_ORDER=2 \
  RUN_LABEL=failure-dns APP_COMMIT="$app_commit" EXPECTED_SQL_CALLS_PER_REQUEST=1 \
  "$fixture_repo/load-test/k6/traffic/run-aws-discovery.sh" > "$temp_dir/failure.out" 2>&1; then
  fail "DNS verification failure did not stop AWS discovery"
fi
assert_contains "$temp_dir/failure.log" 'dns verify aws'
if grep -Fq -- 'ssm send-command' "$temp_dir/failure.log"; then
  fail "DNS verification failure allowed remote staging or traffic"
fi

for failed_stage in inspect warmup; do
  : > "$temp_dir/failure.log"
  if env PATH="$temp_dir/bin:$PATH" \
    FAKE_DISCOVERY_LOG="$temp_dir/failure.log" FAKE_OBJECTS="$temp_dir/objects" FAKE_FAIL_STAGE="$failed_stage" \
    AWS_REGION=ap-northeast-2 RUN_ID=phase3-test TARGET=accommodation-detail \
    OCI_ORIGIN_IPV4=198.51.100.20 \
    RATE=1 DURATION=1s WARMUP_DURATION=1s MIN_COMPLETED_SAMPLES=1 ROUND=1 RUN_ORDER=2 \
    RUN_LABEL="failure-$failed_stage" APP_COMMIT="$app_commit" EXPECTED_SQL_CALLS_PER_REQUEST=1 \
    "$fixture_repo/load-test/k6/traffic/run-aws-discovery.sh" > "$temp_dir/failure.out" 2>&1; then
    fail "$failed_stage failure did not stop AWS discovery"
  fi
  if grep -Fq -- '--comment Airbob discovery SQL idle-before' "$temp_dir/failure.log" ||
    grep -Fq -- '--comment Airbob discovery measure' "$temp_dir/failure.log"; then
    fail "$failed_stage failure allowed digest baseline or measurement"
  fi
  if rg -n 'benchmark-password|SESSION_ID|BENCHMARK_SESSION' "$temp_dir/failure.log" "$temp_dir/failure.out" >/dev/null 2>&1; then
    fail "$failed_stage failure replayed an authentication secret marker"
  fi
done

write_snapshot "$temp_dir/objects/idle-after.jsonl" 11 1100000000 11 11
: > "$temp_dir/failure.log"
if env PATH="$temp_dir/bin:$PATH" \
  FAKE_DISCOVERY_LOG="$temp_dir/failure.log" FAKE_OBJECTS="$temp_dir/objects" \
  AWS_REGION=ap-northeast-2 RUN_ID=phase3-test TARGET=accommodation-detail \
  OCI_ORIGIN_IPV4=198.51.100.20 \
  RATE=1 DURATION=1s WARMUP_DURATION=1s MIN_COMPLETED_SAMPLES=1 ROUND=1 RUN_ORDER=3 \
  RUN_LABEL=failure-idle APP_COMMIT="$app_commit" EXPECTED_SQL_CALLS_PER_REQUEST=1 \
  "$fixture_repo/load-test/k6/traffic/run-aws-discovery.sh" > "$temp_dir/failure.out" 2>&1; then
  fail "ambient SQL did not stop AWS discovery"
fi
if grep -Fq -- '--comment Airbob discovery SQL before' "$temp_dir/failure.log" ||
  grep -Fq -- '--comment Airbob discovery measure' "$temp_dir/failure.log"; then
  fail "ambient SQL allowed digest baseline or measurement"
fi
cp "$temp_dir/objects/idle-before.jsonl" "$temp_dir/objects/idle-after.jsonl"

printf '%s\n' 'AWS discovery runner contract tests passed'
