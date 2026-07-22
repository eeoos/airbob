# Wishlist bulk DELETE 성능 개선

## 상태

- 구현 및 현재 코드 기준 로컬 통제 측정: 완료
- AWS 격리 환경 최종 측정: 예정
- 로컬 측정일: 2026-07-22
- 로컬 측정 애플리케이션 커밋: `d91911d9b91c9437343d57d874044765742cf5f1`

이 문서의 시간 수치는 로컬 전용 MySQL 환경에서 얻은 사전 검증 결과다. SQL 수 감소, 기능 동등성, 측정 절차는 확인했지만 이력서의 최종 정량 수치는 AWS에서 같은 절차로 다시 측정한 뒤 확정한다.

## 문제와 개선 가설

Wishlist를 삭제할 때 연관된 `WishlistAccommodation`도 먼저 제거해야 한다. 초기 구현의 Spring Data 파생 삭제 메서드 `deleteAllByWishlistId()`는 대상 엔티티를 조회한 뒤 엔티티별 DELETE를 실행했다. 연관 행이 `N`개면 DELETE 문도 `N`개가 된다.

초기 구현은 엔티티 삭제 의미를 그대로 사용하고 코드가 단순하므로 소규모 Wishlist에는 합리적이었다. 다만 한 Wishlist에 저장되는 숙소 수가 커질 때 DELETE 실행 횟수가 선형으로 증가한다. 동일한 소유권 검증과 트랜잭션을 유지하면서 조회한 엔티티를 `deleteAllInBatch(Iterable)`에 전달하면 현재 Spring Data JPA/Hibernate 조합에서는 PK 목록을 이용한 bulk DELETE 한 문장으로 줄어들 것이라고 가정했다.

## 변경 범위와 실제 동작

- Before: benchmark profile에 보존한 `deleteAllByWishlistId(wishlistId)`
- After: 운영 서비스의 `findAllByWishlistId(wishlistId)` 후 `deleteAllInBatch(entities)`
- 유지: Wishlist 존재 여부와 소유권 검증
- 유지: 자식 삭제와 Wishlist soft delete를 하나의 Spring 트랜잭션에서 처리
- 유지: Wishlist 자체는 `DELETED` 상태로 변경하는 UPDATE 1회
- 제외: Wishlist 안의 숙소 한 건만 삭제하는 API
- 제외: 조회용 반정규화 개선과 다른 도메인의 bulk write

운영 경로는 [`WishlistService.deleteWishlist`](../../src/main/java/kr/kro/airbob/domain/wishlist/service/WishlistService.java)가 담당한다. 비교용 기존 구현은 [`WishlistDeleteBeforeBenchmarkService`](../../src/main/java/kr/kro/airbob/domain/wishlist/service/WishlistDeleteBeforeBenchmarkService.java)에만 보존했고 `bulk-write-benchmark` profile과 명시적 enable 속성이 있을 때만 생성된다.

### `deleteAllInBatch(Iterable)`을 정확히 해석하기

이번 After는 다음과 같은 SQL 형태로 관찰됐다.

```sql
SELECT ...
FROM wishlist_accommodation
WHERE wishlist_id = ?;

DELETE FROM wishlist_accommodation
WHERE id IN (?, ?, ...);
```

따라서 다음을 구분해야 한다.

- 줄어든 것은 자식 DELETE **문장 수**다. `N`회에서 `N > 0`일 때 1회가 됐다.
- `findAllByWishlistId()`가 여전히 `N`개 엔티티를 조회하고 영속화한다.
- DELETE에는 `N`개의 PK bind가 들어가고 DB가 실제로 삭제하는 행도 `N`개다.
- `DELETE WHERE wishlist_id = ?`로 직접 삭제하는 구현이 아니다.
- `JdbcTemplate` batch가 아니며 영향 행 수를 반환하지 않는다.
- 그러므로 전체 작업량이 `N`과 무관한 O(1)이 됐다고 표현하지 않는다.

현재 요구사항은 이미 조회한 대상의 PK만 삭제하는 `deleteAllInBatch(Iterable)`을 선택했다. 향후 실제 cardinality와 메모리 사용이 더 큰 문제가 되면 `@Modifying DELETE ... WHERE wishlist_id = :id` 같은 직접 조건 삭제를 별도 후보로 측정해야 한다.

## SQL 변화

