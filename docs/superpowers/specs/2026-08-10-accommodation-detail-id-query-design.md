# 숙소 상세 ID 기반 연관 조회 설계

## 목적

숙소 상세 조회가 이미 확보한 DB PK `accommodationId`를 이미지와 예약 불가 날짜 조회에도 사용한다. UUID를 조건으로 연관 테이블을 조회할 때 필요한 `accommodation` 조인을 제거하되, UUID만 전달받는 Elasticsearch 색인 경로의 동작과 이벤트 계약은 유지한다.

이번 변경은 쿼리 수를 줄이지 않는다. 동일한 이미지·예약 데이터를 더 직접적인 FK 조건으로 조회하는 것이 목표다.

## 현재 구조와 문제

`AccommodationService#findAccommodation()`은 `accommodationId`로 게시 숙소를 조회한 뒤 이미지와 예약 불가 날짜를 조회할 때 다시 `accommodationUid`를 사용한다.

- 이미지는 `findByAccommodation_AccommodationUidOrderByIdAsc(UUID)`를 호출한다.
- 예약은 `findFutureCompletedReservations(UUID)`를 호출한다.
- 두 조회 모두 연관된 `Accommodation#accommodationUid`를 조건으로 사용하므로 `accommodation` 테이블 조인이 필요하다.
- 서비스가 첫 조회에서 `Accommodation`의 PK를 이미 알고 있으므로 공개·호스트 상세 경로에서는 이 조인이 불필요하다.

예약 UUID 조회는 상세 조회에만 사용되지 않는다. `AccommodationDocumentBuilder`와 `AccommodationIndexUpdater`도 같은 메서드를 사용하며, 예약 변경 Kafka 이벤트는 `accommodationUid`만 전달한다. UUID 메서드를 제거하거나 ID 메서드로 전역 치환하면 색인 갱신에서 ID를 얻기 위한 추가 DB 조회 또는 이벤트 스키마 변경이 필요하다.

## 접근 방식 비교

### 상세용 ID 메서드 추가와 색인용 UUID 메서드 유지 — 채택

예약 저장소에 `accommodationId` 전용 메서드를 추가하고 기존 UUID 메서드는 색인 경로를 위해 유지한다. 상세 조회만 새 메서드를 사용한다. 외부 API, Kafka 이벤트와 Elasticsearch 문서 계약을 바꾸지 않으면서 불필요한 조인을 제거할 수 있다.

### 동일 메서드명 오버로드

`findFutureCompletedReservations(Long)`와 `findFutureCompletedReservations(UUID)`를 함께 둘 수 있다. 그러나 호출부에서 식별자 종류가 이름으로 드러나지 않고 `null` 호출이 모호해진다. 명시적인 ID 메서드명보다 가독성이 낮아 채택하지 않는다.

### 전체 ID 기반 통일

검색 색인 이벤트에 `accommodationId`를 추가하거나 색인 소비자가 UUID로 ID를 다시 조회하게 한다. 장기적으로 식별자 정책을 통일할 수 있지만 이벤트 호환성과 재처리 데이터까지 검토해야 하며, 이번 상세 조회 최적화 범위를 초과하므로 채택하지 않는다.

## 설계

### 이미지 조회

`AccommodationService#getImageUrls()`의 인자를 `UUID`에서 `Long accommodationId`로 변경한다. 공개 숙소 상세와 호스트 숙소 상세 모두 이미 조회한 `Accommodation#getId()`를 전달한다.

저장소의 기존 `findByAccommodationIdOrderByIdAsc(Long)`를 사용한다. 변경 후 사용처가 없어지는 `findByAccommodation_AccommodationUidOrderByIdAsc(UUID)`는 제거한다.

이미지 정렬 순서, 응답 DTO와 빈 목록 동작은 유지한다.

### 예약 불가 날짜 조회

`ReservationRepositoryCustom`에 다음 메서드를 추가한다.

```java
List<Reservation> findFutureCompletedReservationsByAccommodationId(Long accommodationId);
```

기존 메서드는 검색 색인 호환성을 위해 유지한다.

```java
List<Reservation> findFutureCompletedReservations(UUID accommodationUid);
```

두 public 메서드는 숙소 식별 조건만 다르고 다음 조건은 동일해야 한다.

- 예약 상태는 `CONFIRMED`
- `checkOut`은 현재 시각 이상
- 반환형은 기존과 동일하며 예약 조회 자체에는 새로운 정렬을 추가하지 않음

