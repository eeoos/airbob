# 쿠폰 조건부 UPDATE / SKIP LOCKED 실험

> 발급 비교 실험은 종료됐다. V31 앱은 대리 키 RR만 제공하며 아래 발급 비교 API와 fixture API는 제거됐다.
> 이 문서의 발급 비교 명령은 측정 당시 V30 소스 `43edf992227af2ae628ac0d83815c56722cf34b8`와
> 별도 V30 실험 DB에서 재현한다. 현재 앱이나 V31 DB에 과거 실행기를 연결하지 않는다.
> [최종 AWS 결과](../../../docs/performance/results/coupon-aws-2026-10-11/README.md)와
> [현재 발급 구조 및 V31 전환](../../../docs/coupon-issuance-operations.md)을 참고한다.
> 별도의 쿠폰 조회 비교 API는 유지하며, 발급 전략별 분기는 제거했다.


같은 앱에서 재고 카운터와 unit 소비를 비교한다. 모든 비교군은 MySQL 커밋 후 201을 반환한다.
설계와 단계별 완료 기준은 [계획서](../../../docs/performance/coupon-skip-locked-benchmark-plan.md),
배포 입력은 [AWS 가이드](AWS.md), 직접 실행은 [수동 가이드](MANUAL.md)를 참고한다.
[2026-10-09 로컬 반복 측정 결과](../../../docs/performance/coupon-skip-locked-local-results.md)에 무효 회차까지 보존했다.

## 비교군과 고정 조건

| variant | 재고 원장 | 발급 트랜잭션 |
|---|---|---|
| `db` (A) | `coupon.issued_quantity` 조건부 UPDATE | RR |
| `skip_surrogate_rr` (B) | 대리 키 unit DELETE | RR |
| `skip_surrogate_rc` (D) | 대리 키 unit DELETE | RC |
| `skip_composite_rr` (C) | 복합 키 unit DELETE | RR |
| `skip_composite_rc` (E) | 복합 키 unit DELETE | RC |

이번 계획과 AWS 설정 예제는 A·B·D·C·E 모두를 비교한다. B·C 사전 비교 자료도 보존한다.
로컬 실행기의 variant 생략 기본값은 A·B·D이므로 다섯 방식은 아래처럼 명시한다.
Lua 경로는 제거했다. Redis는 실제 세션 인증에 사용하며 재고를 저장하지 않는다.
비교군별 고정 API를 사용하며 토큰·세션 검증은 동일하다. 아래 경로는 모두 POST다.

| variant | 경로 |
|---|---|
| `db` | `/api/v2/coupons/{couponId}/issue/db` |
| `skip_surrogate_rr` | `/api/v2/coupons/{couponId}/issue/skip-surrogate-rr` |
| `skip_surrogate_rc` | `/api/v2/coupons/{couponId}/issue/skip-surrogate-rc` |
| `skip_composite_rr` | `/api/v2/coupons/{couponId}/issue/skip-composite-rr` |
| `skip_composite_rc` | `/api/v2/coupons/{couponId}/issue/skip-composite-rc` |

기존 `.../issue?variant=...` API는 제거했다. 발급 전 별도 전략 조회는 없으며, 이미 읽는 쿠폰의
불변 `issue_strategy`를 업무 트랜잭션 안에서 검증한다. A는 조건부 UPDATE를 첫 SQL로 유지하고
전략 불일치 시 카운터 변경까지 롤백한다. SKIP은 unit 확보 전에 잠금 조회한 쿠폰으로 검증한다.
SQL 계측과 다른 캠페인 준비 시점 관측도 각 고정 경로를 사용한다.

운영 V1 API는 최종 전략 선정 전까지 저장된 전략으로 라우팅한다. 이번 비교용 API 변경으로
이 라우팅 비용까지 측정한 것으로 해석하지 않는다. 과거 자료의 선행 조회 포함 SQL·성능 수치는
그대로 보존하며, 변경한 코드의 성능은 새 바이너리로 별도 측정해야 한다.

unit 잠금 조회·소비·잔여 재고 조회·감량은 키별 Spring Data JPA 저장소의 네이티브 쿼리로 수행한다.
`CouponIssueUnitStore`는 비교군의 저장소 선택과 기존 준비 작업을 연결한다. 준비 시 500행씩 넣는
JdbcTemplate 다중 INSERT와 OSIV의 오래된 쿠폰을 비우는 `EntityManager.clear()`는 기존 동작을 유지한다.
DB 시각의 별도 조회와 실패한 시도를 끝낸 뒤 새 트랜잭션으로 재확인하는 순서도 유지한다.

