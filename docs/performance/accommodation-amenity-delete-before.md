# AccommodationAmenity DELETE Before 기준선

## 상태

- U4 Before 기준선 구현 및 로컬 통제 측정: 완료
- U5 predicate bulk DELETE 구현: 시작 전
- AWS 격리 환경 Before/After 최종 측정: 예정
- 로컬 측정일: 2026-07-22
- 로컬 측정 애플리케이션 커밋: `69c20d62a23c083e44ef48a725f1ca369b8b5667`

이 문서는 개선 결과가 아니라 **현재 운영 코드의 기준선과 개선 가치**를 기록한다. 아직 After가 없으므로 감소율·배속·성능 개선을 주장하지 않는다. 로컬 시간 수치는 U5의 실험 설계에만 사용하고, 이력서의 최종 수치는 같은 조건의 Before/After를 AWS에서 다시 측정한 뒤 결정한다.

## 판단하려는 가설

숙소 정보를 수정하면서 편의시설 목록이 전달되면 [`AccommodationService.updateAmenities`](../../src/main/java/kr/kro/airbob/domain/accommodation/service/AccommodationService.java)가 기존 편의시설 전체를 지우고 새 목록을 저장한다. 현재 [`AccommodationAmenityRepository.deleteByAccommodationId`](../../src/main/java/kr/kro/airbob/domain/accommodation/repository/AccommodationAmenityRepository.java)는 Spring Data 파생 삭제라 대상 엔티티를 먼저 조회한 뒤 행마다 DELETE를 실행한다.

기존 편의시설이 `N`개라면 삭제 단계의 Hibernate SQL은 `SELECT 1 + DELETE N`이다. 이를 `DELETE ... WHERE accommodation_id = ?` 형태의 predicate bulk DELETE로 바꾸면 삭제 단계를 한 문장으로 줄일 수 있다는 것이 U5의 가설이다.

다만 이 후보의 현실적인 크기는 제한적이다. 측정 시 활성 `AMENITY_TYPE` 코드는 30개였고, 운영 저장 로직은 같은 코드를 합산하므로 정상적인 숙소 한 건에 저장 가능한 서로 다른 편의시설 행도 최대 30개다. 따라서 이 후보가 SQL 구조상 개선 대상인 것과 이력서의 대표 성능 사례로 충분히 큰지는 별도로 판단해야 한다.

## 두 측정 경계

[`AccommodationAmenityDeleteBenchmarkService`](../../src/main/java/kr/kro/airbob/domain/accommodation/service/AccommodationAmenityDeleteBenchmarkService.java)는 같은 fixture로 서로 다른 두 경계를 측정한다. 두 결과는 목적이 다르므로 서로 빼거나 합쳐서 DELETE 비용 또는 개선율로 계산하지 않는다.

### `FULL_REPLACEMENT`

실제 Spring 프록시를 거쳐 운영 `AccommodationService.updateAccommodation()`을 호출한다. 다음을 포함한다.

- 숙소 조회와 소유자 확인
- 기존 편의시설 파생 삭제
- 요청의 중복 코드를 합산한 새 편의시설 저장
- 기존 SCD2 history 종료 UPDATE와 새 history INSERT
- transaction flush와 commit

따라서 이 값은 실제 운영 full replacement 경로의 비교 기준이지만 순수 DELETE latency는 아니다. 이번 replacement 요청은 편의시설 외 숙소 필드를 모두 `null`로 두므로 부모 숙소 UPDATE는 발생하지 않고, 관찰된 UPDATE 1회는 기존 SCD2 history를 닫는 문장이다. 측정 fixture는 DRAFT 숙소를 사용하므로 게시 숙소의 outbox/Elasticsearch 이벤트 발행은 포함하지 않는다.

### `DELETE_ONLY`

benchmark profile 전용 [`AccommodationAmenityDeleteBeforeBenchmarkService`](../../src/main/java/kr/kro/airbob/domain/accommodation/service/AccommodationAmenityDeleteBeforeBenchmarkService.java)가 현재 파생 삭제만 호출한다. 삭제 SQL 증가를 분리해서 보는 진단값이며 사용자 API의 end-to-end 지연이 아니다.

## workload 분류

- `REALISTIC`: `N <= 측정 시 활성 AMENITY_TYPE 코드 수`
- `STRESS`: `N > 측정 시 활성 AMENITY_TYPE 코드 수`

여기서 `REALISTIC`은 실제 운영 분포라는 뜻이 아니라 **현재 카탈로그로 표현 가능한 범위**라는 뜻이다. 이번 `N=30`은 그 범위의 상한이며 대표 사용자 숙소의 편의시설 수는 아직 확인하지 않았다. `N=100`은 삭제문의 선형 증가를 관찰하기 위한 synthetic stress 값이다.

