# 부하 테스트

- 쿠폰 DB 조건부 UPDATE / Redis Lua: [k6/coupon/README.md](k6/coupon/README.md)
- 캐시·조회 등 전체 k6 실험 목록: [k6/README.md](k6/README.md)

## Bulk write 벤치마크 서버 실행

Wishlist DELETE와 ReservationHistory INSERT 비교는 운영 DB와 분리된 전용 스키마에서만 실행한다. 서버는 느슨한 profile 환경 변수나 직접 `bootRun`으로 시작하지 않고 전용 launcher를 사용한다.

ReservationHistory INSERT 실험은 MySQL cleanup 트랜잭션 안의 예약 상태 변경, 쿠폰 복원, history 쓰기만 비교한다. 예약용 외부 임시 재고나 분산 락은 측정 범위에 없으며, 앞의 쿠폰 발급 db/Lua 비교와는 서로 다른 실험이다.

이 실험은 공용 AWS world를 수정하지 않는다. `bulk-expiration-history-v1`은 전용
`*_bulk_write_benchmark` 스키마에서 요청마다 fixture를 생성·검증·삭제하는 로컬
쓰기 프로토콜이다. 서버 guard와 fixture preflight는 AWS/OCI profile, 다른 스키마,
기존 만료 대상 예약을 거부한다. launcher는 manifest의 최대 bucket과 맞도록 한 번의
cleanup 상한을 2,000으로 고정한다.

```bash
read -rsp 'Bulk write benchmark token: ' BENCHMARK_BULK_WRITE_TOKEN
printf '\n'
export BENCHMARK_BULK_WRITE_TOKEN
export BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA=airbob_bulk_write_benchmark
export JDBC_REWRITE_BATCHED_STATEMENTS=true
export BENCHMARK_DATASET_MANIFEST="$(pwd)/build/benchmark-dataset-v1.json"
load-test/k6/bulk-write/run-bulk-write-benchmark-server.sh
```

launcher는 자격 증명을 명령 인자나 출력에 넣지 않고 자식 환경으로만 전달한다. 또한 `BENCHMARK_BULK_WRITE_ENABLED=true`, profile 순서 `dev,bulk-write-benchmark`, Hibernate `show_sql`/`format_sql`과 SQL/bind/동결 BEFORE logger의 `OFF`를 강제한다. 설정 우회를 막기 위해 추가 Gradle 인자를 받지 않는다. 측정 뒤 서버를 중지하고 실행 셸에서 `unset BENCHMARK_BULK_WRITE_TOKEN BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA JDBC_REWRITE_BATCHED_STATEMENTS BENCHMARK_DATASET_MANIFEST`를 실행한다.

### Bulk write raw observation 측정

통계용 측정은 단일 k6 실행에서 여러 표본을 모으지 않는다. 같은 공개 실험 메타데이터와 자격 증명 환경 변수를 준비한 뒤 candidate별 wrapper가 표본마다 `SAMPLES=1`인 격리된 child artifact를 순서대로 만든다. `RUN_LABEL`은 영문자·숫자·점·밑줄·하이픈만 사용하고 결과 경로는 실행 전에 존재하지 않아야 한다.

ReservationHistory INSERT 부하 발생기 셸에서는 먼저 다음 공통 계약을 준비한다.
`SCHEMA_LABEL`, `JVM_VERSION`, `MYSQL_VERSION`은 임의 설명이 아니라 각각 benchmark
서버의 `SELECT DATABASE()`, `java -version`, `SELECT VERSION()` 결과와 대조한 공개
표식이다. `REWRITE_BATCHED_STATEMENTS`는 위 서버의
`JDBC_REWRITE_BATCHED_STATEMENTS`와 반드시 같은 값이어야 한다. 로그인 비밀번호와
서버 토큰은 명령행이나 파일에 하드코딩하지 않고 숨김 입력으로만 받는다.

```bash
export DATASET_SIZE=2000
export APP_COMMIT="$(git rev-parse HEAD)"
export APP_INSTANCE_COUNT=1
export SCHEMA_LABEL=airbob_bulk_write_benchmark
read -rp 'JVM version label from benchmark server (java -version): ' JVM_VERSION
export JVM_VERSION
read -rp 'MySQL version label from SELECT VERSION(): ' MYSQL_VERSION
export MYSQL_VERSION
export REWRITE_BATCHED_STATEMENTS=true
export BENCHMARK_DATASET_MANIFEST="$(pwd)/build/benchmark-dataset-v1.json"
read -rp 'Benchmark account email: ' BENCHMARK_EMAIL
export BENCHMARK_EMAIL
read -rsp 'Benchmark account password: ' TEST_PASSWORD
printf '\n'
export TEST_PASSWORD
read -rsp 'Bulk write benchmark token: ' BENCHMARK_BULK_WRITE_TOKEN
printf '\n'
export BENCHMARK_BULK_WRITE_TOKEN
```

`APP_COMMIT`은 축약 SHA가 아닌 배포한 앱과 일치하는 40자리 commit이어야 한다.
위 명령은 현재 checkout의 full commit을 사용하므로, 다른 이미지를 측정한다면 해당
이미지의 full commit으로 바꾼다. 공통 계약을 준비한 뒤 AFTER 표본은 다음과 같이
실행한다.

```bash
export PHASE=measure
export VARIANT=AFTER
export ROUND=1
export RUN_ORDER=2
export RAW_OBSERVATION_SAMPLES=10
export RUN_LABEL=reservation-after-n2000-r1
export RAW_OBSERVATION_RESULT_PATH=build/k6/bulk-write/reservation-after-n2000-r1-observations.json
load-test/k6/bulk-write/run-reservation-history-insert-observations.sh
```

