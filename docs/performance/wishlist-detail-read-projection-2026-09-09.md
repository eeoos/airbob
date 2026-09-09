# 위시리스트 상세 조회 컬럼 축소

## 범위와 판단

`GET /api/v1/members/wishlists/accommodations/{wishlistId}`에서 제목·소유권은
`WishlistDetailHeaderProjection`의 2개 필드로, 항목 페이지는 기존 응답 DTO에
12개 스칼라 필드를 전달하는 방식으로 변경했다. 상세 경로의 위시리스트·숙소·주소
엔티티 로딩을 제거하며 응답 JSON과 SQL 2회는 유지한다.

[기존 정확성 수정](wishlist-detail-read-correctness-2026-09-08.md)과
[제목·찜 상태 반복 조회 제거](wishlist-metadata-membership-read-reduction-2026-09-08.md)
후에도 남아 있던 전체 엔티티 조회를 줄이는 단계다.

프론트는 지정된 `codex/editable-booking-review` 작업트리에서 확인했다.
원격 main을 다시 fetch한 결과 `f80bc095`이며 작업트리 HEAD `6542dd7a`에
이미 포함되어 있었다. 응답과 프론트 소비가 맞아 프론트 수정은 필요하지 않았다.

## 응답 소비와 조회 분리

| 정보 | 사용처 및 판단 |
|---|---|
| wishlist_name | 첫 상세 페이지의 제목. 항목이 없는 목록에서도 필요 |
| 위시리스트 소유 회원 ID | 서버의 소유권 확인에 사용하며 응답하지 않음 |
| wishlist_accommodation_id | 메모 수정·항목 삭제 대상, 커서 보조키 |
| memo | 카드 메모 표시 및 수정 초기값 |
| accommodation.id | 숙소 상세 이동 대상. 항목 연결 ID와 구분 |
| accommodation.name·thumbnail_url | 카드 이름·사진·접근성 이름 |
| address_summary | city·district 표시, 없으면 country 사용. state는 공통 모델에서 보존 |
| review_summary | 후기 표시 여부, 평점·후기 수 표시 |
| created_at | 서버 커서 생성과 UTC 응답 계약. 프론트 모델에서 보존 |
| is_in_wishlist | 기존 계약의 true 값을 유지하며 추가 조회하지 않음 |
| page_info | 다음 페이지 요청과 목록 연결 |

제목·소유권 조회와 항목 페이지 조회는 분리한다. 빈 목록에도 제목이 필요하고,
타인 접근은 항목 유무와 무관하게 거부해야 한다. 단순 항목 JOIN으로 합치면
이 동작을 보장하기 어려우며 중복된 헤더 데이터가 각 항목에 붙는다.

활성 위시리스트를 먼저 찾고 그다음 소유권을 비교한다. 삭제·미존재는 기존
찾을 수 없음 오류, 타인의 활성 위시리스트는 기존 접근 거부 오류를 유지한다.
거부된 요청은 항목 페이지를 조회하지 않는다. 쓰기에서 사용하는 엔티티 조회는
유지하고 상세 읽기에만 새 제목·소유권 projection을 사용한다.

항목은 공개 숙소 필터, 주소 INNER JOIN, 리뷰 요약 LEFT JOIN을 유지한다.
주소 행이 없는 숙소를 제외하는 기존 동작도 유지한다. 주소 필드 자체가 null인
경우에는 해당 값을 반환한다. 리뷰 요약 행 없음과 0건 요약의 0건·0점 변환도 같다.

정렬은 연결 행의 `created_at DESC, id DESC`, 조회량은 페이지 크기+1을 유지한다.
동일 생성 시각에서 항목 ID로 다음 페이지를 나누며, 생성 시각은 기존처럼
UTC Instant로 응답하고 커서 계산 때 UTC LocalDateTime으로 변환한다.
일반 위시리스트 목록·membership·최근 본 목록·쓰기·인덱스·스키마·ES는 변경하지 않았다.

## 측정과 검증

MySQL 8.4.11 Testcontainers에서 실제 서비스와 저장소를 호출했다. 조회 전에
영속성 컨텍스트·Hibernate 통계·SQL 기록을 비우며 인증 필터의 DB 읽기는 제외한다.

| 측정 항목 | 변경 전 | 변경 후 |
|---|---:|---:|
| 제목·소유권 조회 선택 컬럼 | 10 | 2 |
| 항목 페이지 조회 선택 컬럼 | 37 | 12 |
| 항목 1건 fixture의 엔티티 로딩 | 3 | 0 |
| 정상 상세와 빈 상세의 SQL | 2 | 2 |
| 타인·삭제·미존재 요청의 SQL | 1 | 1 |

변경 전 엔티티 3개는 위시리스트 1개, 숙소 1개, 주소 1개다. 기존 통합 테스트에
계측과 현재 값 재조회, nullable 주소 필드, 주소 행 없음, 비공개 상태별 제외,
항목 있는 타인 목록의 접근 거부 검증을 보강했다. 변경 전 17개 테스트로 동작을
확인하고, 변경 후에는 선택 컬럼 2/12개·엔티티 0개도 검증한다.

동일 시각·다른 시각의 커서 경계, 공유 JSON, 빈 목록의 제목, 타인·삭제·미존재
오류와 항목 조회 생략, 이름 변경 명령 후 재조회, 최신 숙소·주소·후기·메모를
확인했다. 기존 위시리스트 추가·삭제·대표 숙소 갱신과 비교실험 회귀도 통과했다.

- 백엔드: `./gradlew test --tests '*WishlistDetailQueryIntegrationTest' --tests '*WishlistDenormalizationIntegrationTest' --tests '*WishlistDeleteBenchmarkIntegrationTest' --tests '*WishlistMembershipApiTest' --tests '*UtcResponseJsonTest' --tests '*RecentlyViewedQueryIntegrationTest'`
  — 39개 통과, 실패·오류·건너뛰기 없음.
- 프론트: `npm run test:ci -- src/features/wishlist src/screens/wishlist src/workflows/wishlist-membership`
  — 20개 파일, 132개 테스트 통과.
- 브라우저: `npm run test:e2e:characterization -- tests/e2e/specs/wishlist-characterization.spec.ts`
  — 4개 통과. 일반 목록 요청 없이 상세·빈 상세에 진입하는 흐름과 화면 이동,
  최근 조회 계약·삭제 동작을 확인했다.
- 두 저장소의 `wishlist-detail-without-reviews.json`, `wishlist-membership.json` 내용 일치 확인.

브라우저 테스트는 공유 계약의 모의 HTTP 응답을 사용하며 실제 백엔드 연결을
검증한 것은 아니다. 확인한 개선은 선택 컬럼과 엔티티 로딩 감소다.
운영 응답 시간이나 실행계획 개선은 아직 측정하지 않았다.