Hibernate `StatementInspector`에서 관찰한 한 번의 삭제 작업 계약은 다음과 같다. `TOTAL`은 `StatementInspector`가 분류한 Hibernate SQL만 합산한다. 아래 JDBC 행은 명시적으로 보고된 custom writer 통계이며 Wishlist 구현은 해당 writer를 호출하지 않는다.

| 구분 | Before | After (`N = 0`) | After (`N > 0`) |
| --- | ---: | ---: | ---: |
| SELECT | 2 | 2 | 2 |
| INSERT | 0 | 0 | 0 |
| UPDATE | 1 | 1 | 1 |
| DELETE | N | 0 | 1 |
| TOTAL | `N + 3` | 3 | 4 |
| custom JDBC writer batch 계측 호출 | 0 | 0 | 0 |
| custom JDBC writer 제출 행 | 0 | 0 | 0 |

SELECT 2회는 Wishlist 조회·소유권 검증과 자식 membership 조회에 사용된다. UPDATE 1회는 Wishlist soft delete의 dirty checking 결과다. Hibernate SQL 개수는 애플리케이션에서 관찰한 statement 수이며 DB 네트워크 왕복을 직접 계측한 값으로 표현하지 않는다. JDBC 값은 `ReservationHistoryBatchWriter`가 명시적으로 보고하는 custom writer 통계이므로, Hibernate나 JDBC 드라이버의 모든 `executeBatch` 호출을 가로채는 범용 계측값도 아니다.

## 정확성과 회귀 검증

성능 수치를 사용하기 전에 [`WishlistDeleteBenchmarkIntegrationTest`](../../src/test/java/kr/kro/airbob/domain/wishlist/WishlistDeleteBenchmarkIntegrationTest.java)에서 다음을 검증했다.

- Before와 After 모두 요청한 `N`개의 자식만 제거한다.
- 대상 Wishlist는 soft delete되고 무관한 Wishlist·membership·Accommodation은 보존된다.
- `N = 0`은 Before와 After 모두, 최대 허용 크기 `N = 1,000`은 After에서 처리한다.
- After의 `N > 0`에서 Hibernate DELETE가 1회다.
- flush 뒤 예외가 발생하면 자식 bulk DELETE와 Wishlist soft delete가 함께 rollback된다.
- 존재하지 않는 Wishlist와 다른 회원 소유 Wishlist의 기존 예외 의미를 유지한다.
- fixture 생성·검증·정리는 측정 구간 밖에 있으며 실패 시에도 정리를 시도한다.

benchmark API는 [`WishlistDeleteBenchmarkController`](../../src/main/java/kr/kro/airbob/domain/wishlist/api/WishlistDeleteBenchmarkController.java)로 격리했다. 일반 세션 인증에 더해 별도의 `X-Bulk-Write-Benchmark-Token`, ADMIN 권한, 전용 schema guard를 요구하며 일반 profile에서는 endpoint 자체가 생성되지 않는다.

## 모니터링과 측정 경계

[`BulkOperationMonitor`](../../src/main/java/kr/kro/airbob/common/monitoring/bulkwrite/BulkOperationMonitor.java)는 `System.nanoTime()`으로 서버 연산 시간을 측정하고 Hibernate SQL을 유형별로 기록한다. 현재 삭제 경로는 동기식 단일 스레드이므로 `ThreadLocal` 컨텍스트 범위 안에 있다. 비동기 또는 멀티스레드 실행으로 바꾸면 컨텍스트 전달 방식을 다시 설계해야 한다.

`server_operation_ms`에 포함되는 범위는 다음과 같다.

- Wishlist 조회와 소유권 검증
- 자식 membership 조회 및 엔티티 materialization
- 자식 DELETE
- Wishlist soft delete UPDATE flush와 transaction commit

다음은 제외된다.

- HTTP 로그인, controller 처리와 응답 직렬화
- 전용 benchmark DB guard 확인
- fixture 생성
- 삭제 결과·control fixture 검증
- fixture 정리
- k6 클라이언트와 네트워크 왕복 시간

따라서 이 수치는 실제 사용자 API의 end-to-end 지연이 아니라 **비교 대상 서버 연산 구간**의 시간이다.

## 현재 코드 기준 로컬 통제 측정

### 환경과 절차

- 애플리케이션 인스턴스: 1
- profile: `dev,bulk-write-benchmark`
- JVM: OpenJDK 21.0.6
- MySQL: 8.0.33
- 전용 schema: `airbob_bulk_write_benchmark`
- `rewriteBatchedStatements`: false
- 요청 제한 시간: 120초
- SQL·bind 로그: 비활성화
- dataset: `N ∈ {0, 100, 1000}`