위 예는 `ROUND=1`의 AB 순서에서 두 번째(`AFTER`, `RUN_ORDER=2`) block이다. 같은
round의 첫 번째 block은 `BEFORE`, `RUN_ORDER=1`로 실행한다. 다음 paired round에서
순서 효과를 상쇄하려면 BA로 뒤집어 `AFTER`에 `RUN_ORDER=1`, `BEFORE`에
`RUN_ORDER=2`를 부여한다. 한 block이 만드는 모든 child 표본은 같은 `ROUND`와
`RUN_ORDER`를 공유한다. 모든 필요한 block을 마친 뒤 부하 발생기 셸에서
`unset BENCHMARK_EMAIL TEST_PASSWORD BENCHMARK_BULK_WRITE_TOKEN`으로 자격 증명을
제거한다.

Wishlist DELETE는 같은 방식으로 별도 wrapper를 사용한다.

```bash
export PHASE=measure
export VARIANT=AFTER
export RUN_ORDER=2
export RAW_OBSERVATION_SAMPLES=10
export RUN_LABEL=wishlist-after-n1000-r1
export RAW_OBSERVATION_RESULT_PATH=build/k6/bulk-write/wishlist-after-n1000-r1-observations.json
load-test/k6/bulk-write/run-wishlist-delete-observations.sh
```

AccommodationAmenity DELETE는 측정 범위도 명시한다.

```bash
export PHASE=measure
export VARIANT=AFTER
export MEASUREMENT=FULL_REPLACEMENT
export RUN_ORDER=2
export RAW_OBSERVATION_SAMPLES=10
export RUN_LABEL=amenity-full-after-n100-r1
export RAW_OBSERVATION_RESULT_PATH=build/k6/bulk-write/amenity-full-after-n100-r1-observations.json
load-test/k6/bulk-write/run-accommodation-amenity-delete-observations.sh
```

세 wrapper는 공통 실행기에 닫힌 candidate 값을 전달한다. `VARIANT`는 `BEFORE` 또는 `AFTER`를 반드시 명시하고, AccommodationAmenity는 `MEASUREMENT`도 `FULL_REPLACEMENT` 또는 `DELETE_ONLY`로 명시한다. `RUN_ORDER`는 라운드의 AB/BA block 실행 순서이며 1부터 1,000,000 사이의 정규 정수여야 한다. 공통 실행기는 이 block 순서를 모든 child에 그대로 전달한다. child label과 source 경로는 `${RUN_LABEL}-sample-001`부터 순서대로 만들며, 이 source 순서가 companion의 `sample_index=1..N`이 된다. 따라서 `sample_index`는 raw 표본 순서이고 `run_order`는 모든 표본에 공통인 block 순서다.

child 하나라도 실패하거나 artifact를 만들지 않으면 즉시 중단하고 이번 실행이 만든 child까지 정리하며 companion artifact를 만들지 않는다. 모든 child가 성공한 뒤에만 sanitizer/aggregator를 호출한다. 토큰, 비밀번호, 세션 ID, 이메일, 회원 ID와 DB 자격 증명은 인자·출력·companion에 복사하지 않는다.

기존 child artifact의 `schema_version`은 `bulk-write-benchmark-v1` 그대로 유지한다. companion은 별도 `bulk-write-observations-v1`이며, 공개 공통 메타데이터와 다음 allowlist만 보존한다.

- 표본 번호, source 경로, variant, dataset 크기, round, run order
- 서버 연산 시간, 검증 성공 여부와 검증 행 수
- Hibernate `SELECT/INSERT/UPDATE/DELETE/OTHER/TOTAL`
- 명시적으로 계측한 custom JDBC writer의 batch 호출, 제출 행, 설정 batch 크기, 영향 행

`observations`의 입력 순서 raw 목록이 정본이다. `statistics.server_operation_ms`는 이 목록을 오름차순 정렬한 뒤 nearest-rank 방식으로 다시 계산한다. 표본 수를 `n`, 분위수를 `p`(`0.50`, `0.95`)라 할 때 1부터 시작하는 순위는 `max(1, ceil(p * n))`이고 해당 정렬값을 p50/p95로 사용한다. 보간은 하지 않는다.

각 child source는 성공한 measure 1표본, 정확한 candidate/공개 실험 메타데이터, 순차 index/path, 동일한 block run order, 필수 trend count 1과 candidate별 SQL/JDBC/검증 계약을 모두 만족해야 한다. 일부 source, 중복 source, 메타데이터 불일치, 비유한 값, 임의 자격 증명 필드가 있으면 fail-closed로 companion을 남기지 않는다.

Wishlist DELETE의 SQL 계약은 dataset 크기를 `N`이라 할 때 다음과 같다.

| Variant | SELECT | INSERT | UPDATE | DELETE | TOTAL | custom JDBC writer batch 계측 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Before | 2 | 0 | 1 | N | `N + 3` | 0 |
| After, `N = 0` | 2 | 0 | 1 | 0 | 3 | 0 |
| After, `N > 0` | 2 | 0 | 1 | 1 | 4 | 0 |

ReservationHistory child artifact의 `database_observation.jdbc`는 `affected_rows_known_samples`와 `affected_rows_unknown_samples`를 함께 기록한다. child마다 성공 표본은 1개이며, 집계기는 그 영향 행이 known일 때만 `affected_rows` 숫자를 허용하고 unknown이면 `null`을 요구한다. companion의 `observations[].jdbc`에는 호출·제출 행·batch size·영향 행만 보존한다. Wishlist는 이 custom JDBC writer를 사용하지 않으므로 모든 표본에서 명시 계측 호출·제출 행이 0이고 batch size·영향 행은 `null`이어야 한다. 이 값은 Hibernate나 JDBC 드라이버의 모든 `executeBatch` 호출을 가로채는 범용 계측값이 아니다.
