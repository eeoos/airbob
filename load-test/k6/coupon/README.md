# 쿠폰 조건부 UPDATE / SKIP LOCKED 실험

같은 앱에서 재고 카운터와 unit 소비를 비교한다. 모든 비교군은 MySQL 커밋 후 201을 반환한다.
설계와 단계별 완료 기준은 [계획서](../../../docs/performance/coupon-skip-locked-benchmark-plan.md),
배포 입력은 [AWS 가이드](AWS.md), 직접 실행은 [수동 가이드](MANUAL.md)를 참고한다.

## 비교군과 고정 조건

| variant | 재고 원장 | 발급 트랜잭션 |
|---|---|---|
| `db` (A) | `coupon.issued_quantity` 조건부 UPDATE | RR |
| `skip_surrogate_rr` (B) | 대리 키 unit DELETE | RR |
| `skip_surrogate_rc` (D) | 대리 키 unit DELETE | RC |
| `skip_composite_rr` (C) | 복합 키 unit DELETE | RR |
| `skip_composite_rc` (E) | 복합 키 unit DELETE | RC |

기본 비교는 A·B·D이다. B·C 사전 비교로 키 변경의 의미를 판단하고 C·E 확대 여부를 정한다.
Lua 경로는 제거했다. Redis는 실제 세션 인증에 사용하며 재고를 저장하지 않는다.
모든 비교군이 같은 `POST /api/v2/coupons/{id}/issue?variant=...`와 토큰·세션 검증을 거친다.
쿠폰의 불변 `issue_strategy`와 요청 전략이 다르면 거부한다. A의 업무 트랜잭션 첫 SQL은 조건부 UPDATE다.

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
두 비교군은 AB/BA, 네 비교군 이상도 회전·역순으로 위치 편향을 줄인다.

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

Docker, Java 21, Node, k6가 필요하다. 관측 모드는 macOS와 PyMySQL 1.1.2가 필요하다.
새 MySQL 8.4.11·Redis 7.2 컨테이너와 실제 앱을 실행하고 V29 마이그레이션·회원 생성·로그인·HTTP 발급을 수행한다.
기존 개발 DB·Redis는 사용하지 않으며 종료 시 자신이 생성한 자원만 정리한다.

```bash
./gradlew bootJar -x test -x generateAdoc -x asciidoctor -x copyDocument
python3 load-test/k6/coupon/run_local_e2e.py --rate 50 --duration 20 --rounds 6 --observe
```

Java의 시간대 데이터가 오래됐다면 최신 Java 21 경로를 `--java-home`으로 지정한다.
`--observe`는 두 시나리오에 DB·풀·호스트 관측을 붙인다. 호스트 표본을 확보하려면 20초 이상을 권장한다.
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

## AWS 준비와 실행

같은 digest 이미지, V29 검증 완료 데이터, 앱 수·Hikari·DB/Redis 사양, 부하 발생기를 고정한다.
`aws,coupon-performance` 프로필은 시작 시 V29를 **검증만** 하며 마이그레이션을 실행하지 않는다.
V28 원본을 보존한 파생 데이터셋에 V29를 별도로 적용하고 새 manifest를 만들어야 한다.

```bash
make aws-coupon-prepare
python3 load-test/k6/coupon/run_experiments.py --config load-test/k6/coupon/experiment.example.json
python3 load-test/k6/coupon/run_experiments.py --config /absolute/coupon-experiment.json --check-inputs
# 환경과 개인 입력 파일이 준비된 전용 Linux 부하 발생기에서 실행
python3 load-test/k6/coupon/run_experiments.py --config /absolute/coupon-experiment.json --execute
```

기본 예제는 A·B·D, 6라운드, 180초, 최대 1,000 RPS로 180,001명과 최대 108회 본 측정이 필요하다.
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
