# 게스트 예약 목록 주소 JOIN 제거

대상은 GET /api/v1/profile/guest/reservations의
findMyReservationsByGuestIdWithCursor다. 변경 전 백엔드는 5076071b,
프론트 codex/editable-booking-review는 5661dbc 기준으로 확인했다.

## 소비 확인과 변경

GuestReservationInfo는 예약 식별자·날짜·시간대·상태·생성 시각과 숙소의
ID·이름·썸네일을 반환한다. 프론트의 reservationReadMappers와
toGuestTripCardViewModel도 이 계약을 사용하고 주소에는 접근하지 않는다.
기존 주소 fetch join은 DTO 생성에 필요하지 않았다.

게스트 목록 쿼리에서 accommodation.address의 fetch join만 제거했다.
숙소의 이름·썸네일을 읽는 accommodation fetch join은 유지했다.
응답 DTO, 필터, 정렬, 커서 조건, size+1 조회와 hasNext 계산은 그대로다.
주소가 필요한 게스트·호스트 예약 상세 쿼리는 변경하지 않았다.

## 실제 MySQL에서 확인한 결과

같은 테스트 데이터에서 DTO 조립까지 수행하고 생성된 SQL을 수집했다.
영속성 컨텍스트를 비워 기존에 로딩한 객체가 추가 조회를 가리지 않도록 했다.

| 항목 | 변경 전 | 변경 후 |
|---|---:|---:|
| 목록 페이지와 DTO 조립 SELECT 수 | 1 | 1 |
| 선택 컬럼 수 | 57 | 44 |
| 주소 JOIN | 있음 | 없음 |
| 주소 테이블에서 선택하는 컬럼 | 14 | 0 |

주소 엔티티를 선택하는 대신 숙소의 address_id FK 한 컬럼은 남으므로 전체
선택 컬럼은 13개 감소했다. 예약·숙소 엔티티에는 아직 DTO에서 사용하지 않는
컬럼이 있다. 이번 변경은 주소 JOIN 제거이며 전체 DTO projection 전환은 아니다.

## 검증

- 변경 전 새 회귀 테스트는 UPCOMING·PAST·CANCELLED 모두 실제 주소 JOIN 때문에 실패했다.
  변경 후 세 필터 모두 통과했다.
- 같은 created_at의 ID 내림차순, 다른 회원의 예약 제외, 다음 페이지, 정확히 채워진
  마지막 페이지와 그 이후 빈 페이지, 주소 없는 숙소를 검증했다.
- 각 페이지에서 DTO를 조립한 뒤에도 SELECT가 1회이고 주소 JOIN이 없는지 확인했다.
  숙소명·썸네일·숙박 날짜·예약 상태를 함께 확인했다.
- 기존 MySQL 테스트의 체크아웃 경계·취소/만료 상태 필터와 JSON 응답 회귀를 포함해
  백엔드 19개 테스트 통과, 건너뛴 테스트 없음.
- 프론트 목록 API·mapper·카드·날짜 그룹·커서·프로필 화면 관련 7개 파일,
  59개 테스트 통과. 프론트 production 코드와 DTO 변경은 필요하지 않았다.

인덱스·스키마는 변경하지 않았다. 운영 응답 시간이나 실행계획은 측정하지 않았으며,
확인한 개선은 불필요한 JOIN과 조회 컬럼 감소다. 이번 단계에서 브라우저 E2E나
실제 서버에 연결한 HTTP 통합 검증은 수행하지 않았다.
