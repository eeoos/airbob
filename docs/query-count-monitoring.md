# API Query Count Monitoring

Airbob는 HTTP 요청 단위로 Hibernate/JPA SQL 실행 횟수를 수집해 Prometheus metric으로 노출한다. 목적은 N+1 후보 API를 찾고, fetch join/entity graph/batch size 같은 개선 전후의 쿼리 수 변화를 같은 조건에서 측정하는 것이다.

## Scope

- 포함: Spring MVC 요청 스레드에서 Hibernate가 실행하는 JPA/QueryDSL SQL
- 제외: `JdbcTemplate`, native JDBC, `@Async`, Kafka consumer, scheduler, 요청 밖 background job
- 제외 path: `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/error`, static resource

Spring MVC async 요청은 최초 dispatch에서 센 쿼리와 종료 ASYNC redispatch에서 센 쿼리를 같은 request 상태로 합쳐 한 번만 기록한다. Handoff 때 컨테이너 스레드의 `ThreadLocal`은 즉시 제거하며 async worker로 전파하지 않으므로, worker 내부에서 실행한 SQL은 포함되지 않는다. Timeout/error가 Spring의 종료 redispatch로 이어지면 같은 상태를 복원하고, redispatch 없이 container가 바로 완료하면 `AsyncListener.onComplete`가 분리된 상태를 한 번 기록한다. `onTimeout`/`onError`에서는 이후 redispatch보다 먼저 표본을 확정하지 않고, 실제 terminal callback에서만 atomic하게 완료한다.

아주 짧은 async 작업이 `afterConcurrentHandlingStarted`의 listener 등록보다 먼저 끝나 등록 자체가 `IllegalStateException`으로 경합하고, 동시에 container가 종료 redispatch도 제공하지 않는 경우에는 안전하게 호출할 terminal callback이 남지 않는다. 이때 dispatch 예정인지 완전 종료인지 Servlet API로 구분할 수 없으므로, 불완전한 표본을 성급히 기록해 이중 집계하는 대신 해당 표본을 누락한다. 일반 Spring MVC `Callable`/`DeferredResult`의 timeout/error는 종료 redispatch를 수행하고, 정상적인 no-redispatch `complete()`는 등록된 `onComplete` fallback으로 처리된다.

## Metric

Micrometer meter name은 `app.query.per_request`이고, Prometheus scrape 이름은 base unit 때문에 아래처럼 노출된다.

- `app_query_per_request_queries_bucket`
- `app_query_per_request_queries_count`
- `app_query_per_request_queries_sum`
- `app_query_per_request_queries_max`

Tags:

- `path`: Spring MVC route template. 예: `/api/v1/accommodations/{accommodationId}`
- `http_method`: `GET`, `POST`, `PUT`, `DELETE` 등
- `query_type`: `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `OTHER`, `TOTAL`
- `application`: 운영 profile 공통 tag. 예: `airbob-api`

Histogram bucket은 Prometheus에서 여러 인스턴스 bucket을 합산할 수 있도록 `publishPercentileHistogram()` 기반으로 내보낸다. client-side `publishPercentiles()`는 사용하지 않는다.

현재 명시 bucket은 `1, 3, 5, 10, 20, 50, 100, 200, 250, 500, 1000`이고 `maximumExpectedValue`도 `1000`이다. 따라서 R=201 benchmark 경계를 넘는 요청도 `+Inf`에만 들어가지 않고 유한한 bucket에 기록된다. Micrometer 1.15는 SLO boundary 0을 허용하지 않지만, `record(0)`은 가능하므로 쿼리 0회 요청도 표본으로 들어간다.

## PromQL

p95 SELECT query count per request:

```promql
histogram_quantile(
  0.95,
  sum by (le, path, http_method) (
    rate(app_query_per_request_queries_bucket{query_type="SELECT"}[5m])
  )
)
```

p95 total query count per request:

```promql
histogram_quantile(
  0.95,
  sum by (le, path, http_method) (
    rate(app_query_per_request_queries_bucket{query_type="TOTAL"}[5m])
  )
)
```

Average total query count per request:

```promql
sum by (path, http_method) (
  rate(app_query_per_request_queries_sum{query_type="TOTAL"}[5m])
)
/
sum by (path, http_method) (
  rate(app_query_per_request_queries_count{query_type="TOTAL"}[5m])
)
```

N+1 후보 API top 10:

```promql
topk(
  10,
  histogram_quantile(
    0.95,
    sum by (le, path, http_method) (
      rate(app_query_per_request_queries_bucket{query_type="SELECT"}[5m])
    )
  )
)
```

운영 profile에서는 `application="airbob-api"` tag가 붙으므로, 여러 앱이 같은 Prometheus에 들어오면 selector에 `application="airbob-api"`를 추가한다.

## Measurement Protocol

N+1 개선 효과를 k6로 비교할 때는 ASG를 `min=1`, `max=1`로 고정한다. ASG가 늘어나면 처리량, DB connection 분산, cache warmup 차이 때문에 개선 효과가 코드 변경 때문인지 scale-out 때문인지 분리하기 어렵다.

권장 순서:

1. 같은 AWS instance type, 같은 DB dataset, 같은 k6 scenario를 사용한다.
2. ASG `min=1`, `max=1`로 고정한다.
3. warmup traffic을 먼저 흘린다.
4. 개선 전 branch에서 latency와 `TOTAL`/`SELECT` p95 query count를 기록한다.
5. 개선 후 branch에서 같은 조건으로 다시 측정한다.
6. 필요하면 운영 관찰 단계에서만 ASG를 여러 대로 돌리고 histogram bucket을 합산해 본다.

멀티 인스턴스 운영 관찰은 이 metric 구조로 가능하다. 다만 개선 전후 실험 자체는 scale 변수를 고정하는 편이 맞다.

## Local Check

운영 profile처럼 Prometheus endpoint가 노출된 상태에서 API를 호출한 뒤 확인한다.

```bash
curl -s http://localhost:8080/actuator/prometheus | rg 'app_query_per_request_queries'
```

route template이 아니라 실제 id가 `path` tag에 들어가면 안 된다. 예를 들어 `/api/v1/accommodations/10`이 아니라 `/api/v1/accommodations/{accommodationId}`로 보여야 한다.

## Local Compose Monitoring

로컬 Spring Boot application은 Docker container가 아니라 host의 `8080` port에서 실행한다. application이 실행 중인 상태에서 monitoring profile의 Prometheus와 Grafana만 시작한다.

```bash
docker compose --profile monitoring up -d prometheus grafana
```

Prometheus의 `/-/ready` healthcheck가 통과한 뒤 Grafana가 시작되고, Grafana는 `/api/health`로 준비 상태를 확인한다. `docker compose ps`에서 두 서비스가 모두 `healthy`인지 확인한다.

- Prometheus: <http://localhost:9091>
- Prometheus targets: <http://localhost:9091/targets>
- Grafana: <http://localhost:3001>
- Grafana 초기 계정: `admin` / `admin`

로컬 초기 암호는 첫 로그인 때 반드시 변경한다. Prometheus targets 화면에서 `airbob` job이 `UP`인지 확인한 뒤 실제 API를 한 번 이상 호출하고, application endpoint와 Prometheus query API 양쪽에서 metric을 확인한다.

Prometheus와 Grafana UI는 loopback에만 열리지만, `/actuator/prometheus`의 접근 범위는 host에서 실행한 Spring Boot application의 `8080` bind/firewall 설정을 따른다. 신뢰할 수 없는 네트워크에서는 application port를 외부에 노출하지 않는다.

```bash
curl -s http://localhost:8080/actuator/prometheus \
  | rg 'app_query_per_request_queries'

curl -fsSG http://localhost:9091/api/v1/query \
  --data-urlencode 'query=app_query_per_request_queries_count{job="airbob",application="airbob-api"}'