## Before SQL 계약

`N`은 삭제할 기존 행 수, `R`은 중복 코드를 합산한 replacement의 서로 다른 활성 코드 수다.

| 측정 | SELECT | INSERT | UPDATE | DELETE | TOTAL |
| --- | ---: | ---: | ---: | ---: | ---: |
| `FULL_REPLACEMENT` | 3 | `R + 1` | 1 | `N` | `N + R + 5` |
| `DELETE_ONLY` | 1 | 0 | 0 | `N` | `N + 1` |

`FULL_REPLACEMENT`의 INSERT `R + 1`은 편의시설 `R`개와 새 history 1개이고, UPDATE 1회는 기존 현재 history 종료다. 이번 활성 코드는 30개이므로 `N=100`에서도 `R=30`이다. 검증 응답의 `verified_rows=100`은 새 편의시설 100개가 아니라 기존 target 행 100개가 제거됐다는 뜻이다.

이 개수는 Hibernate `StatementInspector`에서 관찰한 SQL 문장 수다. DB 네트워크 왕복 횟수나 DB 내부 작업량으로 단정하지 않는다. 이 후보는 custom JDBC writer를 사용하지 않으므로 명시적 JDBC batch 계측은 호출 0·제출 행 0이고 batch size·영향 행은 `null`이다.

## 정확성과 보호 경계

성능 수치보다 먼저 [`AccommodationAmenityDeleteBenchmarkIntegrationTest`](../../src/test/java/kr/kro/airbob/domain/accommodation/AccommodationAmenityDeleteBenchmarkIntegrationTest.java)에서 다음을 검증했다.

- `FULL_REPLACEMENT`는 실제 트랜잭션 프록시의 운영 서비스를 호출한다.
- 기존 target 편의시설 ID가 모두 사라지고 replacement map이 코드 합산 결과와 일치한다.
- `DELETE_ONLY`는 target 편의시설만 제거하고 숙소·history를 변경하지 않는다.
- target과 control 숙소의 전체 영속 필드, control 편의시설, SCD2 history 효과를 검증한다.
- replacement INSERT flush 직후 주입한 실패가 이미 수행한 derived DELETE와 replacement INSERT를 함께 rollback하고, 아직 변경되지 않은 숙소·history와 control이 그대로 보존됨을 확인한다.
- 대상이 없는 두 측정의 동작, 잘못된 요청의 DB 접근 전 거부, operation 실패 뒤 cleanup 시도와 operation·cleanup 동시 실패 시 원래 예외 보존을 검증한다.
- 통합 테스트는 `N=0`, 카탈로그 상한과 상한+1 stress를 다루고, 요청 최대 `N=100`은 아래 로컬 측정에서 두 경계 각각 20회 실행·검증한다.

benchmark endpoint는 일반 세션 인증에 더해 ADMIN 권한, 별도 `X-Bulk-Write-Benchmark-Token`, `bulk-write-benchmark` profile, 명시적 enable 속성, `_bulk_write_benchmark` suffix의 전용 schema와 필수 테이블을 요구한다. fixture 생성·검증·정리는 측정 구간 밖에서 각각 별도 트랜잭션으로 수행하고, 요청이 만든 target/control 데이터만 정리한다.

## 모니터링 경계

[`BulkOperationMonitor`](../../src/main/java/kr/kro/airbob/common/monitoring/bulkwrite/BulkOperationMonitor.java)는 `System.nanoTime()`으로 transactional service 프록시 호출 전체를 측정하고 같은 스레드의 Hibernate SQL을 유형별로 기록한다. 현재 숙소 수정과 진단 서비스는 동기식 단일 스레드라 `ThreadLocal` 컨텍스트와 맞는다. 비동기 또는 멀티스레드로 바뀌면 컨텍스트 전달을 다시 설계해야 한다.

다음은 `server_operation_ms`에서 제외된다.

- HTTP 로그인, controller 처리와 응답 직렬화
- 전용 DB guard 확인
- fixture 생성
- 결과 및 control fixture 검증
- fixture 정리
- k6 클라이언트와 네트워크 왕복 시간

## 로컬 통제 측정

### 환경과 절차

- 애플리케이션 인스턴스: 1
- profile: `dev,bulk-write-benchmark`
- JVM: OpenJDK 21.0.6
- MySQL: 8.0.33
- 전용 schema: `airbob_bulk_write_benchmark`
- `rewriteBatchedStatements`: false
- 요청 제한 시간: 120초
- 활성 `AMENITY_TYPE` 코드: 30개
- SQL·bind 로그: 비활성화

