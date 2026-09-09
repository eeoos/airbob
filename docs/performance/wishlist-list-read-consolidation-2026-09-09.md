# 위시리스트 목록·대표 사진·저장 상태 조회 통합

## 범위와 판단

`GET /api/v1/members/wishlists`의 일반 목록과 `accommodationId`를 전달하는
저장 선택 모달 조회를 각각 SQL 한 번으로 통합했다. 위시리스트 엔티티 대신
`WishlistSummaryProjection`으로 필요한 값만 읽는다. 응답 DTO와 경로는 유지한다.

프론트는 지정된 `codex/editable-booking-review` 작업트리에서 확인했다.
원격 main을 다시 fetch한 결과 `f80bc095`이며 작업 시작 시 HEAD `6542dd7a`에
이미 포함되어 있었다. 운영 코드의 응답 소비는 맞아 수정할 필요가 없었고,
백엔드와 같은 JSON으로 목록 카드·선택 모달 매핑 검증을 추가했다.

대표 숙소는 `accommodation.id` 기본키로 최대 한 행만 연결된다. 선택한 숙소의
저장 관계도 V1의 `UNIQUE(wishlist_id, accommodation_id)`로 목록당 최대 한 행이다.
따라서 두 LEFT JOIN은 목록 행을 늘리지 않아 커서 페이지 안에 합칠 수 있다.
V3의 대표 숙소 ID는 nullable 스칼라 값으로 외래키가 없으므로, 대표가 없거나
이미 사라진 ID를 가리켜도 목록이 유지되도록 LEFT JOIN을 사용한다.

`accommodationId`가 있을 때만 저장 관계를 조인한다. 일반 목록은 저장 여부를
요청하지 않았으므로 `is_contained`와 `wishlist_accommodation_id` 모두 기존처럼
null이다. 선택 모달은 저장되지 않았으면 false/null, 저장됐으면 true/연결 행 ID다.
이 ID는 삭제 대상이며 위시리스트 ID나 숙소 ID와 구분한다.

## 보존한 동작과 분리 유지

| 정보 | 소비 및 유지한 동작 |
|---|---|
| id, name | 목록 카드·선택 모달의 식별자와 이름 |
| created_at | UTC Instant 응답과 커서 계산 |
| wishlist_item_count | 반정규화된 저장 개수로 카드 문구 표시 |
| thumbnail_image_url | 대표 사진 표시, 대표나 사진이 없으면 null |
| is_contained | 선택한 숙소의 저장 여부, 일반 목록에서는 null |
| wishlist_accommodation_id | 선택 모달의 기존 저장 삭제 대상 |
| page_info | 페이지 크기+1로 다음 페이지 판단 및 커서 제공 |

회원·ACTIVE 필터, `created_at DESC, id DESC`, 같은 생성 시각의 ID 경계,
빈 마지막 페이지를 유지한다. 대표 사진과 저장 관계에 숙소 공개 상태 필터를
추가하지 않았다. 기존처럼 비공개·삭제 상태 숙소의 저장 관계와 대표 사진도
목록에 반영한다. 상세 항목의 공개 숙소 필터는 별도 경로의 기존 동작이다.

특정 숙소의 전체 저장 여부를 확인하는 전용 membership API는 유지한다.
목록을 최적화해도 3페이지를 모두 확인하면 SQL 3회가 필요하고, 전용 조회는
SQL 1회로 답한다. 기존 비교 회귀 테스트도 이 기준으로 갱신했다.

`WishlistBenchmarkService.findWishlistsBefore`의 엔티티 목록 조회와 별도 집계·
저장 관계 조회는 비교용으로 유지했다. 호출처가 사라진 대표 썸네일 배치 조회만
`AccommodationRepository`에서 제거했다. 쓰기, 스키마, 인덱스, ES는 변경하지 않았다.

## 측정과 검증

MySQL 8.4.11 Testcontainers에서 실제 서비스·저장소를 호출했다. 조회 전
영속성 컨텍스트·Hibernate 통계·SQL 기록을 비우며 인증 필터의 읽기는 제외한다.
동일한 목록 4개 fixture로 변경 전 15개 테스트를 통과시킨 뒤 변경 후와 비교했다.

| 측정 항목 | 변경 전 | 변경 후 |
|---|---:|---:|
| 대표 숙소가 있는 일반 목록 SQL | 2 | 1 |
| 대표 숙소가 있는 선택 모달 SQL | 3 | 1 |
| 선택 모달의 빈 목록 SQL | 1 | 1 |
| 목록 4개 반환 시 엔티티 로딩 | 4 | 0 |
| 일반 목록의 SQL별 SELECT 항목 수 | 10 / 2 | 6 |
| 선택 모달의 SQL별 SELECT 항목 수 | 10 / 2 / 2 | 6 |

일반 목록의 SELECT 6개는 실제 필드 5개와 저장 관계 ID 자리의 SQL null이다.
선택 모달은 실제 필드 6개다. 변경 전에는 대표 ID가 하나도 없는 페이지의
사진 조회를 생략했으므로 모든 일반 목록이 원래 2회였다는 뜻은 아니다.

공유 JSON, 일반 목록의 nullable 저장 상태, 선택 모달의 정확한 삭제용 연결 ID,
동일 시각·다른 시각 커서 경계, 타인·삭제 목록 제외, 숙소 상태별 대표 사진,
미존재 대표 ID·null 사진, 같은 대표 숙소를 공유하는 목록의 중복 방지,
이름 변경·마지막 항목 삭제 후 재조회를 검증했다.

- 백엔드: `./gradlew test --tests '*WishlistListReadIntegrationTest' --tests '*WishlistDetailQueryIntegrationTest' --tests '*WishlistDenormalizationIntegrationTest' --tests '*WishlistDeleteBenchmarkIntegrationTest' --tests '*WishlistMembershipApiTest' --tests '*WishlistBenchmarkControllerTest' --tests '*UtcResponseJsonTest' --tests '*RecentlyViewedQueryIntegrationTest'`
  — 58개 통과, 실패·오류·건너뛰기 없음.
- 프론트: `npm run test:ci -- src/features/wishlist src/screens/wishlist src/workflows/wishlist-membership`
  — 20개 파일, 134개 테스트 통과.
- 브라우저: `npm run test:e2e:characterization -- tests/e2e/specs/wishlist-characterization.spec.ts tests/e2e/specs/auth-session-characterization.spec.ts --grep wishlist`
  — 6개 통과. 상세·빈 상세 진입, 목록 화면 이동, 최근 조회 계약·삭제,
  비로그인 저장 요청의 로그인 후 재개·취소 흐름을 확인했다.
- 프론트 앱·테스트 타입 검사와 변경 테스트 파일 ESLint 통과, Prettier 적용.
- 두 저장소의 `wishlist-list-with-membership.json` 내용 일치와 변경 diff 검사 통과.

브라우저 테스트는 공유 계약의 모의 HTTP 응답을 사용하며 실제 백엔드 연결을
검증한 것은 아니다. 확인한 개선은 SQL 왕복·선택 항목·엔티티 로딩 감소다.
운영 응답 시간과 실행계획은 별도 측정이 필요하다.
