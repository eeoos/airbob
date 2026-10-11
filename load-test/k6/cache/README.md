# 숙소 상세 Redis 캐시 전후 비교

AWS 실험의 오프라인 준비는 `make aws-cache-prepare`로 실행한다. AWS를 켜거나 호출하지 않는다.
실제 리소스 준비, 최대 지속 RPS 탐색, 장애 복구 절차는
[AWS 캐시 실험 가이드](../../../docs/performance/aws-cache-experiments.md)를 참고한다.
로컬과 AWS는 `accommodation-detail-experiment.js`를 공유하며 환경 제어 실행기는 각각 분리한다.

## 로컬 성능·경합·장애 실험

기존 V2/V1 비교 외에 다음 실행기로 네 가지 성과의 로컬 측정 기반을 확인할 수 있다.

```bash
python3 load-test/k6/cache/run-local-experiments.py
```

이 실행기는 **같은 V1 API와 같은 JAR**에서 캐시 ON/OFF 또는 로컬 요청 병합 ON/OFF만
전환한다. 아래의 기존 V2/V1 실행기와 결과를 섞지 않는다. 기존 V28 MySQL은 읽기만 하고,
실험 소유의 Redis와 loopback 앱 프로세스를 생성한다. 원래 앱·일반 Redis·캐시 Redis에는
종료·정지·초기화 명령을 보내지 않는다.

| 실험 | 비교 조건 | 기록 |
|---|---|---|
| latency | 같은 고정 요청량, 캐시 OFF/ON | p95/p99, 오류율, 실제 SELECT·DB 로딩 수 |
| capacity | 같은 앱 수와 JVM 설정, 단계별 요청량 | SLO 통과 최고 단계, 최초 미통과 단계, 실제 성공 RPS, 누락 |
| miss | JVM·DB 예열 후 실험 Redis만 비우고 같은 숙소에 버스트 | 실제 DB 로딩·SELECT·캐시 적중·병합, 앱별 집계, 클라이언트 출발 편차 |
| outage | 캐시 Redis를 pause한 상태에서 병합 OFF/ON | 지속 부하와 버스트 각각의 DB 로딩·병합률·p95·오류율, 복구 후 응답 동일성 |

기본값은 앱 1대, 숙소 20개, 회차별 예열 5초·측정 15초, AB/BA 두 회차다.
latency는 40 RPS, capacity는 40/100/200/400/800 RPS, outage는 40 RPS와 60 VU 버스트를
각각 실행한다. 실험용 참고 SLO는 p95 100ms 이하, 오류율 0, 요청 누락 0,
목표 RPS의 99% 이상 완료이며 서비스의 실제 운영 SLO라는 뜻은 아니다.
앱마다 384MB heap, JVM ActiveProcessorCount=2, Tomcat 64 threads, Hikari 10 connections를
동일하게 사용한다. ActiveProcessorCount는 JVM 병렬성 설정이며 CPU 사용량을 강제로 제한하지 않는다.

```bash
# 전체 흐름의 짧은 동작 점검
python3 load-test/k6/cache/run-local-experiments.py \
  --rounds 1 --duration 5 --warmup 2 --rates 40 100 --burst 30

# 실제 Redis를 공유하는 앱 두 대에서 경합과 장애 경로 확인
python3 load-test/k6/cache/run-local-experiments.py \
  --apps 2 --scenarios miss outage --duration 15 --rounds 2

# 판정 기준과 측정 구간을 명시한 처리량 탐색
python3 load-test/k6/cache/run-local-experiments.py \
  --scenarios latency capacity --p95-ms 100 --duration 30 --rates 40 100 200 400 800 1000
```

앱 수는 1~2대, 최대 부하 단계는 1000 RPS로 제한한다. 두 앱에는 k6가 요청을 균등 분배하며
실제 ALB를 거치는 시험은 아니다. 부하 상한까지 통과하면 결과는 최고 확인 RPS의
**하한(lower-bound-only)**이다. 이를 최대 처리량 또는 증가율로 바꾸어 쓰지 않는다.
부하 발생기의 요청 누락이 있으면 서버 처리량 상한을 확정하지 않는다.
DB 로딩은 애플리케이션의 loader 실행 지표이며 정상 상세 한 번은 SELECT 세 번으로 검증한다.
동시 버스트는 클라이언트의 출발 시각을 맞추며, 모든 요청이 동시에 서버에 도착하거나
모두 cache miss를 관측했다는 뜻은 아니다.

