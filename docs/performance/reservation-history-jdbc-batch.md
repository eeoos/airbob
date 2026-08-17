# ReservationHistory JDBC Batch 성능 개선

## 상태

- 구현 및 로컬 통제 측정: 완료
- AWS 격리 환경 최종 측정: 예정
- 로컬 측정 기준 커밋: `d388cc71ea649f62026bc15d3b2cb474b9dee450`
- 운영 기본 batch size: `100`

이 문서의 시간 수치는 로컬의 일회성 MySQL·Redis 환경에서 얻은 사전 검증 결과다. 구현 효과와 측정 절차가 정상적으로 동작하는지는 확인했지만, 이력서에 사용할 최종 수치는 아래 AWS 측정 절차로 다시 수집한 뒤 교체한다.

## 문제와 개선 가설

결제 대기 시간이 만료된 예약을 정리할 때 예약마다 `ReservationHistory`를 한 건씩 저장했다. `ReservationHistory.id`가 `IDENTITY` 전략이므로 Hibernate는 식별자를 얻기 위해 각 `persist` 시점에 INSERT를 즉시 실행한다. 만료 대상이 `N`건이면 history INSERT도 `N`회 실행된다.

초기 구현은 JPA 생명주기와 감사 필드를 그대로 사용할 수 있어 단순하지만, 한 트랜잭션에서 다수의 이력을 적재하는 스케줄 작업에서는 개별 INSERT 실행 횟수가 대상 건수에 비례한다. 따라서 business snapshot과 트랜잭션 원자성은 유지하면서 INSERT만 JDBC batch로 묶으면 처리시간을 줄일 수 있다고 가정했다.

## 변경 범위

성능 최적화 대상은 결제 대기 예약 만료 스케줄러의 history INSERT다.

- Before: 예약별 `ReservationHistoryRepository.save()`
- After: `JdbcTemplate.batchUpdate()`를 이용한 100건 단위 INSERT
- 유지: 만료 대상 SELECT와 관리 상태의 reservation dirty-check UPDATE
- 추가: 만료 예약 ID 묶음에 사용된 쿠폰을 복원하는 JPQL UPDATE 1회 (`N > 0`)
- 유지: 하나의 Spring 트랜잭션 경계
- 제외: Kafka가 처리하는 단건 예약 상태 변경 경로
- 제외: 정산, 쿠폰 발급 등 다른 쓰기 경로

운영 경로는 [`ExpiredReservationCleanupService`](../../src/main/java/kr/kro/airbob/domain/reservation/service/ExpiredReservationCleanupService.java)가 담당한다. 비교용 JPA 구현은 [`bulk-write-benchmark` 전용 Before 서비스](../../src/main/java/kr/kro/airbob/domain/reservation/service/ReservationHistoryInsertBeforeBenchmarkService.java)에만 남겨 운영 코드에 분기문을 추가하지 않았다.

## 구현

### 1. 트랜잭션 흐름

After 경로는 다음 순서로 실행된다.

1. 하나의 cutoff 시각으로 만료된 `PAYMENT_PENDING` 예약을 조회한다.
2. 조회한 관리 엔티티를 `EXPIRED`로 변경하고 history snapshot을 생성한다.
3. 만료 예약에 연결된 사용 쿠폰을 ID 묶음으로 한 번에 복원한다.
4. 모든 history를 100건 단위 JDBC batch로 저장한다.
5. 트랜잭션 commit 시 reservation UPDATE가 dirty checking으로 반영된다.

`JdbcTemplate`은 JPA와 같은 `DataSource` 및 Spring 트랜잭션에 참여한다. 따라서 중간 batch가 실패하면 앞에서 성공한 history chunk는 rollback되고 관리 엔티티의 reservation 상태 변경도 DB에 반영되지 않는다.

### 2. JDBC writer

[`ReservationHistoryBatchWriter`](../../src/main/java/kr/kro/airbob/domain/reservation/repository/ReservationHistoryBatchWriter.java)는 AUTO_INCREMENT `id`를 제외한 21개 컬럼을 명시적으로 바인딩한다.

- 예약 snapshot: 예약 ID·코드, 숙소·회원, 숙박일, 인원, 금액, 통화, 상태 등
- history 문맥: 변경 유형, 사유, source system, client IP
- 감사 문맥: JPA Auditing을 거치지 않으므로 cleanup 시작 cutoff를 모든 행의 `history_created_at`으로 명시하고 system actor의 `history_created_by`는 `null`로 기록

