# AccommodationAmenity DELETE U5 로컬 Before/After 비교

## 상태

- U4 Before 기준선 구현 및 로컬 통제 측정: 완료
- U5 predicate bulk DELETE 구현 및 로컬 AB/BA Before/After 측정: 완료
- AWS 격리 환경 Before/After 최종 측정: 예정(TODO)
- 문서 갱신 및 U5 로컬 측정일: 2026-08-02
- U5 측정 애플리케이션 커밋: `26d6f42703110e722885010077ee524e74b0c8ad`
- 역사적 U4 측정일/커밋: 2026-07-22 / `69c20d62a23c083e44ef48a725f1ca369b8b5667`

U5는 기존 Spring Data 파생 삭제를 `accommodation_id` predicate bulk DELETE로 교체했다. 같은 측정 경계와 같은 `N` 안에서만 Before/After를 비교한 로컬 결과, 카탈로그 상한 `N=30`의 `FULL_REPLACEMENT` pooled p50은 32.127583ms에서 20.752125ms로 11.375458ms(35.41%) 감소했다. 이 결과는 SQL 단순화와 불필요한 엔티티 materialization 제거를 보여 주는 **보조 성능 개선 사례**로는 유효하다. 다만 로컬 `N=30`은 실제 트래픽 분포가 아니라 카탈로그 상한이므로 운영 병목이나 대표 사용자 지연으로 주장하지 않는다.

## 구현과 가설

숙소 수정 요청에 편의시설 목록이 있으면 [`AccommodationService.updateAmenities`](../../src/main/java/kr/kro/airbob/domain/accommodation/service/AccommodationService.java)가 기존 편의시설 전체를 지우고 새 목록을 저장한다. U5 이전 [`AccommodationAmenityRepository.deleteByAccommodationId`](../../src/main/java/kr/kro/airbob/domain/accommodation/repository/AccommodationAmenityRepository.java)는 대상 엔티티를 먼저 조회한 뒤 행마다 DELETE를 실행했다. U5 이후 운영 경로는 같은 repository의 `deleteByAccommodationIdInBulk`가 실행하는 다음 형태의 JPQL bulk DELETE를 사용한다.

```sql
DELETE FROM AccommodationAmenity amenity
WHERE amenity.accommodation.id = :accommodationId
```

따라서 기존 편의시설이 `N`개일 때 삭제 단계는 `SELECT 1 + DELETE N`에서 `DELETE 1`로 바뀐다. [`AccommodationAmenityDeleteBeforeBenchmarkService`](../../src/main/java/kr/kro/airbob/domain/accommodation/service/AccommodationAmenityDeleteBeforeBenchmarkService.java)는 U5 이전 derived DELETE와 full replacement 동작을 benchmark profile 안에 동결하고, [`AccommodationAmenityDeleteAfterBenchmarkService`](../../src/main/java/kr/kro/airbob/domain/accommodation/service/AccommodationAmenityDeleteAfterBenchmarkService.java)는 After의 delete-only 경계를 제공한다. `FULL_REPLACEMENT` After는 별도 복제 코드가 아니라 실제 운영 `AccommodationService`의 Spring 트랜잭션 프록시를 호출한다.

Predicate bulk DML은 엔티티 callback과 cascade를 우회하고 영속성 컨텍스트를 자동 동기화하지 않는다. 이 변경은 해당 삭제에 JPA 생명주기 부수효과가 없고, 이후 같은 트랜잭션에서 계속 사용하는 managed 부모 숙소와 새 편의시설·이력 상태가 정확하다는 통합 테스트를 전제로 한다.

## 두 측정 경계와 비교 규칙

[`AccommodationAmenityDeleteBenchmarkService`](../../src/main/java/kr/kro/airbob/domain/accommodation/service/AccommodationAmenityDeleteBenchmarkService.java)는 같은 fixture로 서로 다른 두 경계를 측정한다.

### `FULL_REPLACEMENT`