새 쿠폰은 양의 유한 수량만 지원한다. SKIP LOCKED는 공용 카운터를 갱신하지 않는다.
빈 unit 조회 뒤 새 스냅샷에서 중복·유효성·잔여 재고를 확인한다. 재고가 남은 경합은 제한 재시도 후
`503/CP012`, 실제 품절은 `409/CP002`, 중복은 `409/CP003`로 구분한다.
실패한 발급 트랜잭션 전체가 롤백되므로 별도 재고 재삽입·보상은 없다.

## 두 실험과 판정

| 실험 | 조건 | 주요 결과 |
|---|---|---|
| capacity | 고유 회원, 재고 = RPS × 시간 + 1 | 성공 p95/p99, 성공 처리량, 반복 통과한 설정 RPS |
| scarcity | 고유 회원, 같은 한정 재고 | 성공과 품절 각각의 지연, SQL 수, DB CPU, 경합 응답 |

`constant-arrival-rate`로 보내며 요청은 RPS × 시간 건으로 제한한다. 회원 세션은 경계 여유분 1개를 더 준비한다.
다른 쿠폰 간에는 회원 풀을 재사용하지만 같은 쿠폰 안에서는 재사용하지 않는다.
각 측정은 새 쿠폰과 별도 워밍업을 쓴다. 세 비교군의 여섯 순서는 ABD, BDA, DAB, ADB, DBA, BAD이다.
두 비교군은 AB/BA, 다섯 비교군의 5라운드는 ABDCE, BDCEA, DCEAB, CEABD, EABDC로 각 위치를 한 번씩 차지한다.
추가 라운드는 역방향 순서를 사용한다.

- 모든 요청 결과를 성공·품절·중복·경합·인증·설정·예상 밖 오류로 분리한다.
- 회원 쿠폰 수 = 고유 회원 수 = 성공 응답 수를 검증한다.
- A는 카운터 = 회원 쿠폰 수, SKIP은 남은 unit + 회원 쿠폰 수 = 총수량이며 카운터를 집계에 쓰지 않는다.
- capacity는 전 요청 성공, p95 ≤ 500ms, p99 ≤ 1초, 성공 처리량 ≥ 목표 99%가 기본 기준이다.
- scarcity는 준비 재고 소진과 품절 응답을 확인한다. 경합 응답은 품절로 합치지 않으며 SLO 실패로 기록한다.
- 요청 누락·인증/정합성/관측 오류와 부하 발생기 포화는 무효 결과다. 실패 원본도 보존한다.
- 유효 반복 전체가 통과한 최상위 설정값만 확인된 하한이다. 최대 처리량이라고 단정하지 않는다.

성공 RPS의 분모는 첫 요청 시작부터 마지막 응답 완료까지다. 빠른 품절 응답으로 성공 지연을 희석하지 않는다.
SQL 수는 해당 HTTP 경로의 Hibernate 문장 수이며 native unit SQL도 포함한다.
커밋·격리 수준 설정·로그인·준비·사후 검증은 포함하지 않는다. SQL 개수를 CPU 비용으로 환산하지 않는다.
RDS CPU는 HTTP 구간에 완전히 포함되는 1분 버킷만 사용한다.

## Grafana 관측

`Airbob - Coupon Issuance Comparison` (`uid=airbob-coupon-issuance`)은
[대시보드 JSON](../../../monitoring/grafana/dashboards/airbob-coupon-issuance.json)으로 관리한다.
Strategy에서 조건부 UPDATE/RR와 SKIP LOCKED의 대리 키·복합 키/RR·RC를 선택한다.
비교할 환경·인스턴스·시간 범위를 고정하고, 같은 전략의 캠페인은 한 번에 하나씩 측정한다.
캠페인별 라벨은 없으므로 같은 전략의 동시 발급은 합산된다.