병합 대조군은 cache-benchmark 프로필에서만
`CACHE_BENCHMARK_LOCAL_COALESCING_ENABLED=false`를 허용한다. 일반 실행은 기본적으로 병합을
유지하며 이 설정으로 비활성화하면 시작을 거부한다. Redis 연결·명령 timeout은 두 대조군에서
같은 설정을 유지한다. 원본 DB에 인위적인 지연을 넣지 않으므로 실제 병합 효과가 작게 나올 수도 있다.
요청 병합 대기 제한과 그 이후 개별 조회 정책도 그대로 유지한다.

결과는 `build/k6/cache-experiments/<실행 ID>/report.md`, `comparison.json`, 개별 k6 JSON,
앱별 Prometheus 전후 snapshot과 로그에 저장한다. 중간 실패도 `incomplete` 상태와 이미 얻은
결과를 남기고 실험 리소스를 정리한다. 앱·DB·부하 발생기가 같은 컴퓨터를 사용하므로
로컬 수치를 AWS 최대 처리량으로 주장할 수 없다.

Lua permit의 정합성은 부하 통계와 별개로 실제 Redis 경합 테스트를 사용한다.

```bash
./gradlew test --tests '*AccommodationDetailCacheInvalidationRaceIntegrationTest'
python3 -m unittest discover -s load-test/k6/test -p 'test_cache_local*.py'
```

## 로컬 한계 처리량과 병목 함께 확인하기

`run-local-capacity.py`는 같은 V1 API·JAR에서 캐시 OFF/ON을 비교하며, 각 측정 구간에
커넥션 풀·앱·MySQL·Redis·부하 발생기 관측을 추가한다. macOS와 로컬 Docker를 사용한다.
기존 짧은 검증의 부하 상한은 유지하고, 이 실행기만 최대 20,000 RPS·600초 구간을 허용한다.
AWS에 접근하거나 기존 DB·Redis를 초기화하지 않는다.

```bash
# 먼저 넓은 간격으로 통과/미통과 구간 찾기
python3 load-test/k6/cache/run-local-capacity.py --grafana \
  --apps 1 --rates 100 250 500 1000 2000 4000 8000 16000 \
  --duration 30 --warmup 10 --rounds 1

# 찾은 구간 안의 RPS를 좁혀 더 긴 구간에서 반복 확인
python3 load-test/k6/cache/run-local-capacity.py --grafana \
  --apps 1 --variants cache-off --rates 1000 1250 1500 \
  --duration 120 --warmup 10 --rounds 3
```

두 번째 명령의 RPS는 절차 예시이며 측정된 한계가 아니다. OFF/ON 각각 통과·미통과 구간을
찾고 반복 결과가 일관적인지 확인한다. 기본 비교 기준은 p95 100ms 이하, 오류·누락 0,
목표 RPS의 99% 이상 완료다. 운영 SLO에서 도출한 값이 아니라 명시적인 실험 기준이다.
최상위 단계를 통과하면 더 높은 단계가 필요하며, 짧은 1회 탐색 결과로 최대 지속 처리량을 확정하지 않는다.
각 부하 단계는 기본적으로 `min(목표 RPS, 1000)`의 요청량으로 먼저 예열한다.
`--warmup-rate-cap`으로 이 상한을 조절하며 실제 예열 결과도 별도 JSON에 남긴다.
초기 JVM 준비와 각 단계의 예열은 측정 구간에서 제외한다.

`--pool-size`는 기본 10이며 4~40을 지원한다. 풀 대기가 병목 후보일 때 같은 요청량에서
풀 크기만 바꾸는 추가 진단에 사용한다. 캐시 개선율은 같은 풀 크기·앱 수·부하에서 비교한다.
`--client-vus`는 기본 512이며 진단 모드에서 최대 4,000까지 지원한다.
VU 수는 고정 요청률을 바꾸지 않으며, 응답을 기다리는 요청을 유지할 여유를 조절한다.
VU를 늘렸을 때 부하 발생기 메모리·CPU도 함께 확인한다. VU 부족이나 부하 발생기 자원 부족으로 요청이 누락되면
서버 상한으로 확정하지 않고 클라이언트 조건을 점검한다.
다른 k6 프로세스가 실행 중이면 시작을 거부한다. 측정 도중 다른 k6가 관측돼도
`competing-load-generator`로 표시해 서버 한계 판정에서 제외한다. 다른 부하 테스트·빌드와
동시에 실행하지 않으며, 이 실행기는 다른 작업의 프로세스를 종료하지 않는다.
`requestCriteriaPassed`는 p95·오류·누락·성공 RPS 기준만의 결과다. 한계 탐색의 `sloPassed`에는
관측 유효성도 반영한다. 요청 기준을 만족해도 공유 호스트 CPU 압박 등으로 서버 상한 판정이
제한될 수 있으므로 두 값과 `capacityObservationReasons`를 함께 읽는다.