- Before는 동결한 `AccommodationAmenityDeleteBeforeBenchmarkService.fullReplacement()`를 실제 트랜잭션 프록시로 호출한다.
- After는 실제 운영 `AccommodationService.updateAccommodation()`을 실제 트랜잭션 프록시로 호출한다.
- 두 variant 모두 숙소 조회와 소유자 확인, 기존 편의시설 삭제, 중복 코드를 합산한 새 편의시설 저장, 기존 SCD2 history 종료 UPDATE, 새 history INSERT, flush와 commit을 포함한다.
- Replacement 요청은 편의시설 외 숙소 필드를 모두 `null`로 두므로 부모 숙소 UPDATE는 발생하지 않는다. 관찰된 UPDATE 1회는 기존 현재 history 종료다.
- DRAFT fixture를 사용하므로 게시 숙소의 outbox/Elasticsearch 이벤트 발행은 포함하지 않는다.

따라서 이 경계는 운영 full replacement 경로의 Before/After 비교값이지만 순수 DELETE latency는 아니다.

### `DELETE_ONLY`

- Before는 benchmark 전용 동결 서비스가 derived DELETE만 호출한다.
- After는 benchmark 전용 After 서비스가 predicate bulk DELETE만 호출한다.

이 경계는 삭제 SQL 증가를 분리해 보는 진단값이며 사용자 API의 end-to-end 지연이 아니다.

`FULL_REPLACEMENT`와 `DELETE_ONLY`는 포함 작업과 트랜잭션 경계가 다르므로 서로 빼거나 나누거나 합쳐서 DELETE 비용·배속·개선율을 계산하지 않는다. 개선율은 반드시 **같은 measurement, 같은 `N`, 같은 workload class의 Before와 After** 사이에서만 계산한다.

## workload 분류

- `REALISTIC`: `N <= 측정 시 활성 AMENITY_TYPE 코드 수`
- `STRESS`: `N > 측정 시 활성 AMENITY_TYPE 코드 수`

여기서 `REALISTIC`은 실제 운영 분포가 아니라 **현재 카탈로그로 표현 가능한 범위**라는 뜻이다. U5 측정 시 활성 코드는 30개였고 `N=30`은 그 범위의 상한이다. 대표 사용자 숙소의 실제 편의시설 수는 확인하지 않았다. `N=100`은 삭제문의 선형 증가와 상수 SQL 전환을 관찰하기 위한 synthetic stress 값이다.

## U5 Before/After SQL 계약

`N`은 삭제할 기존 행 수, `R`은 중복 코드를 합산한 replacement의 서로 다른 활성 코드 수다.

| 측정 | variant | SELECT | INSERT | UPDATE | DELETE | TOTAL |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `FULL_REPLACEMENT` | `BEFORE` | 3 | `R + 1` | 1 | `N` | `N + R + 5` |
| `FULL_REPLACEMENT` | `AFTER` | 2 | `R + 1` | 1 | 1 | `R + 5` |
| `DELETE_ONLY` | `BEFORE` | 1 | 0 | 0 | `N` | `N + 1` |
| `DELETE_ONLY` | `AFTER` | 0 | 0 | 0 | 1 | 1 |

`FULL_REPLACEMENT`의 INSERT `R + 1`은 편의시설 `R`개와 새 history 1개이고, UPDATE 1회는 기존 현재 history 종료다. 활성 코드가 30개이므로 이번 `N=30`, `N=100` 모두 `R=30`이다. 실제 관찰 계약은 다음과 같다.

| 측정 | N | Before S/I/U/D/T | After S/I/U/D/T |
| --- | ---: | --- | --- |
| `FULL_REPLACEMENT` | 30 | 3/31/1/30/65 | 2/31/1/1/35 |
| `FULL_REPLACEMENT` | 100 | 3/31/1/100/135 | 2/31/1/1/35 |
| `DELETE_ONLY` | 30 | 1/0/0/30/31 | 0/0/0/1/1 |
| `DELETE_ONLY` | 100 | 1/0/0/100/101 | 0/0/0/1/1 |

