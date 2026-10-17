# 쿠폰 시간 계약

쿠폰 업무 시각의 저장·비교와 API는 UTC를 사용한다. 사용자가 보는 한국 시간 변환은 프론트에서 한다.

| 경계 | 표현 | 예시 |
| --- | --- | --- |
| 관리자 생성·수정 요청 | offset이 있는 ISO 8601 `Instant` | `2027-01-01T00:30:00+09:00` 또는 `2026-12-31T15:30:00Z` |
| DB·엔티티·기간 비교 | UTC `DATETIME(6)` / `LocalDateTime` | `2026-12-31 15:30:00` |
| 캠페인·보유 쿠폰 응답 | UTC `Instant` | `2026-12-31T15:30:00Z` |
| 프론트 표시 | 명시적인 `Asia/Seoul` | `2027-01-01 00:30` |

시간대 없는 관리자 요청은 400이다. `CouponTime`은 API instant와 UTC 엔티티 값을 변환한다.
`CouponTimeProvider`는 주입한 `Clock`의 instant를 UTC로 해석하며, 재고 경로의 DB 현재 시각은
`UTC_TIMESTAMP(6)` 그대로 사용한다. 발급·사용 기간은 시작 포함, 종료 제외다.
기존 JPA `hibernate.jdbc.time_zone=UTC` 설정과 테스트·실험 실행기의 JVM
`-Duser.timezone=UTC` 설정을 유지한다.

프론트 `airbob-front`의 쿠폰 API adapter는 offset 없는 날짜를 거부한다. 숙소 상세와 예약 검토는
같은 view model에서 `Intl.DateTimeFormat`의 `timeZone: "Asia/Seoul"`로 표시한다.
브라우저 시간대로 발급·사용 가능 여부를 판단하지 않고 서버 상태를 따른다. 현재 관리자 쿠폰 입력
화면은 없으며, 향후 입력 화면도 한국 시간에 offset을 붙이거나 UTC로 변환하여 전송해야 한다.
공개 캠페인 응답에는 `issued_quantity`가 없고 사용자 화면에 남은 수량을 표시하지 않는다.
대응하는 프론트 변경은 `airbob-front`의 `a484c5a8` 커밋이다.

## 목록 커서와 시간

`GET /api/v1/coupons`와 `GET /api/v1/members/me/coupons`는 `size`(기본 20, 최대 50)와 `cursor`로
페이지를 조회한다. 응답에는 기존 `data.infos`와 `data.page_info`의 `has_next`, `next_cursor`,
`current_size`가 들어간다. 첫 요청에는 커서를 생략하고, 프론트는 다음 페이지가 있으면 서버가 준
커서를 그대로 전달한다. 파라미터 없는 요청도 첫 20개만 반환하므로 다음 페이지 처리가 필요하다.

캠페인은 UTC `issue_start_at`(`lastIssueStartAt`)과 쿠폰 ID를, 내 쿠폰은 이미 UTC인 감사 시각
`member_coupon.created_at`(`lastCreatedAt`)과 **회원 쿠폰 ID**를 내림차순 경계로 사용한다.
동일 시각의 행은 각 ID로 구분하고 DB의 마이크로초 정밀도를 유지한다. 커서는 불투명한 서버 값이며,
프론트에서 시각을 한국 시간으로 바꾸거나 잘라서 다시 만들지 않는다. 기존 응답의 업무 날짜는 계속
UTC `Instant`와 `Z`로 반환한다.

페이지마다 현재 상태를 조회하므로 캠페인 종료나 시작 전 기간 수정으로 포함 여부·순서가 바뀔 수
있고, 여러 페이지를 하나의 스냅샷으로 보장하지 않는다. 최신 목록은 커서 없이 다시 요청한다.

V32는 `member_coupon.created_at`을 `DATETIME(6) NOT NULL`로 제한한다. 이 값은 V30 전에도 UTC였으므로
9시간 변환을 다시 하지 않는다. NULL인 기존 값은 신뢰할 수 있는 실제 발급 이력으로만 복구하며,
마이그레이션이 시각을 만들어 넣지 않는다. 누락값이 있으면 테이블 변경 전 실패한다.
스키마·프론트 전환 절차는 [쿠폰 발급·조회 운영의 V32 적용](coupon-issuance-operations.md#v32-적용)을 따른다.

## V30 전환

1. 쿠폰 조회·발급·관리와 쿠폰을 사용하는 예약·결제 경로의 구버전 reader/writer를 모두 중지한다.
2. V29까지의 DB를 백업하고 기존 쿠폰 업무 값이 서울 시간이라는 전제를 확인한다. 이미 별도로 UTC로
   변환한 DB에 V30을 다시 적용하지 않는다. 기존 Flyway 파일이나 성공 이력을 수정하지 않는다.
3. V30은 `coupon.issue_start_at`, `issue_end_at`, `usable_from`, `usable_until`,
   `stock_prepared_at`과 `member_coupon.used_at`에서 9시간을 뺀다. NULL은 유지한다.
   이미 UTC인 `created_at`/`updated_at`, 수량·unit·회원 소유·사용 상태·예약 연결은 그대로 보존한다.
4. UTC API를 사용하는 백엔드와 한국 시간으로 표시하는 프론트를 함께 전환한다. 과거 API는 offset이
   없는 서울 시각이었으므로 구 프론트/구 백엔드와 혼합 배포하지 않는다.
5. 현재 시각과 경계 시각의 발급·사용 상태, 한국 시간 표시를 확인한다. V30 이후에는 UTC 호환
   바이너리로만 진행하며 V29 앱으로 되돌리지 않는다.

실제 MySQL 마이그레이션 테스트는 자정 날짜 변경, 마이크로초, NULL, 감사 시각·재고·소유권 보존과
Flyway 재실행 시 추가 변환이 없는 것을 확인한다. 현재 쿠폰 동시성 테스트는 대리 키 RR의 재고 불변식을
검증한다. 양쪽 저장소의 공유 JSON fixture는 동일해야 한다.

## 성능 자료

V29에서 측정한 결과는 당시 바이너리·실행기·manifest와 함께 보존한다. 보존한 발급 비교 실행기는
V30과 UTC fixture 응답을 요구한다. V31 앱의 비교 API 제거와 스키마 정리는
[쿠폰 발급 운영](coupon-issuance-operations.md)을 따른다. 이전 JAR를 현재 실행기에 섞거나 과거 manifest의 버전만
바꾸지 않는다. 시간 계약 변경을 포함한 새 측정은 새 바이너리 해시와 스키마 버전으로 기록한다.