한계 탐색의 기본 `--response-check body`는 예열 전에 응답을 기존 DB 응답과 의미적으로
대조한 뒤, 측정 중 **모든 응답의 본문 전체**를 확인된 문자열과 비교한다. k6가 요청마다
JSON 전체를 정렬·재직렬화하는 비용을 줄이면서 응답 누락·변조 검증은 유지한다.
본문의 표현이 달라져도 실패하므로 응답이 안정적인 고정 fixture에서만 사용한다.
기존 비교 방식은 `--response-check canonical`로 선택한다. 양쪽은 같은 검증 방식을 사용한다.
AWS와 기존 짧은 실험의 기본 검증 방식은 바뀌지 않는다.
검증 데이터의 파일 읽기·파싱은 `SharedArray` 내부에서 한 번 수행하고, 각 요청은 해당 숙소의
비교 문자열만 가져온다. 가상 사용자마다 전체 fixture 객체를 중복 보관하지 않는다.
([k6 SharedArray 문서](https://grafana.com/docs/k6/latest/javascript-api/k6-data/sharedarray/))

결과는 기존 `build/k6/cache-experiments/<실행 ID>/` 아래에 저장된다. 각 단계의
`*-diagnostics/summary.json`에는 커넥션 획득·보유 시간, 풀 대기 표본, GC, SQL digest의
실행 시간·읽은 행 수, 컨테이너 CPU, 앱/k6 프로세스·호스트 자원이 남는다.
`samples.ndjson`과 전후 snapshot으로 요약 근거도 확인할 수 있다.
앱/컨테이너 CPU는 전후 전체 구간과 HTTP 구간 내부 표본 간 차이를 구분하고,
호스트의 실제 swap-in/out 속도도 기록한다. 스왑 누적 사용량만으로 현재 병목을 단정하지 않는다.
`--jfr`를 추가하면 실험 소유 JVM의 JFR 기록도 남긴다. 프로파일링을 켠 실행은 원인 분석용으로
구분하고, 최종 처리량 확인은 프로파일링 없이 반복한다. 실행 당시 측정 소스도
`source-snapshots/`에 보존한다.

지연의 위치를 구분하기 위한 별도 진단에는 `--trace-latency --jfr`를 사용할 수 있다.
실험 소유 Redis에 PING과 앱 liveness 요청을 각각 약 초당 10회 보내고, 독립적인 왕복 시간·
실행 지연을 NDJSON에 기록한다. k6 로그에는 Go GC 기록과 정렬용 프로세스 시작 시각도 남긴다.
이 옵션을 켠 실행은 추가 관측 부하가 있으므로 최종 처리량 확인과 구분한다.

DB/컨테이너 누적 지표의 구간에는 k6 초기화·마지막 응답 대기가 포함된다. 대기·호스트 자원
표본은 실제 HTTP 시작~완료 구간만 요약한다. MySQL 지표는 다른 클라이언트 활동도 포함하며,
진단 수집 자체의 비용과 로컬 장비 공유 영향을 AWS 성능으로 해석하지 않는다.
CPU 1코어는 논리 CPU 하나를 완전히 사용한 시간이고, JVM `ActiveProcessorCount=2`는 CPU 제한이 아니다.
호스트 CPU 90% 이상·메모리 여유 15% 미만·진단 누락은 처리량 판정을 불확정으로 남긴다.

## 기존 로컬 Grafana에서 실험 보기

실험은 캐시 초기화·Redis pause와 기존 앱의 작업이 섞이지 않도록 임시 Redis와 별도 JVM을 사용한다.
기존 MySQL은 읽기 전용으로 재사용한다. 격리된 대상도 같은 Prometheus/Grafana에서 볼 수 있다.

최초 한 번, 추가된 target 디렉터리 마운트와 수집 설정을 로컬 모니터링에 반영한다.
이미 실행 중인 인프라는 그대로 두고 Prometheus 설정만 갱신하는 명령이다.

```bash
docker compose --profile monitoring up -d --no-deps prometheus
```

Grafana와 기존 Redis Exporter가 실행 중인 상태에서 다음 명령을 사용한다.

```bash
python3 load-test/k6/cache/run-local-experiments.py \
  --grafana --apps 2 --scenarios latency miss outage \
  --duration 30 --warmup 5 --rounds 1 --burst 60
```

[Grafana 실험 대시보드](http://127.0.0.1:3001/d/airbob-cache-experiments)를 열고 **실행 ID**와
**조건**을 선택한다. 실행 중 출력되는 링크에는 현재 실행 ID가 포함된다.

- 실시간 그래프: 요청량, DB 로딩, 캐시 처리 경로, 락 대기, Redis 조회 실패와 자원 사용량.
- 완료된 측정 결과: 단계가 끝나면 k6 p95·오류율, DB 로딩·병합률 등 보고서와 동일한 수치를 표시.
- 앱은 1초, Redis는 3초 간격으로 수집한다. 짧은 버스트의 순간 피크는 그래프에서 평활화될 수 있다.
  실시간 그래프에는 예열도 포함되므로 전후 비교의 정확한 값은 완료된 결과와 report.md를 사용한다.
- 실험 종료 후에도 Prometheus 보존 기간 동안 과거 데이터를 볼 수 있다. 시간 범위를 실행 시각에 맞춘다.
- 기존 숙소 캐시 대시보드에서는 Environment를 cache-experiment로, 기존 Redis 대시보드에서는
  namespace를 cache-experiment로 선택해도 된다.

관측 모드는 실험 대상과 전용 Redis Exporter를 자동 등록하고 종료 시 자기 대상·Exporter만 정리한다.
짧은 버스트도 수집되도록 각 측정이 끝난 후 측정 구간 밖에서 3초 기다린다. 원래 앱과 Redis는 변경하지 않는다.
--grafana를 생략하면 기존 측정 방식이다. 관측 모드는 로컬 수집 부하가 추가되므로 report.md와
run.json의 liveMonitoring 여부를 함께 기록하며, 관측 ON/OFF 결과를 같은 조건으로 비교하지 않는다.

강제 종료로 target 파일이 남았다면 monitoring/prometheus/targets/에서 해당 실행 ID의 JSON만 제거한다.
다른 실행의 파일이나 기존 모니터링 대상은 제거하지 않는다.

## 기존 V2/V1 비교

이 실험은 같은 앱에서 다음 두 메서드를 비교한다. 서버 캐시는 계속 켜 둔다.

| 변형 | API | 실행 경로 |
|---|---|---|
| before | `GET /api/v2/accommodations/{id}` | `AccommodationDetailBenchmarkController.findAccommodationBefore`, DB 직접 조회 |
| after | `GET /api/v1/accommodations/{id}` | `AccommodationController.getAccommodation`, Redis 캐시 조회 |

기존 `traffic/dataset-read.js`는 **V1 API 하나의 서버 캐시 설정 OFF/ON** 실험으로 유지한다.
아래 `run-local-comparison.py`는 V2/V1 비교용이며, 위 `run-local-experiments.py`와 결과를 섞지 않는다.

## 로컬 실행

저장소 루트에서 실행한다. 최신 시간대 데이터를 지원하는 JDK 21, Docker, Python 3, k6 v1.5.0과 현재 로컬 Compose의 `mysql`, `redis`, `redis-cache`, Elasticsearch가 필요하다. `.env`는 기존 로컬 `airbobdb`의 접속 설정을 사용하며, DB는 이미 V28까지 적용돼 있어야 한다. macOS에서는 `java_home -v 21`로 설치된 JDK를 선택한다. 다른 JDK를 지정하려면 `--java-home /absolute/path/to/jdk`를 사용한다.

```bash
python3 load-test/k6/cache/run-local-comparison.py
```

기본값은 공개된 숙소 20개, 초당 20회, 변형별 예열 5초·측정 15초, 각 분포 2라운드다. 첫 라운드는 before → after, 다음은 after → before 순서다. 더 긴 로컬 확인은 다음과 같이 실행한다.

```bash
python3 load-test/k6/cache/run-local-comparison.py \
  --keys 20 --rate 30 --warmup 10 --duration 30 --rounds 3
```

실행기는 현재 소스로 앱 JAR를 만들고, 사용 중인 Redis 이미지로 **별도의 임시 캐시 Redis**를 생성한다. 앱은 임의의 loopback 포트에서 `dev,cache-benchmark`로 실행한다. 기존 MySQL은 읽기만 하며 Flyway는 검증만 수행한다. 기존 일반 Redis와 캐시 Redis에는 초기화 명령을 보내지 않는다. 종료 시 실험이 생성한 앱과 Redis만 제거한다.

결과는 `build/k6/cache/<실행 ID>/comparison.json`에 저장된다. 원본 k6 결과, 측정 직전·직후 Prometheus 지표, 앱 로그, 공개 상세 응답 fixture도 같은 디렉터리에 남는다. 실행 토큰은 프로세스 환경변수로만 전달한다. 테스트 데이터는 ID 순으로 고른 작은 집합이므로, 이 결과의 범위는 **로컬 동작 검증**이다.

## 세 가지 요청 분포

| 분포 | 요청 방식 | 해석 |
|---|---|---|
| `same-key` | 첫 숙소 하나를 반복 조회 | 캐시가 계속 적중할 때의 효과를 확인 |
| `uniform` | 모든 숙소를 순서대로 같은 비율로 조회 | 여러 키의 캐시 적중과 DB 부하 감소를 확인 |
| `hotset-80-20` | 앞쪽 20%의 숙소에 요청의 80% 집중 | 인기도가 치우쳤을 때의 동작을 확인 |

hotset의 키 수는 `floor(숙소 수 / 5)`이며 최소 5개 숙소가 필요하다. 요청 10개마다 8개는 인기 키, 2개는 나머지 키를 각 집합 안에서 순환한다. 분포는 두 변형에서 동일하다. 순서는 재현 가능한 결정적 순서이며 실제 사용자 접근 분포를 추정한 것은 아니다.

모든 측정은 **미리 채운 캐시의 정상 적중 상태**를 비교한다. 인기 키가 아닌 키도 미리 채운다. 따라서 hotset 결과는 80% hit / 20% miss를 뜻하지 않는다. cold start, TTL 만료, 메모리 부족에 따른 eviction, Redis 장애·무효화는 별도 실험 대상이다.

## 성공 판정

각 변형 전에 임시 Redis를 초기화하고, 모든 대상의 before/after **전체 상세 응답**이 같은지 확인한다. 객체 키와 amenity 배열의 순서만 정규화하며 다른 배열 순서는 유지한다. 예열을 별도 k6 실행으로 수행한 다음, 측정 구간에만 일정한 RPS를 보낸다. 로그인 요청은 없다.

다음 조건을 모두 만족해야 결과를 유효하게 기록한다.

- 모든 요청이 HTTP 200이고 전체 응답이 fixture와 같다.
- 실패 및 dropped iteration이 없고, `RATE × 측정 초` 이상의 표본이 완료된다.
- 서버가 기록한 요청 수와 k6 완료 수가 같다.
- before는 요청당 SELECT 3회이며 캐시 경로를 사용하지 않는다.
- after는 요청당 SELECT 0회, 모든 요청이 Redis hit, DB 로딩 및 Redis 오류가 0회다.
- 마지막까지 대상 데이터의 응답이 변하지 않는다.

비로그인 조회만 측정하므로 사용자별 찜 조회는 포함하지 않는다. 로그인 요청은 cache hit에서도 찜 확인 SELECT가 남는다. k6 JSON의 지연 시간은 HTTP 응답 시간이며 클라이언트 JSON 비교 시간은 포함하지 않는다.

## 프로필과 AWS 적용

`cache-benchmark`는 숙소 상세 V1/V2 GET에만 같은 `X-Benchmark-Token`을 요구하고, 다른 업무 API를 차단한다. Kafka 소비·스케줄링·인벤토리 준비·검색 인덱스 준비·외부 서비스 쓰기를 비활성화한다. 운영 요청을 받지 않는 측정용 앱에서 사용한다. 기존 `read-model-benchmark`의 격리 규칙은 변경하지 않는다.

AWS에서는 이 로컬 실행기를 사용하지 않는다. 같은 이미지·데이터·인스턴스 수의 전용 측정 앱에 `aws,cache-benchmark`, 서버 캐시 ON, 분리된 Redis, 토큰을 설정하고 AWS 내부 부하 발생기에서 동일한 k6 클라이언트를 실행해야 한다. 현재 `make lab-start`와 예전 `make aws-discovery`는 이 실험을 자동으로 구성하지 않는다. AWS ALB 연결, fixture 생성·응답 동등성 확인, 예열, 측정 구간별 서버 지표 수집을 연결한 뒤 실행한다.

로컬과 AWS 수치를 직접 비교하지 않는다. AWS에서는 먼저 같은 변형을 반복해 편차를 확인하고, 같은 고정 RPS에서 before/after 순서를 교대한다. 더 높은 처리량을 알아보려면 별도의 RPS 단계별 측정이 필요하다. 이 고정 RPS 실험의 성공 요청 수만으로 최대 처리량을 주장하지 않는다.

## 검증 명령

```bash
k6 run load-test/k6/test/accommodation-cache-benchmark-test.js
bash load-test/k6/test/accommodation-detail-comparison-test.sh
python3 -m unittest discover -s load-test/k6/test -p 'test_cache_local_runner.py'
```