검증 응답의 `verified_rows=N`은 기존 target 행 `N`개가 제거됐다는 뜻이다. `FULL_REPLACEMENT N=100`의 새 편의시설은 100개가 아니라 활성 코드로 합산된 30개다. SQL 수는 Hibernate `StatementInspector`에서 관찰한 문장 수이며 DB 네트워크 왕복 횟수나 DB 내부 작업량으로 단정하지 않는다. Custom JDBC writer를 사용하지 않으므로 명시적 JDBC batch 호출·제출 행은 0이고 batch size·영향 행 계측값은 `null`이다.

## 정확성과 보호 경계

[`AccommodationAmenityDeleteBenchmarkIntegrationTest`](../../src/test/java/kr/kro/airbob/domain/accommodation/AccommodationAmenityDeleteBenchmarkIntegrationTest.java)는 Before와 After에 대해 다음을 검증한다.

- Predicate bulk DELETE가 target 편의시설만 한 statement로 삭제하고 영향 행 수를 반환한다.
- `FULL_REPLACEMENT`와 `DELETE_ONLY`의 Before/After가 `N=0`, 카탈로그 경계, 경계 초과 stress에서 위 SQL 공식을 지킨다.
- `amenityInfos=null`은 기존 편의시설을 유지하고, empty는 전부 삭제하며, populated는 대소문자·중복·무효 코드·0개 입력을 기존 의미대로 처리한다.
- Predicate DELETE 뒤에도 managed 부모 숙소, address, occupancy policy와 전체 history snapshot 변경을 함께 commit한다.
- 기존 target ID 제거, replacement map, target/control 숙소의 전체 영속 필드, control 편의시설, SCD2 history 효과가 일치한다.
- 대상이 없을 때 Before delete-only는 `SELECT 1`, After delete-only는 영향 행 0인 `DELETE 1`로 정상 종료한다.
- Replacement INSERT flush 뒤 주입한 실패가 After의 predicate DELETE와 INSERT를 함께 rollback한다. 동결한 Before도 derived DELETE와 INSERT를 함께 rollback하며 부모·history·control을 보존한다.
- 잘못된 요청은 DB 접근 전에 거부하고, operation 실패 뒤 cleanup을 시도하며, operation·cleanup이 함께 실패하면 원래 operation 예외를 보존한다.

Benchmark endpoint는 일반 세션 인증에 더해 ADMIN 권한, 별도 `X-Bulk-Write-Benchmark-Token`, `bulk-write-benchmark` profile, 명시적 enable 속성, `_bulk_write_benchmark` suffix의 전용 schema와 필수 테이블을 요구한다. Fixture 생성·검증·정리는 측정 구간 밖에서 각각 별도 트랜잭션으로 수행하고, 요청이 만든 target/control 데이터만 정리한다.

## 모니터링 경계

[`BulkOperationMonitor`](../../src/main/java/kr/kro/airbob/common/monitoring/bulkwrite/BulkOperationMonitor.java)는 `System.nanoTime()`으로 transactional service 프록시 호출 전체를 측정하고 같은 스레드의 Hibernate SQL을 유형별로 기록한다. 현재 숙소 수정과 진단 서비스는 동기식 단일 스레드라 `ThreadLocal` 컨텍스트와 맞는다. 비동기 또는 멀티스레드로 바뀌면 컨텍스트 전달을 다시 설계해야 한다.

다음은 `server_operation_ms`에서 제외된다.

- HTTP 로그인, controller 처리와 응답 직렬화
- 전용 DB guard 확인
- fixture 생성
- 결과 및 control fixture 검증
- fixture 정리
- k6 클라이언트와 네트워크 왕복 시간

## U5 로컬 통제 측정

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
- app commit: `26d6f42703110e722885010077ee524e74b0c8ad`