batch 결과가 `Statement.SUCCESS_NO_INFO`이면 영향 행 수를 임의로 추정하지 않고 `null`로 기록한다. `Statement.EXECUTE_FAILED`, 음수 미지원 값, 제출 행 수와 결과 배열 크기 불일치는 실패로 처리한다.

### 3. 모니터링

[`BulkOperationMonitor`](../../src/main/java/kr/kro/airbob/common/monitoring/bulkwrite/BulkOperationMonitor.java)는 다음 값을 한 작업 단위로 기록한다.

- `System.nanoTime()` 기반 서버 연산 시간
- Hibernate SQL 유형별 실행 횟수
- JDBC batch 호출 수
- JDBC 제출 행 수와 설정 batch size
- 드라이버가 알려준 경우에만 영향 행 수
- 성공 또는 실패 결과

Hibernate `StatementInspector`는 `JdbcTemplate` SQL을 볼 수 없으므로 JDBC batch 통계는 writer가 성공한 chunk만 별도로 기록한다. 작업 컨텍스트는 현재 동기식 단일 실행 스레드에서 `ThreadLocal`로 전달되며 `finally`에서 제거된다. k6 비교도 1 VU에서 `SAMPLES=1` child를 순차 실행하므로 이 전달 방식의 범위 안에 있다. 멀티스레드 batch에 그대로 일반화하지 않는다.

## 정확성 검증

성능 비교 전에 다음 동등성과 실패 경계를 통합 테스트로 검증했다.

- 대상 예약만 `EXPIRED`가 되고 미래 예약과 다른 상태 예약은 보존된다.
- Before와 After가 같은 수의 history를 생성한다.
- business snapshot 필드의 동등성과 scheduler 감사 계약(`history_created_at` non-null, system actor, `STATUS_CHANGE`, `BATCH`)을 검증한다.
- After 경로의 Hibernate INSERT는 0회이고 제출한 JDBC 행 수는 대상 수와 같다.
- 두 번째 JDBC chunk가 실패하면 첫 chunk INSERT는 rollback되고 관리 엔티티의 reservation 상태 변경도 DB에 반영되지 않는다.

핵심 회귀 테스트는 [`ReservationHistoryInsertBenchmarkIntegrationTest`](../../src/test/java/kr/kro/airbob/domain/reservation/ReservationHistoryInsertBenchmarkIntegrationTest.java)에 있다.

## SQL 및 batch 변화

`N > 0`, batch size 100일 때 한 번의 cleanup에서 관찰되는 값은 다음과 같다.

| 구분 | Before | After |
| --- | ---: | ---: |
| Hibernate SELECT | 1 | 1 |
| Hibernate INSERT | N | 0 |
| Hibernate UPDATE | N | `N + 1` |
| Hibernate TOTAL | `2N + 1` | `N + 2` |
| JDBC batch 호출 | 0 | `ceil(N / 100)` |
| JDBC 제출 행 | 0 | N |

After의 고정 UPDATE 1회는 사용된 쿠폰이 없는 fixture에서도 실행되는 멱등 bulk UPDATE다. 이 표의 `Hibernate TOTAL`은 raw JDBC 호출을 포함하지 않는다. 또한 Connector/J의 `rewriteBatchedStatements=true`가 실제 wire-level SQL을 재작성할 수 있으므로 JDBC batch 호출 수를 DB 네트워크 왕복 수와 완전히 같은 값으로 단정하지 않는다.

## 로컬 통제 측정

### 측정 환경

- 애플리케이션 인스턴스: 1
- JVM: OpenJDK 21.0.6
- MySQL: 8.0.33, 일회성 컨테이너
- 전용 schema: `_bulk_write_benchmark` suffix
- batch size: 100
- `rewriteBatchedStatements`: true
- SQL·bind·Before 행별 WARN 로그: 비활성화
- 자동 scheduler와 Kafka listener: 비활성화

각 `N ∈ {0, 100, 1000, 2000}`에 대해 Before 3회와 After 3회를 워밍업한 뒤, variant별로 독립된 1회 요청 결과를 10개씩 보존했다. 통계는 원시 `server_operation_ms`를 정렬해 nearest-rank 방식으로 다시 계산했다.

