# 호스트 숙소 편집 조회 축소

대상은 GET /api/v1/profile/host/accommodations/{id}다. 프론트 소비 경로는
listingEditorApi → listingEditorMappers → 편집 화면과 저장·이미지 처리 workflow다.
프론트 codex/editable-booking-review의 4c6c207, 백엔드 63f44567을 변경 전 기준으로 삼았다.

## 변경

| 항목 | 확인한 소비 | 처리 |
|---|---|---|
| 후기 요약 review_summary | 편집 mapper에서 사용하지 않음 | 호스트 DTO 필드와 별도 요약 SELECT 제거 |
| 호스트 host | 편집 mapper에서 사용하지 않음 | 호스트 DTO 필드와 member fetch join 제거 |
| 좌표 coordinate | 편집 mapper에서 사용하지 않음 | 호스트 DTO 필드 제거 |
| 이름·설명·유형·가격·통화·체크인/아웃 시각 | 편집과 변경 요청 구성에 사용 | 유지 |
| 주소·인원 정책 | 편집 초기화와 저장 비교에 사용 | 숙소와 함께 조회 유지 |
| 이미지·편의시설 | 편집·삭제·업로드·저장에 사용 | 별도 목록 조회 유지 |
| time_zone_id | 현재 편집 mapper에서 전달하지 않음 | 저장된 숙소 시각 기준을 나타내는 메타데이터로 유지 |

호스트 응답에서 위 세 필드를 실제로 삭제했다. 공개 숙소 상세는 별도 DTO와
AccommodationDetailReader를 사용하므로 후기 요약·호스트·좌표를 계속 반환한다.
공개 상세와 캐시 조회 회귀 테스트도 실행했다.

조회 서비스는 더 이상 후기 요약 repository에 의존하지 않는다. 소유권은 기존처럼
accommodation.id와 accommodation.member_id를 동시에 검사한다. 호스트 JOIN이
없어도 소유권 검사는 유지되며, 타인 소유·존재하지 않는 숙소는 기존 예외로 거부한다.

## 실제 MySQL 검증

Hibernate 영속성 컨텍스트를 비우고 통계를 초기화한 뒤 동일한 서비스 경로를
실행했다. 변경 전 신규 회귀 테스트의 SELECT 3회 기대값에 실제 4회가 관측되었다.
변경 후 같은 테스트는 3회로 통과했다.

| 검증 항목 | 결과 |
|---|---|
| 후기와 편의시설·이미지가 있는 호스트 상세 | SELECT 4회 → 3회 |
| 변경 후 호스트 Member·AccommodationReviewSummary 엔티티 로딩 | 각각 0개 |
| 변경 후 정책·주소가 없는 초안 | SELECT 3회, 빈 목록과 기존 null 표현 유지 |
| 타인 소유·없는 숙소 | SELECT 1회 후 거부, 자식 목록 미조회 |
| 공개 상세 | 기존 SELECT 3회와 후기 요약·호스트·좌표 값 유지 |

실제 호스트 상세 SQL의 JOIN은 address와 occupancy_policy만 남았으며 WHERE는
accommodation.id=? AND accommodation.member_id=?다. 이미지 ID 순서와 두 편의시설의
값이 유지되는지도 검증했다. 서로 독립된 다건 목록을 하나의 JOIN으로 합치지 않았다.

좌표는 응답에서만 제거했다. 주소 엔티티를 읽으므로 SQL에는 여전히 latitude,
longitude와 주소 감사 컬럼 등이 포함된다. 이 변경을 모든 미사용 SELECT 컬럼을
제거한 결과로 해석하면 안 된다. 인덱스·스키마·페이지네이션은 변경하지 않았다.

## 계약과 검증 범위

- [공유 JSON](../../src/test/resources/contracts/host-accommodation-editor.json)은 실제
  HostDetail DTO를 직렬화하여 검증한다. 프론트 API 테스트와 브라우저 테스트가 같은
  fixture를 사용하고, 브라우저에서는 이미지 URL만 로컬 합성 자산으로 바꾼다.
- 프론트 production mapper는 이미 이 세 필드를 소비하지 않아 로직 변경이 필요하지 않았다.
- 백엔드 관련 테스트 30개 통과, 건너뛴 테스트 없음. 실제 MySQL/Redis를 사용하는
  상세·캐시 조회 및 앞 단계 정책 저장 회귀를 포함한다.
- 프론트 14개 파일 179개 테스트와 Chromium 11개 시나리오 통과.
  축소 응답으로 편집·저장·게시·이미지 작업과 부분 정책 보완을 검증했다.
- 프론트 테스트·E2E 타입 검사, 변경 파일 lint·포맷 검사와 fixture 일치 확인 통과.

브라우저 검증은 프로덕션 빌드와 합성 API를 사용했다. 실제 백엔드와 연결한 HTTP
통합 검증, 운영 응답 시간, EXPLAIN 또는 대량 데이터 성능 측정은 수행하지 않았다.
확인한 개선은 조회 수와 불필요한 엔티티 로딩 감소다.
