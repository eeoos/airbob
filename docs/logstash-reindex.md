# Logstash Accommodation Reindex

`accommodations`는 물리 인덱스가 아니라 애플리케이션이 읽고 쓰는 alias다. 재색인은
`accommodations-vYYYYMMDDhhmmss` 버전 인덱스를 별도로 채운 뒤 alias를 원자 전환한다.
검색 요청은 작업 내내 기존 인덱스를 사용한다.

## Safety Contract

- 모든 애플리케이션 인스턴스에서 숙소 색인 consumer를 먼저 중지한다.
- consumer offset은 유지하며 `ACCOMMODATION.events`의 변경 이벤트를 Kafka에 쌓아 둔다.
- Logstash는 `LOGSTASH_TARGET_INDEX`가 없으면 시작되지 않으며 live alias에 직접 쓰지 않는다.
- MySQL의 공개 숙소 건수와 새 인덱스 문서 건수가 다르면 alias를 변경하지 않는다.
- 재색인 도중 다른 작업이 alias를 바꾸면 현재 작업은 alias를 변경하지 않는다.
- Logstash는 분리 실행한 단일 컨테이너 ID로만 추적한다. 제한 시간을 넘기면 그 컨테이너만
  중지·제거하고 alias를 변경하지 않는다.
- alias 전환 요청 전까지 실패하면 기존 alias와 인덱스를 유지한다. 실패한 새 인덱스도 조사 전에는 삭제하지 않는다.
- 전환 후 consumer를 재개하면 쌓인 이벤트가 MySQL 최신 상태로 새 인덱스를 보정한다.

검색은 중단되지 않지만 consumer를 멈춘 동안에는 기존 검색 결과가 일시적으로 오래될 수 있다.

## Prerequisites

실행 호스트에 `bash`, `curl`, `jq`, Docker Compose가 필요하다. MySQL volume이 이미
생성되어 있다면 Logstash 전용 읽기 계정을 만든다.

```sql
CREATE USER IF NOT EXISTS 'logstash'@'%' IDENTIFIED BY 'logstash';
GRANT SELECT ON airbobdb.* TO 'logstash'@'%';
FLUSH PRIVILEGES;
```

`docker/mysql/init`으로 새 MySQL을 초기화하면 계정이 자동 생성된다.

## One-time Alias Bootstrap

애플리케이션은 `accommodations` 물리 인덱스를 자동 생성하지 않는다. 인덱스와 alias가
모두 없는 환경에서는 아래 재색인 명령이 첫 버전 인덱스와 alias를 함께 생성한다.

이전 버전이 만든 `accommodations` 물리 인덱스가 있으면 스크립트는 데이터를 삭제하지
않고 중단한다. 현재 환경처럼 데이터 적재 전인 경우에만 문서 건수가 0인지 확인한 후
물리 인덱스를 한 번 제거하고 bootstrap한다.

```bash
curl 'http://localhost:9200/accommodations/_count?pretty'
curl -X DELETE 'http://localhost:9200/accommodations'
```

데이터가 있는 legacy 물리 인덱스는 위 명령으로 삭제하지 말고 별도 보존·전환 계획을
세워야 한다.

## Local Reindex

1. MySQL과 Elasticsearch를 시작한다.

```bash
docker compose up -d mysql elasticsearch
```

2. 모든 애플리케이션 인스턴스를 다음 설정으로 기동해 숙소 색인 consumer만 멈춘다.

```text
ACCOMMODATION_INDEXING_AUTO_STARTUP=false
```

3. 중지 상태를 확인한 운영자가 안전 확인 변수를 지정해 재색인한다.

```bash
CONFIRM_INDEXING_CONSUMER_PAUSED=true \
./scripts/reindex-accommodations.sh
```

4. 출력된 alias와 문서 수를 확인한 뒤 `ACCOMMODATION_INDEXING_AUTO_STARTUP=true`로
   모든 인스턴스의 consumer를 재개한다. `accommodation-indexing-group` lag가 0으로
   돌아오는지 확인한다.

## OCI Reindex

동일한 순서로 consumer를 중지한 후 OCI Compose 파일을 지정한다.

```bash
COMPOSE_FILE=docker-compose.oci.yml \
CONFIRM_INDEXING_CONSUMER_PAUSED=true \
LOGSTASH_JDBC_USER=logstash \
LOGSTASH_JDBC_PASSWORD='...' \
./scripts/reindex-accommodations.sh
```

Elasticsearch 보안이 활성화된 환경에서는 다음 변수도 함께 지정한다.

```text
ELASTICSEARCH_URL=https://...
ELASTICSEARCH_USERNAME=...
ELASTICSEARCH_PASSWORD=...
```