`FULL_REPLACEMENT`와 `DELETE_ONLY`의 `N=30`, `N=100` 네 그룹을 각각 3회 워밍업했다. 이후 각 그룹에서 `SAMPLES=1`인 독립 child artifact를 20개씩 수집해 총 80개 measure 표본을 만들었다. 실행 순서는 `FULL N30 → DELETE_ONLY N100 → DELETE_ONLY N30 → FULL N100`이었다. 최초 smoke와 워밍업 값은 통계에서 제외했다.

모든 80개 표본은 다음 조건을 만족했다.

- 요청 성공 및 `verification.succeeded=true`
- old target 삭제 수와 최종 replacement/control 상태 일치
- 위 Hibernate SQL 계약과 일치
- custom JDBC writer 호출·제출 행 0, batch size·영향 행 `null`
- 동일한 app commit, schema, JVM, MySQL, instance count, timeout
- token·세션·이메일·비밀번호·webhook 필드 또는 값 없음

통계는 원시 `server_operation_ms`를 오름차순 정렬한 nearest-rank 방식이다. 표본 수를 `n`, 분위수를 `p`라 할 때 순위는 `max(1, ceil(p × n))`이고 보간하지 않았다.

### 결과

단위는 ms다. 서로 다른 측정 경계 또는 workload class 사이의 수치를 개선율로 해석하지 않는다.

| 측정 | N | 분류 | SQL (S/I/U/D/T) | min | p50 | p95 | max | 표본 |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |
| `FULL_REPLACEMENT` | 30 | `REALISTIC` | 3/31/1/30/65 | 24.321 | 28.785 | 43.976 | 48.813 | 20 |
| `DELETE_ONLY` | 30 | `REALISTIC` | 1/0/0/30/31 | 10.250 | 11.584 | 17.422 | 22.296 | 20 |
| `FULL_REPLACEMENT` | 100 | `STRESS` | 3/31/1/100/135 | 38.168 | 42.635 | 44.206 | 47.769 | 20 |
| `DELETE_ONLY` | 100 | `STRESS` | 1/0/0/100/101 | 26.061 | 30.085 | 36.776 | 40.949 | 20 |

현재 카탈로그 상한 `N=30`에서도 파생 삭제가 `SELECT 1 + DELETE 30`을 실행하는 것은 20개 표본 모두에서 재현됐다. 분리 진단의 p50은 11.584ms, full replacement의 p50은 28.785ms였다. `N=100` stress에서는 DELETE가 100회로 늘고 두 경계의 시간도 증가해 선형 SQL 구조가 측정 가능한 비용이라는 신호는 확인했다.

그러나 `N=30`의 절대 시간은 작고, 실제 숙소의 편의시설 수 분포는 보통 더 작을 수 있다. 따라서 이 결과만으로 “운영 병목”이나 “성능 개선”이라고 표현하지 않는다. U5 후 실제 범위에서 SQL 감소와 절대 지연 감소가 모두 재현되는지 확인하고, 효과가 작으면 대표 이력서 항목이 아닌 SQL 단순화·불필요한 엔티티 materialization 제거 사례로 분류한다.

## 원시 로컬 표본

빌드 산출물 `build/k6/**`는 Git에서 제외되므로 companion artifact 입력 순서의 원시 `server_operation_ms`도 문서에 보존한다.

```text
FULL_REPLACEMENT N=30 REALISTIC [35.289375, 33.312709, 30.194667, 28.785084, 31.514083, 32.121, 35.448625, 31.952375, 28.349833, 26.754375, 26.385375, 28.395083, 26.987083, 27.364583, 25.342958, 43.975917, 48.813375, 32.511084, 26.207834, 24.320708]
DELETE_ONLY N=30 REALISTIC [14.542417, 10.812459, 11.56575, 10.549292, 12.091458, 10.983916, 17.421875, 14.440583, 11.583625, 11.182209, 11.06575, 11.712, 15.635417, 22.29625, 12.538542, 11.499042, 11.64225, 10.993333, 10.250375, 11.83575]
FULL_REPLACEMENT N=100 STRESS [44.206375, 41.02525, 41.294792, 38.1675, 43.877208, 41.131791, 42.634833, 39.54625, 42.682125, 43.541292, 43.765041, 41.301208, 42.097583, 39.967167, 43.819833, 42.946833, 39.224959, 47.769, 44.094458, 43.913708]
DELETE_ONLY N=100 STRESS [26.061333, 31.927, 30.117166, 29.422125, 28.909042, 30.957708, 33.991208, 30.085125, 33.742458, 34.1475, 29.667416, 26.345791, 29.96875, 34.448583, 26.128167, 29.314583, 36.776208, 29.412416, 40.94875, 34.447333]
```