각 `N`과 variant 그룹을 3회 워밍업한 뒤, `SAMPLES=1`인 독립 child artifact를 10개씩 수집했다. 1라운드는 Before→After, 2라운드는 After→Before 순서로 교차했다. 따라서 각 `N`·variant에는 20개, 전체에는 검증된 measure 표본 120개가 있다.

모든 표본은 다음 조건을 만족했다.

- `verification_succeeded=true`
- `verified_rows=N`
- 위 표의 Hibernate SQL 계약과 일치
- 명시적으로 계측한 custom JDBC writer 호출·제출 행 0, batch size·영향 행 `null`
- 동일한 app commit, schema, JVM, MySQL, instance count, timeout

통계는 원시 `server_operation_ms`를 오름차순 정렬한 뒤 nearest-rank 방식으로 계산했다. 표본 수를 `n`, 분위수를 `p`라 할 때 순위는 `max(1, ceil(p × n))`이고 보간하지 않았다. 라운드별 `n=10`의 p95는 사실상 최대값이므로 최종 AWS 측정에서는 더 많은 표본을 사용한다.

### 라운드별 결과

단위는 ms다. 감소율은 `(Before - After) / Before × 100`이며 음수는 After가 느렸다는 의미다.

| N | 라운드 / 순서 | Before p50 | Before p95 | After p50 | After p95 | p50 감소율 | p95 감소율 |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | R1 Before→After | 8.318 | 11.606 | 9.098 | 11.823 | -9.38% | -1.87% |
| 0 | R2 After→Before | 5.965 | 7.958 | 5.864 | 7.032 | 1.68% | 11.64% |
| 100 | R1 Before→After | 34.662 | 39.426 | 8.424 | 13.851 | 75.70% | 64.87% |
| 100 | R2 After→Before | 30.816 | 37.180 | 6.644 | 14.574 | 78.44% | 60.80% |
| 1,000 | R1 Before→After | 234.759 | 322.730 | 19.636 | 26.432 | 91.64% | 91.81% |
| 1,000 | R2 After→Before | 209.385 | 230.329 | 22.646 | 50.258 | 89.18% | 78.18% |

### 두 라운드 합산 결과

동일한 variant·N과 환경 통제 메타데이터를 가진 두 라운드의 20개 원시 표본을 합산해 다시 계산했다. 의도적으로 달라지는 round·run label·실행 순서는 동일성 조건에서 제외했다.

| N | Before p50 | Before p95 | After p50 | After p95 | p50 감소율 | p95 감소율 | p50 배속 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 6.766 | 10.324 | 6.829 | 11.630 | -0.93% | -12.65% | 0.99x |
| 100 | 32.420 | 38.684 | 7.846 | 13.851 | 75.80% | 64.19% | 4.13x |
| 1,000 | 228.643 | 255.426 | 20.795 | 26.432 | 90.91% | 89.65% | 11.00x |

`N = 0`은 두 구현의 SQL 계약이 같고 결과도 실행 순서에 따라 방향이 바뀌므로 성능 차이가 없다고 해석한다. `N = 100`과 `N = 1,000`에서는 두 라운드 모두 같은 방향으로 개선됐고, 대상 수가 커질수록 N회 DELETE 제거 효과가 커졌다. 다만 `N = 1,000` R2 After에 50.258ms 표본이 있어 분산과 꼬리 지연은 AWS에서 표본 수를 늘려 다시 확인한다.

이 결과는 synthetic stress dataset의 결과다. 실제 사용자 Wishlist의 행 수 분포가 대부분 작다면 운영 효과와 이력서 우선순위도 작아진다. AWS 최종 측정 전에 운영과 분리된 통계 데이터로 Wishlist별 membership 수의 p50·p95·max를 확인하고, 실제 workload와 연결되지 않으면 ReservationHistory 사례를 대표 성능 항목으로 사용한다.

## 원시 로컬 표본

아래 배열은 각 companion artifact의 입력 순서 그대로인 `server_operation_ms` 10개다. 빌드 산출물은 Git에서 제외되므로 통계를 다시 계산할 수 있는 원시 시간값을 문서에도 보존한다.

