# Accommodation indexing operations

숙소 검색 문서는 MySQL을 원본 데이터로 삼고, Kafka 이벤트는 "이 UID를 다시 읽어라"는 신호로만 사용한다. 생성·수정·리뷰·예약 변경은 같은 전체 문서 갱신 경로로 수렴하고, 삭제만 Elasticsearch 문서를 제거한다.

## Kafka topics

운영 환경에서 토픽 자동 생성이 꺼져 있다면 배포 전에 아래 토픽을 생성한다.

- `ACCOMMODATION.events`: Debezium이 outbox 이벤트를 발행하는 원본 토픽
- `ACCOMMODATION.events.RETRY`: 일시적인 MySQL/Elasticsearch 실패 재시도
- `ACCOMMODATION.events.DLT`: 설정된 횟수만큼 실패한 이벤트 격리

기본값은 총 4회 시도, 시도 간격 30초다. `ACCOMMODATION_INDEXING_KAFKA_ATTEMPTS`와 `ACCOMMODATION_INDEXING_KAFKA_BACKOFF_MS`로 조정할 수 있다.

## Full reindex

전체 MySQL projection을 다시 적재할 때는 Logstash를 live alias에 직접 연결하지 않는다.
숙소 색인 consumer를 중지하고 버전 인덱스를 채운 뒤 alias를 원자 전환하는 절차는
[`docs/logstash-reindex.md`](logstash-reindex.md)를 따른다. consumer 재개 후 Kafka lag가
0으로 돌아와야 새 인덱스가 MySQL 최신 상태로 수렴한 것이다.

재색인 스크립트는 one-shot Logstash 컨테이너를 분리 실행하고 기본 3,600초의 runtime
watchdog으로 감시한다. 제한 시간이나 비정상 exit가 발생하면 캡처한 컨테이너만
정리하고 alias 전환 전에 중단한다. 실패한 대상 인덱스는 자동 삭제하지 않으므로,
원인 확인 전까지 수동으로 alias를 전환하거나 인덱스를 삭제하지 않는다.

## DLT response

`[accommodation-indexing-quarantined]` 알림에는 이벤트 타입, 숙소 UID, 원본 topic/partition/offset만 포함된다.

1. Elasticsearch와 MySQL 상태를 먼저 확인한다.
2. 해당 숙소 UID가 MySQL에 존재하는지 확인한다. 삭제된 숙소라면 삭제 이벤트인지 확인한다.
3. 원인을 수정한 뒤 DLT 레코드의 원본 payload와 key를 `ACCOMMODATION.events`에 다시 발행한다.
4. 검색 문서가 MySQL의 현재 상태와 일치하는지 확인한다.

DLT 레코드는 조사와 재처리 근거이므로 원인 확인 전에 토픽이나 consumer offset을 삭제하지 않는다.

## Search outage behavior

Elasticsearch가 연결 실패 또는 Elasticsearch 오류 응답을 반환하면 검색 API는 빈 결과처럼 보이는 HTTP 200이 아니라 `SE001`과 HTTP 503을 반환한다. 클라이언트는 이를 "검색 결과 없음"으로 캐시하지 말고 일시 장애로 처리해야 한다.

## Thumbnail projection

게시된 숙소의 썸네일이 업로드 또는 삭제로 바뀌면 이미지 변경과 같은 MySQL 트랜잭션에 `ACCOMMODATION_UPDATED` outbox 이벤트가 저장된다. 초안·미게시 숙소는 다시 게시할 때 전체 문서 갱신 이벤트가 생성되므로 이미지 변경 시 별도 색인 이벤트를 만들지 않는다.
