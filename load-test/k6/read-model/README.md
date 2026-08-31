# Manifest-bound read-model benchmark

이 harness는 `benchmark-dataset-v2`의 `read-model-v2` capsule을 그대로 실행한다. 숙소 ID,
page size, cursor, 회원 이메일, 매출 날짜와 예상 행 수를 셸에서 정하지 않는다. 실행자는
`TARGET_ID` 하나만 선택하고 k6가 manifest의 tagged query와 account binding을 강제한다.

지원 query kind는 다음 세 가지다.

| query kind | target 예 | 비교 경로 |
|---|---|---|
| `REVIEW_SUMMARY_V1` | `review-hot`, `review-median`, `review-cold`, `review-empty` | v2 raw summary ↔ v1 summary table |
| `WISHLIST_PAGE_V1` | hot/median/cold/empty first page, `wishlist-hot-deep` | v2 N+1 path ↔ v1 denormalized path |
| `REVENUE_RANGE_V1` | recent 1d/7d, medium, broad, empty, refund boundary | v2 ledger ↔ v1 daily stats |

위시리스트와 매출 계정은 target의 ACTIVE MEMBER/ADMIN binding을 사용한다. 비밀번호만
`BENCHMARK_ACCOUNT_PASSWORD` 환경 변수로 실행 프로세스에 주입한다. 이메일, 비밀번호,
session과 benchmark token은 결과 artifact에 포함하지 않는다.

## Fail-closed setup

각 measurement의 setup은 before와 after를 한 번씩 호출한 뒤 다음 조건을 모두 확인한다.

1. HTTP 및 domain response contract가 유효하다.
2. 두 canonical business payload가 같다. 매출의 `source=raw|stats` 차이만 제외한다.
3. observed row count가 manifest `expectedRows`와 같다. 0-row target도 유효하다.
4. 두 결과의 canonical SHA-256이 manifest `expectedResultHash`와 같다.

결과 hash는 ETL selector와 같은 length-prefixed field stream이다. 각 UTF-8 field 앞에
4-byte big-endian 길이를 넣는다. 위시리스트 `created_at`은 UTC Instant를
`YYYY-MM-DDTHH:mm:ss.ffffff`로 정규화한다. setup이 실패하면 warmup과 measurement는
시작하지 않는다. runtime recompute endpoint는 호출하지 않는다.

warmup과 setup/login/ANALYZE/EXPLAIN은 headline에서 제외된다. 실제 headline에는 선택한
variant의 `phase=measure` latency만 들어간다. 오류나 dropped iteration이 하나라도 있으면
artifact는 `invalid`다.

## Required run context

모든 evidence run은 `READ_MODEL_EVIDENCE_CONTEXT`로 mode-0600 JSON 파일을 받는다. schema는
`read-model-run-context-v1`이며 다음을 결속한다.

- immutable release tuple과 raw manifest byte SHA-256
- provisioning `run_id`, resource fencing-token SHA-256, measurement-lease fencing-token SHA-256
- target ID, query parameter hash, expected rows/result hash, PII-free account reference
- app commit/image/build, runtime revision, EC2 instance ID와 single-instance count
- clone ID, pre/post DB fingerprint, MySQL exact patch
- ANALYZE receipt, optimizer/statistics/histogram snapshot SHA-256
- one Performance Schema statement event window와 raw JSON/TREE EXPLAIN
- 각 window 직전/직후 fresh challenge에 응답한 runtime assertion과 scheduler, Kafka listener,
  inventory lifecycle, external side-effect가 모두 꺼진 관찰값
- app session에 적용된 일반 read-model treatment (`candidate_index=null`)

context의 unknown/missing field, manifest digest mismatch, target drift, DB fingerprint drift,
statistics/histogram drift, auto statistics recalculation 또는 writer lifecycle 활성화는 모두
init 단계에서 거부된다.

## Local inspect and short rehearsal

실제 v2 manifest와 private evidence context를 사용한다. 아래 값은 manual query parameter가
아니라 artifact 경로와 target 선택뿐이다.