```text
R1 N=0 AFTER  [9.209375, 10.359792, 9.827791, 11.823459, 8.361042, 11.630334, 6.677542, 8.605833, 9.097959, 6.992292]
R1 N=0 BEFORE [8.318125, 10.076125, 11.606167, 7.596417, 10.324209, 9.012, 9.821083, 7.614292, 5.431459, 6.473458]
R1 N=100 AFTER  [8.938375, 8.582709, 9.163625, 7.525083, 10.490583, 8.066667, 8.424375, 8.013167, 13.851458, 8.291334]
R1 N=100 BEFORE [33.833083, 36.047417, 34.662291, 31.789292, 35.086125, 39.425959, 33.587625, 38.68425, 33.428333, 35.455167]
R1 N=1000 AFTER  [18.737875, 19.636375, 17.881417, 19.703917, 20.284667, 18.296875, 26.432291, 22.395875, 20.093, 18.9115]
R1 N=1000 BEFORE [322.729916, 232.002875, 235.866625, 252.342833, 255.425875, 235.180541, 234.75925, 230.771333, 234.132125, 228.642708]
R2 N=0 AFTER  [6.369625, 4.416791, 4.314042, 6.829209, 7.031875, 5.634333, 5.871292, 6.010958, 5.562375, 5.864458]
R2 N=0 BEFORE [6.381959, 6.765959, 5.918833, 5.069375, 6.110917, 5.352208, 5.964583, 7.324625, 5.843125, 7.957917]
R2 N=100 AFTER  [6.215666, 6.853416, 6.617833, 6.745, 6.116125, 6.643959, 6.317958, 14.574042, 6.669041, 7.845916]
R2 N=100 BEFORE [32.301542, 31.261542, 31.695417, 32.4195, 28.655167, 37.179708, 28.591833, 29.588625, 30.815667, 28.088667]
R2 N=1000 AFTER  [23.438834, 50.258084, 22.645833, 18.9785, 23.356958, 21.857541, 22.698416, 21.835, 22.760666, 20.794584]
R2 N=1000 BEFORE [210.589166, 209.385334, 217.151666, 208.484542, 207.08, 201.090625, 230.329167, 220.603083, 207.969042, 224.780708]
```

## AWS 최종 측정 절차

1. 일반 트래픽과 분리된 benchmark 인스턴스 1개와 전용 비운영 schema를 준비한다.
2. 현재 branch의 Flyway 계보를 먼저 적용하고 schema 이름에 `_bulk_write_benchmark` suffix를 사용한다.
3. 전용 launcher가 강제하는 `dev,bulk-write-benchmark` profile, SQL 로그 OFF, 명시적 token과 schema allowlist를 그대로 사용한다.
4. 외부에 공개된 load balancer를 경유하지 않고 같은 사설망 또는 SSH tunnel/loopback에서 호출한다.
5. app commit·JVM·MySQL·instance count·schema label·timeout을 실제 runtime과 교차검증한다.
6. `N ∈ {0, 100, 1000}`과 실제 workload에서 확인한 대표 cardinality를 측정한다.
7. 각 그룹을 워밍업한 뒤 라운드당 최소 30개, 가능하면 50개의 독립 `SAMPLES=1` 표본을 모은다.
8. R1 Before→After, R2 After→Before로 교차하고 라운드별 결과를 먼저 확인한다.
9. 모든 표본의 결과 검증·SQL 계약·메타데이터가 일치할 때만 합산한다.
10. 원시 JSON, 비밀 제거 runtime 로그, SHA-256 manifest를 접근 제한된 내구성 저장소에 보관한다.
11. token·세션·이메일·DB 자격 증명이 artifact에 없는지 검사한 뒤 benchmark 인스턴스와 endpoint를 제거한다.

### AWS 결과 입력표

| N | 집계 범위 / 실행 순서 | Before p50 | Before p95 | After p50 | After p95 | p50 감소율 | p95 감소율 | 표본 수/variant |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | R1 Before→After | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| 0 | R2 After→Before | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| 100 | R1 Before→After | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| 100 | R2 After→Before | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| 1,000 | R1 Before→After | TODO | TODO | TODO | TODO | TODO | TODO | TODO |
| 1,000 | R2 After→Before | TODO | TODO | TODO | TODO | TODO | TODO | TODO |

조건과 분포가 일치할 때만 별도의 `Pooled` 행을 추가한다. 유리한 라운드만 선택하거나 로컬 수치와 AWS 수치를 섞지 않는다.

## 실행 진입점

