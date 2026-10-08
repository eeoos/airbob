# 쿠폰 발급 DB 조건부 UPDATE / Redis Lua 비교

쿠폰 실험 코드는 이 디렉토리에 모은다. `coupon-issuance-comparison.js`는 한 번의 고정 RPS 실행,
`run_experiments.py`는 준비·반복 실행·서버 지표 수집·결과 집계를 담당한다.
DB와 Lua 모두 **MySQL 커밋 후 201**을 반환하는 API를 비교한다.

AWS용 파일만 준비하려면 `make aws-coupon-prepare`를 사용한다. AWS를 호출하지 않으며,
앱 프로필·실제 회원 manifest·전달 파일과 이후 실행 절차는 [AWS 준비 가이드](AWS.md)에 정리했다.

## 두 실험

| 실험 | 요청·재고 | 확인할 결과 |
|---|---|---|
| `capacity` | 모든 요청의 회원이 다름, `재고 = RPS × 시간 + 1` | 성공 발급 p95/p99, 기준을 반복 통과한 최대 설정 RPS |
| `scarcity` | 모든 요청의 회원이 다름, `재고 = floor(RPS × 시간 × stockRatio)` | 같은 요청량·발급량에서 SQL 수, RDS CPU, 성공 발급 p95, 매진 응답 지연 |

각 RPS 단계에서 1라운드는 DB → Lua, 2라운드는 Lua → DB 순으로 진행한다. 이후 라운드도 번갈아 실행한다.
각 실행은 별도 쿠폰으로 낮은 RPS 워밍업 → 대기 → 새 쿠폰으로 측정 순서다.
워밍업 RPS는 두 실험의 시작 RPS 중 작은 값이며, 측정 쿠폰을 미리 발급하지 않는다.
부하 발생기는 `constant-arrival-rate`를 사용한다.
종료 경계에서 추가 iteration이 시작돼도 HTTP 요청은 `RPS × 시간`건까지만 보낸다.
회원 세션은 경계 여유분 1개를 포함해 준비하고, 실제 적용 여부는 결과의 `requestBudgetPolicy`에 기록한다.

## 실행 전 준비

- **이미 준비된 격리 AWS 실험 환경**과 전용 Linux 부하 발생기에서 실행한다. 이 실행기는 Terraform,
  이미지 배포, ASG 설정 변경이나 AWS 자원 생성을 수행하지 않는다.
- AWS 앱에는 같은 이미지와 `aws,coupon-performance` 프로필, `BENCHMARK_READ_MODEL_TOKEN`을 적용한다.
  이 프로필은 `coupon-benchmark`를 포함하며 V28 검증·백그라운드 작업 격리·쿠폰 경로 제한을 적용한다.
  `benchmark.read-model.enabled=true`가 필요하다(쿠폰 프로필의 기본값).
  앱·DB·Redis 사양, 앱 수, Hikari 풀과 배경 작업 설정을 고정하고 다른 실험을 동시에 실행하지 않는다.
- Python 3.10+, Node.js 18+, 저장소에서 고정한 k6 버전, AWS CLI가 필요하다.
  실행 역할에는 해당 리전의 `cloudwatch:GetMetricStatistics` 읽기 권한이 필요하다.
- `appMetricsUrls`에는 **각 앱의 직접 접근 주소**를 넣는다. ALB 주소를 반복해서 넣지 않는다.
  `/actuator/prometheus`의 `app_query_per_request_queries_sum/count`와 `process_start_time_seconds`를 읽는다.
- AWS에는 원본 V28 데이터에 연결된 `coupon-accounts-v1` manifest의 고유 회원 풀이 있어야 한다.
  구형 전체 `benchmark-dataset-v2` 형식도 단일 k6/로컬 검증에는 유지하지만 AWS 실행에는 사용하지 않는다.
  필요한 회원 수는 `최대 RPS × 측정 시간 + 1`이다. 계정을 순환 재사용하면 중복 발급 실험이 되므로 중단한다.
- 세션·비밀번호·벤치마크 토큰은 소유자만 읽는 파일(0600)에 보관한다. 설정에는 **파일 경로만** 넣는다.
  `adminSessionFile`은 ACTIVE ADMIN 회원의 SESSION_ID 값, `benchmarkTokenFile`은 서버와 같은 토큰 값이다.
  관리자 계정은 발급 부하용 회원 풀과 분리한다.