```bash
export BENCHMARK_DATASET_MANIFEST=/absolute/path/benchmark-dataset-v2.json
export READ_MODEL_EVIDENCE_CONTEXT=/absolute/path/review-hot-context.json
export TARGET_ID=review-hot
export VARIANT=before
read -rsp 'Benchmark API token: ' BENCHMARK_READ_MODEL_TOKEN
echo
export BENCHMARK_READ_MODEL_TOKEN

k6 inspect --include-system-env-vars \
  load-test/k6/read-model/review-summary-comparison.js

BASE_URL=http://localhost:8080 RATE=1 WARMUP_DURATION=1s MEASURE_DURATION=2s \
  k6 run --address '' --include-system-env-vars \
  load-test/k6/read-model/review-summary-comparison.js
unset BENCHMARK_READ_MODEL_TOKEN
```

위시리스트나 매출은 manifest account의 비밀번호를 추가로 환경 변수에 넣는다.

```bash
read -rsp 'Synthetic benchmark account password: ' BENCHMARK_ACCOUNT_PASSWORD
echo
export BENCHMARK_ACCOUNT_PASSWORD
# TARGET_ID에 맞춰 wishlist-comparison.js 또는 revenue-stats-comparison.js 실행
unset BENCHMARK_ACCOUNT_PASSWORD
```

일반 사용에서는 `READ_MODEL_MODE=evidence`가 setup/parity/measure 후 곧바로
`read-model-evidence-v1`을 만든다. AWS runner는 statement window를 측정 뒤 결속하기 위해
두 단계로 실행한다.

- `READ_MODEL_MODE=measure`: parity와 실제 부하를 실행해 private metric summary를 남긴다.
- `READ_MODEL_MODE=assemble`: 요청을 전혀 보내지 않고 post-window MySQL evidence와 metric
  summary를 결합한다.

최종 artifact는 별도 adapter 없이
`aggregate-read-model-observations.mjs`의 입력이다.

## AWS protocol plan

AWS 실행 전 plan mode로 release/clone/target/window fence와 순서를 확인한다.

```bash
READ_MODEL_DISCOVERY_MODE=plan \
RUN_ID=read-model-20260827 \
RELEASE_ID=production-seed-20260827t000000z \
CLONE_ID=clone-a \
TARGET_ID=review-hot \
BENCHMARK_DATASET_MANIFEST=/absolute/path/benchmark-dataset-v2.json \
  load-test/k6/read-model/run-aws-read-model-discovery.sh | jq .
```

read-model comparison plan은 같은 after variant의 paired A/A 세 block을 먼저 실행하고,
`AB/BA/AB` 세 block으로 before/after를 교차한다. A/A envelope가
`AA_MAX_RELATIVE_DELTA`를 넘으면 A/B artifact를 publish하지 않는다.
허용치는 기본 0.10이며 0.10보다 크게 완화할 수 없다. plan과 A/A·A/B aggregate의
`metadata.aa_max_relative_delta`가 같은 수치를 보존하므로, 다른 기준으로 만든 A/A artifact를
candidate gate에 재사용할 수 없다.

## AWS isolated-read run

runner는 DB에 접근 가능한 격리된 AWS load-generator에서 실행한다. MySQL credential은
`mysql_config_editor` login path에만 둔다. benchmark token과 synthetic account password는
각각 mode-0600 파일로 전달하며 runner가 자식 k6 환경에만 주입한다. raw/summary S3 object에
credential을 쓰지 않는다.

필수 입력은 다음과 같다.

- `RELEASE_TUPLE_JSON`: `read-model-evidence-v1`의 exact snake_case release tuple
- `APP_BUILD_JSON`: exact commit/image/build, `instance_count=1`, runtime revision, app instance ID,
  provisioning resource fencing-token SHA-256
- `BENCHMARK_TOKEN_FILE`: isolated app의 read-model token, mode 0600
- account target인 경우 `BENCHMARK_ACCOUNT_PASSWORD_FILE`, mode 0600
- `MYSQL_LOGIN_PATH`, `AWS_EVIDENCE_BUCKET`, HTTPS `BASE_URL`
- orchestration lease table/lock/owner와 선택한 release/clone/target

summary object는 load-generator IAM과 같은
`measurements/<run-id>/read-model/<target-id>/` prefix에만 쓴다. 다른
`AWS_EVIDENCE_PREFIX`는 S3 호출 전에 거부한다.

