# 위시리스트 상세 조회 정확성 검증

대상: GET /api/v1/members/wishlists/accommodations/{wishlistId}.
프론트 작업 브랜치: codex/editable-booking-review (시작 커밋 05476db).

## 확인된 문제와 수정

리뷰 요약 행이 없는 공개 숙소를 조회하면 기존 응답은 total_count=0,
average_rating=null이었다. 프론트 카드가 averageRating.toFixed(1)을 호출하므로
카드를 만들 수 없는 응답이었다. MySQL 8.0.33과 실제 JPA 조회 및 Jackson
직렬화를 사용하는 테스트에서 이 차이를 먼저 재현했다.

WishlistAccommodationInfo의 QueryDSL 생성자에서 nullable reviewCount를 받고
기존 ReviewSummary.of를 재사용하도록 수정했다. 이제 요약 행이 없으면
0건·0점을 반환하고, 기존 평점과 후기 수는 보존한다. 프론트에서는 0건인 카드의
평점을 숨기는 기존 표시 규칙을 유지한다.

이전 정적 감사에서 의심한 wishlist.id 별칭은 **현재 Hibernate에서 실제 오류가
발생하지 않았다.** 수정 전에도 생성 SQL은 wishlist_accommodation.wishlist_id를
조건으로 사용했고 대상 위시리스트 분리 및 cursor 테스트가 통과했다. 해당 조건은
wishlistAccommodation.wishlist.id로 명시했지만, 이를 실행 오류 수정이나 쿼리 수
감소로 설명하지 않는다.

## 응답 계약 검증

[JSON fixture](../../src/test/resources/contracts/wishlist-detail-without-reviews.json)는
실제 MySQL 조회 결과를 백엔드 Jackson 설정으로 직렬화한 값과 비교한다.
같은 파일을 프론트 src/features/wishlist/api/__fixtures__에 두고 API adapter 및
카드 표시/버튼 동작 테스트에서 사용한다. 계약이 바뀌면 두 저장소의 fixture를
함께 갱신한다.

[MySQL 통합 테스트](../../src/test/java/kr/kro/airbob/domain/wishlist/WishlistDetailQueryIntegrationTest.java)는
다음 항목을 검증한다.

- 지정한 위시리스트의 공개 숙소만 반환하며 주소·메모·연결 ID·후기 요약을 보존한다.
- 리뷰 요약 행 없음과 기존 0건 요약 행을 모두 0건·0점으로 처리한다.
- 같은 생성 시각의 ID 정렬 및 더 오래된 생성 시각을 넘는 cursor에서 중복/누락이 없다.
- 빈 위시리스트와 비공개 숙소만 있는 위시리스트는 빈 마지막 페이지를 반환한다.
- 타인 접근 거부와 삭제/미존재 위시리스트의 찾을 수 없음 처리를 유지한다.

프론트에서는 실제 계약 fixture의 카드가 표시되고 평점이 숨겨지는지 확인한다.
숙소 이동은 accommodation ID=31, 메모와 삭제는 wishlist item ID=501을 사용한다.
기존 위시리스트 페이지·오류·membership 회귀 테스트도 함께 실행한다.

검증 결과:

- 백엔드 compileJava 및 WishlistDetailQueryIntegrationTest: 8개 통과, 건너뛴 테스트 없음.
- 프론트 wishlist feature, WishlistController, wishlist-membership 테스트: 19개 파일, 116개 통과.
- 프론트 production/test TypeScript 검사와 변경 파일 ESLint·Prettier 검사 통과.
- 양쪽 JSON fixture의 파일 내용이 동일함을 확인했다.

이번 변경은 응답 정확성 수정이다. 인덱스, 조회 SQL 개수, API 경로, 페이지 크기,
소유권 검사, 다른 도메인의 계약을 변경하지 않는다. 실제 브라우저와 구동 중인
백엔드를 연결한 HTTP E2E 및 부하 테스트는 이번 검증에 포함하지 않았다.