Elasticsearch API의 기본 연결 제한은 5초, 요청 전체 제한은 30초다.
`ELASTICSEARCH_CONNECT_TIMEOUT_SECONDS`와 `ELASTICSEARCH_MAX_TIME_SECONDS`로 조정할 수 있다.

Logstash 적재 제한 시간은 기본 3,600초이고 상태 확인 간격은 기본 2초다. 데이터 규모와
평상시 적재 시간을 기준으로 아래 값을 조정할 수 있다.

```text
LOGSTASH_MAX_RUNTIME_SECONDS=3600
LOGSTASH_POLL_INTERVAL_SECONDS=2
```

스크립트는 `docker compose run -d`가 반환한 컨테이너 ID를 검증한 뒤 그 ID만
`inspect`, `logs`, `stop`, `rm` 대상으로 사용한다. Logstash가 제한 시간을 넘기거나
0이 아닌 코드로 종료되면 로그를 출력하고 비정상 종료한다. 이때 alias는 기존 인덱스를
계속 가리키며 실패한 버전 인덱스는 원인 조사와 재실행을 위해 보존된다. 스크립트를
중단해도 종료 trap은 캡처한 컨테이너만 제거하며 다른 Compose 컨테이너를 중지하지 않는다.

## Verification

```bash
curl 'http://localhost:9200/_alias/accommodations?pretty'
curl 'http://localhost:9200/accommodations/_count?pretty'
curl 'http://localhost:9200/accommodations/_search?size=1&pretty'
```

alias는 정확히 하나의 `accommodations-v...` 인덱스를 가리키며 해당 인덱스의
`is_write_index`가 `true`여야 한다.

## Post-Deploy Monitoring & Validation

consumer를 재개한 운영자가 최소 30분 동안 다음 항목을 확인한다.

- `accommodation-indexing-group` lag가 0으로 수렴하고 다시 증가하지 않는다.
- `ACCOMMODATION.events.DLT` 유입과 `accommodation-indexing-quarantined` 알림이 없다.
- 검색 API의 `SE001`/HTTP 503 비율이 배포 전 기준보다 증가하지 않는다.
- `GET /_alias/accommodations` 결과가 정확히 하나의 버전 인덱스와
  `is_write_index: true`를 유지한다.
- MySQL 공개 숙소 수와 `GET /accommodations/_count` 결과가 일치한다.

DLT 유입, 지속적인 lag, 검색 오류율 증가, alias 또는 문서 수 불일치가 발견되면
consumer를 중지하고 아래 rollback 절차를 적용한다.

재색인 출력에 `Logstash exceeded maximum runtime` 또는 `Logstash exited with code`가
나오면 consumer를 재개하거나 alias를 수동 전환하지 않는다. 출력된 Logstash 로그와
보존된 대상 인덱스를 조사한 뒤 새 버전 이름으로 처음부터 다시 실행한다.

## Rollback

새 인덱스에서 문제가 발견되면 먼저 모든 숙소 색인 consumer를 다시 중지한다. 아래의
`CURRENT_INDEX`와 `PREVIOUS_INDEX`를 실제 인덱스명으로 바꾸어 한 요청으로 되돌린다.

```bash
curl -X POST 'http://localhost:9200/_aliases' \
  -H 'Content-Type: application/json' \
  -d '{
    "actions": [
      {"remove": {"index": "CURRENT_INDEX", "alias": "accommodations", "must_exist": true}},
      {"add": {"index": "PREVIOUS_INDEX", "alias": "accommodations", "is_write_index": true}}
    ]
  }'
```

consumer를 재개하고 Kafka lag와 검색 오류율을 확인한다. 이전 인덱스는 롤백 관찰 기간이
끝날 때까지 보존한다.

alias 전환 요청이 승인된 뒤 검증 호출만 실패했다면 자동 롤백하지 않는다. 먼저
`GET /_alias/accommodations`로 실제 대상을 확인한 뒤 위 절차를 적용한다.

## Cleanup

alias가 가리키는 인덱스를 먼저 확인한다. 현재 대상이 아닌 이전 버전만 명시적인 이름으로
삭제한다. wildcard 삭제는 사용하지 않는다.

```bash
curl 'http://localhost:9200/_alias/accommodations?pretty'
curl -X DELETE 'http://localhost:9200/accommodations-vYYYYMMDDhhmmss'
```

## Indexed Fields

파이프라인은 다음 MySQL 데이터를 읽는다.

- `accommodation`, `address`, `occupancy_policy`
- `accommodation_review_summary`
- `accommodation_amenity.amenity_code`
- 검색 가용 기간과 겹치는 확정·취소 처리 중 예약

`logstash/config/elasticsearch/accommodations-index.json`이 버전 인덱스의 설정과
엄격한 mapping을 정의한다. 문서 필드를 바꿀 때 `AccommodationDocument`, Logstash SQL과
이 파일을 같은 변경에서 갱신해야 한다.