app host의 `/run/airbob/read-model-benchmark-token`은 load-generator로 자동 전달되지 않는다.
실제 AWS read-model run 전에는 승인된 SSM/Secrets Manager `SecureString` handoff가 같은 값을
load-generator의 mode-0600 `BENCHMARK_TOKEN_FILE`에 직접 기록해야 한다. 값이 Run Command
parameter/output, S3 object, shell history 또는 transcript에 나타나면 안 되며, 두 host에서는
원문 대신 SHA-256만 비교한다. 현재 repository는 이 cross-host secret handoff를 provision하지
않으므로 이 prerequisite가 별도 승인·구현되기 전 실제 AWS K6 수집은 pending이다. 파일을
수동 복사하거나 command output으로 token을 읽어 우회하지 않는다.

runner는 정적 lifecycle JSON을 받지 않는다. orchestration measurement lease의 fencing-token
SHA-256은 lease evidence로만 기록하고 앱에 보내지 않는다. 앱 endpoint에는
`APP_BUILD_JSON`의 provisioning resource-fence digest를 보내며, 두 digest가 달라도 각자 자기
경계에 정확히 결속되면 유효하다. 각 window 직전과 직후에는 새 challenge digest로
token-protected runtime assertion endpoint를 호출해 run/resource fence/revision/instance/profile과
모든 writer flag를 exact 검증한다. stale response나 한 번 받은 response의 재사용은 다음
challenge에서 실패한다.

runner는 data load와 candidate build가 끝난 뒤 한 번만 `ANALYZE TABLE`을 실행한다.
`innodb_stats_auto_recalc`가 이미 꺼져 있지 않으면 시작하지 않는다. 일반 12-window run의
extended table checksum은 `run-start`, `after-AA`, `final` 경계에서만 총 3회 실행한다. A/A
6개 window를 모두 측정한 뒤 start/after-AA fingerprint가 같은 경우에만 조립하고, A/B 6개도
after-AA/final fingerprint가 같은 경우에만 조립한다. 따라서 A/A artifact의 모든 window는
run-start → after-AA bracket에, A/B artifact의 모든 window는 after-AA → final bracket에
결속된다. 경계 drift는 summary S3 publish 전에 run 전체를 무효화한다. 대형 테이블을 window
전후마다 다시 스캔하지 않아 측정 대상의 buffer-pool/cache 상태를 checksum 작업으로 반복
교란하지 않는다.

각 window마다 다음 증거는 계속 분리 수집한다.

- optimizer statistics와 histogram pre/post snapshot
- closed Performance Schema timer/event window
- `EXPLAIN FORMAT=JSON`과 `EXPLAIN ANALYZE FORMAT=TREE` 원문
- non-instrumented k6 latency

fingerprint bracket drift, optimizer/statistics/histogram drift 또는 오류/drop이 있으면 publish
전에 실패한다. 최종 S3 write는 orchestration lease를 다시 확인하고
`--if-none-match '*'`로 immutable하게 수행한다. invisible-index candidate raw mode는 선행
A/A fingerprint와 현재 clone을 대조하고 candidate capture 전후 checksum을 각각 한 번씩
유지한다.

## Invisible-index candidate rehearsal

이 harness는 index를 만들거나 삭제하지 않으며 migration을 결정하지 않는다. 별도 clone에
후보가 이미 하나의 invisible index로 준비된 경우 candidate plan은 inventory와 선행 A/A
noise gate를 검증한 뒤 raw MySQL capture 절차만 연다.

```bash
READ_MODEL_DISCOVERY_MODE=plan \
RUN_ID=review-index-20260827 \
RELEASE_ID=production-seed-20260827t000000z \
CLONE_ID=clone-review-candidate \
TARGET_ID=review-hot \
CANDIDATE_INDEX=idx_review_status_accommodation \
INVISIBLE_INDEX_INVENTORY=/absolute/path/invisible-index-inventory.json \
AA_NOISE_OBSERVATION=/absolute/path/review-hot-aa-observations.json \
BENCHMARK_DATASET_MANIFEST=/absolute/path/benchmark-dataset-v2.json \
  load-test/k6/read-model/run-aws-read-model-discovery.sh | jq .
```

선행 artifact는 exact six-window/three-pair observation에서 재구성 가능한
`AA_NOISE`여야 한다. 같은 release/target/clone/app runtime/query parameter를 결속하고,
candidate run 시작 시 live DB fingerprint가 A/A pre/post fingerprint와 같아야 하며 모든
maximum relative delta가 `AA_MAX_RELATIVE_DELTA` 이하여야 한다. 한 clone에 invisible 후보가
0개 또는 2개 이상이거나 이름이 다르면 실패한다.