관리 API는 다음 경로다. 모두 벤치마크 프로필·활성 설정·벤치마크 토큰·ADMIN 세션이 필요하다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| POST | `/api/v1/admin/coupons/benchmark/fixtures` | 실행 식별자가 붙은 새 쿠폰 생성, ID 반환 |
| GET | `…/fixtures/{id}?run_id={runId}` | DB 발급·고유 회원 수, Redis 재고·발급 집합 크기 확인 |
| DELETE | `…/fixtures/{id}?run_id={runId}` | 해당 실행 소유의 **DB 비교 쿠폰만** 비활성화 |

Lua 재고는 기존 `POST /api/v1/admin/coupons/{id}/stock/prepare`를 사용한다.
설정과 호출은 실행기가 수행한다. DB 비교 쿠폰은 Redis에 준비하지 않는다.

## 실행

[experiment.example.json](experiment.example.json)을 `build/` 등 Git 제외 경로에 복사해 환경에 맞게 채운다.
`example`을 `false`로 변경하고 `appVersion`에는 배포한 **40자리 커밋 또는 이미지 digest**를 넣는다.
이 값은 기록용 선언이므로 실제 배포 버전과 직접 맞춰야 한다.
실험마다 새 `runId`를 사용한다. 결과 디렉토리가 이미 있으면 덮어쓰지 않는다.

먼저 계획과 필요한 계정 수만 확인한다. 이 명령은 서버·AWS에 접근하지 않는다.

```bash
python3 load-test/k6/coupon/run_experiments.py \
  --config load-test/k6/coupon/experiment.example.json
```

실제 환경으로 채운 설정 파일로 부하를 보낸다(저장소 루트 기준).

```bash
python3 load-test/k6/coupon/run_experiments.py \
  --config /absolute/path/coupon-experiment.json --execute
```

기본 출력은 `build/k6/coupon-experiments/{runId}/`다. `--output /absolute/new/directory`로 바꿀 수 있다.
예시 설정은 180초 × 최대 1,000 RPS라서 **180,001개의 회원 세션**, 최대 24회의 본 측정이 필요하다.
최종 B manifest의 실제 계정 수를 확인하고 RPS·시간을 정한다. 계정 생성은 이 실행기의 역할이 아니다.