`A=BEFORE`, `B=AFTER`로 두고, 각 measurement/`N` 셀에서 R1은 `A→B`, R2는 `B→A` 순서로 실행했다. 각 라운드·variant는 워밍업 3회 뒤 `SAMPLES=1`인 독립 child artifact 10개를 수집했다. 2개 라운드 × 2개 measurement × 2개 `N` × 2개 variant × 10개로 총 160개 measure 표본이며, 16개 `u5-local-20260802-26d6f42-v2-*-observations.json` companion에 variant당 10개씩 집계했다.

초기 `v1` 라벨(`u5-local-20260802-26d6f42`) 시도는 첫 셀의 워밍업 3회 직후 측정 명령 인자 오류로 중단됐다. Measure 표본은 0개였고, 해당 워밍업 3회도 U5 통계에서 제외했다. 인자를 바로잡은 `v2`만 아래 결과에 사용했다.

모든 16개 companion과 160개 독립 표본은 별도 검증에서 다음을 만족했다.

- 요청 성공 및 `verification.succeeded=true`
- old target 삭제 수와 최종 replacement/control 상태 일치
- 위 variant별 Hibernate SQL 계약과 일치
- custom JDBC writer 호출·제출 행 0, batch size·영향 행 `null`
- 동일한 app commit, schema, JVM, MySQL, instance count, timeout
- token·세션·이메일·비밀번호·webhook 필드 또는 값 없음

통계는 원시 `server_operation_ms`를 오름차순 정렬한 nearest-rank 방식이다. 표본 수를 `n`, 분위수를 `p`라 할 때 순위는 `max(1, ceil(p × n))`이고 보간하지 않았다. 라운드별 `n=10`의 p95는 10번째 값, 즉 max다.

### 라운드별 결과

단위는 ms다. 각 행은 해당 companion artifact의 nearest-rank 통계를 그대로 기록한다.

| 라운드 | 측정 | N | 분류 | variant | min | p50 | p95 | max | 표본 |
| --- | --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |
| R1 (`A→B`) | `FULL_REPLACEMENT` | 30 | `REALISTIC` | `BEFORE` | 30.784167 | 33.187333 | 42.491833 | 42.491833 | 10 |
| R1 (`A→B`) | `FULL_REPLACEMENT` | 30 | `REALISTIC` | `AFTER` | 20.078459 | 21.996292 | 27.887125 | 27.887125 | 10 |
| R1 (`A→B`) | `FULL_REPLACEMENT` | 100 | `STRESS` | `BEFORE` | 44.673708 | 51.003041 | 82.245084 | 82.245084 | 10 |
| R1 (`A→B`) | `FULL_REPLACEMENT` | 100 | `STRESS` | `AFTER` | 19.634750 | 20.771792 | 27.017334 | 27.017334 | 10 |
| R1 (`A→B`) | `DELETE_ONLY` | 30 | `REALISTIC` | `BEFORE` | 10.411875 | 11.929875 | 63.170667 | 63.170667 | 10 |
| R1 (`A→B`) | `DELETE_ONLY` | 30 | `REALISTIC` | `AFTER` | 2.510333 | 3.296250 | 11.659042 | 11.659042 | 10 |
| R1 (`A→B`) | `DELETE_ONLY` | 100 | `STRESS` | `BEFORE` | 28.489542 | 32.375958 | 41.302875 | 41.302875 | 10 |
| R1 (`A→B`) | `DELETE_ONLY` | 100 | `STRESS` | `AFTER` | 3.273792 | 3.725084 | 4.769625 | 4.769625 | 10 |
| R2 (`B→A`) | `FULL_REPLACEMENT` | 30 | `REALISTIC` | `AFTER` | 16.404500 | 20.075417 | 36.752667 | 36.752667 | 10 |
| R2 (`B→A`) | `FULL_REPLACEMENT` | 30 | `REALISTIC` | `BEFORE` | 25.987584 | 27.426417 | 69.670708 | 69.670708 | 10 |
| R2 (`B→A`) | `FULL_REPLACEMENT` | 100 | `STRESS` | `AFTER` | 18.550209 | 19.405709 | 37.527709 | 37.527709 | 10 |
| R2 (`B→A`) | `FULL_REPLACEMENT` | 100 | `STRESS` | `BEFORE` | 42.366250 | 46.562667 | 80.907500 | 80.907500 | 10 |
| R2 (`B→A`) | `DELETE_ONLY` | 30 | `REALISTIC` | `AFTER` | 2.248041 | 2.885375 | 3.657292 | 3.657292 | 10 |
| R2 (`B→A`) | `DELETE_ONLY` | 30 | `REALISTIC` | `BEFORE` | 11.452500 | 12.925208 | 19.443125 | 19.443125 | 10 |
| R2 (`B→A`) | `DELETE_ONLY` | 100 | `STRESS` | `AFTER` | 3.137583 | 3.524334 | 8.326916 | 8.326916 | 10 |
| R2 (`B→A`) | `DELETE_ONLY` | 100 | `STRESS` | `BEFORE` | 30.028417 | 36.239125 | 60.191209 | 60.191209 | 10 |