현재 앱 Hikari session에 `use_invisible_indexes`를 켜고 끄는 격리 contract는 없다. 그러므로
candidate plan은 `application_k6_latency_supported=false`,
`application_performance_publication_supported=false`,
`raw_evidence_publication_supported=true`를 반환한다. 앱 latency를 candidate 효과로 표시하면
안 된다.

candidate를 지정한 `run` mode는 K6를 호출하지 않는다. 동일 typed query를 direct MySQL
session에서 `index-baseline`(`use_invisible_indexes=off`)과
`index-candidate`(`use_invisible_indexes=on`)로 실행해 다음을 하나의
`read-model-candidate-raw-evidence-v1`에 남긴다.

```bash
READ_MODEL_DISCOVERY_MODE=run \
RUN_ID=review-index-20260827 \
RELEASE_ID=production-seed-20260827t000000z \
CLONE_ID=clone-review-candidate \
TARGET_ID=review-hot \
CANDIDATE_INDEX=idx_review_status_accommodation \
INVISIBLE_INDEX_INVENTORY=/secure/invisible-index-inventory.json \
AA_NOISE_OBSERVATION=/secure/review-hot-aa-observations.json \
BENCHMARK_DATASET_MANIFEST=/secure/benchmark-dataset-v2.json \
RELEASE_TUPLE_JSON=/secure/release-tuple.json \
APP_BUILD_JSON=/secure/app-build.json \
BENCHMARK_TOKEN_FILE=/secure/read-model-token \
MYSQL_LOGIN_PATH=airbob-benchmark \
BASE_URL=https://isolated-read.example.invalid \
AWS_EVIDENCE_BUCKET=airbob-performance-lab-evidence-example \
load-test/k6/read-model/run-aws-read-model-discovery.sh
```

- 두 treatment의 pre/post optimizer/statistics/histogram 원문과 hash
- 동일 query SHA-256, evidence query role, JSON/TREE EXPLAIN 원문
- pre/post database fingerprint, one-time ANALYZE receipt, four live runtime assertion receipts
- structured chosen-plan 판정과 선행 A/A artifact SHA-256

raw artifact만 `Retention=raw`로 immutable publish한다. candidate가 chosen plan에 없으면 raw
artifact는 `not-chosen` 사유를 보존한 뒤 runner가 실패하며, 성능 headline이나
`read-model-evidence-v1`은 만들지 않는다. Wishlist endpoint는 여러 SQL을 실행하므로 이
harness는 manifest cursor를 포함한 `WISHLIST_PAGE_SELECT`를 대표 statement로 고정한다.
before revenue는 CONFIRM gross와 CANCEL/PARTIAL_CANCEL refund를 합치는 repository UNION
rollup을 그대로 사용한다. 실제 앱-session treatment, column order, 성능 결론과 V28+
migration은 별도 evidence review와 후속 계획에서 결정한다.

## Runtime isolation and teardown

AWS `isolated-read` app만 active profile `aws,traffic-benchmark`에
`read-model-benchmark`를 include한다. `performance-lab` profile은 활성화하지 않는다.
start contract가 scheduler를 profile로 차단하고 Kafka listener, inventory
startup/seed/retention, search bootstrap, Toss, Google, S3 write, Slack delivery를 explicit
environment flag로 끈다. live endpoint는 정확한 active profile set
`aws,read-model-benchmark,traffic-benchmark`와 실제/설정 writer state를 매 window 전후 확인한다.
app instance는 하나여야 한다.

base runtime env는 기존 exact verifier를 먼저 통과하고, isolated 전용 profile/token 및
search bootstrap/listener 차단 값을 append한 뒤 별도의 exact count와 token-file equality
check를 통과해야 한다. non-isolated
시작은 이전 `/run/airbob/read-model-benchmark-token`을 제거하고 app env에 profile/token이
없음을 검증한다.

실험이 끝나면 isolated app을 폐기하거나 read-model profile/token 없이 다시 배포한다.
일반 serving profile에서 v2 before endpoint가 404인지 확인한다. write capsule을 실행했다면
read-model evidence를 이어서 수집하지 말고 base snapshot을 다시 restore한다.