`accountPasswordFile`이 있으면 세션 파일이 없거나 오래됐을 때 기존 세션 준비 도구로 다시 로그인한다.
생성한 세션 파일은 결과와 분리된 임시 디렉토리에 두고 종료 시 제거한다. Redis 세션 자체는 기존 TTL로 만료된다.
자동 로그인을 원하지 않으면 이 값을 `null`로 두고 [수동 실행 가이드](MANUAL.md#1-고유-회원-세션-fixture-준비)로 세션을 준비한다.
수동 세션 파일의 mtime은 최초 로그인 시점 이후로 인위적으로 갱신하지 않는다. 다른 호스트에서 복사할 때도 원래 시간을 보존한다.
파일 시각 검사는 만료를 예방하는 보조 수단이며 실제 인증 실패는 무효 결과로 분류한다.
관리자 세션은 자동 갱신하지 않으며, 파일은 API 호출 때마다 다시 읽으므로 긴 실험 중 새 로그인 값으로 교체할 수 있다.

## 판정과 결과

- `REPORT.ko.md`: 실험별 SQL 수·RDS CPU·성공 p95·성공 RPS의 반복 중앙값, 유효 라운드와 SLO 통과 수.
- `report.json`: 모든 라운드, 최소·최대·중앙값, 최대 처리량 판정, 생성한 쿠폰 ID와 종료 상태.
- 실행별 `client.json`, `k6.log`, `sql-before/after.json`, `state.json`, `db-cpu.json`, `generator.json`:
  원본 지표와 검증 근거. 실험 설정도 함께 보관한다. 세션 값은 결과에 넣지 않는다.

한 그룹에 무효 라운드가 있으면 그 그룹의 중앙값은 표시하지 않는다. 정상 라운드만 골라 개선율을 만들지 않는다.

`capacity`는 모든 요청 성공, 성공 p95 ≤ `p95LimitMs`, p99 ≤ `p99LimitMs`, 성공 처리량 ≥ 목표의 99%를 요구한다.
성공 RPS 분모는 **첫 HTTP 요청 시작부터 마지막 응답 완료까지**다. 전체 응답 p99에도 같은 p99 상한을 적용한다.
`scarcity`는 성공 발급 수가 준비 재고와 일치하고 매진 응답이 실제로 발생해야 한다.
응답별 오류 코드와 성공/매진 지연을 분리하므로 빠른 매진 응답으로 성공 p95나 성공 처리량이 희석되지 않는다.

두 실험 모두 누락 요청·중복·인증/준비 오류, DB/Redis 수량 불일치, 앱 재시작·SQL 카운터 누락,
부하 발생기 CPU ≥ 90% 또는 가용 메모리 < 15%를 정상 비교 결과로 인정하지 않는다.
CPU 관측은 Linux 호스트 전체 값이며 결과에 원본 샘플을 남긴다. 누락 부하는 서버의 상한으로 단정하지 않는다.

최대 처리량은 미리 정한 RPS 단계에서 **모든 라운드가 통과한 가장 높은 값**이다.
어느 방식이 한 단계에서 실패하면 그 방식의 상위 단계는 중단하지만 같은 단계의 반복과 다른 방식 측정은 완료한다.

| 상태 | 해석 |
|---|---|
| `bracketed` | 하위 단계 반복 통과, 다음 단계 반복 실패. 두 단계 사이를 좁혀 추가 측정 가능 |
| `lower-bound-only` | 설정한 최상위 단계까지 통과. 아직 최대치라고 쓰지 않음 |
| `unstable` | 같은 단계에서 통과·실패가 섞임 |
| `inconclusive` | 요청 누락, 정합성 오류 또는 근거 부족 |
| `no-passing-rate` | 첫 단계부터 반복 실패. 더 낮은 시작 RPS 필요 |

SQL 수는 발급 API의 **Hibernate SQL 문장 수**다. 커밋·세션 설정·관리 API 등 DB 전체 명령 수는 포함하지 않는다.
측정 전후 각 앱의 TOTAL sum 차이를 더하고, TOTAL count 차이가 k6 요청 수와 같은지도 확인한다.
SQL 개수로 CPU 비용을 추정하지 않고 RDS CPU를 따로 수집한다.

RDS `CPUUtilization`은 [기본 1분 간격 지표](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/monitoring-cloudwatch.html)이므로
실제 HTTP 측정 구간에 완전히 들어오는 60초 버킷만 평균 낸다. 시작·끝 일부 구간은 제외되며 정확한 구간을 결과에 기록한다.
버킷이 누락되면 잠시 재조회하고, 끝까지 없으면 불완전 결과로 중단한다. CPU 0%로 대체하지 않는다.
`db.t3`처럼 크레딧을 사용하는 RDS라면 양쪽의 크레딧 상태도 동일한지 별도로 확인한다.

이력서용 SQL 감소율은 같은 `scarcity` RPS·시간·재고의 DB/Lua 결과로 계산한다.
각 방식의 서로 다른 최대 RPS에서 SQL 수나 CPU를 직접 비교하지 않는다.
`complete`는 실행이 끝났다는 뜻이며 성능 기준 통과를 뜻하지 않는다. 실패 라운드를 제외하고 성과를 계산하지 않는다.

## 종료와 재실행

DB 워밍업·측정 쿠폰은 결과를 수집한 뒤 `finally`에서 비활성화한다.
Lua 쿠폰은 측정 시간 + 300초 뒤 발급이 종료되고 기존 Redis 보존 정책을 따른다. DB 발급 이력은 남겨 둔다.
공용 Redis를 비우거나 발급 수량을 초기화하지 않는다.

중단 시에도 보고서가 남는다. `cleanup-required`인 DB 쿠폰은 관리자 세션을 갱신하고
보고서의 ID와 runId로 위 DELETE API를 호출한다. 프로세스 강제 종료 시 `created` 상태 쿠폰도 확인한다.
생성 요청의 응답을 잃으면 `create-requested`만 남을 수 있다. 이 경우 `coupon.description`의
`coupon-benchmark:{runId}:db|lua`와 이름의 실행 label로 생성 여부를 확인한다. POST를 자동 재시도하지 않는다.
정리 후 새로운 runId·새 쿠폰으로 재측정한다.
실험 프로필 제거와 정상 배포 전 DB 쿠폰 종료 확인은 [수동 가이드의 teardown](MANUAL.md#6-측정-후-서버-teardown)을 따른다.

## 로컬 검증

실제 Airbob JAR에 k6를 연결하려면 아래 로컬 실행기를 사용한다. 새 MySQL·Redis 컨테이너를 띄우고
V28까지 마이그레이션한 뒤, 회원 생성·실제 로그인·쿠폰 생성·Lua 준비·HTTP 발급·SQL 집계·재고 검증을 수행한다.
기존 개발 DB·Redis는 사용하지 않는다. 실행이 끝나면 자신이 띄운 앱과 컨테이너만 정리한다.

```bash
./gradlew bootJar -x test -x generateAdoc -x asciidoctor -x copyDocument
PYTHONDONTWRITEBYTECODE=1 python3 load-test/k6/coupon/run_local_e2e.py --rate 50 --duration 20 --rounds 2
```

Docker에 `mysql:8.4.11`, `redis:7.2-alpine` 이미지와 현재 시간대 데이터를 지원하는 Java 21이 필요하다.
macOS에서는 설치된 Java 21을 찾으며 `--java-home`으로 지정할 수도 있다.
`test,coupon-benchmark` 프로필로 스케줄러·Kafka·외부 연동을 끄고, 쿠폰 발급과 인증은 실제 구현을 사용한다.
DB/Lua × 충분한 재고/매진 × 2라운드, 총 8회 본 측정과 별도 워밍업을 실행한다.
결과는 `build/k6/coupon-local/{runId}/REPORT.ko.md`와 원본 JSON에 남는다.
회원·쿠폰만 넣은 작은 로컬 DB이므로 **B 데이터나 AWS 성능, 최대 지속 처리량의 측정 결과로 사용하지 않는다.**
로컬 manifest는 테스트 계약 형식에 실제 로컬 계정 목록을 넣은 입력이다. RDS CPU는 이 실행기에서 수집하지 않는다.

### 로컬에서 RPS를 올리며 한계 구간 찾기

`--capacity-rates`를 주면 충분한 재고 실험만 실행한다. 각 단계에서 DB → Lua / Lua → DB 순서로
반복하고, 모든 라운드가 통과한 단계와 실패·미확정 단계 사이를 `--refine-step` 간격까지 좁힌다.
한 방식이 실패해도 다른 방식의 상위 단계는 계속 측정한다. 각 측정은 새 쿠폰과 별도 워밍업을 사용한다.

```bash
python3 load-test/k6/coupon/run_local_e2e.py \
  --capacity-rates 50 100 200 400 800 \
  --duration 20 --rounds 2 --refine-step 25 --client-vus 300 \
  --p95-limit-ms 500 --p99-limit-ms 1000
```

위 설정은 최대 800 × 20 + 1 = **16,001명**을 준비하고 실제 로그인한다. 준비 시간은 측정에 포함하지 않는다.
앱·DB·Redis는 전체 탐색에서 유지하고, 회원은 쿠폰 간에 재사용하되 같은 쿠폰에는 한 번씩만 발급을 요청한다.
로컬 실행의 응답 기준은 기본 p95 500ms·p99 1초다. `--client-vus`는 k6의 동시 실행 여유분이며 목표 RPS가 아니다.

- 전 요청 성공, 성공 처리량 ≥ 목표의 99%, 응답 기준 충족, 누락 0건, DB/Redis 정합성 일치를 확인한다.
- 상위 단계까지 모두 통과하면 `lower-bound-only`다. 더 높은 단계를 추가해야 상한을 찾을 수 있다.
- 모든 반복에서 응답 기준을 넘으면 `bracketed`, 반복별 결과가 섞이면 `unstable`로 기록한다.
- 요청 누락·인증/재고/관측 오류는 `inconclusive`다. 아래 구간은 탐색하지만 이를 서버의 실패 상한으로 확정하지 않는다.
- 보고서는 실행 중에도 갱신된다. 성능 기준을 넘은 측정도 원본 결과에 남기고 다음 탐색에 반영한다.

부하 발생기와 앱·Docker가 같은 호스트 자원을 공유하며 기존 개발 컨테이너도 유지한다.
따라서 이 결과는 **현재 로컬 환경의 짧은 구간 탐색**이다. k6 프로세스의 평균 CPU 사용 코어 수도 기록하지만
부하 발생기가 독립적으로 충분한 여유를 가졌다는 증거는 아니다. AWS의 최대 지속 처리량은 별도 호스트에서
더 긴 측정으로 다시 검증한다. 지원 범위는 최대 2,000 RPS, 15~60초, 2~3라운드, 고유 세션 최대 60,001개다.

### 고정 RPS에서 병목 관측하기

macOS에서는 `--diagnose-rate`로 한 RPS를 유지하며 DB·커넥션 풀·호스트 지표를 수집할 수 있다.
이 모드는 `PyMySQL==1.1.2`가 설치된 Python이 필요하다. 기존 lab 가상 환경에 해당 의존성이 있다면:

```bash
.local/airbob-lab/venv/bin/python load-test/k6/coupon/run_local_e2e.py \
  --diagnose-rate 500 --duration 120 --rounds 3 --client-vus 1000 \
  --cooldown 10 --p95-limit-ms 500 --p99-limit-ms 1000
```

회원 60,001명을 생성하고 실제 로그인한 뒤, DB → Lua / Lua → DB 순서를 번갈아 총 6회 본 측정한다.
준비·로그인·별도 쿠폰 워밍업은 본 측정에 포함하지 않는다. 120초는 짧은 측정에서 마지막 응답 몇백 ms가
완료 처리량 판정에 크게 영향을 주는 현상을 줄이기 위한 설정이며, 통과 기준 자체를 낮추지는 않는다.
지원 시간은 10~180초이며 `RPS × 시간 + 1 ≤ 60,001` 조건도 만족해야 한다.

### 로컬에서 한정 수량 프로모션 비교하기

`--scarcity-rates`는 재고를 고정하고 발급 시도 RPS를 단계적으로 높인다. macOS와 PyMySQL을 사용하는
위 병목 관측을 함께 수행하며 발급 코드는 변경하지 않는다.

```bash
.local/airbob-lab/venv/bin/python load-test/k6/coupon/run_local_e2e.py \
  --scarcity-rates 500 1000 2000 3000 --scarcity-stock 1000 \
  --duration 20 --rounds 3 --client-vus 5000 --cooldown 10 \
  --p95-limit-ms 500 --p99-limit-ms 1000
```

- 각 실행은 재고 1,000장의 새 쿠폰이며, DB → Lua / Lua → DB 순서를 번갈아 반복한다.
- 같은 쿠폰에 회원을 재사용하지 않으므로 3,000 × 20 + 1 = 60,001명의 실제 로그인 세션을 준비한다.
- 성공과 매진 응답 각각에 p95/p99 기준을 적용하고, 전체 응답 처리량 ≥ 목표의 99%, 누락·예상 밖 응답 0건,
  DB/Redis 발급 수와 성공 응답 수의 일치를 확인한다. 매진 응답은 정상적인 업무 결과다.
- 성능 기준을 넘거나 누락된 실행도 원본에 남기고 상대 방식과 나머지 반복을 측정한다.
  예상 밖 응답·타임아웃은 성능 실패로 남긴다. 인증·재고 정합성 오류는 중단한다.
- 워밍업은 전 요청 성공·전송 누락·정합성을 검증한다. 워밍업 지연은 기록하되 본 측정의 SLO 통과로
  판정하거나 본 측정을 중단시키지 않는다. 본 측정의 지연 기준은 그대로 적용한다.
- 발급 직후 지연으로 작업자가 모두 점유될 수 있어 로컬 한정 수량 모드는 최대 5,000 VU를 허용한다.
  작업자를 늘려도 호스트 CPU·메모리 여유가 부족하면 유효한 비교로 인정하지 않는다.
- SQL·CPU 비교는 같은 재고·목표 RPS·시간에 실제 요청량까지 맞는 실행끼리만 한다.
  DB CPU는 관측 구간의 사용 코어 수이며 RDS CPU %가 아니다.
- 20초 결과는 짧은 프로모션 부하 관측이다. 장시간의 최대 지속 처리량으로 표시하지 않는다.
  예를 들어 통과한 1,000 RPS를 더 길게 확인하려면 `--scarcity-rates 1000 --duration 60`을 사용한다.

최대 5,000 RPS, 10~180초, 2~3라운드를 지원하되 `최대 RPS × 시간 + 1 ≤ 60,001`을 지켜야 한다.
목표 전체 요청 수보다 재고가 적어야 하며, 같은 호스트의 자원 경쟁이 관측되면 비교 근거에서 구분한다.

이미 과부하를 확인한 방식을 반복하지 않고 남은 확인을 이어갈 수도 있다. 아래 예시는 세션을 한 번
준비한 뒤, DB/Lua 500 RPS·60초를 각각 2회 먼저 확인하고 Lua 3,000 RPS·20초를 3회 측정한다.
기존 실패 결과는 보존하며 서로 다른 VU 설정의 실행을 한 비교로 합치지 않는다.

```bash
.local/airbob-lab/venv/bin/python load-test/k6/coupon/run_local_e2e.py \
  --scarcity-rates 3000 --scarcity-variants lua --scarcity-stock 1000 \
  --duration 20 --rounds 3 --client-vus 5000 --cooldown 10 \
  --scarcity-confirm-rate 500 --scarcity-confirm-duration 60 --scarcity-confirm-rounds 2
```

필요한 세션은 기본 단계와 추가 확인의 `RPS × 시간 + 1` 중 큰 값이며, 보고서에 각 행의 시간을 표시한다.

- MySQL `Innodb_row_lock_waits/time` 차이와 `data_lock_waits`를 수집해 잠금 대기량·테이블·인덱스를 확인한다.
- Hikari의 active/pending/max와 커넥션 획득 시간·타임아웃, JVM GC, SQL별 DB 실행 시간을 수집한다.
- 전용 MySQL·Redis 컨테이너 CPU·메모리·블록 I/O와 MySQL 파일 I/O 지연을 관측한다.
- macOS 호스트 CPU·메모리 여유, 해당 k6 프로세스의 CPU·RSS를 기록한다.

표본은 2초 간격이며 호스트 전체 CPU·메모리는 약 10초 간격이다. MySQL 관측은 새 전용 DB에 만든 읽기용
계정으로 수행한다. 파일 I/O 계측 설정도 해당 일회성 MySQL에만 적용한다. 기존 개발 인프라와 AWS는 사용하지 않는다.
계측 자체의 비용은 양쪽 측정에 포함되며, 표본별 수집 시간도 원본에 남긴다.

`REPORT.ko.md`의 병목 표와 실행별 `diagnostics.json`, `diagnostic-samples.ndjson`,
`diagnostic-before.json`, `diagnostic-after.json`을 함께 본다. 누락 지표나 수집 실패는 정상 0으로 해석하지 않는다.
잠금 대기 시간은 여러 트랜잭션의 누적 합이므로 측정 시간보다 클 수 있다. MySQL 파일 I/O 지연만으로 물리 디스크의
전체 포화를 확정하지 않는다. SQL 템플릿·파일 이벤트별 시간은 해당 카운터의 전후 차이다.

VU를 늘려도 응답이 계속 밀리면 설정한 상한에 다시 도달할 수 있다. `dropped_iterations`가 있으면 목표 RPS를
모두 전달한 측정이 아니며, 지연·잠금·커넥션·호스트 지표와 함께 해석한다. 이 모드는 최대 RPS를 자동 확정하지 않는다.

아래는 실행기 자체의 계약 검사와 가짜 HTTP 서버를 사용하는 빠른 테스트다.

```bash
K6_NO_USAGE_REPORT=true k6 run --address '' load-test/k6/test/coupon-benchmark-fixture-test.js
node load-test/k6/test/prepare-coupon-sessions-test.js
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s load-test/k6/test -p 'test_coupon_*.py' -v
```

Python 검증은 로컬 가짜 HTTP 서버에 실제 k6를 보내는 smoke test도 포함한다. AWS 계정이나 실제 앱은 필요 없다.