### Pooled 결과

두 라운드를 같은 measurement/`N`/variant별로 합쳐 variant당 20개를 다시 정렬하고 nearest-rank 통계를 계산했다. 라운드 통계의 평균이 아니다.

| 측정 | N | 분류 | variant | min | p50 | p95 | max | 표본 |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |
| `FULL_REPLACEMENT` | 30 | `REALISTIC` | `BEFORE` | 25.987584 | 32.127583 | 42.491833 | 69.670708 | 20 |
| `FULL_REPLACEMENT` | 30 | `REALISTIC` | `AFTER` | 16.404500 | 20.752125 | 27.887125 | 36.752667 | 20 |
| `FULL_REPLACEMENT` | 100 | `STRESS` | `BEFORE` | 42.366250 | 48.813416 | 82.091209 | 82.245084 | 20 |
| `FULL_REPLACEMENT` | 100 | `STRESS` | `AFTER` | 18.550209 | 20.189541 | 27.017334 | 37.527709 | 20 |
| `DELETE_ONLY` | 30 | `REALISTIC` | `BEFORE` | 10.411875 | 12.349375 | 19.443125 | 63.170667 | 20 |
| `DELETE_ONLY` | 30 | `REALISTIC` | `AFTER` | 2.248041 | 3.063042 | 3.826167 | 11.659042 | 20 |
| `DELETE_ONLY` | 100 | `STRESS` | `BEFORE` | 28.489542 | 33.987875 | 46.721791 | 60.191209 | 20 |
| `DELETE_ONLY` | 100 | `STRESS` | `AFTER` | 3.137583 | 3.725084 | 4.769625 | 8.326916 | 20 |

같은 measurement/`N` 안에서 계산한 pooled 감소율은 다음과 같다.

| 측정 | N | p50 Before→After | p50 감소 | p95 Before→After | p95 감소 |
| --- | ---: | --- | ---: | --- | ---: |
| `FULL_REPLACEMENT` | 30 | 32.127583 → 20.752125 | 35.41% | 42.491833 → 27.887125 | 34.37% |
| `FULL_REPLACEMENT` | 100 | 48.813416 → 20.189541 | 58.64% | 82.091209 → 27.017334 | 67.09% |
| `DELETE_ONLY` | 30 | 12.349375 → 3.063042 | 75.20% | 19.443125 → 3.826167 | 80.32% |
| `DELETE_ONLY` | 100 | 33.987875 → 3.725084 | 89.04% | 46.721791 → 4.769625 | 89.79% |

현실 상한 `N=30`의 운영 full path에서 절대 p50 감소는 11.375458ms(소수 셋째 자리 표기 시 11.375ms)다. 같은 셀에서 전체 SQL은 65→35, DELETE는 30→1로 줄었다. `DELETE_ONLY`의 더 큰 비율 감소는 삭제 문장 자체를 분리한 진단 결과일 뿐 `FULL_REPLACEMENT` 개선율과 합치거나 운영 요청 개선율로 바꾸지 않는다. `N=100` 결과는 상수 SQL 구조가 stress에서 유지됨을 보여 주지만 정상 카탈로그 범위를 넘으므로 이력서 대표 수치로 사용하지 않는다.