```

target이 `DOWN`이면 먼저 host에서 application이 `8080` port로 실행 중인지 확인한다. API traffic이 없으면 query-count series가 아직 생성되지 않았을 수 있다.

### Auto-provisioned dashboard

Grafana를 시작하면 `Airbob - API Query Count` dashboard(`uid=airbob-query-count`)가 자동 provisioning되므로 JSON을 수동 import할 필요가 없다. 첫 번째 row는 강의의 API별 쿼리 수 관찰 방식에 맞춰 다음 panel을 제공한다.

- `Average queries per request`: `_sum / _count`로 path, HTTP method, query type별 평균 쿼리 수를 계산한다.
- `p50 / p95 queries per request`: `app_query_per_request_queries_bucket`에 `histogram_quantile()`을 적용한다.

두 번째 row는 N+1 후보를 찾고 표본 크기를 함께 판단하기 위한 panel을 제공한다.

- `Top 10 N+1 candidates (p95 SELECT)`: `query_type="SELECT"`의 p95가 높은 API 열 개를 표시한다.
- `Measured requests in range`: 선택한 시간 범위에서 `query_type="TOTAL"`의 `app_query_per_request_queries_count` 증가량을 합산한다. 이는 Prometheus scrape 횟수가 아니라 실제로 metric에 기록된 HTTP 요청 표본 수다.

세 번째 row는 `API response time p95` panel을 제공한다. 이 panel은 성공한 요청의 `http_server_requests_seconds_bucket`에 `histogram_quantile(0.95, ...)`를 적용해 Spring MVC 서버 내부 처리시간 p95를 표시한다. Dashboard의 HTTP method와 path 변수를 함께 사용하지만, 클라이언트 네트워크 시간은 포함하지 않는다.

Dashboard가 사용하는 Prometheus histogram series는 `app_query_per_request_queries_bucket`, `app_query_per_request_queries_count`, `app_query_per_request_queries_sum`이며, 관측 구간의 최댓값은 `app_query_per_request_queries_max`로도 노출된다.

`histogram_quantile(0.95, ...)`의 p95는 개별 요청을 정렬해 구한 정확한 percentile이 아니다. Prometheus가 p95가 속한 bucket의 경계 사이를 보간해 계산한 근삿값이므로, 쿼리 횟수가 정수여도 결과가 소수일 수 있다. Dashboard query는 먼저 `sum by (le, http_method, path, query_type)`로 instance별 cumulative bucket을 합친 다음 percentile을 계산한다. 따라서 여러 application instance의 histogram을 합산할 수 있지만, bucket 내부 분포를 알 수 없어 생기는 보간 오차는 남는다.

Top 10 panel만 SELECT로 고정하고 나머지 첫 row panel은 dashboard의 HTTP method, path, query type 변수를 따른다. `Measured requests in range`를 함께 확인해 표본이 너무 적은 API의 높은 p95를 N+1 신호로 과대 해석하지 않는다.

Grafana의 응답시간 p95는 실제 트래픽을 지속 관찰하기 위한 서버 측 지표다. 개선 전후 비교에서는 k6가 같은 요청률을 만들고 클라이언트 관점의 `http_req_duration`을 기록하며, 같은 시간대의 Grafana panel에서 서버 응답시간과 쿼리 수를 함께 확인한다. 두 값은 측정 지점이 다르므로 어느 한쪽이 다른 쪽을 대체하지 않는다.

## OCI Compose Monitoring

OCI에서는 Grafana 초기 관리자 암호를 `.env.oci`에 필수로 설정한다. 실제 암호 파일은 commit하지 않는다.

```dotenv
GRAFANA_ADMIN_PASSWORD=replace-with-a-strong-password
```

OCI Compose file의 Prometheus와 Grafana를 시작한다.

```bash
docker compose --env-file .env.oci -f docker-compose.oci.yml up -d prometheus grafana
```

OCI에서도 Grafana는 Prometheus의 readiness healthcheck가 통과한 뒤 시작한다. `docker compose --env-file .env.oci -f docker-compose.oci.yml ps`에서 두 서비스의 `healthy` 상태를 확인한다.

두 UI port는 OCI instance의 loopback에만 bind되므로 인터넷에서 직접 접근할 수 없다. 로컬 terminal에서 다음 SSH tunnel을 열어 둔다.

```bash
ssh -N \
  -i ~/.ssh/airbob-oci.key \
  -L 3002:127.0.0.1:3000 \
  -L 9093:127.0.0.1:9090 \
  ubuntu@140.245.76.140
```

Tunnel이 열린 동안 다음 주소로 접근한다.

- Grafana: <http://localhost:3002> — user `admin`, password는 `.env.oci`의 `GRAFANA_ADMIN_PASSWORD`
- Prometheus: <http://localhost:9093>
- Prometheus targets: <http://localhost:9093/targets>

OCI에서도 같은 `Airbob - API Query Count` dashboard가 자동 provisioning된다. Target 상태와 metric은 local 절차에서 port만 각각 `9093`, 필요 시 application 대신 Prometheus query API로 바꾸어 확인한다.

Loopback bind는 Prometheus/Grafana 관리 UI가 OCI public ingress에 직접 노출되는 것을 막는다. 또한 public Nginx는 정확한 `/actuator`와 `/actuator/` 하위 요청에 `404`를 반환한다. Prometheus는 Docker network 안에서 application의 actuator endpoint를 직접 scrape하므로 이 외부 차단의 영향을 받지 않으며, application container를 직접 호출하는 Docker healthcheck도 그대로 동작한다.
