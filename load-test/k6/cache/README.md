# 숙소 상세 Redis 캐시 전후 비교

이 실험은 같은 앱에서 다음 두 메서드를 비교한다. 서버 캐시는 계속 켜 둔다.

| 변형 | API | 실행 경로 |
|---|---|---|
| before | `GET /api/v2/accommodations/{id}` | `AccommodationDetailBenchmarkController.findAccommodationBefore`, DB 직접 조회 |
| after | `GET /api/v1/accommodations/{id}` | `AccommodationController.getAccommodation`, Redis 캐시 조회 |

기존 `traffic/dataset-read.js`는 **V1 API 하나의 서버 캐시 설정 OFF/ON** 실험으로 유지한다. 이 디렉터리는 V2/V1 비교 전용이며, 두 실험의 결과를 섞지 않는다.

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