## U5 및 AWS 재측정 계획

1. 운영 repository에 `accommodation_id` predicate bulk DELETE를 추가한다.
2. Before는 benchmark profile 전용 서비스에 그대로 보존한다.
3. After의 기능 동등성, control 보존, rollback, bulk DML의 영속성 컨텍스트 영향을 통합 테스트로 검증한다.
4. 같은 fixture에서 After SQL 계약을 먼저 확인한다.
5. 로컬에서 `N=0`, 실제 범위의 대표값과 상한 `N=30`, stress `N=100`을 워밍업 후 Before/After 교차 순서로 측정한다.
6. AWS 격리 환경에서는 실제 숙소별 편의시설 cardinality의 p50·p95·max를 먼저 확인하고 대표 `N`을 정한다.
7. 각 그룹을 라운드당 최소 30개, 가능하면 50개의 독립 표본으로 측정한다.
8. 모든 표본의 결과 검증·SQL 계약·메타데이터가 일치할 때만 라운드별 및 pooled 통계를 계산한다.

### AWS 결과 입력표

| N / 실제 분위 | 실행 순서 | Before p50 | Before p95 | After p50 | After p95 | DELETE Before→After | 표본 수/variant |
| --- | --- | ---: | ---: | ---: | ---: | --- | ---: |
| 실제 p50 | R1 Before→After | TODO | TODO | TODO | TODO | TODO | TODO |
| 실제 p50 | R2 After→Before | TODO | TODO | TODO | TODO | TODO | TODO |
| 실제 p95 또는 30 | R1 Before→After | TODO | TODO | TODO | TODO | TODO | TODO |
| 실제 p95 또는 30 | R2 After→Before | TODO | TODO | TODO | TODO | TODO | TODO |
| 100 stress | 선택 검증 | TODO | TODO | TODO | TODO | TODO | TODO |

## 실행 진입점

- 서버 launcher: [`run-bulk-write-benchmark-server.sh`](../../load-test/k6/bulk-write/run-bulk-write-benchmark-server.sh)
- 단일 k6 실행: [`run-accommodation-amenity-delete.sh`](../../load-test/k6/bulk-write/run-accommodation-amenity-delete.sh)
- 원시 표본 수집: [`run-accommodation-amenity-delete-observations.sh`](../../load-test/k6/bulk-write/run-accommodation-amenity-delete-observations.sh)
- 원시 표본 검증·집계: [`aggregate-bulk-write-observations.mjs`](../../load-test/k6/bulk-write/aggregate-bulk-write-observations.mjs)
- 수동 smoke 요청: [`accommodation-amenity-delete-bulk-write-comparison.http`](../../load-test/http/accommodation-amenity-delete-bulk-write-comparison.http)
- 공통 모니터링 설명: [`query-count-monitoring.md`](../query-count-monitoring.md)

## 현재 이력서 판단

지금 단계에서 외부 제출용 문장을 만들지 않는다. 확인된 사실은 “운영 full replacement 안의 Spring Data 파생 삭제가 현재 카탈로그 상한 30건에서 SELECT 1회와 DELETE 30회를 실행한다”까지다.

U5와 AWS 재측정 후에는 다음 기준으로 결정한다.

- 실제 cardinality에서도 절대 지연 감소가 명확하다면 보조 성능 개선 사례로 사용한다.
- SQL만 `N → 1`로 줄고 절대 효과가 작다면 코드·SQL 단순화 사례로 설명한다.
- 실제 데이터가 대부분 소수 행이라면 Wishlist DELETE나 ReservationHistory INSERT보다 이력서 우선순위를 낮춘다.

## 한계

- 현재 결과는 Before 한 구현의 한 번의 로컬 실행 순서이므로 After 효과와 실행 순서 편향을 알 수 없다.
- `REALISTIC`은 카탈로그 상한 기준이며 실제 운영 데이터 분포가 아니다.
- `DELETE_ONLY`는 진단 경계이고 `FULL_REPLACEMENT`는 여러 조회·쓰기와 commit을 포함한다.
- `N=100`은 현재 정상 저장 경로의 서로 다른 코드 수를 넘는 synthetic stress fixture다.
- 단일 인스턴스·동시성 없는 로컬 측정이라 DB contention과 운영 네트워크를 포함하지 않는다.
- `StatementInspector` SQL 수는 DB round trip 수가 아니며 DB CPU·lock wait·rows examined를 직접 측정하지 않는다.
- predicate bulk DML은 엔티티 callback과 cascade를 우회하고 영속성 컨텍스트를 자동 동기화하지 않으므로 U5에서 별도 검증해야 한다.
