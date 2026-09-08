# 최근 본 숙소와 후기 요약 조회 통합

대상은 GET /api/v1/members/recently-viewed다. 변경 전 백엔드는 934f7ccf,
프론트 codex/editable-booking-review는 5661dbc 기준으로 확인했다.

## DTO 소비와 구조 판단

프론트의 recentlyViewedApi → toRecentlyViewedCollection →
toRecentlyViewedAccommodationCardViewModel → RecentlyViewedView 경로를 확인했다.
숙소 ID는 상세 이동·찜·기록 삭제에, 이름·썸네일·주소는 카드에,
viewed_at은 날짜 그룹에, 후기 요약은 평점·후기 수 표시에,
is_in_wishlist는 저장 상태와 버튼 표시에 쓰인다.
응답 순서를 그대로 카드로 전달하며 total_count는 목록 모델에 보존한다.

후기 요약은 accommodation_id가 PK이므로 숙소당 최대 한 행이다.
숙소·주소 조회에 요약의 개수와 평균을 LEFT JOIN하고 기존
AccommodationDetailProjection을 재사용했다. 따로 요약 엔티티 목록을
조회하고 Map으로 조립하던 과정을 운영 경로에서 제거했다.

찜 여부는 별도 일괄 조회를 유지한다. 같은 숙소를 한 회원의 여러 위시리스트에
저장할 수 있으므로 단순 JOIN을 합치면 결과가 중복될 수 있다. 현재의 ID 집합
조회는 회원별 찜 여부를 중복 없이 반환한다. EXISTS 등으로 추가 통합할지는
추후 실행계획과 처리량을 비교할 대상이며, 분리가 항상 빠르다는 뜻은 아니다.

## 유지한 동작

- Redis의 점수 내림차순과 동점일 때의 Redis 반환 순서를 그대로 사용한다.
  SQL IN 조회의 반환 순서에 의존하지 않는다.
- PUBLISHED 숙소만 응답한다. DRAFT·UNPUBLISHED·DELETED·존재하지 않는 ID는
  해당 회원의 Redis 기록에서 제거한다. 다른 회원의 기록에는 손대지 않는다.
- 후기 요약 행이 없으면 기존과 같이 review_summary는 null이다.
  행이 있지만 후기가 0개이면 0건·0점 객체다. total_review_count의 NOT NULL
  제약으로 LEFT JOIN의 누락과 실제 0건을 구별한다.
- 주소 없는 숙소도 반환하며 address_summary의 각 필드는 null이다.
- 유효한 숙소가 하나도 없으면 기록 정리 후 빈 응답을 반환한다.
- getRecentlyViewedBefore의 주소 지연 로딩과 별도 후기 요약 조회는 유지한다.
  이 벤치마크와 운영 경로를 비교하면 이제 주소 N+1 제거뿐 아니라 요약 통합도
  차이에 포함되므로 측정 결과를 주소 JOIN만의 효과로 해석하면 안 된다.

## 실제 MySQL·Redis 검증

MySQL 8.0.33과 Redis 7.2 Testcontainers를 사용했다. 영속성 컨텍스트와
Hibernate 통계를 비운 후 서비스 호출과 DTO 조립까지의 SELECT 수를 측정했다.
인증 필터의 읽기와 Redis 명령은 아래 DB 조회 횟수에 포함하지 않는다.

| 기록 상태 | 변경 전 SELECT | 변경 후 SELECT |
|---|---:|---:|
| 공개 숙소가 있는 목록 | 3 | 2 |
| 비공개·존재하지 않는 숙소만 있는 목록 | 3 | 1 |
| Redis 기록 없음 | 0 | 0 |

새 회귀 테스트는 기존 코드에서 응답 계약 검증을 통과한 뒤 SELECT 수
검증 두 건에서 각각 3회로 실패했다. 변경 후 네 통합 테스트가 모두 통과했다.
같은 조회 시각, 후기 요약 행의 유무와 0건, 주소 없음, 회원당 중복 찜,
다른 회원의 찜 제외, 비공개 기록 정리, 빈 기록, 벤치마크 응답을 확인했다.

검증 명령과 결과:

- 백엔드: `./gradlew test --tests '*RecentlyViewed*Test' --tests '*AccommodationDetailQueryCountTest'`
  — 22개 통과, 실패·건너뛰기 없음. 기존 공개 상세 조회도 함께 검증했다.
- 프론트: `npm run test:ci -- src/features/wishlist` — 15개 파일, 75개 테스트 통과.
- 브라우저: `npm run test:e2e:characterization -- tests/e2e/specs/wishlist-characterization.spec.ts`
  — 2개 통과. 순서·후기 표시·찜 상태·기록 삭제·화면 이동 이력을 확인했다.
- 프론트 테스트 및 E2E TypeScript 검사, 변경 파일 ESLint·Prettier 검사 통과.

백엔드 contracts/recently-viewed-mixed-history.json과 프론트 API fixture는
같은 응답 내용을 검증한다. 0.0/0 표기는 JSON 숫자로 같은 값이다.
브라우저는 해당 계약을 모의 HTTP 응답으로 사용하며 실제 백엔드 서버에
연결하는 검증은 아니다. 프론트 운영 코드는 변경할 필요가 없었다.

인덱스와 스키마는 변경하지 않았다. 운영 응답 시간이나 실행계획은 측정하지
않았으며, 확인한 개선은 DB 왕복 횟수 감소다. 숙소·주소의 엔티티 전체 조회는
남아 있어 카드에 필요한 컬럼만 선택하는 projection은 별도 개선 후보다.