| 패널 | 해석 |
|---|---|
| Successful issuance RPS / All-outcome issuance service RPS | 최종 성공과 모든 최종 결과의 처리량을 분리한다. 인증 단계에서 거절된 HTTP 요청은 포함하지 않는다. |
| Successful issuance latency | 재시도·대기·새 스냅샷 재확인을 포함한 서비스 내부 지연이다. V1의 선행 전략 조회와 HTTP·인증·네트워크 시간은 제외되므로 클라이언트 지연은 k6로 확인한다. |
| Final issuance outcomes | `contended`(503/CP012), `sold_out`(409/CP002), `duplicate`(409/CP003)를 별개로 표시한다. |
| DB attempt latency / outcomes | 커넥션 획득과 커밋/롤백까지 포함한 발급 시도 단위다. 별도의 새 스냅샷 판정 트랜잭션은 제외된다. |
| Retryable conditions | `empty_unit`, `deadlock`, `lock_timeout`, `policy_lock_busy` 발생을 표시한다. 추가 시도 횟수나 최종 경합 응답 수와 같지 않다. |
| HikariCP / process CPU | 환경·인스턴스 공용 지표다. Strategy 선택으로 필터링하지 않으며 풀 패널에만 Pool 선택을 적용한다. 획득 시간은 평균이고, CPU는 DB가 아닌 JVM 프로세스 기준이다. |

기본 Prometheus 수집 주기는 15초다. 짧은 burst와 정확한 요청 수·성공 지연은 k6 원본으로 확인한다.
Grafana의 `rate`, `increase`, 히스토그램 백분위수는 구간 추정치이며, 표본 없음은 0으로 채우지 않는다.
지연 백분위수를 서로 빼서 단계별 시간을 구하지 않는다. 재고 정합성은 실험 후 DB 검증을 기준으로 한다.
운영 Grafana는 배포된 main의 파일을 사용하므로, PR의 대시보드는 해당 파일이 배포된 뒤 반영된다.

## 실험용 API

쿠폰 전용 프로필과 활성 설정, `X-Benchmark-Token`, ADMIN 세션으로 fixture를 관리한다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| POST | `/api/v1/admin/coupons/benchmark/fixtures` | 전략 고정 쿠폰 생성, SKIP unit 준비 후 발급 시작 예약 |
| GET | `…/fixtures/{id}?run_id={runId}` | 회원 수·고유 회원 수·A 카운터 또는 잔여 unit |
| DELETE | `…/fixtures/{id}?run_id={runId}` | 실행 소유 확인 후 모든 전략 비활성화, 발급 이력 보존 |

실행기가 준비·발급 시작 대기·검증·종료를 수행한다. 직접 준비 API를 다시 호출하지 않는다.
생성 POST의 응답을 잃으면 자동 재시도하지 않는다. 보고서의 label과
`coupon-benchmark:{runId}:{variant}` 설명으로 조회해 기존 생성 결과부터 확인한다.
중단 뒤 `created`/`cleanup-required` fixture는 같은 runId로 종료한다. Redis 전체 초기화는 하지 않는다.

## 로컬 검증과 반복 측정

Docker, Java 21, Node, k6가 필요하다. 관측 모드는 macOS와 `PyMySQL[rsa]==1.1.2`가 필요하다.
`rsa` 추가 의존성은 일회성 MySQL의 `caching_sha2_password` 계측 계정 인증에 사용한다.
새 MySQL 8.4.11·Redis 7.2 컨테이너와 실제 앱을 실행하고 V30까지 마이그레이션·회원 생성·로그인·HTTP 발급을 수행한다.
기존 개발 DB·Redis는 사용하지 않으며 종료 시 자신이 생성한 자원만 정리한다.

```bash
./gradlew bootJar -x test -x generateAdoc -x asciidoctor -x copyDocument
python3 load-test/k6/coupon/run_local_e2e.py --rate 500 --duration 20 --rounds 5 --observe \
  --variants db skip_surrogate_rr skip_surrogate_rc skip_composite_rr skip_composite_rc \
  --client-vus 500 --cooldown 3
```

Java의 시간대 데이터가 오래됐다면 최신 Java 21 경로를 `--java-home`으로 지정한다.
`--observe`는 두 시나리오에 DB·풀·호스트 관측을 붙이며 최대 2,000 RPS를 지원한다.
관측 모드는 각 측정 전 별도 쿠폰으로 최대 100 RPS·10초 워밍업을 수행한다. 호스트 표본을 확보하려면 20초 이상을 권장한다.
원본은 `build/k6/coupon-local/{runId}/`의 `local-results.json`, `run.json`, 실행별 JSON과 보고서에 남는다.
JAR 해시, 실제 MySQL 설정, 다른 컨테이너, 실제 로컬 회원 manifest를 기록한다.
앱·부하 발생기·Docker가 한 호스트를 공유하는 결과이므로 AWS 성능으로 사용하지 않는다.

