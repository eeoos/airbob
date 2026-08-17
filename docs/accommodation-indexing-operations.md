# Accommodation indexing operations

숙소 검색 문서는 MySQL을 원본으로 삼고, Kafka 레코드는 “이 숙소 UID의 현재 상태를 다시
읽어라”는 wake-up signal로만 사용한다. 숙소·이미지·리뷰·예약의 색인 관련 변경은 모두
[`AccommodationSearchRefreshRequestedV1`](../src/main/java/kr/kro/airbob/search/messaging/event/AccommodationSearchRefreshRequestedV1.java)로
수렴한다.

[`AccommodationSearchRefreshListener`](../src/main/java/kr/kro/airbob/search/messaging/kafka/AccommodationSearchRefreshListener.java)가
이벤트를 받은 뒤
[`AccommodationSearchSnapshotReader`](../src/main/java/kr/kro/airbob/search/service/AccommodationSearchSnapshotReader.java)로
현재 MySQL snapshot을 읽는다. 현재 `PUBLISHED`면 전체 문서를 저장하고, 누락·미게시·삭제
상태면 Elasticsearch 문서를 제거한다. 이벤트 생성 당시의 숙소 필드를 payload로
전달하지 않으므로 늦게 도착한 이벤트가 최신 문서를 과거 상태로 되돌리지 않는다.

## Kafka topics

topic 자동 생성은 운영에서 꺼져 있다. 배포 bootstrap이 다음 세 topic을 같은 partition
수로 생성했는지 확인한다.

- `ACCOMMODATION_INDEX.events`
- `ACCOMMODATION_INDEX.events.RETRY`
- `ACCOMMODATION_INDEX.events.DLT`

기본값은 총 4회 시도, 시도 간격 30초다.
`ACCOMMODATION_INDEXING_KAFKA_ATTEMPTS`와
`ACCOMMODATION_INDEXING_KAFKA_BACKOFF_MS`로 조정할 수 있다. retry/DLT publisher는
원본의 framework retry header를 정리해 header가 무한히 누적되지 않게 한다.

## Alias bootstrap과 listener readiness

애플리케이션은 concrete index `accommodations`를 직접 쓰지 않는다. 시작 시
[`ElasticsearchAccommodationIndexAliasBootstrap`](../src/main/java/kr/kro/airbob/search/infrastructure/elasticsearch/ElasticsearchAccommodationIndexAliasBootstrap.java)이
다음을 확인한다.

1. `accommodations` alias가 정확히 하나의 `accommodations-v*` index를 가리킨다.
2. 그 alias target이 write index다.
3. alias가 없으면 canonical mapping으로 `accommodations-vbootstrap`과 write alias를 함께
   만든다.
4. 같은 이름의 concrete index나 불완전한 bootstrap index가 있으면 자동으로 덮지 않고
   기동을 실패시킨다.

alias readiness가 확보된 뒤에만 색인 listener가 자동 시작된다. readiness 실패를
consumer lag 문제로 오해하지 말고 alias/index 상태를 먼저 복구한다.

## Full reindex

전체 MySQL projection을 다시 적재할 때 Logstash를 live alias에 직접 연결하지 않는다.
색인 listener를 중지하고 canonical mapping의 새 version index를 채운 뒤 검증하고 alias를
원자 전환한다. 전체 절차는 [`logstash-reindex.md`](logstash-reindex.md)를 따른다.

one-shot Logstash 컨테이너에는 기본 3,600초 runtime watchdog이 있다. timeout이나 비정상
exit이면 해당 실행 컨테이너만 정리하고 alias 전환 전에 중단한다. 실패한 대상 index는
자동 삭제하지 않으므로 원인 확인 전에 alias를 수동 전환하거나 index를 삭제하지 않는다.

alias 전환 뒤 listener를 재개하고 `ACCOMMODATION_INDEX.events` consumer lag가 0으로
돌아올 때까지 기다린다. 마지막으로 대표 문서를 MySQL snapshot과 비교한다.

## DLT response

DLT handler는 원인 사건과 별도의 새 트랜잭션에서 durable operator alert를 outbox에
저장하고, 그 commit이 성공한 뒤 DLT record를 ACK한다. 알림에는 가능한 경우 숙소 UID와
원본 topic/partition/offset만 포함한다. payload, exception message, retry header는 알림에
복사하지 않는다.

DLT에서 색인 이벤트를 자동 재발행하지 않는 이유는 장애가 해결되기 전 retry loop를 다시
만들지 않기 위해서다.

1. Elasticsearch alias/readiness와 MySQL 연결 상태를 확인한다.
2. 원본 좌표로 incident를 찾고, UID가 안전하게 식별되면 MySQL의 현재 숙소 상태를
   확인한다.
3. 원인을 해결한다.
4. DLT value/header를 main topic에 복사하지 않는다. 애플리케이션의 canonical publisher를
   통해 해당 UID의 **새** refresh V1을 만들거나 승인된 전체 reindex를 수행한다.
5. 현재 MySQL snapshot과 Elasticsearch 문서가 일치하는지 확인한다.

poison DLT는 숙소 UID가 없을 수 있다. 이때 임의 payload를 복원하거나 추측하지 않고 원본
좌표와 upstream 변경 이력으로 대상을 확인한다. DLT topic과 consumer offset은 조사 증거를
보존할 때까지 삭제하지 않는다.

## Search outage behavior

Elasticsearch 연결 실패나 오류 응답은 “검색 결과 없음”이 아니다. 검색 API는 `SE001`과
HTTP 503을 반환한다. 클라이언트는 이를 빈 검색 결과로 캐시하지 않고 재시도 가능한 장애로
취급한다.

## Thumbnail projection

게시된 숙소의 썸네일이 업로드·삭제되면 이미지 변경과 같은 MySQL 트랜잭션에 단일 refresh
outbox 이벤트를 저장한다. 초안·미게시 숙소는 다시 게시할 때 전체 document refresh가
생기므로 이미지 변경 시 별도 색인 이벤트를 만들지 않는다.
