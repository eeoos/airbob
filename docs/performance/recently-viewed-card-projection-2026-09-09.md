# 최근 본 숙소 카드 조회 컬럼 축소

## 범위와 판단

`GET /api/v1/members/recently-viewed`의 숙소·주소 전체 엔티티 조회를
`RecentlyViewedAccommodationProjection`의 9개 스칼라 필드 조회로 변경했다.
[앞선 조회 통합](recently-viewed-read-consolidation-2026-09-08.md)에서 정상 목록의
SQL을 3회에서 2회로 줄인 뒤 남아 있던 엔티티 로딩을 정리하는 단계다.

프론트는 `codex/editable-booking-review` 작업트리에서 점검했다. 원격 main을
다시 fetch한 결과 `f80bc095`였고, 작업트리 HEAD `6542dd7a`에 이미 포함되어
있었다. 프론트 운영 코드와 응답 JSON 계약은 변경하지 않았다.

| 응답 데이터 | 현재 프론트 사용 | 조회 원천 |
|---|---|---|
| 숙소 ID | 상세 이동, 찜 대상, 최근 기록 삭제 | accommodation.id |
| 이름·썸네일 | 카드 제목·사진·접근성 이름 | accommodation의 2개 컬럼 |
| 주소 요약 | city·district 표시, 없으면 country 사용 | address의 4개 컬럼 |
| 후기 수·평균 | 후기 표시 여부, 수·평점 표시 | accommodation_review_summary의 2개 컬럼 |
| viewed_at | 최근 순서, 날짜별 묶음, 목록 요약 | Redis sorted set score |
| is_in_wishlist | 저장/변경 버튼 및 찜 상태 | 회원별 숙소 ID 일괄 조회 |
| total_count | 프론트 컬렉션 모델 | 반환 목록 크기 |

`address_summary.state`는 어댑터와 모델에서 보존하지만 현재 카드에는 직접
표시하지 않는다. 공통 주소 응답 계약을 유지하기 위해 이번 컬럼 축소에 포함했다.
`total_count` 역시 어댑터에서 보존하며 화면은 주로 목록 길이를 사용한다.
이 두 필드를 현재 카드의 필수 표시 정보라고 판단한 것은 아니다.

각 숙소에 주소와 후기 요약이 최대 하나씩 매칭되므로 LEFT JOIN 한 번으로 읽는다.
찜은 동일 숙소가 한 회원의 여러 위시리스트에 들어갈 수 있다. 현재의 회원별
ID 일괄 조회는 중복을 제거하며 카드 행 수를 늘리지 않으므로 분리를 유지했다.
조회 시각과 순서는 Redis가 관리하며 DB 결과는 ID로 매핑해 원래 Redis 순서로 조립한다.

## 변경과 보존한 동작

- 숙소 ID·이름·썸네일, 주소 4개 필드, 후기 수·평균만 선택한다.
  숙소 설명·가격·시간·정책, 상세 주소·좌표, 감사 컬럼은 읽지 않는다.
- 기존 엔티티 기반 `AccommodationDetailProjection`은 마지막 사용처를 대체한 후 제거했다.
- 공개 숙소 필터, 동일 조회 시각에서의 Redis 순서, 원래 조회 시각을 유지한다.
- 삭제·비공개·미존재 숙소는 요청 회원의 기록에서만 정리한다.
- 주소가 없으면 필드가 null인 주소 요약 객체를 반환한다. 후기 요약 행이 없으면
  null, 0건인 요약 행이 있으면 수·평균이 0인 객체를 유지한다.
- 별도 회원의 찜 상태와 기록을 구분하고, 이름·주소·후기·공개 상태·찜 변경을
  다음 조회에 반영한다.
- 주소 지연 로딩 비교용 `getRecentlyViewedBefore`와 엔티티 기반 DTO 변환은 유지한다.
  ES 검색, 인덱스, 스키마, 기록 저장·삭제 API는 변경하지 않았다.

## 측정과 검증

MySQL 8.4.11과 Redis 7.2 Testcontainers에서 영속성 컨텍스트 및 Hibernate 통계를
비운 뒤 서비스 호출부터 DTO 조립까지 측정했다. 동일한 혼합 기록 fixture에서
공개 숙소 3개가 반환되며 그중 2개는 같은 주소를 공유하고 1개는 주소가 없다.
인증 필터의 DB 읽기와 Redis 명령은 SQL 횟수에서 제외한다.

| 측정 항목 | 변경 전 | 변경 후 |
|---|---:|---:|
| 숙소·주소·후기 조회 선택 컬럼 | 34 | 9 |
| 찜 조회 선택 컬럼 | 1 | 1 |
| 위 fixture의 엔티티 로딩 수 | 4 | 0 |
| 유효한 숙소가 있는 목록의 SQL | 2 | 2 |
| 비공개·미존재 숙소만 있는 목록의 SQL | 1 | 1 |
| Redis 기록이 없는 목록의 SQL | 0 | 0 |

변경 전 엔티티 4개는 숙소 3개와 공유 주소 1개다. 변경 후 테스트에서
SQL 2회·선택 컬럼 9/1개·엔티티 0개를 검증한다. 기존 JSON fixture 비교와
벤치마크 응답 비교, 빈 기록, 비공개 기록 정리에 더해 최신 값 재조회 및
다른 회원의 찜·조회 시각·기록 정리를 검증했다.

- 백엔드: `./gradlew test --tests '*RecentlyViewed*Test' --tests '*AccommodationResponseJsonTest' --tests '*AccommodationDetailQueryCountTest' --tests '*PublicAccommodationDetailReadIntegrationTest' --tests '*HostAccommodationDetailReadIntegrationTest'`
  — 46개 통과, 실패·오류·건너뛰기 없음. 공용 저장소와 DTO를 사용하는 공개·호스트 상세도 포함한다.
- 프론트: `npm run test:ci -- src/features/wishlist src/screens/wishlist src/screens/accommodation-detail/useRecentlyViewedRecording.test.tsx`
  — 18개 파일, 107개 테스트 통과.
- 브라우저: `npm run test:e2e:characterization -- tests/e2e/specs/wishlist-characterization.spec.ts`
  — 4개 통과. 최근 목록 순서·후기·찜 버튼·삭제와 화면 이동 이력, 위시리스트 직접 진입을 확인했다.
- 백엔드와 프론트의 `recently-viewed-mixed-history.json` 내용 일치 확인.

브라우저 테스트는 공유 계약에 기반한 모의 HTTP 응답을 사용하며 실제 백엔드와
연결한 검증은 아니다. 확인한 개선은 선택 컬럼 및 엔티티 로딩 감소다.
운영 응답 시간이나 실행계획 개선을 측정한 것은 아니다.