```bash
# 동일 RR에서 키 구조만 비교
python3 load-test/k6/coupon/run_local_e2e.py --diagnose-rate 200 --duration 30 --rounds 4 \
  --variants skip_surrogate_rr skip_composite_rr --client-vus 300
# 기본 비교군의 capacity 단계 탐색
python3 load-test/k6/coupon/run_local_e2e.py --capacity-rates 50 100 200 400 \
  --duration 30 --rounds 6 --refine-step 25 --client-vus 500 --observe
# 한정 재고, 성공과 품절 응답 분리
python3 load-test/k6/coupon/run_local_e2e.py --scarcity-rates 200 400 --scarcity-stock 500 \
  --duration 30 --rounds 6 --client-vus 500
```

로컬은 최대 고유 세션 60,001개, capacity 최대 2,000 RPS, scarcity 최대 5,000 RPS,
관측 모드 10~180초, 1~6라운드(단계 비교는 2회 이상)를 지원한다.
진단은 MySQL 잠금 대기, SQL digest, 파일 I/O, 컨테이너 CPU, Hikari 획득·대기·타임아웃, JVM GC,
호스트 CPU·메모리, k6 CPU·RSS를 기록한다. 누락 관측을 0으로 취급하지 않는다.

### 풀 10 고정과 시나리오 확장

기존 시나리오 결과는 maximumPoolSize/minimumIdle 10을 고정했다. 로컬 기본값도 10이며,
`--pool-size`로 별도 실험의 값을 지정할 수 있다. 실제 Hikari max/min 값이 설정과 다른 측정을 거부한다.
풀 점유 시간 평균, active/idle 표본과 MySQL 실행 세션 수도 기록한다.
획득·점유 시간 평균을 p95/p99로 해석하지 않는다. 공유 호스트의 처리 경계를 운영 환경의 최대 성능으로 제시하지 않는다.

```bash
# C: 모든 RPS에 같은 초기 재고, 요청 누락을 상한으로 오인하지 않는 탐색
python3 load-test/k6/coupon/run_local_e2e.py --capacity-rates 100 250 500 750 1000 \
  --capacity-stock 20001 --duration 20 --rounds 2 --refine-step 100 --client-vus 1000 \
  --variants db skip_surrogate_rr skip_surrogate_rc skip_composite_rr skip_composite_rc
# B/A/경계 시나리오: 요청량은 먼저 C의 결과를 보고 계획 파일에 명시한다.
python3 load-test/k6/coupon/run_local_e2e.py --workload-plan /absolute/local-workloads.json \
  --rounds 2 --client-vus 1000 --cooldown 3 \
  --variants db skip_surrogate_rr skip_surrogate_rc skip_composite_rr skip_composite_rc
```

계획 파일은 `schemaVersion: 1`과 `cases` 배열을 갖는다. 각 case는 `name`, `scenario`, `stock`,
`segments: [{name, rate, seconds}]`를 지정한다. `scenario`는 capacity/scarcity/duplicate 중 하나다.
capacity 재고는 모든 구간의 예정 요청 합보다 커야 한다. 각 실행의 모든 구간은 같은 쿠폰을 사용한다.
급증은 서로 다른 요청률의 constant-arrival-rate 구간을 이어 붙인다. 늦은 응답은 요청 시작 구간에 남는다.
`segments`별 예정 요청만큼 회원 인덱스를 분리해 구간 전환이 중복 발급으로 변하지 않게 한다.
executor의 `dropped_iterations`는 사용자 정의 `segment` 태그 대신 기본 `scenario` 태그로 분리하고,
구간 합계가 전체 누락과 일치하는지 검사한다. HTTP 수·지연은 요청을 시작한 `segment` 기준이다.
처리율의 분모는 해당 구간의 첫 요청 시작부터 마지막 응답 완료까지다. 특히 짧은 5초 구간에서는
마지막 응답 지연도 99% 처리율 판정에 영향을 주므로 지연·요청 수·후속 회복 결과와 함께 읽는다.