- 서버 launcher: [`run-bulk-write-benchmark-server.sh`](../../load-test/k6/bulk-write/run-bulk-write-benchmark-server.sh)
- 단일 k6 실행: [`run-wishlist-delete.sh`](../../load-test/k6/bulk-write/run-wishlist-delete.sh)
- 원시 표본 수집: [`run-wishlist-delete-observations.sh`](../../load-test/k6/bulk-write/run-wishlist-delete-observations.sh)
- 원시 표본 검증·집계: [`aggregate-bulk-write-observations.mjs`](../../load-test/k6/bulk-write/aggregate-bulk-write-observations.mjs)
- 수동 smoke 요청: [`wishlist-delete-bulk-write-comparison.http`](../../load-test/http/wishlist-delete-bulk-write-comparison.http)
- 공통 query monitoring: [`query-count-monitoring.md`](../query-count-monitoring.md)

## 이력서 서술

### AWS 재측정 전 내부 초안 — 외부 제출 보류

> Wishlist 삭제의 Spring Data 파생 삭제가 연관 행 수만큼 DELETE를 실행하는 경로를 SQL 유형별로 계측하고, 기존 소유권 검증과 단일 트랜잭션을 유지한 채 `deleteAllInBatch(Iterable)`로 DELETE 문을 N회에서 1회로 줄였습니다. 로컬 교차 실험에서 synthetic 100·1,000건의 p50을 각각 75.80%, 90.91% 단축했으며 최종 수치는 AWS 격리 환경에서 재검증할 예정입니다.

이 문장은 구현과 로컬 검증을 정리하기 위한 내부 초안이다. AWS 결과와 실제 Wishlist cardinality를 확인하기 전에는 외부 제출용 정량 문장으로 확정하지 않는다.

### AWS 측정 후 교체할 문장

> 실제 Wishlist 규모 분포와 SQL 유형별 실행 횟수를 바탕으로 삭제 병목을 식별하고, 엔티티별 파생 삭제를 `deleteAllInBatch(Iterable)`로 전환해 DELETE 문을 N회에서 1회로 줄였습니다. AWS 격리 환경의 `[실제 대표 범위]`건에서 p50을 `[Before]ms → [After]ms([감소율]%)`, p95를 `[Before]ms → [After]ms([감소율]%)`로 단축했고, final state·소유권·rollback 동등성을 통합 테스트로 검증했습니다.

면접에서는 “처음부터 틀린 코드였다”가 아니라 다음 맥락으로 설명한다.

1. 초기 구현은 예상 cardinality가 작은 사용자 기능에서 단순성과 JPA 삭제 의미를 우선한 선택이었다.
2. 데이터 규모가 커질 때 DELETE 수가 선형 증가한다는 가설을 SQL 계측으로 확인했다.
3. Before를 운영 분기문이 아닌 격리된 benchmark profile에 보존했다.
4. 같은 fixture와 교차 순서의 원시 표본으로 변경 효과를 검증했다.
5. 개선 효과뿐 아니라 엔티티 조회와 PK bind가 여전히 N에 비례하는 한계도 함께 설명한다.

실제 Wishlist 규모가 작아 성능 영향이 미미하다면 이 사례를 이력서의 대표 항목으로 과장하지 않고, ReservationHistory JDBC batch 사례를 앞에 배치한다.

## 한계와 후속 과제

- After도 자식 엔티티 `N`개를 조회·materialize하므로 메모리와 SELECT 비용은 남아 있다.
- PK `IN` 목록도 `N`에 비례한다. 더 큰 dataset에서는 DB·드라이버 parameter 한계와 SQL parsing 비용을 다시 확인해야 한다.
- 조회와 bulk DELETE 사이에 동시에 추가된 membership은 PK 목록에 없어 남을 수 있다. 동시 생성과 Wishlist 삭제의 정책·locking은 이번 변경 범위가 아니다.
- bulk DML은 엔티티 callback과 cascade를 실행하지 않고 영속성 컨텍스트를 자동으로 정리하지 않는다. 현재 흐름은 삭제 뒤 해당 자식 엔티티를 다시 사용하지 않지만 후속 로직이 추가되면 주의해야 한다.
- `deleteAllInBatch(Iterable)`은 영향 행 수를 반환하지 않으므로 최종 상태 조회로 정확성을 검증했다.
- 로컬 `N = 100`, `N = 1,000`은 synthetic stress 값이다. 실제 cardinality와 요청 빈도가 낮으면 사용자 체감 및 비용 효과는 작다.
- 격리된 단일 인스턴스 측정이므로 실제 네트워크, 동시 부하, DB contention을 포함한 운영 end-to-end 성능을 나타내지 않는다.