### 측정 경계

`server_operation_ms`에 포함되는 범위는 만료 대상 SELECT, history write, reservation UPDATE flush, transaction commit이다.

다음은 제외된다.

- fixture 생성과 정리
- 로그인과 응답 검증
- HTTP 네트워크 왕복

따라서 결과는 history INSERT를 주된 최적화 대상으로 한 cleanup 트랜잭션 비교이며, 운영 스케줄러 전체 처리량을 의미하지 않는다.

### 로컬 결과

아래 수치는 쿠폰 복원 UPDATE가 추가되기 전 커밋의 원시 표본이다. 기능 경계가 바뀌었으므로 성능 수치는 다음 격리 측정에서 다시 수집해야 한다. 각 행은 원시 표본 10개를 사용했고, 표본이 10개인 nearest-rank p95는 열 번째 값이므로 max와 같다.

| N | Variant | Min (ms) | p50 (ms) | p95 (ms) |
| ---: | --- | ---: | ---: | ---: |
| 0 | Before | 1.937666 | 2.587875 | 3.199917 |
| 0 | After | 2.069625 | 2.581041 | 5.555417 |
| 100 | Before | 87.084542 | 106.140292 | 122.464791 |
| 100 | After | 46.498083 | 52.265041 | 53.841834 |
| 1,000 | Before | 695.472667 | 778.889541 | 1,341.043208 |
| 1,000 | After | 369.230083 | 379.355083 | 429.478375 |
| 2,000 | Before | 1,287.282667 | 1,334.268834 | 1,405.116334 |
| 2,000 | After | 695.255500 | 719.705667 | 790.012542 |

| N | Before p50 | After p50 | p50 감소율 | 속도 향상 |
| ---: | ---: | ---: | ---: | ---: |
| 0 | 2.587875 ms | 2.581041 ms | 0.26% | 1.00배 |
| 100 | 106.140292 ms | 52.265041 ms | 50.76% | 2.03배 |
| 1,000 | 778.889541 ms | 379.355083 ms | 51.30% | 2.05배 |
| 2,000 | 1,334.268834 ms | 719.705667 ms | 46.06% | 1.85배 |

0건 대조군은 거의 같았고 변경 경로가 실행되는 100~2,000건에서는 p50이 46.06~51.30% 감소했다. 이는 로컬 사전 검증 결과이며 AWS 최종 수치로 일반화하지 않는다.

## AWS 최종 측정 계획

### 안전 경계

현재 [`BulkWriteBenchmarkDatabaseGuard`](../../src/main/java/kr/kro/airbob/common/benchmark/bulkwrite/BulkWriteBenchmarkDatabaseGuard.java)는 활성 profile에 `aws` 또는 `oci`가 있으면 기동을 거부한다. 벤치마크 API를 운영 AWS profile이나 운영 ASG에 활성화하지 않는다.

AWS 측정은 다음 조건의 격리된 환경에서만 수행한다.

- AWS에 별도 측정 인스턴스 1대를 준비한다.
- 전용 launcher가 고정하는 `dev,bulk-write-benchmark` profile로 실행한다.
- 운영 RDS와 자원을 공유하지 않는 전용 DB 인스턴스 또는 일회성 DB 컨테이너에 미리 migration한 폐기 가능 schema를 사용한다.
- schema 이름은 `_bulk_write_benchmark`로 끝나야 한다.
- 전용 ADMIN 계정과 32자 이상의 benchmark token을 사용한다.
- HTTP를 사용하면 k6 보호 규칙에 맞게 같은 호스트 또는 SSH tunnel의 정확한 loopback 주소로 호출한다. 원격 hostname을 직접 사용해야 하면 유효한 HTTPS origin만 허용한다.
- 측정 endpoint는 public load balancer에 연결하지 않는다.
- 측정이 끝나면 애플리케이션, schema, Redis와 자격 증명을 폐기한다.

`bulk-write-benchmark` profile에서는 Flyway가 비활성화되므로 빈 schema를 애플리케이션이 자동 생성한다고 가정하면 안 된다. 현재 migration을 측정 전에 별도로 적용한다.

### 고정할 변수