duplicate는 단일 구간과 `uniqueMembers`, `duplicateBurst`를 지정한다. 같은 회원에게 burst만큼 연속 요청한 뒤
다음 회원으로 넘어가며, 모든 회원을 순회하면 반복한다. 회원 수보다 재고를 많이 준비해 품절과 중복을 섞지 않는다.
단일 capacity/scarcity 구간에는 `otherPreparation: {stock, count, intervalSeconds}`를 지정할 수 있다.
같은 전략의 다른 캠페인을 측정 중 만들고, 준비 완료 시간·재고와 발급 구간의 겹침을 검증한다.
전체 DB 관측에는 준비 작업도 포함되므로 발급만 실행한 결과와 별도 시나리오로 비교한다.
scarcity에서는 재고 소진 뒤에도 요청을 계속 보내며 다음 캠페인을 준비할 수 있다.
빈 unit 범위 조회와 이웃 캠페인 INSERT의 실제 경합을 관찰하며, 잠금을 인위적으로 유지하지 않는다.

각 실행의 source/k6에 스크립트 사본을 보존하며 k6와 세션 준비는 이 사본을 사용한다.
회원·관리자 세션은 측정 밖에서 갱신한다. 세션 값·비밀번호·토큰은 저장소에 넣지 않는다.
실행 완료는 SLO 통과와 별개이며, 중단·무효·실패 실행도 삭제하지 않는다.

완료된 큰 부하 단계에서 경계 탐색만 별도 실행할 때는 `--refine-from /absolute/local-results.json`을 쓴다.
같은 `--capacity-stock`, 시간, 반복 수, VU 수, SLO와 JAR 해시를 검사하며 `--capacity-rates`에는 계정 준비를 위한
최고 요청률을 지정한다. 이전의 경계 관측을 출발점으로 사용하되 새 앱·일회성 DB라는 사실과 원본 해시를 기록한다.
이미 끝난 단계와 부분 실행을 다시 합산하지 않는다. `--refine-step`은 멈출 구간 폭이며 후보는 양끝의 중간값이다.

### V30 시나리오 재검증 · 2026-10-10

`local-scenarios-recheck-20261010.json`은 기존 7개 시나리오에 재고가 남은 상태에서 다른 캠페인을
준비하는 경우를 추가한다. 고정한 V30 JAR, 앱 1개, Hikari maximum/minimumIdle 10,
실제 세션 39,001개로 5개 방식을 각각 5회 비교한다. 예정 본 측정은 200회, HTTP 2,812,500건이다.

수정된 계획은 `--match-pool-isolation`을 사용한다. 조건부 UPDATE·RR 후보는
`spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ`, RC 후보는
`TRANSACTION_READ_COMMITTED`로 앱을 각각 재시작한다. 후보 하나의 모든 시나리오를 실행한 뒤
다음 후보로 넘어가며, 첫 후보도 로그인 준비 후 재시작한다. 본 측정 전에 실제 앱 연결 10개 모두의
격리 수준을 `performance_schema.variables_by_thread`에서 확인한다.

