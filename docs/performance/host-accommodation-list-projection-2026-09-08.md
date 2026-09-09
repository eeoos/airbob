# 호스트 숙소 목록 조회 컬럼 축소

대상은 GET /api/v1/profile/host/accommodations다. 변경 전 백엔드는 2b7e611c,
프론트 codex/editable-booking-review는 57038f9 기준으로 확인했다.

## 변경 근거와 범위

프론트의 hostListingsApi → toHostListingPage → toHostListingViewModels →
HostListingsPanel 및 AccommodationActionModal 경로를 대조했다.

| 값 | 소비와 유지 이유 |
|---|---|
| id | 관리 대상 식별, 상세 이동·편집·공개/비공개·삭제 동작, 백엔드 커서 |
| name, thumbnail_url | 목록과 관리 모달의 이름·이미지·접근성 설명 |
| status | 상태 표시, 상세 이동·공개·비공개 메뉴 선택 |
| address_summary.city/district/country | 위치 표시와 대체 표시 |
| address_summary.state, type | 프론트 모델에 매핑되지만 현재 카드에서는 미표시. 기존 응답 계약 유지 |
| created_at | 프론트 모델 보존, 백엔드 정렬·커서. UTC 변환과 마이크로초 정밀도 유지 |
| page_info | 무한 스크롤의 다음 페이지 판단과 커서 전달 |

기존 쿼리는 숙소·주소 엔티티 전체를 읽었다. 목록 응답과 커서를 만드는 데 필요한
숙소 6개 컬럼과 주소 4개 컬럼만 HostAccommodationProjection으로 선택하도록 변경했다.
주소 LEFT JOIN은 위치 표시에 필요하고 주소 없는 작성 중 숙소도 반환해야 하므로
유지했다. 호스트 조건, 삭제 상태 제외, 선택적 상태 필터, 정렬, size+1 조회,
hasNext 계산, 응답 JSON 필드는 유지했다.

현재 화면에서 미표시인 응답 필드를 일괄 삭제하지 않았다. 이번 변경의 목적은
기존 계약을 유지하면서 DB가 읽는 불필요한 컬럼과 엔티티 로딩을 줄이는 것이다.
공개 상세·호스트 편집 상세·최근 본 숙소 쿼리와 인덱스·스키마는 변경하지 않았다.

## 함께 확인한 프론트 계약 불일치

주소가 없는 숙소는 address_summary가 null인 대신 각 필드가 null인 객체로
반환된다. 프론트 타입은 country와 city를 필수 문자열로 선언했고, 위치 표시도
주소 객체 자체가 null일 때만 대체 문구를 사용했다. 실제 백엔드 형상의 fixture를
입력하자 locationLabel이 기대한 “위치 정보 없음” 대신 null이 되는 것을 재현했다.

country와 city 타입에 null을 허용하고, 주소 객체가 있어도 표시할 값이 없으면
“위치 정보 없음”을 반환하도록 수정했다. 이름·썸네일·상태별 관리 동작은 유지했다.

## 실제 MySQL 검증 결과

MySQL 8.0.33 Testcontainers에서 영속성 컨텍스트와 Hibernate 통계를 초기화하고
서비스 호출, DTO 조립, 커서 생성을 수행했다. 생성된 SQL을 직접 수집했다.
아래 횟수는 목록 서비스의 DB 조회이며 인증 과정의 읽기는 포함하지 않는다.

| 항목 | 변경 전 | 변경 후 |
|---|---:|---:|
| 페이지당 SELECT | 1 | 1 |
| SELECT 컬럼 수 | 32 | 10 |
| 숙소·주소 엔티티 로딩 | 있음 | 없음 |
| 주소 LEFT JOIN | 있음 | 있음 |

새 통합 테스트 5개는 기존 코드에서 응답·페이징 검증을 통과하고, 선택 컬럼이
32개여서 실패했다. 변경 후 모두 통과했다. DRAFT·PUBLISHED·UNPUBLISHED별
조회, 다른 호스트 제외, 삭제 숙소 제외, 필터 없는 조회, 빈 목록, 동일 생성 시각의
ID 내림차순, 마이크로초 커서, 두 번째 페이지, 정확히 채워진 마지막 페이지와
그 이후 빈 페이지, 주소 없는 숙소의 JSON을 검증했다.

검증 명령과 결과:

- 백엔드: `./gradlew test --tests '*HostAccommodationListQueryIntegrationTest' --tests '*AccommodationQueryServiceTest' --tests '*AccommodationResponseJsonTest' --tests '*AccommodationDetailQueryCountTest'`
  — 28개 통과, 실패·건너뛰기 없음.
- 프론트: `npm run test:ci -- src/features/profile src/screens/profile src/features/accommodations/components/AccommodationActionModal`
  — 11개 파일, 44개 테스트 통과.
- 브라우저: `npm run test:e2e:characterization -- tests/e2e/specs/host-listings-characterization.spec.ts`
  — 1개 통과. 공개·비공개·작성 중 필터 전환, 주소 없는 카드, 상태별 관리 메뉴를 확인했다.
- 프론트 운영·테스트·E2E TypeScript 검사, 변경 파일 ESLint·Prettier 검사 통과.

백엔드 contracts/host-accommodation-list.json과 프론트의 같은 이름 fixture는
동일한 JSON 내용을 사용한다. 브라우저는 이 fixture를 모의 HTTP 응답으로 사용하며
실제 서버에 연결한 검증은 아니다. 운영 응답 시간과 실행계획은 측정하지 않았으므로
성능 증가율을 주장하지 않는다. 확인한 개선은 컬럼 수와 엔티티 로딩 감소다.