### U5 원시 160개 표본

빌드 산출물 `build/k6/**`는 Git에서 제외되므로 16개 companion의 입력 순서대로 원시 `server_operation_ms` 배열을 문서에 보존한다. 각 배열은 10개이며 총 160개다.

```text
R1 FULL_REPLACEMENT N=30 REALISTIC BEFORE [36.043875, 42.491833, 36.380125, 34.507167, 32.932833, 33.549792, 33.187333, 31.595791, 31.470542, 30.784167]
R1 FULL_REPLACEMENT N=30 REALISTIC AFTER [21.780959, 22.854, 21.996292, 23.599667, 20.659625, 20.078459, 25.675375, 26.395833, 20.752125, 27.887125]
R1 FULL_REPLACEMENT N=100 STRESS BEFORE [44.673708, 82.091209, 82.245084, 51.003041, 63.714333, 53.864375, 45.894583, 48.813416, 45.810167, 60.194875]
R1 FULL_REPLACEMENT N=100 STRESS AFTER [21.297334, 20.189541, 22.939542, 20.676917, 19.63475, 27.017334, 22.404417, 20.386, 21.335875, 20.771792]
R1 DELETE_ONLY N=30 REALISTIC BEFORE [63.170667, 13.704, 11.163, 11.418042, 11.550334, 10.411875, 12.30675, 13.195792, 12.349375, 11.929875]
R1 DELETE_ONLY N=30 REALISTIC AFTER [3.29625, 2.8175, 11.659042, 2.881083, 3.469916, 3.324333, 2.510333, 3.063042, 3.826167, 3.733042]
R1 DELETE_ONLY N=100 STRESS BEFORE [30.574958, 35.839916, 41.302875, 35.806, 33.135583, 29.337667, 33.987875, 32.375958, 31.21725, 28.489542]
R1 DELETE_ONLY N=100 STRESS AFTER [4.105208, 3.273792, 3.492583, 3.566208, 3.727458, 3.725084, 3.417833, 4.769625, 3.956583, 4.3145]
R2 FULL_REPLACEMENT N=30 REALISTIC AFTER [23.062625, 19.4955, 20.075417, 23.126209, 36.752667, 18.282292, 18.006208, 16.4045, 20.611958, 20.160125]
R2 FULL_REPLACEMENT N=30 REALISTIC BEFORE [30.233458, 27.426417, 69.670708, 26.592875, 26.795792, 33.495291, 26.803875, 32.779, 25.987584, 32.127583]
R2 FULL_REPLACEMENT N=100 STRESS AFTER [19.992875, 19.405709, 18.976541, 37.527709, 19.679333, 19.959166, 18.764083, 18.550209, 19.293084, 20.976625]
R2 FULL_REPLACEMENT N=100 STRESS BEFORE [46.562667, 47.079667, 46.544, 80.9075, 43.170667, 48.968917, 43.003917, 51.049792, 42.36625, 55.306875]
R2 DELETE_ONLY N=30 REALISTIC AFTER [2.852583, 2.885375, 3.093041, 2.248041, 3.287541, 3.657292, 2.430125, 3.024666, 3.5315, 2.503334]
R2 DELETE_ONLY N=30 REALISTIC BEFORE [13.610125, 13.058708, 11.4525, 14.193833, 12.925208, 11.96025, 12.920917, 13.323834, 11.887708, 19.443125]
R2 DELETE_ONLY N=100 STRESS AFTER [3.954583, 4.132, 4.298042, 3.524334, 3.4305, 4.215792, 8.326916, 3.137583, 3.206667, 3.185833]
R2 DELETE_ONLY N=100 STRESS BEFORE [39.658333, 34.789875, 40.008291, 30.028417, 60.191209, 30.987625, 30.73525, 36.239125, 37.770583, 46.721791]
```

