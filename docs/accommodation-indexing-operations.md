# Accommodation indexing operations

숙소 검색 문서는 MySQL을 원본 데이터로 삼고, Kafka 이벤트는 "이 UID를 다시 읽어라"는 신호로만 사용한다. 숙소·이미지·리뷰·예약 변경과 삭제는 모두 `ACCOMMODATION_SEARCH_REFRESH_REQUESTED` V1으로 수렴한다. consumer가 현재 MySQL 스냅샷을 읽고 `PUBLISHED`면 전체 문서를 덮어쓰며, 누락·미게시·삭제 상태면 문서를 제거한다.

## Kafka topics

운영 환경에서 토픽 자동 생성이 꺼져 있다면 배포 전에 아래 토픽을 생성한다.

- `ACCOMMODATION_INDEX.events`: Debezium이 outbox refresh 이벤트를 발행하는 원본 토픽
- `ACCOMMODATION_INDEX.events.RETRY`: 일시적인 MySQL/Elasticsearch 실패 재시도
- `ACCOMMODATION_INDEX.events.DLT`: 설정된 횟수만큼 실패한 이벤트 격리

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

`[accommodation-indexing-quarantined]` 알림에는 숙소 UID와 원본 topic/partition/offset만 포함된다. 원문 payload는 알림에 포함하지 않는다.

1. Elasticsearch와 MySQL 상태를 먼저 확인한다.
2. 해당 숙소 UID의 현재 상태와 관련 projection을 MySQL에서 확인한다.
3. 원인을 수정한 뒤 정상 V1 DLT는 canonical payload/key로 재처리한다. poison DLT는 고정된 safe payload로 격리되므로 원문을 재발행하지 말고, DLT의 원본 topic/partition/offset으로 대상을 확인한 뒤 새 canonical refresh 이벤트를 생성한다.
4. 검색 문서가 MySQL의 현재 상태와 일치하는지 확인한다.

DLT 레코드는 조사와 재처리 근거이므로 원인 확인 전에 토픽이나 consumer offset을 삭제하지 않는다.

## Search outage behavior

Elasticsearch가 연결 실패 또는 Elasticsearch 오류 응답을 반환하면 검색 API는 빈 결과처럼 보이는 HTTP 200이 아니라 `SE001`과 HTTP 503을 반환한다. 클라이언트는 이를 "검색 결과 없음"으로 캐시하지 말고 일시 장애로 처리해야 한다.

## Thumbnail projection

게시된 숙소의 썸네일이 업로드 또는 삭제로 바뀌면 이미지 변경과 같은 MySQL 트랜잭션에 단일 refresh outbox 이벤트가 저장된다. 초안·미게시 숙소는 다시 게시할 때 전체 문서 refresh가 생성되므로 이미지 변경 시 별도 이벤트를 만들지 않는다.