| 항목 | 기록값 |
| --- | --- |
| Git commit / image digest | TODO |
| EC2 instance type / AMI / OS / vCPU / memory | TODO |
| container runtime 또는 직접 실행 방식 | TODO |
| load generator 위치 / 사양 | TODO |
| JVM vendor / version / heap / GC | TODO |
| RDS engine / version / instance class / storage / IOPS | TODO |
| RDS parameter group / Multi-AZ 여부 | TODO |
| 애플리케이션과 RDS region / AZ | TODO |
| 버스터블 인스턴스 CPU credit 상태 | TODO 또는 해당 없음 |
| Hikari maximum pool 등 pool 설정 | TODO |
| batch size | 100 |
| 앱 `JDBC_REWRITE_BATCHED_STATEMENTS` | true로 명시 |
| k6 `REWRITE_BATCHED_STATEMENTS` metadata | true로 명시 |
| 애플리케이션 인스턴스 수 | 1 |
| schema label | TODO (`*_bulk_write_benchmark`) |
| Redis 구성 | TODO |
| 요청 timeout | 30s |

### 실행 순서

1. launcher 로그에서 profile, 단일 인스턴스, SQL·bind·Before WARN 비활성화를 확인한다.
2. 실행자가 입력한 artifact metadata를 실제 runtime과 교차검증한다.
   - `APP_COMMIT`: `git rev-parse HEAD` 또는 배포 image digest
   - `APP_INSTANCE_COUNT=1`: 실제 listener와 프로세스 수
   - `SCHEMA_LABEL`: `SELECT DATABASE()` 및 `BENCHMARK_BULK_WRITE_ALLOWED_SCHEMA`
   - `MYSQL_VERSION`: `SELECT VERSION()`
   - `JVM_VERSION`: `java -version`
   - rewrite metadata: 앱의 `JDBC_REWRITE_BATCHED_STATEMENTS=true`와 k6의 `REWRITE_BATCHED_STATEMENTS=true`
3. Before `N=0`과 After `N=100` 1표본 smoke로 선택적 metric과 JDBC batch 계약을 확인한다.
4. `N=0, 100, 1000, 2000`마다 variant별 워밍업을 3회 수행한다.
5. 각 variant에서 독립된 1표본을 라운드별 최소 30개, 가능하면 50개 수집한다. 표본 수는 실행 전에 고정한다.
6. 1라운드는 Before→After, 2라운드는 After→Before 순서로 실행해 순서 효과를 확인한다.
7. 각 라운드 결과를 먼저 따로 보고하고 환경 메타데이터가 같을 때만 합산한다. 유리한 라운드만 사후 선택하지 않는다.
8. 원시 표본에서 nearest-rank min·p50·p95·max를 독립적으로 재계산한다.
9. 결과물과 로그에 token, session, 이메일, DB 자격 증명이 없는지 검사한다.
10. 측정 자원과 endpoint를 제거한 뒤 listener와 schema 정리를 확인한다.

모든 표본은 `verification_succeeded=true`, `verified_rows=N`, 예상 SQL/JDBC 수, 동일 commit·schema·JVM·MySQL 메타데이터를 만족해야 한다. 하나라도 실패하면 해당 그룹의 시간을 성능 근거로 사용하지 않는다.

### AWS 결과 입력표

AWS 재측정 후 로컬 수치와 섞지 않고 이 표를 채운다.

| N | 집계 범위 / 실행 순서 | Before p50 | Before p95 | After p50 | After p95 | p50 감소율 | p95 감소율 | 표본 수/variant |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | R1 Before→After | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| 0 | R2 After→Before | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| 100 | R1 Before→After | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| 100 | R2 After→Before | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| 1,000 | R1 Before→After | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| 1,000 | R2 After→Before | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| 2,000 | R1 Before→After | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| 2,000 | R2 After→Before | TODO | TODO | TODO | TODO | TODO | TODO | TODO |

두 라운드의 runtime 조건과 분포가 일치하면 `Pooled` 행을 추가한다. 조건이 다르면 합산하지 않고 원인을 제거한 뒤 다시 측정한다.

AWS 원시 JSON은 Git에서 제외되는 `build/k6/**`에만 두지 않는다. 다음 provenance를 함께 만든 뒤 접근이 제한된 내구성 있는 저장소에 보관한다.