## 역사적 U4 Before 기준선과 U5 설계 기록

U4는 U5 구현 전 후보의 SQL 구조와 측정 가능성을 확인한 완료된 역사적 기준선이다. U5 pooled 통계에는 U4 표본을 섞지 않았다.

### U4 환경과 절차

- 측정일: 2026-07-22
- app commit: `69c20d62a23c083e44ef48a725f1ca369b8b5667`
- 애플리케이션 인스턴스: 1
- profile: `dev,bulk-write-benchmark`
- JVM: OpenJDK 21.0.6
- MySQL: 8.0.33
- 전용 schema: `airbob_bulk_write_benchmark`
- `rewriteBatchedStatements`: false
- 요청 제한 시간: 120초
- 활성 `AMENITY_TYPE` 코드: 30개
- SQL·bind 로그: 비활성화

`FULL_REPLACEMENT`와 `DELETE_ONLY`의 `N=30`, `N=100` 네 그룹을 각각 3회 워밍업했다. 이후 각 그룹에서 `SAMPLES=1`인 독립 child artifact를 20개씩 수집해 총 80개 measure 표본을 만들었다. 실행 순서는 `FULL N30 → DELETE_ONLY N100 → DELETE_ONLY N30 → FULL N100`이었고 최초 smoke와 워밍업은 통계에서 제외했다. 모든 표본은 성공·상태 검증·Before SQL 계약·메타데이터·secret 부재 검증을 통과했다.

U4에서 세운 U5 설계는 Before 경로를 benchmark profile에 동결하고, 실제 운영 경로에 predicate bulk DELETE를 적용한 뒤, 같은 fixture·measurement·`N`에서 AB/BA 순서의 독립 표본으로 비교한다는 것이었다. 이 설계의 로컬 단계는 위 U5 `v2` 측정으로 완료했고 AWS 최종 단계만 남았다.

### U4 결과

단위는 ms다. 아래는 Before 하나의 역사적 통계이며 U5 개선율 계산에 사용하지 않는다.

| 측정 | N | 분류 | SQL (S/I/U/D/T) | min | p50 | p95 | max | 표본 |
| --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |
| `FULL_REPLACEMENT` | 30 | `REALISTIC` | 3/31/1/30/65 | 24.321 | 28.785 | 43.976 | 48.813 | 20 |
| `DELETE_ONLY` | 30 | `REALISTIC` | 1/0/0/30/31 | 10.250 | 11.584 | 17.422 | 22.296 | 20 |
| `FULL_REPLACEMENT` | 100 | `STRESS` | 3/31/1/100/135 | 38.168 | 42.635 | 44.206 | 47.769 | 20 |
| `DELETE_ONLY` | 100 | `STRESS` | 1/0/0/100/101 | 26.061 | 30.085 | 36.776 | 40.949 | 20 |

### U4 원시 80개 표본

```text
FULL_REPLACEMENT N=30 REALISTIC [35.289375, 33.312709, 30.194667, 28.785084, 31.514083, 32.121, 35.448625, 31.952375, 28.349833, 26.754375, 26.385375, 28.395083, 26.987083, 27.364583, 25.342958, 43.975917, 48.813375, 32.511084, 26.207834, 24.320708]
DELETE_ONLY N=30 REALISTIC [14.542417, 10.812459, 11.56575, 10.549292, 12.091458, 10.983916, 17.421875, 14.440583, 11.583625, 11.182209, 11.06575, 11.712, 15.635417, 22.29625, 12.538542, 11.499042, 11.64225, 10.993333, 10.250375, 11.83575]
FULL_REPLACEMENT N=100 STRESS [44.206375, 41.02525, 41.294792, 38.1675, 43.877208, 41.131791, 42.634833, 39.54625, 42.682125, 43.541292, 43.765041, 41.301208, 42.097583, 39.967167, 43.819833, 42.946833, 39.224959, 47.769, 44.094458, 43.913708]
DELETE_ONLY N=100 STRESS [26.061333, 31.927, 30.117166, 29.422125, 28.909042, 30.957708, 33.991208, 30.085125, 33.742458, 34.1475, 29.667416, 26.345791, 29.96875, 34.448583, 26.128167, 29.314583, 36.776208, 29.412416, 40.94875, 34.447333]
```