`@Transactional(isolation=...)`과 현재 `TransactionTemplate.setIsolationLevel(...)`은 모두
트랜잭션에 필요한 격리 수준을 지정한다. 풀 기본값과 다르면 시작할 때 변경하고 종료할 때 복구하므로
어노테이션으로 바꾸는 것만으로 전환 비용이 사라지지는 않는다. 이번 수정은 비교군별 **풀 기본값**을
맞추는 것이다. 품절/경합 판별의 새 RR 트랜잭션과 RR을 명시한 관리자 준비 경로는 그대로이므로,
RC 풀에서도 해당 경로의 전환은 남을 수 있다. `diagnostics.json`의 `driverIsolation`에
Hibernate SQL 집계에 포함되지 않는 격리 수준 조회·변경 명령의 측정 구간 증분을 따로 기록한다.
[Spring 6.2.14의 연결 준비·복구 구현](https://github.com/spring-projects/spring-framework/blob/v6.2.14/spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceUtils.java#L207-L244)에서도
현재 격리 수준이 다를 때 변경하고 이전 값을 복구하는 것을 확인할 수 있다.

수정 전 RR 기본 풀에서 측정한 V30 결과 20회는 중단된 별도 실험이다. 원본과 누적 드라이버 관측을
`build/k6/coupon-scenario-recheck-20261010-retry1/`에 보존하며 새 비교의 5회 반복에 합산하지 않는다.

이번 반복은 한 DB에서 `--rounds 5`를 실행하지 않는다. 아래와 같이 `--rounds 1`을 다섯 번 실행해
반복마다 앱·MySQL·Redis를 새로 만들고, `--variants` 순서를 한 칸씩 회전한다.
첫 순서는 DB → 대리 RR → 대리 RC → 복합 RR → 복합 RC다. 각 후보가 각 실행 위치에 한 번씩 온다.
각 반복 안에서 완료된 캠페인 이력이 쌓이는 영향과 직전 후보의 영향까지 제거한 설계는 아니다.

```bash
# 반복 1. 반복 2~5는 variants를 왼쪽으로 한 칸씩 회전하고 같은 JAR를 사용한다.
python3 load-test/k6/coupon/run_local_e2e.py \
  --workload-plan load-test/k6/coupon/local-scenarios-recheck-20261010.json \
  --rounds 1 --client-vus 1000 --cooldown 3 --pool-size 10 --match-pool-isolation \
  --variants db skip_surrogate_rr skip_surrogate_rc skip_composite_rr skip_composite_rc
```

계획 파일의 선택 기준은 결과를 보기 전에 고정했다. 정합성, 같은 부하의 반복 SLO 통과 여부,
실질적인 지연 차이 순서로 판단한다. 지연 우위를 주장하려면 다섯 회차가 모두 유효하고,
동일 반복의 비교에서 p95가 20% 및 5ms 이상 개선되는 경우가 적어도 네 번 있어야 하며
p99나 정합성의 악화가 없어야 한다. 마지막 한 장의 성공 지연 하나를 백분위 성능 근거로 쓰지 않는다.
차이가 분명하지 않으면 기존 대리 키 RR을 유지하고, 이 실험으로 우열을 입증하지 못했다고 기록한다.

모든 무효·성능 실패 원본을 보존한다. 한 반복의 호스트 압력/관측 누락 무효 비율이 20%를 초과하면
후속 반복을 중단해 환경 문제를 다시 진단한다. 이 중단을 DB 처리량 상한으로 해석하지 않는다.
개발용 컨테이너의 일시 중지는 사용자 승인을 받은 이번 실행의 환경 조치이며 일반 실행기의 동작이 아니다.
실행 전 켜져 있던 정확한 컨테이너 ID를 기록하고 정상 종료·실패·중단 시 복구한다.

### 커넥션 풀 10·20·30 비교

아래 실행은 대리 키 RR과 같은 JAR·DB 설정을 유지한 채 풀 크기만 비교한다. 전체 27회 본 측정으로,
500/1,000/1,500 RPS를 각각 20초씩, 초기 재고 30,001개로 세 번 반복한다.

```bash
python3 load-test/k6/coupon/run_local_e2e.py \
  --pool-sizes 10 20 30 --rounds 3 --variants skip_surrogate_rr \
  --workload-plan load-test/k6/coupon/local-pool-sizing-20261010.json \
  --client-vus 1000 --cooldown 3
```

풀 순서는 10→20→30 / 20→30→10 / 30→10→20이다. RPS 순서도 라운드별로 회전한다.
각 풀 차례마다 같은 포트에서 앱을 재시작하고, 각 본 측정 전에 100 RPS·10초 워밍업을 한다.
최초 세션 준비 직후의 앱도 재시작하여 한 후보만 로그인 부하로 예열되는 것을 피한다.
maximumPoolSize와 minimumIdle은 모두 후보 크기로 맞춰 연결 생성 지연과 용량 비교를 분리한다.
실행 시작 때 JAR를 출력 디렉터리에 복사하며 모든 재시작에 같은 사본을 사용한다.

한 실행은 하나의 일회성 MySQL/Redis를 공유한다. 계정·쿠폰·발급 데이터는 운영 데이터와 별개이며,
끝난 쿠폰은 비활성화하지만 행은 남으므로 데이터 누적 영향까지 제거한 실험은 아니다.
풀 크기·재시작 차례는 각 결과에, 앱 PID·시작 시 누적 행 수·실행 순서는 run.json에 남긴다.
풀은 1..64개, 비교 후보 2..5개이며 반복 수는 후보 수의 배수여야 한다(최대 6회).
서로 다른 발급 전략, 초기 재고, 측정 시간을 한 풀 비교에 섞으면 준비 전에 거부한다.
요청 누락·호스트 압력 회차도 보존하며, 설정 불일치·인증·재고 정합성 오류는 후속 실행을 중단한다.
이 실험은 공용 풀 크기의 로컬 민감도 확인이며, 전용 풀 격리 효과나 운영 최적 크기를 확정하지 않는다.

## 캠페인 목록 COUNT / EXISTS 로컬 비교

발급 전략 비교와 별개로, 목록 응답의 OPEN/SOLD_OUT 판정을 발급 내역 COUNT와 남은 unit EXISTS로 비교한다.
V1은 기존 COUNT를 유지한다. 비교 경로는 `coupon-benchmark` 프로필과
`benchmark.read-model.enabled=true`에서만 등록되며 세션과 `X-Benchmark-Token`을 요구한다.

- `GET /api/v2/coupons/campaigns/count`: 기존 조회 서비스
- `GET /api/v2/coupons/campaigns/exists`: 전략별 재고 존재 여부를 조회하는 후보

```bash
./gradlew bootJar -x test -x generateAdoc -x asciidoctor -x copyDocument
python3 load-test/k6/coupon/run-campaign-read-local.py \
  --output build/k6/coupon-campaign-read-new --java-home /path/to/current/java21 \
  --rounds 3 --rate 50 --seconds 15
```

실행기는 별도 MySQL·Redis·앱을 준비하고 종료 시 자신이 만든 자원을 삭제한다.
네 SKIP 전략별 10,000장 캠페인에서 발급 0·5,000·9,999·10,000장 상태를 비교하며,
실제 V1 응답과의 일치, 요청별 응답 해시, JDBC SQL, DB 실행 수와 HTTP 요청 수의 일치를 확인한다.
로컬에 `mysql:8.4.11`, `redis:7.2-alpine` 이미지와 k6, Python 3, 최신 Java 21이 필요하다.
실행 중 비밀값은 임시 파일에만 두며 기존 개발 DB에는 접속하지 않는다.

[2026-10-12 결과와 한계](../../../docs/performance/results/read-api-local-2026-10-12/README.md)에
모든 회차와 제외한 실행의 근거를 보존했다. 이 경로는 `test,coupon-benchmark`에서 검증했으며,
발급용 `coupon-performance` 허용 목록에는 추가하지 않았다.

## AWS 준비와 실행

같은 digest 이미지, V30 검증 완료 데이터, 앱 수·Hikari·DB/Redis 사양, 부하 발생기를 고정한다.
`aws,coupon-performance` 프로필은 시작 시 V30을 **검증만** 하며 마이그레이션을 실행하지 않는다.
V28 원본을 보존한 파생 데이터셋에 V29와 V30을 별도로 적용하고 새 manifest를 만들어야 한다.

```bash
make aws-coupon-prepare
python3 load-test/k6/coupon/run_experiments.py --config load-test/k6/coupon/experiment.example.json
python3 load-test/k6/coupon/run_experiments.py --config /absolute/coupon-experiment.json --check-inputs
# 환경과 개인 입력 파일이 준비된 전용 Linux 부하 발생기에서 실행
python3 load-test/k6/coupon/run_experiments.py --config /absolute/coupon-experiment.json --execute
```

기본 예제는 A·B·D·C·E, 5라운드, 180초, 최대 1,000 RPS로 180,001명과 최대 150회 본 측정이 필요하다.
워밍업·대기를 포함한 고정 워크로드의 상한은 9시간 10분이며, 로그인·CloudWatch 대기는 별도다.
AWS 실행 시간과 계정 준비 시간은 별도이므로 실제 환경에 맞춰 설정한다.
토큰·비밀번호·세션은 0600 파일에 두고 설정에는 경로만 넣는다. 앱별 직접 metrics URL을 사용한다.
소스·도구·설정 해시를 보존하며 변경하면 새 출력 경로를 쓴다.

`REPORT.ko.md`와 `report.json`은 반복 범위와 유효성, SQL·RDS CPU·성공 지연을 집계한다.
무효 반복을 골라 버린 개선율은 계산하지 않는다. 최대 처리량 상태는
`bracketed`, `lower-bound-only`, `unstable`, `inconclusive`, `no-passing-rate`로 구분한다.

## 도구 계약 검사

```bash
K6_NO_USAGE_REPORT=true k6 run --address '' load-test/k6/test/coupon-benchmark-fixture-test.js
node load-test/k6/test/prepare-coupon-sessions-test.js
python3 -m unittest discover -s load-test/k6/test -p 'test_coupon_*.py'
```

Python 검증에는 로컬 가짜 HTTP 서버에 실제 k6를 보내는 계약 검사도 포함한다. AWS 호출은 없다.