- UTC `generated_at`
- 공개 정보만 포함한 run label과 artifact prefix
- 파일 목록 및 SHA-256 manifest
- 비밀을 제거한 runtime 교차검증 로그
- 각 그룹의 원시 `server_operation_ms` 배열
- 문서에서 참조할 비공개 archive ID

AWS 측정 후 이 문서에는 archive의 자격 증명이나 실제 endpoint 대신 archive ID, manifest digest, 원시 시간 배열을 기록한다.

## 실행 진입점

- 서버 launcher: [`run-bulk-write-benchmark-server.sh`](../../load-test/k6/bulk-write/run-bulk-write-benchmark-server.sh)
- 단일 k6 실행: [`run-reservation-history-insert.sh`](../../load-test/k6/bulk-write/run-reservation-history-insert.sh)
- 원시 표본 수집: [`run-reservation-history-insert-observations.sh`](../../load-test/k6/bulk-write/run-reservation-history-insert-observations.sh)
- 수동 smoke 요청: [`reservation-history-insert-bulk-write-comparison.http`](../../load-test/http/reservation-history-insert-bulk-write-comparison.http)
- 공통 query monitoring: [`query-count-monitoring.md`](../query-count-monitoring.md)

실제 token, 이메일, 비밀번호, session ID는 명령 기록이나 결과 JSON에 남기지 않는다.

## 이력서 서술

### AWS 재측정 전 내부 초안 — 외부 제출 보류

> 결제 대기 예약 만료 작업에서 JPA `IDENTITY`로 인해 `ReservationHistory` INSERT가 대상 수만큼 즉시 실행되는 경로를 계측하고, 21개 컬럼을 명시적으로 바인딩하는 JDBC batch(size 100)로 전환했습니다. 단일 트랜잭션과 business snapshot을 유지했으며 로컬 통제 실험에서 100~2,000건 cleanup의 p50을 46.06~51.30% 단축했습니다.

이 문장은 구현과 로컬 검증 내용을 정리하기 위한 내부 초안이다. AWS 최종 측정 전에는 외부 제출용 이력서의 정량 문장으로 확정하지 않는다.

### AWS 측정 후 교체할 문장

> 결제 대기 예약 만료 작업의 JPA `IDENTITY` INSERT 병목을 SQL 유형별 실행 횟수와 원시 latency 표본으로 확인하고 JDBC batch(size 100)로 전환했습니다. AWS 격리 환경에서 `[N 범위]`건 처리 시 p50을 `[Before]ms → [After]ms([감소율]%)`, p95를 `[Before]ms → [After]ms([감소율]%)`로 단축했으며, 실패 chunk 전체 rollback과 business snapshot·scheduler 감사 계약을 통합 테스트로 검증했습니다.

면접에서는 “JPA를 잘못 사용했다”가 아니라 다음 순서로 설명한다.

1. 초기 구현은 일반적인 단건 도메인 저장에는 단순하고 안전했다.
2. 동일 트랜잭션에서 만료 이력이 대량 생성되는 workload를 별도로 계측했다.
3. Before 경로를 benchmark profile에 동결해 같은 fixture로 비교했다.
4. `IDENTITY` 특성상 일반 Hibernate INSERT batching으로 해결되지 않는 범위를 확인했다.
5. JDBC로 우회하면서 감사 필드의 명시적 바인딩, 트랜잭션, 실패 순서를 테스트로 보강했다.
6. 코드 변경 후 동일 조건의 원시 표본으로 다시 측정했다.

## 한계와 후속 과제

- 만료 대상을 한 번에 조회해 하나의 트랜잭션에서 처리하므로 대상 수가 무제한으로 커지는 scheduler 확장성까지 해결한 것은 아니다.
- 여러 애플리케이션 인스턴스의 중복 실행이나 결제 완료와의 경쟁을 막는 claim/lock 전략은 이번 변경 범위가 아니다.
- hold 제거는 DB commit 전에 호출된다. history batch 실패 전파는 보장하지만, hold 제거 후 DB commit 자체가 실패하는 경우의 외부 상태 불일치는 별도 신뢰성 과제다.
- 로컬 측정은 Redis 네트워크를 제외했으므로 운영 end-to-end 시간을 나타내지 않는다.
- AWS 최종 수치는 격리된 동일 환경에서 다시 측정한 뒤 이 문서와 이력서 문장을 함께 갱신한다.