## AWS 최종 측정 계획(TODO)

1. AWS 격리 환경에서 실제 숙소별 편의시설 cardinality의 p50·p95·max를 먼저 확인하고 대표 `N`을 정한다.
2. 같은 app commit과 전용 schema, 동일 인스턴스 수·JVM·MySQL·timeout·로깅 조건을 고정한다.
3. 실제 범위의 대표값과 상한 `N=30`을 라운드당 최소 30개, 가능하면 50개의 독립 표본으로 `A→B`, `B→A` 교차 측정한다.
4. `N=100` stress는 필요할 때만 구조 재확인용으로 실행하고 실제 트래픽 결과와 분리한다.
5. 모든 표본의 성공·상태 검증·SQL 계약·메타데이터가 일치할 때만 라운드별 및 pooled nearest-rank 통계를 계산한다.

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

현재 카탈로그 상한 `N=30`에서 운영 full path의 pooled p50은 32.127583ms에서 20.752125ms로 11.375458ms 감소했고, 전체 SQL은 65→35, DELETE는 30→1로 줄었다. 따라서 “숙소 편의시설 교체 시 파생 삭제의 엔티티 조회·행별 DELETE를 predicate bulk DELETE로 바꾸고, 카탈로그 상한의 로컬 통제 측정에서 SQL 65→35와 p50 35.41% 감소를 검증했다”는 **보조 성능 개선 사례**로는 사용할 수 있다.

다만 `N=30`은 실제 트래픽 대표값이 아니라 카탈로그 상한이고 로컬 결과이므로 “운영 병목을 제거했다”, “실제 사용자 지연을 35% 개선했다” 또는 stress `N=100` 수치를 대표 성과처럼 표현하지 않는다. AWS에서 실제 cardinality와 같은 조건의 결과가 확인되기 전에는 SQL 구조 개선과 통제 환경 수치로 한정한다.

## 한계

- U5는 라운드당 variant 10개, pooled 20개의 작은 표본이며 AB/BA 한 쌍뿐이다. 일부 max가 p95보다 크게 튀어 추가 라운드에서 분포 안정성을 확인해야 한다.
- 초기 `v1` 시도는 워밍업 3회 뒤 인자 오류로 중단됐고 measure 0개라 전부 제외했다. 아래 결과는 수정한 `v2` 실행만 사용한다.
- `REALISTIC N=30`은 카탈로그 상한 기준이며 실제 운영 데이터 분포가 아니다.
- `N=100`은 현재 정상 저장 경로의 서로 다른 코드 수를 넘는 synthetic stress fixture다.
- `DELETE_ONLY`는 진단 경계이고 `FULL_REPLACEMENT`는 여러 조회·쓰기와 commit을 포함하므로 두 경계의 수치를 직접 비교하지 않는다.
- 단일 인스턴스·동시성 없는 로컬 측정이라 DB contention과 운영 네트워크를 포함하지 않는다.
- `StatementInspector` SQL 수는 DB round trip 수가 아니며 DB CPU·lock wait·rows examined를 직접 측정하지 않는다.
- Predicate bulk DML의 callback·cascade·영속성 컨텍스트 위험은 현재 통합 테스트로 보호하지만 entity 연관관계나 트랜잭션 흐름이 바뀌면 다시 검증해야 한다.
- U4와 U5는 커밋·날짜·실행 설계가 다른 독립 측정이므로 표본을 합치거나 서로 개선율을 계산하지 않는다.
- AWS 격리 환경 최종 측정과 실제 cardinality 확인은 아직 TODO다.