구현에서는 숙소 조건을 `BooleanExpression`으로 받는 private 조회 메서드에 공통 상태·시간 조건을 모은다. ID 메서드는 `reservation.accommodation.id.eq(accommodationId)`를 전달해 예약 테이블의 `accommodation_id` FK를 직접 조건으로 사용한다. UUID 메서드는 기존 `reservation.accommodation.accommodationUid.eq(accommodationUid)` 조건을 전달한다.

`AccommodationService#getUnavailableDates()`는 인자를 `Long accommodationId`로 변경하고 ID 전용 저장소 메서드를 호출한다. 예약 기간을 `LocalDate` 목록으로 펼친 뒤 중복 제거와 정렬을 수행하는 기존 응답 변환은 변경하지 않는다.

### 검색 색인 경로

다음 호출부는 기존 UUID 메서드를 그대로 사용한다.

- `AccommodationDocumentBuilder#getReservationRanges()`
- `AccommodationIndexUpdater#getReservedDates()`

`ReservationChangedEvent`의 `accommodationUid`, Kafka payload, Elasticsearch document ID와 재색인 동작은 변경하지 않는다.

## 데이터 흐름

### 공개·호스트 상세

1. 서비스가 `accommodationId`로 숙소와 to-one 상세 정보를 조회한다.
2. 같은 PK로 이미지 목록을 조회한다.
3. 같은 PK로 미래 확정 예약을 조회한다.
4. 기존 로직으로 예약 불가 날짜와 응답 DTO를 만든다.

### 검색 색인

1. 색인 빌더 또는 예약 변경 소비자가 `accommodationUid`를 받는다.
2. 기존 UUID 예약 조회 메서드를 호출한다.
3. 기존 방식으로 예약 범위 또는 예약 날짜를 Elasticsearch 문서에 반영한다.

## 오류와 호환성

- 존재하지 않는 숙소는 이미지·예약 조회 전에 기존 숙소 조회에서 실패한다.
- 이미지나 미래 예약이 없으면 기존과 동일하게 빈 목록을 반환한다.
- 예약 상태, 현재 시각 경계와 날짜 펼침 의미는 변경하지 않는다.
- API 요청·응답, Kafka 이벤트, Elasticsearch 문서 스키마는 변경하지 않는다.
- 기존 UUID 조회를 제거하지 않으므로 과거 이벤트 재처리와 운영 색인 갱신에 영향이 없다.

## 테스트 전략

### `AccommodationServiceTest`

- 공개 상세 조회가 이미지 저장소의 ID 메서드를 호출하는지 검증한다.
- 공개 상세 조회가 예약 저장소의 ID 전용 메서드를 호출하는지 검증한다.
- 로그인·비로그인 응답과 위시리스트 분기는 기존대로 유지되는지 검증한다.
- 기존 UUID 기반 이미지·예약 mock을 ID 기반으로 변경한다.

### 예약 저장소 검증

- 동일한 숙소에 대해 ID 조회와 기존 UUID 조회가 같은 미래 `CONFIRMED` 예약을 반환하는지 검증한다.
- 다른 숙소, 과거 체크아웃과 비확정 상태 예약이 제외되는지 검증한다.
- 실제 생성 SQL 또는 `EXPLAIN` 확인에서 ID 경로가 `accommodation_uid` 조건을 위한 숙소 조인을 사용하지 않는지 확인한다.

### 회귀 검증

- 관련 숙소 서비스 테스트와 예약 저장소 테스트를 실행한다.
- `./gradlew compileJava`로 QueryDSL 구현과 저장소 계약 컴파일을 검증한다.
- `git diff --check`를 실행한다.

## 범위 제외

- 예약 불가 날짜 projection 도입
- `(accommodation_id, status, check_out)` 복합 인덱스 추가
- 예약 상태 정의와 `CANCELLATION_FAILED` 처리 변경
- 예약 가능 기간 제한 또는 달력 전용 API 분리
- `ReservationChangedEvent`에 accommodation ID 추가
- 검색 색인 조회의 UUID 제거
- 숙소 상세 SQL 개수 축소와 캐시 도입

## 완료 조건

- 공개·호스트 숙소 상세의 이미지 조회가 `accommodationId`를 사용한다.
- 공개 숙소 상세의 예약 불가 날짜 조회가 `accommodationId`를 사용한다.
- 검색 색인의 두 예약 조회 경로는 기존 UUID 메서드를 유지한다.
- 상세 조회의 이미지, 예약 날짜와 응답 계약이 변경 전과 동일하다.
- Kafka 이벤트와 Elasticsearch 문서 계약이 변경되지 않는다.
- 관련 테스트, 컴파일과 `git diff --check`가 통과한다.
