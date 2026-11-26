# 코드 구조와 단계별 이전 계획

airbob은 [team-dodn/spring-boot-java-template](https://github.com/team-dodn/spring-boot-java-template)의
모듈 구조와 계층 구조를 점진적으로 적용한다. 동시에 도메인 사이의 의존을 객체지향 원칙에 맞게 정리한다.
작업은 PR 단위로 나눈다. 각 PR은 동작을 바꾸지 않는 구조 변경으로 끝내고, 별도로 검증할 수 있어야 한다.

## 현재 상태

| 모듈 | 내용 |
|---|---|
| `core/core-api` | 유일한 실행 모듈이다. 애플리케이션 코드, 설정, Flyway 마이그레이션, Elasticsearch 인덱스 정의가 모두 여기에 있다 |
| `support/monitoring` | actuator starter, Prometheus 레지스트리, `monitoring.yml`(프로필별 actuator 노출·probe·메트릭 태그·히스토그램) |
| `support/logging` | `logging.yml`(프로필별 로그 레벨) |
| `clients/client-slack` | Slack incoming webhook 전송(`SlackWebhookClient`). 2xx만 성공으로 보고, 실패는 URL·cause를 숨긴 `SlackWebhookException`으로 알린다 |
| `clients/client-toss-payments` | Toss Payments 승인·취소·조회 전송(`TossPaymentsClient`), 응답 DTO, Basic 인증, 타임아웃. 4xx/5xx는 상태와 provider 코드만 담은 `TossPaymentsApiException`, 전송 실패는 cause 체인을 유지한 `RestClientException`으로 알린다 |
| `clients/client-google-geocoding` | Google Geocoding 주소 조회 전송(`GoogleGeocodingClient`)과 응답 DTO(`GoogleGeocodeResponse`). 응답 `status`는 해석하지 않고 그대로 돌려주며, 전송 실패와 4xx/5xx는 Spring `RestClientException`으로 알린다 |
| `clients/client-aws-s3` | S3 버킷 하나에 대한 객체 업로드·삭제(`S3StorageClient`). spring-cloud-aws `S3Template`을 감싸며 키 이름과 공개 URL은 정하지 않는다. 실패는 `S3Exception`이나 AWS SDK 런타임 예외로 알린다 |

core-api의 `application.yaml`이 `spring.config.import`로 `monitoring.yml`과 `logging.yml`을 불러온다.
readiness 그룹 구성(`accommodationInventory` 포함)처럼 애플리케이션 health indicator에 묶인 설정은
core-api의 `application*.yaml`에 남는다. core-api 코드가 직접 쓰는 `spring-boot-actuator`(HealthIndicator)와
`micrometer-core`는 core-api가 직접 선언한다.

client 모듈은 스프링 빈을 직접 등록하지 않고 전송 객체와 팩토리만 제공한다. 설정 프로퍼티(환경변수 계약)와
빈 등록, 외부 응답을 도메인 의미로 바꾸는 일은 core-api가 맡는다. 예를 들어 운영 알림의
`operator-alert.slack.*` 프로퍼티, 미설정 판정, 메시지 구성, `OperatorAlertDelivery*Exception` 매핑은
core-api의 `core/domain/operatoralert/infrastructure/slack`에 남는다.
결제도 마찬가지로 `payment.toss.*` 프로퍼티, 활성화 여부, provider 코드 분류(승인·거절·재시도·결과 불명·수동 검토),
응답 상관 검증, 도메인 값 정규화는 core-api의 `TossPaymentsAdapter`에 남는다.
지오코딩은 `google.api.*` 프로퍼티와 빈 등록(`core.domain.accommodation.location.config`), 활성화 여부,
`OK` 상태 판정, 실패를 `GeocodeResult.fail()`로 바꾸는 일이 core-api의 `GoogleGeocodingAdapter`에 남는다.
S3는 `cloud.aws.s3.*` 프로퍼티와 빈 등록(`core.domain.image.config`), 쓰기 허용 여부, 키 이름(`디렉터리/UUID.확장자`),
키와 CloudFront URL 사이의 변환이 core-api의 `S3ImageUploader`에 남는다. `S3Template` 빈은 core-api의 spring-cloud-aws 자동 구성이 만든다.

공통 패키지 중 템플릿 위치로 옮긴 것은 다음과 같다.

| 이전 위치 | 현재 위치 |
|---|---|
| `common.exception`(`BaseException`, `ErrorCode`, `InvalidInputException`, `AdminAccessDeniedException`) | `core.support.error` |
| `common.dto`(`ApiResponse`, `ErrorResponse`) | `core.support.response` |
| `cursor.*` | `core.support.cursor.*` |
| `common.context`(`UserContext`, `UserInfo`) | `core.support.context` (PR 17d. 이력 엔티티가 읽으므로 `core.api.security`가 아니라 support에 둔다) |
| `common.domain.BaseEntity`, `common.history.*` | `core.support.persistence`, `core.support.persistence.history` (PR 17d. 템플릿은 `storage.db.core`에 두며, 엔티티를 storage/db-core로 옮기는 선택 단계에서 함께 옮긴다) |
| `common.monitoring`(쿼리 수 측정, 로컬 SQL 추적) | `core.support.monitoring` (PR 17d) |
| `common.validation` | `core.support.validation` (PR 17d) |
| `config`(앱 설정), `config.apidocs` | `core.api.config`, `core.api.config.apidocs` (PR 17e. 템플릿의 `core.api.config` 자리. JPA 설정은 템플릿에서 storage/db-core에 있으나 엔티티와 함께 선택 단계에서 옮긴다) |
| `config.AccommodationDetailRedisConfig`·`AccommodationDetailRedisProperties` | `core.domain.accommodation.cache.config` (PR 17e. 숙소 상세 캐시 전용 Redis라 도메인이 자기 설정을 갖는다. 설정 클래스는 이름 규칙 PR에서 `AccommodationDetailRedisConfiguration`이 됐다) |
| `messaging.event`, `messaging.outbox`, `messaging.infrastructure.kafka` | `core.support.messaging.event`, `.outbox`, `.kafka` (PR 17f. 모든 도메인이 쓰는 기술 인프라이고 도메인에 의존하지 않는다. 배포·스크립트 계약 테스트는 `core.support.messaging.infrastructure`) |
| `messaging.alert` | `core.domain.operatoralert` (PR 17g. 수동 확인·DLT 사고를 Slack까지 전달하는 업무 기능이라 도메인으로 둔다. `application`은 도메인 루트로, `event`·`infrastructure.{kafka,outbox,slack}`·`monitoring`은 하위 패키지로) |
| `geo`(지오코딩·시간대 포트와 구현) | `core.domain.accommodation.location` (PR 17h. 유일한 사용처인 accommodation이 포트를 갖는다. `GeocodingService` → `GeocodingGateway`, `GoogleGeocodingService`(`@Service`) → `GoogleGeocodingAdapter`(`@Component`). 도메인 결과 `GeocodeResult`에서 Google 응답 타입 viewport를 빼고, viewport 보정에만 쓰이던 `ViewportAdjuster`·`Coordinate`를 삭제) |
| `common.exception.GlobalExceptionHandler` | `core.api.controller` (Presentation이 도메인 예외를 아는 정방향 의존이 되어 `common` ↔ 도메인 순환이 사라졌다) |
| `domain.auth`의 웹 보안(`SessionAuthFilter`, `AdminAuthInterceptor`, `@CurrentMemberId`와 리졸버, `AuthenticationRequiredException`, `SessionUtil` → `SessionCookie`) | `core.api.security` (PR 10a. 모든 Controller가 쓰는 웹 인프라라서 auth 도메인에서 뺐다. 이 때문에 생기던 member → auth 간선이 사라져 순환이 끊겼다) |

#### auth와 member의 경계 (PR 10a)

- **auth**: 로그인·로그아웃(`AuthService`), 요청의 세션 확인(`SessionAuthenticationService`), 세션 발급·조회·삭제.
  세션 저장 형식(Redis 키)은 `SessionRedisRepository`만 안다. 필터도 `SessionAuthenticationService`를 거친다.
- **member**: 회원 생애주기(가입·탈퇴·프로필), 역할과 이력, 자격 증명. 비밀번호 해시 생성은 가입에서, 검증은 `Member.matchesPassword`에서 한다.
  auth는 `MemberReader`(`readActiveByEmail`, `readActiveForUpdate`)로 회원을 읽고, member의 Repository를 쓰지 않는다.
- **웹 보안**(`core.api.security`): 필터는 세션을 확인해 `UserContext`를 채우고, 인터셉터는 `MemberService.isActiveAdmin`으로 관리자 경로를 막는다.
  세션 쿠키 이름과 속성은 `SessionCookie`가 정한다.
- 탈퇴 시 세션 무효화는 member가 소유한 포트(`SessionInvalidator`)를 auth의 `SessionWriter`가 구현한다.

#### review의 경계 (PR 12a)

review는 accommodation(리뷰 대상), reservation(작성 자격), search(색인 갱신 요청)에 의존한다. 반대 방향은 아래처럼 정리했다.

- **리뷰 요약**(`AccommodationReviewSummary`)은 숙소와 기본키를 공유하는 1:1 엔티티이고, 숙소·위시리스트 목록 쿼리가 조인하며 검색 문서에도 들어간다.
  그래서 숙소의 평점 속성으로 보고 accommodation이 소유한다(엔티티, Repository, QueryDSL).
  - review는 `AccommodationReviewSummaryWriter`로 원자적 SQL 갱신을 요청한다.
  - 검색 문서 빌더는 `AccommodationReviewSummaryReader`로 읽는다.
  - 응답 DTO는 `AccommodationResponse.ReviewSummary`다. 스키마 이름 `ReviewResponseReviewSummary`는 그대로 둔다.
- **리뷰 작성 가능 여부**: 게스트 예약 상세가 이미 게시한 리뷰가 있는지 확인한다. reservation이 소유한 포트 `ReservationReviewReader`를 review의 `ReviewReader`가 구현한다.
- review가 쓰는 숙소·예약 조회는 `AccommodationReader.readPublished`와 `ReservationReader.existsPastCompletedReservationByGuest`를 거친다.

#### accommodation의 경계 (PR 13a, 13b)

reservation(예약 대상), search(검색 문서), review, recentlyviewed, payment가 accommodation에 의존한다. accommodation은 이들에 의존하지 않는다.

- 다른 도메인은 `AccommodationReader`(숙소, 견적·체크아웃 스냅샷, 재고 seed 대상, 최근 본 숙소)와 `AccommodationAmenityReader`로 읽는다.
  체크아웃의 `FOR SHARE NOWAIT` 스냅샷도 같은 트랜잭션에서 Reader를 거쳐 읽으며, NOWAIT 분류는 `ReservationCheckoutService`가 그대로 한다.
- accommodation이 필요로 하는 다른 도메인 기능은 accommodation이 소유한 포트(`core.domain.accommodation.port`)로 쓴다.

  | 포트 | 쓰는 곳 | 구현 |
  |---|---|---|
  | `AccommodationSearchRefreshRequester` | 숙소·이미지 변경 시 검색 갱신 요청 | search의 `AccommodationSearchRefreshPublisher`(outbox 발행기)가 이 포트를 확장 |
  | `AccommodationInventorySeeder` | 게시·시간대 변경 시 같은 트랜잭션에서 재고 seed | reservation의 `ReservationInventorySeeder` |
  | `AccommodationReservationReader` | 시간 설정 변경 전 미래 재고 예약 확인 | reservation의 `ReservationReader` |
  | `AccommodationAvailabilityReader` | 예약 가능일 조회 | reservation의 `ReservationAvailabilityReader`(예약 기간 계산 → 조회 시각 → 재고 스냅샷의 불가 구간) |
- 숙소 쓰기(PR 13d): `AccommodationCommandService`와 `AccommodationImageService`에는 흐름만 남았다.
  - 세부 구현은 Implement가 맡는다: `AccommodationReader`(호스트 숙소 조회·잠금), `AccommodationWriter`(생성, 위치·인원 정책·편의시설 교체, 이력 SCD2), `AccommodationValidator`(유형·편의시설 코드, 체크인·체크아웃, 게시 필수 항목, 미래 예약이 있을 때 시간 설정 변경 금지), `AccommodationLocationResolver`(지오코딩 → 시간대), `AccommodationImageReader`·`AccommodationImageWriter`(썸네일 갱신 포함).
  - 입출력은 `AccommodationUpdate`(기본 정보 `AccommodationPatch`, `NewAddress`, `NewAmenity` 목록, `NewOccupancyPolicy`), 숙소 ID, `StoredImage` 목록이다. 변환은 Controller와 요청 DTO의 `toUpdate()`가 맡는다.
- 숙소 상세 캐시 값(PR 13e): `AccommodationDetailSnapshot`은 도메인 루트의 레코드이고, 중첩 값도 응답 DTO 대신 도메인 레코드(주소 요약, 좌표, 호스트, 인원 정책, 편의시설, `StoredImage`, 리뷰 요약)다.
  - 캐시는 클래스 이름 없이 JSON으로 저장되므로, 필드 이름과 구조를 바꾸지 않는 한 타입을 옮겨도 이미 저장된 값을 읽는다.
  - `AccommodationDetailCacheValueJsonTest`가 저장된 JSON 문자열 그대로를 쓰고 읽는지 확인한다. 캐시 값을 바꿀 때는 이 테스트를 먼저 본다.
  - 응답 DTO 변환은 `AccommodationResponse.DetailInfo.from`이 맡는다.
- 숙소 조회(PR 13f): `AccommodationQueryService`도 Repository를 직접 쓰지 않고 `AccommodationReader`(게시 숙소 시간대, 호스트 목록·상세)를 쓴다.
  출력은 `PublicAccommodationDetail`(캐시 스냅샷 + 찜 여부), `AccommodationAvailability`, `HostAccommodationPage`, `HostAccommodationDetail`이고,
  Controller가 `DetailInfo`·`Availability`·`HostAccommodationInfos`·`HostDetail`의 `from`으로 응답을 만든다.
  호스트 목록·상세 projection은 Business 출력에 담기므로 repository 패키지 밖 도메인 루트에 둔다.

#### reservation의 경계 (PR 15a)

payment(결제할 예약의 잠금과 상태 전이), search(검색 문서의 예약 구간), review(작성 자격)가 reservation을 쓴다.

- 다른 도메인은 `ReservationReader`로 읽는다. 결제 흐름의 잠금 조회(`readForUpdate`, `readByUidForUpdate`)도 호출한 쪽 트랜잭션 안에서 Reader를 거치므로 예약 → 날짜 행 잠금 순서는 그대로다.
- 결제가 남기는 예약 이력은 `ReservationHistoryWriter.recordSystem`으로 기록한다.
- 숙소 검색 갱신 요청은 reservation·payment도 accommodation이 소유한 포트 `AccommodationSearchRefreshRequester`를 쓴다.
  요청 내용이 숙소 UID 하나뿐이라 포트를 새로 만들지 않고 공유한다. reservation·payment는 이미 accommodation에 의존하므로 새 의존 방향이 생기지 않는다.
- 날짜 재고(PR 15c-1)는 Implement가 맡는다. `ReservationInventoryManager`는 날짜 행 잠금·점유·해제와 과거 FREE 행 정리를, `ReservationInventorySeeder`는 숙소 재고 seed 포트 구현과 게시 숙소 일괄 seed를 한다.
  잠금·전이 메서드는 `MANDATORY`라서 호출한 Business의 트랜잭션 안에서만 돈다. seed와 정리 스케줄러, 시작 시 seed(`ApplicationRunner`)는 Implement 한 번 호출로 끝나는 유지보수 작업이라 Business 없이 Implement를 바로 부른다.
- 예약 조회(PR 15c-2)는 `ReservationQueryService`가 `ReservationReader`로 읽고 도메인 레코드를 돌려준다. 게스트 목록·상세의 상태와 결제 가능 여부는 조회 시각 기준으로 Business가 계산해 담고(`GuestReservationPage.Item.of`, `GuestReservationDetail.of`), Controller가 `ReservationResponse`의 `from`으로 응답을 만든다.
  조회 projection 4개는 Business 출력에 담기므로 도메인 루트에 둔다.
- 체크아웃(PR 15c-3)은 `ReservationCheckoutService.checkout`이 하나의 `READ_COMMITTED` 트랜잭션에서 한다. 멱등 요청 행 잠금 → 견적 잠금 → 숙소 `FOR SHARE NOWAIT` 스냅샷 → 날짜 행 `FOR UPDATE NOWAIT` → 예약 저장 → 쿠폰 사용 → 재고 점유 → 이력 순서는 그대로다.
  입력은 `ReservationCheckout`(멱등성 지문도 이 두 값으로 만든다), 출력은 `ReservationReady`다. 상태와 결제 가능 여부를 커밋 직전 시각으로 계산하는 점만 전과 다르다(전에는 커밋 뒤 시각).
- 견적·홀드·정리(PR 15c-4)도 Implement를 거친다. 견적은 `ReservationQuoteWriter`로 저장하고 `ReservationQuoteDetail`을, 홀드 해제·결제 시도는 `ReservationReader.readGuestOwnedForUpdate`로 잠근 뒤 `ReservationHoldRelease`·`ReservationPaymentAttempt`를 돌려준다.
  만료 정리는 `ReservationReader.readExpiredPendingBatchForCleanup`과 `ReservationHistoryWriter.recordAll`(JDBC 배치), 견적 정리는 `ReservationQuoteWriter.deleteRetentionExpiredBatch`를 쓴다. 이제 reservation의 Business는 Repository와 web DTO에 의존하지 않는다.
- 예약 취소 API(PR 15b)는 Controller가 결제 취소 Business `PaymentCancellationCommandService`를 직접 호출한다. 전에는 `ReservationService`가 그대로 넘겼는데, 이는 Business가 다른 Business를 부르는 구조라 옮긴 뒤 엄격 규칙에 걸린다.
- 남은 순환은 reservation ↔ payment다. 게스트·호스트 예약 상세가 결제 정보를 결제 테이블 left join으로 한 쿼리에 읽고(쿼리 수 테스트가 SQL 1개를 고정), 응답에 결제 DTO와 결제 enum을 담는다.
  읽기 전용 간선이고 쿼리를 둘로 나누면 계약이 바뀌므로 동결로 남긴다. 예약 취소 요청·응답도 결제 DTO(`PaymentRequest.Cancel`, `PaymentOperationResponse.Cancellation`)를 쓴다.

#### payment의 계층 (PR 16a~16d)

- 결제사 연동 타입(포트 `PaymentProviderGateway`, 명령, 결과, 실패 분류)은 `gateway` 하위 패키지에 둔다(PR 16a). 엔티티가 `service` 패키지에 의존하던 간선이 이것으로 사라졌다.
- 워커와 수동 처리의 트랜잭션 단계는 Implement다(PR 16b): `PaymentOperationLeaseManager`(임대), `PaymentOperationFinalizer`(확정), `PaymentOperationManualReviewProcessor`(수동 처리 트랜잭션).
  메서드별 `@Transactional`을 그대로 두고, 실행 펜스는 트랜잭션 밖의 Business(`PaymentOperationExecutionService`, `PaymentOperationManualReviewCommandService`)가 잡는다.
- 승인·취소 요청, DLT 처리, 복구, 조회는 `PaymentReader`·`PaymentOperationReader`·`PaymentOperationWriter`·`PaymentTransactionReader`·`PaymentOperationManualReviewReader`를 거친다(PR 16c).
  입력은 `PaymentConfirmation`·`PaymentCancellation`, 출력은 `PaymentOperationReceipt`·`PaymentOperationDetail`·`PaymentDetail`·`PaymentOperationManualReviewQueue`다.
  회원의 다음 행동과 재조회 간격은 조회 시각 기준으로 `PaymentOperationDetail.of`가 계산하고, 상태 URL·회원용 상태·안내 문구는 응답 DTO가 만든다.
- 결제 실행기는 Kafka 리스너만 부르는 Business라서 이름 규칙에 맞춰 `PaymentOperationExecutor`를 `PaymentOperationExecutionService`로 바꿨다(PR 16d).

템플릿 구조로 옮긴 도메인은 wishlist(PR 7c), commoncode(PR 8), statistics(PR 9), member(PR 10b-2), auth(PR 10c), image(PR 11), review(PR 12b), accommodation(PR 13g), coupon(PR 14c), reservation(PR 15d), payment(PR 16d), settlement(PR 17a), recentlyviewed(PR 17b), search(PR 17c)다. 다른 도메인을 옮길 때 wishlist를 기준으로 삼는다.

| 계층 | wishlist 위치 |
|---|---|
| Presentation | `core.api.controller.v1.wishlist`(Controller, 문서 인터페이스), `.request`, `.response` |
| Business·Implement·도메인 모델 | `core.domain.wishlist`(`WishlistService`, `WishlistReader`, `WishlistWriter`, 엔티티, enum, 예외, 도메인 레코드) |
| Data Access | `core.domain.wishlist.repository`(Spring Data, `querydsl`, `projection`) |

commoncode도 같은 배치다. 공개 조회 `CommonCodeService`와 관리 `CommonCodeAdminService`가 `CommonCodeReader`(활성 코드 1분 캐시 포함)와 `CommonCodeWriter`를 쓴다.
accommodation은 숙소 유형·편의시설 코드 검증에 `CommonCodeReader.isValidCode`를 쓴다.
statistics는 `RevenueStatsService`가 `RevenueStatsReader`(사전 집계 조회)와 `RevenueStatsWriter`(일자별 재집계)를 쓰고,
진입점 `StatisticsScheduler`가 매일 04:00 UTC에 `RevenueStatsService.recompute`를 호출한다.
member는 `MemberService`(가입·탈퇴·프로필·관리자 확인)와 `MemberAdminService`(역할 변경)가 `MemberReader`와 `MemberWriter`(변경과 이력 SCD2 기록)를 쓴다.
탈퇴 시 세션 무효화 포트는 `core.domain.member.port.SessionInvalidator`에 둔다.
auth는 `AuthService`(로그인·로그아웃)와 `SessionAuthenticationService`(필터의 세션 확인)가 `SessionReader`(세션의 회원 확인),
`SessionWriter`(세션 생성·삭제·회원 세션 전체 무효화), `SessionIssuer`(발급)를 쓴다.
로그인은 비밀번호 비교를 트랜잭션 밖에서 하므로, `SessionIssuer.issue`는 Implement이지만 자기 트랜잭션에서 회원을 잠그고
그 사이 탈퇴하거나 비밀번호가 바뀌지 않았는지 다시 확인한 뒤 세션을 만든다.
image는 숙소·리뷰 이미지가 함께 쓰는 저장 기능만 둔다. 공통 매핑 `Image`, S3 업로드·삭제(`S3ImageUploader`),
트랜잭션 결과에 맞춘 저장소 정리(`ImageStorageTransactionEvents`와 리스너: 커밋 후 삭제, 롤백 후 업로드 객체 정리), 이미지 검증 예외가 `core.domain.image`에 있다.
숙소·리뷰 이미지 엔티티(`AccommodationImage`, `ReviewImage`)는 Repository와 함께 각 소유 도메인에 둔다.
그래서 image는 accommodation과 review에 의존하지 않는다. 공통 응답 DTO `ImageResponse`는 `core.api.controller.v1.image.response`에 있다.
Business가 돌려주는 저장된 이미지는 도메인 레코드 `StoredImage`(ID, 조회 주소)이고, Controller가 `ImageResponse.ImageInfo.from(StoredImage)`로 응답을 만든다.
review는 `ReviewService`(작성·수정·삭제·목록)와 `ReviewImageService`(이미지 업로드·삭제)가 `ReviewReader`·`ReviewWriter`와
`ReviewImageReader`·`ReviewImageWriter`를 쓴다. 목록은 도메인 레코드 `ReviewItem`(QueryDSL `@QueryProjection`)의 커서 페이지 `ReviewPage`로 돌려주고,
Controller가 `ReviewResponse.ReviewInfos.from`으로 응답을 만든다. `ReviewReader`는 reservation이 소유한 포트 `ReservationReviewReader`도 구현한다.
coupon은 관리 `CouponService`·`CouponStockPreparationService`, 조회 `CouponQueryService`, 발급 `CouponSkipLockedIssueService`가
`CouponReader`(MySQL UTC 시각, 정책 `FOR UPDATE` 잠금, 발급 건수, 목록)와 `CouponWriter`(정책 저장·삭제, 재고 번호 추가·회수)를 쓴다.
발급 서비스는 재시도 예산과 시도별 트랜잭션(REQUIRES_NEW, REPEATABLE_READ, 1초 제한)만 갖고, 시도 한 번의 데이터 작업
(정책 `FOR SHARE NOWAIT`, `SKIP LOCKED` 재고 소비, 회원 쿠폰 저장, 실패 뒤 매진·경합 판별)은 `CouponIssuer`가 맡는다.
reservation·payment는 자기 트랜잭션 안에서 `CouponUsageManager`(사용·미리보기·복원·재사용)를 쓴다. 목록은 `CouponCampaignPage`·`MemberCouponPage`로 돌려주고
발급·보유 상태는 도메인 레코드가 계산한다. 발급 지표 기록기는 `core.domain.coupon.monitoring`에 둔다.
reservation은 Business(`ReservationQuoteService`, `ReservationCheckoutService`, `ReservationHoldCommandService`, `ReservationQueryService`, 만료·견적 정리 서비스)와 Implement가 `core.domain.reservation`에 있다.
날짜 재고(`inventory`), 예약 기간·보유·결제 시도 정책(`policy`), 체크아웃 멱등성(`idempotency`), review가 쓰는 포트(`port`)는 하위 패키지로 둔다. 자세한 경계는 위 "reservation의 경계"를 본다.

PR 17j 기준으로 core-api의 애플리케이션 코드는 모두 템플릿 구조(`core.api`, `core.domain`, `core.support`)에 있다.
최상위 패키지에는 `AirbobApplication`만 남았고, 이전 구조 패키지(`kr.kro.airbob.domain`, `common`, `config`, `geo`, `search`, `messaging`)는 없다.

### 경로 계약

배포, CI, 보관된 AWS 도구가 아래 경로를 읽는다. 그래서 모듈을 나눈 뒤에도 이 경로들은 유지한다.

- **실행 JAR**: 루트 `build/libs/airbob.jar`. `core/core-api/build.gradle`의 `bootJar`가 루트 빌드 디렉터리에 쓴다.
- **OpenAPI 산출물**: 루트 `build/openapi`.
- **growth-b 계약 테스트 런타임**: 루트 `build/growth-b-contract-test-runtime`.
- **마이그레이션**: `core/core-api/src/main/resources/db/migration`. JAR 안에서는 `BOOT-INF/classes/db/migration`에 들어간다.
  - growth-b 도구는 JAR의 이 경로에서 마이그레이션을 꺼낸다.
  - OCI Flyway container는 이 디렉터리를 마운트한다.
  - 마이그레이션을 다른 모듈 JAR로 옮기면 위 두 경로가 함께 바뀐다. 따라서 storage 분리 단계에서 따로 다룬다.
- **테스트 작업 디렉터리**: 저장소 루트(`workingDir = rootDir`). 테스트가 `docker/`, `scripts/`, `infra/`, `monitoring/`을 상대경로로 읽기 때문이다.

## 목표 구조

### 모듈

```
airbob/
├─ core/core-api        유일한 실행 모듈 (Presentation, Business, Implement, JPA 엔티티)
├─ support/monitoring   actuator, prometheus 의존성과 monitoring.yml
├─ support/logging      logging.yml
├─ clients/client-slack, clients/client-toss-payments, clients/client-google-geocoding, clients/client-aws-s3   외부 연동 전송 계층만 담당
├─ tests/api-docs       Spring REST Docs 전환 시 생성
└─ (선택) core/core-enum, storage/db-core   엔티티를 분리하는 단계에서 생성
```

- 외부 시스템의 응답을 어떻게 해석하는지는 core-api가 맡는다. 예를 들어 결제 결과 분류와 운영 알림 재시도 의미가 여기에 해당한다. client 모듈은 HTTP 요청과 응답 매핑만 맡는다.
- JPA 엔티티는 상태 전이 로직을 가진 도메인 모델이다. 그래서 당분간 core-api에 둔다.

### core-api 패키지

```
kr.kro.airbob
├─ core.api.config                         앱 설정, OpenAPI 설정(apidocs)
├─ core.api.security                       세션 인증 필터, 관리자 인터셉터, @CurrentMemberId
├─ core.api.controller                     전역 예외 처리, 공통 웹 설정
├─ core.api.controller.v1.<도메인>         Controller, Swagger/문서 인터페이스
│   ├─ request/                            요청 DTO (toXxx()로 도메인 입력을 만든다)
│   └─ response/                           응답 DTO (from(도메인 결과))
├─ core.domain.<도메인>                    @Service(Business), @Component(Implement),
│   │                                      엔티티, 도메인 enum·예외·record
│   └─ repository/                         Spring Data, QueryDSL, projection (Data Access)
└─ core.support.{error,response,cursor,  BaseException·ErrorCode, ApiResponse, 커서 페이징,
       context,persistence,monitoring,    요청 사용자, BaseEntity·이력 기반, 쿼리 수 측정, 검증,
       validation,messaging}              통합 이벤트·outbox·Kafka 인프라(PR 17f)
```

### 계층과 의존 규칙

| 계층 | 형태 | 역할 |
|---|---|---|
| Presentation | `core.api..` | 요청을 검증하고 Business를 호출한 뒤 응답 DTO를 조립한다 |
| Business | `@Service`, 이름 `*Service` | 유스케이스의 흐름만 보이도록 Implement를 조합한다. 트랜잭션 경계를 둔다 |
| Implement | `@Component` (`*Reader`, `*Writer`, `*Validator`, `*Manager` …) | 재사용되는 세부 구현이다. 다른 도메인의 Implement와 협력할 수 있다 |
| 진입점 | `core.domain.<도메인>`의 `@Scheduled`·`@KafkaListener` 컴포넌트(`*Scheduler`, `*Listener`) | 프레임워크가 호출하는 비-HTTP 입구. Controller처럼 Business를 호출해 유스케이스를 시작한다 |
| Data Access | `core.domain.<도메인>.repository` | 영속성. 같은 도메인의 Implement만 사용한다 |

1. **역참조 금지**: `core.domain`은 `core.api`에 의존하지 않는다. Business 메서드는 web Request/Response를 받지도, 반환하지도 않는다.
2. **Presentation은 Business만 호출한다**: Implement나 Repository를 직접 쓰지 않는다.
3. **Business 규칙**: 다른 Business를 부르지 않는다(같은 계층 참조 금지). Repository에도 직접 접근하지 않는다(계층 건너뛰기 금지).
4. **Implement 규칙**: Business에 의존하지 않는다. 진입점(스케줄러·리스너)은 Implement가 아니므로 Business를 호출할 수 있다. 대신 어떤 클래스도 진입점을 호출하지 않는다.
   진입점은 HTTP API가 아니어서 `core.api.controller`가 아니라 자기 도메인 옆(`core.domain.<도메인>`)에 둔다. 도메인 Kafka 어댑터를 도메인 옆에 두는 기존 원칙(CLAUDE.md)과 같다.
5. **Repository 캡슐화**: 다른 도메인의 Repository를 주입하지 않는다. 그 도메인의 Reader/Writer를 쓴다.
6. **도메인 순환 금지**: 순환이 생기면 의존받는 쪽이 포트 인터페이스를 소유하고, 상대 도메인이 그 포트를 구현한다. 기존 예는 `member/port/SessionInvalidator`다.
7. **엔티티는 DTO와 service 패키지에 의존하지 않는다**: 상태로 결정되는 규칙은 엔티티 메서드에 둔다. 예: `Reservation.confirm()`.

#### Reader/Writer와 포트가 뜻하는 것

- **Reader/Writer는 CQRS가 아니다.**
  - Implement 계층 컴포넌트를 역할(조회·저장·검증)로 나눠 부르는 이름이다.
  - 읽기 모델과 쓰기 모델을 따로 두지 않는다. 같은 엔티티와 같은 DB를 쓴다.
  - 도메인의 Repository는 그 도메인의 Reader/Writer만 쓴다. 다른 도메인은 그 Reader를 통해 읽는다.
  - 예: `MemberReader.readActive`, `AccommodationReader.readPublished`.
- **포트는 헥사고날 아키텍처 전체를 도입한다는 뜻이 아니다.**
  - 계층 구조는 그대로이고, 순환 의존이 생기는 곳에만 의존 역전(DIP)을 쓴다.
  - 필요로 하는 쪽 도메인이 인터페이스를 소유하고(`<도메인>/port`), 제공하는 쪽 도메인의 Implement 컴포넌트가 구현한다.
  - 예: accommodation과 search는 위시리스트 포함 여부만 필요하다. 그래서 `AccommodationWishlistReader`와 `SearchWishlistReader`를 각자 소유하고, `WishlistReader`가 둘 다 구현한다.
  - wishlist → accommodation 의존은 그대로 두면서 반대 방향만 없앤다.

#### Business 입출력과 도메인 레코드

wishlist(PR 7b)에서 정한 기준이다. 도메인을 옮길 때 같은 기준을 따른다.

- **입력**: Business는 요청 DTO 대신 원시값이나 도메인 값을 받는다. 예: `createWishlist(String name, Long memberId)`.
  Controller가 `request.name()`처럼 꺼내 넘긴다. 필드가 많으면 요청 DTO에 `toXxx()`를 두고 도메인 입력 record를 만든다.
  생성은 `NewXxx`, null 필드를 유지하는 부분 수정은 `XxxPatch`로 이름 짓는다(예: `NewCommonCode`, `CommonCodePatch`).
- **출력**: 엔티티 ID나 도메인 레코드를 돌려준다. 엔티티는 Business 밖으로 내보내지 않는다. 응답 DTO는 `XxxResponse.from(도메인 레코드)`로 Controller에서 만든다.
  UTC `LocalDateTime` → `Instant` 변환이나 요청에 따라 필드를 null로 비우는 일처럼 응답 모양만 바꾸는 규칙은 `from`에 둔다.
  엔티티 전체 값이 필요한 응답은 `XxxDetail.from(엔티티)` 레코드를 쓴다(예: `CommonCodeDetail`).
- **기준 시각에 따른 값**: 조회 결과의 상태처럼 기준 시각으로 정해지는 값은 Business가 계산해 도메인 레코드에 담는다(예: `CouponCampaignPage.Item.of(projection, now)`). 응답 DTO는 담긴 값을 옮기기만 한다.
- **시도마다 새 트랜잭션을 여는 흐름**: 재시도 횟수·시간 예산과 시도별 트랜잭션 설정(`TransactionTemplate`의 전파·격리 수준·제한 시간)은 Business에 둔다. 시도 한 번의 데이터 작업은 Implement에 둔다(예: `CouponSkipLockedIssueService`와 `CouponIssuer`). 잠금과 트랜잭션 계약을 한 곳에서 볼 수 있게 하기 위해서다.
- **다른 도메인이 쓰는 기능**: Service가 아니라 Reader/Writer에 둔다. Service는 Controller용 흐름만 갖는다.
  예: 숙소의 코드 검증은 `CommonCodeService`가 아니라 `CommonCodeReader.isValidCode`를 쓴다.
- **값 정규화 규칙**: 코드 대문자화처럼 저장 값의 규칙은 엔티티 정적 메서드에 둔다(예: `CommonCode.normalizeCode`). 요청 DTO의 공백 제거는 검증 전에 필요하므로 그대로 둔다.
- **엔티티 입력**: 엔티티 팩터리와 변경 메서드는 요청 DTO나 외부 연동 결과(`GeocodeResult` 등) 대신 도메인 입력 레코드나 값을 받는다(예: `Address.createAddress(NewAddress, 위도, 경도)`). 변환은 요청 DTO의 `toXxx()`가 맡는다.
- **잠금 후 다시 읽기**: `EntityManager.refresh(..., PESSIMISTIC_WRITE)`처럼 영속성 컨텍스트를 직접 다루는 일은 Repository 커스텀 메서드에 둔다(예: `MemberRepository.refreshWithWriteLock`). Business·Implement는 `EntityManager`를 직접 쓰지 않는다.
- **도메인 레코드 위치**: Business가 반환하는 조회 결과는 도메인 루트 패키지에 둔다(예: `WishlistSummary`, `WishlistAccommodationItem`).
  QueryDSL이 `@QueryProjection`으로 바로 만들어도 된다. Implement 안에서만 쓰는 projection(소유자 확인용 헤더, native query 인터페이스)은 `repository/projection`에 남긴다.
  `repository` 패키지의 타입은 엄격 규칙에서 Data Access로 분류되므로 Business가 직접 받으면 안 된다.
- **커서 페이징**: `CursorRequest`, `CursorResponse.PageInfo`, `CursorPageInfoCreator`는 도메인과 무관한 `core.support` 타입이다.
  그래서 Business가 `CursorPageRequest`를 받고, 도메인 레코드 목록과 `PageInfo`를 묶은 결과(예: `WishlistSummaryPage`)를 돌려준다.

### ArchUnit으로 규칙 강제

규칙은 `core/core-api/src/test/java/kr/kro/airbob/architecture`의 ArchUnit 테스트가 강제한다.
`./gradlew :core:core-api:test --tests 'kr.kro.airbob.architecture*'`로 몇 초 안에 실행된다.
Stop 훅도 core-api의 main 코드가 바뀌면 이 테스트를 함께 실행한다.
코드 규칙(`CodingRulesTest`)은 [코딩 컨벤션](../development/coding-convention.md#코드-규칙)에 있다.

- **`LayeredArchitectureTest`(엄격)**: 애플리케이션 코드(`kr.kro.airbob.core..`)에 규칙 1~4와 이름 규칙을 적용한다.
  `core.support`가 도메인·Presentation·설정에 의존하지 않는지도 확인한다.
  `core.domain.<도메인>`은 엔티티와 Service·Reader가 한 패키지에 있어서, 동결 테스트가 패키지 이름(`dto`·`api`·`service`)으로는 규칙 7을 검사하지 못한다.
  그래서 엄격 테스트가 엔티티의 `@Service`·`@Component` 의존을 따로 막는다.
  `@Scheduled`·`@KafkaListener` 메서드를 가진 컴포넌트는 진입점으로 보고 Implement 규칙(4)에서 빼며, 대신 `core..`의 어떤 클래스도 진입점에 의존하지 못하게 한다.
  모든 규칙은 적어도 한 클래스에 적용되어야 통과한다. 패키지 이름이 바뀌어 규칙이 아무것도 검사하지 않게 되면 실패한다.
  이전 중에는 빈 패키지를 허용했지만(`allowEmptyShould`) 이전이 끝나 PR 17j에서 뺐다.
- **`LegacyDependencyFreezeTest`(동결)**: 애플리케이션 전체에 규칙 5~7을 적용한다.
  - 현재 위반은 `core/core-api/src/test/resources/archunit_store`에 기록되어 있다.
  - 기록에 없는 위반이 생기면 실패한다.
  - 위반을 고치면 다음 실행에서 저장소의 해당 줄이 자동으로 지워진다. 줄어든 저장소를 수정과 함께 커밋한다.
  - 위반 메시지는 클래스 이름이나 도메인 이름만 담는다. 생성자 인자 추가 같은 무관한 변경이 기존 위반을 새 위반으로 바꾸지 않게 하기 위해서다.
  - 동결 시점(PR 6)의 위반 수는 다음과 같다.

| 규칙 | 위반 |
|---|---|
| 다른 도메인의 Repository 사용 | 35 (클래스 → Repository 쌍) |
| 도메인 순환 | 26개 간선(13쌍): accommodation↔image·reservation·review·search·wishlist, auth↔member, image↔review, payment↔accommodation·reservation·search, reservation↔review·search, review↔search, search↔wishlist |
| 엔티티·임베디드 타입의 DTO·service 의존 | 14 |

진행 현황(저장소 기준):

| 시점 | Repository 사용 | 순환 간선 | 엔티티 의존 |
|---|---|---|---|
| PR 6 (동결) | 35 | 26 | 14 |
| PR 7a (wishlist 경계) | 30 | 22 (wishlist 관련 4개 해소) | 14 |
| PR 10a (auth·member 경계) | 27 | 20 (auth ↔ member 해소) | 14 |
| PR 10b-1 (member Repository 사용 정리) | 22 | 20 | 14 |
| PR 10b-2 (member 이전) | 22 | 20 | 13 (`Member` → 가입 요청 DTO 해소) |
| PR 11 (image 이전) | 22 | 16 (image ↔ accommodation, image ↔ review 해소) | 13 |
| PR 12a (review 경계) | 18 | 10 (review ↔ accommodation, review ↔ reservation, review ↔ search 해소) | 13 |
| PR 12b (review 이전) | 18 | 10 | 13 |
| PR 13a (accommodation Repository 사용 정리) | 9 | 10 | 13 |
| PR 13b (accommodation 순환 정리) | 9 | 5 (accommodation ↔ reservation, accommodation ↔ search, payment → accommodation 해소) | 13 |
| PR 13c (accommodation 엔티티 입력) | 9 | 5 | 9 (`Accommodation`·`Address`·`OccupancyPolicy` → 요청 DTO·`GeocodeResult` 해소) |
| PR 13d (accommodation 쓰기 계층화) | 9 | 5 | 9 |
| PR 13e (accommodation 상세 캐시 값) | 9 | 5 | 9 |
| PR 13f (accommodation 조회 출력) | 9 | 5 | 9 |
| PR 13g (accommodation 이전) | 9 | 5 | 9 |
| PR 14a (coupon 입력과 사용 처리) | 9 | 5 | 7 (`Coupon` → 요청 DTO 해소) |
| PR 14b (coupon 계층화) | 9 | 5 | 7 |
| PR 14c (coupon 이전) | 9 | 5 | 7 |
| PR 15a (reservation 경계) | 0 (payment·search → reservation Repository 해소) | 2 (reservation ↔ search, payment → search 해소) | 7 |
| PR 15b (reservation 취소 경로와 견적 입력) | 0 | 2 | 6 (`ReservationQuote` → 요청 DTO 해소) |
| PR 15c-1 (reservation 재고 계층) | 0 | 2 | 6 |
| PR 15c-2 (reservation 조회) | 0 | 2 | 6 |
| PR 15c-3 (reservation 체크아웃) | 0 | 2 | 6 |
| PR 15c-4 (reservation 견적·홀드·정리) | 0 | 2 | 6 |
| PR 15d (reservation 이전) | 0 | 2 | 6 |
| PR 16a (payment 결제사 연동 타입) | 0 | 2 | 0 (`Payment`·`PaymentTransaction`·`PaymentOperation` → `service` 패키지 해소) |
| PR 16b (payment 워커·수동 처리 계층) | 0 | 2 | 0 |
| PR 16c-1 (payment 명령) | 0 | 2 | 0 |
| PR 16c-2 (payment 조회) | 0 | 2 | 0 |
| PR 16d (payment 이전) | 0 | 2 | 0 |
| PR 17a (settlement 이전) | 0 | 2 | 0 |
| PR 17b (recentlyviewed 이전) | 0 | 2 | 0 |
| PR 17c (search 이전) | 0 | 2 | 0 |
| PR 17d (common 이전) | 0 | 2 | 0 |
| PR 17e (config 이전) | 0 | 2 | 0 |
| PR 17f (messaging 인프라 이전) | 0 | 2 | 0 |
| PR 17g (운영 알림 이전) | 0 | 2 | 0 |
| PR 17h (geo 정리) | 0 | 2 | 0 |
| PR 17i (Google Geocoding client 모듈) | 0 | 2 | 0 |
| PR 17j (마무리) | 0 | 2 (동결로 남긴 reservation ↔ payment. 위 "reservation의 경계" 참고) | 0 |
| PR 18 (S3 client 모듈) | 0 | 2 | 0 |

새 위반을 저장소에 직접 추가해서 테스트를 통과시키지 않는다. 의도적으로 기준선을 다시 잡아야 하면
`archunit.properties`에 `freeze.refreeze=true`를 잠시 두고 한 번 실행한 뒤 되돌리고, 이유를 PR에 남긴다.
테스트는 저장소 루트를 작업 디렉터리로 실행해야 저장소 경로를 찾는다. Gradle로 실행하면 이 조건이 맞춰진다.

### 템플릿과 다르게 가는 부분

- **엔티티를 core-api의 도메인 모델로 유지한다.** 템플릿은 엔티티를 storage 모듈에 두고 별도 도메인 모델로 변환한다. airbob은 엔티티의 상태 전이 로직, 도메인 간 연관관계, `FOR UPDATE NOWAIT` 쿼리가 많아서 이 분리를 선택 단계로 미룬다.
- **버전은 `gradle/libs.versions.toml`로 관리한다.** `gradle.properties`는 git에 올리지 않는 로컬 JDK 설정 파일이기 때문이다.
- **HTTP 클라이언트는 Feign 대신 기존 RestClient를 유지한다.**
- **spring-javaformat, Spring Boot 4/Java 25 업그레이드, 테스트 태그 체계는 이 계획에 포함하지 않는다.**
- **API 응답 포맷(`ApiResponse`)과 OpenAPI 스냅샷은 구조 변경 중에 바꾸지 않는다.**
- **예외는 `ErrorCode` enum과 도메인별 예외 클래스를 유지한다.** `ErrorCode`가 이미 HTTP 상태·코드·메시지를 한곳에 두어 템플릿의 `ErrorType` 역할을 한다.
  남은 차이는 `BaseException`을 상속한 도메인 예외 클래스(85개)를 `CoreException(ErrorType)` 하나로 합치는지뿐이다.
  합치면 테스트의 예외 타입 단언을 대량으로 바꿔야 하고 구체적인 예외 타입으로 분류하기 어려워지는 데 비해 얻는 것이 적어 보류한다.

## PR 로드맵

| PR | 내용 | 확인할 것 | 상태 |
|---|---|---|---|
| 1 | 멀티모듈 골격: 소스를 `core/core-api`로 이동, version catalog 도입, 경로 계약 유지, 훅·규칙·문서 경로 갱신 | 전체 테스트, 루트 산출물 경로, CI scope·infra 테스트 | 완료 |
| 2 | `support/monitoring`, `support/logging`: 설정 yml 분리 | 프로필별 effective 프로퍼티가 전후로 같은지 | 완료 |
| 3 | `clients/client-slack` | `OperatorAlertDelivery*Exception`의 의미(retry/DLT) 보존 | 완료 |
| 4 | `clients/client-toss-payments`: 전송 계층만 분리하고 분류 로직은 core-api 어댑터에 남김 | payment runbook을 먼저 읽는다. `TossPaymentsAdapterTest`의 단언을 바꾸지 않는다 | 완료 |
| 5 | `common.exception`, `common.dto`, `cursor`를 `core.support.*`로, `GlobalExceptionHandler`를 `core.api.controller`로 이동 | OpenAPI 스냅샷이 그대로인지 | 완료 |
| 6 | ArchUnit 규칙: 새 패키지는 엄격, 레거시는 동결 | 동결 저장소 커밋 | 완료 |
| 7a | wishlist 경계: `MemberReader`·`AccommodationReader` 추가, accommodation·search가 소유한 포트를 wishlist가 구현해 순환 해소, 다른 도메인의 wishlist Repository 사용 제거 | 동결 위반이 줄었는지 | 완료 |
| 7b | wishlist 내부 계층화: `WishlistReader`/`WishlistWriter` 추출, Business 입출력에서 web DTO 제거, 목록 조회 projection을 도메인 레코드로 변경, 테스트 정리 | API 응답·OpenAPI 스냅샷 불변 | 완료 |
| 7c | wishlist를 `core.api.controller.v1.wishlist`·`core.domain.wishlist`로 이동하고 도메인 이전 레시피 확정 | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 8 | commoncode 이전: 레시피 3단계를 한 PR로. `CommonCodeReader`(캐시·조회)·`CommonCodeWriter` 추출, 입출력 도메인 레코드, accommodation이 Reader로 코드 검증 | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 9 | statistics 이전: `RevenueStatsReader`·`RevenueStatsWriter` 추출, 조회 결과를 도메인 레코드 `DailyRevenueTotal`로, 스케줄러를 진입점으로 분류하는 ArchUnit 규칙 추가 | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 10a | auth·member 경계: 웹 보안을 `core.api.security`로 옮겨 순환 해소, auth의 member Repository 사용을 `MemberReader`로, 비밀번호 검증을 `Member`로, 필터의 세션 조회를 `SessionAuthenticationService`로, 관리자 확인을 `MemberService.isActiveAdmin`으로 | 동결 위반 감소, 스냅샷 불변 | 완료 |
| 10b-1 | member 경계: accommodation·coupon·reservation(2)·review의 `MemberRepository` 사용을 `MemberReader`(`readActive`, `readActiveForUpdate`, `readReference`)로. 옮길 때 동결 위반 메시지(클래스 전체 이름)가 바뀌어 새 위반이 되지 않도록 먼저 없앤다 | 동결 위반 감소, 쿼리 불변 | 완료 |
| 10b-2 | member 이전: 내부 계층화(Reader/Writer, 입출력 레코드, 엔티티의 요청 DTO 의존 제거)와 패키지 이동 | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 10c | auth 이전: `SessionReader`·`SessionWriter`·`SessionIssuer`(Implement) 추출, 세션 저장소가 직접 구현하던 `SessionInvalidator`를 `SessionWriter`로, 쓰이지 않던 `NotEqualHostException` 삭제, 패키지 이동 | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 11 | image 이전: 숙소·리뷰 이미지 엔티티를 Repository가 있는 소유 도메인으로 옮겨 순환 해소, 저장 기능(`Image`, S3 업로더, 저장소 정리 이벤트·리스너, 예외)은 `core.domain.image`로, `ImageResponse`는 `core.api.controller.v1.image.response`로 | 동결 위반 감소, 스냅샷 불변 | 완료 |
| 12a | review 경계: 리뷰 요약(엔티티·Repository·응답 DTO)을 accommodation으로 옮기고 `AccommodationReviewSummaryReader`·`AccommodationReviewSummaryWriter` 추가, review의 숙소·예약 Repository 사용을 `AccommodationReader`·`ReservationReader`로, reservation의 리뷰 확인을 포트 `ReservationReviewReader`(구현 `ReviewReader`)로 | 동결 위반 감소, 쿼리·스냅샷 불변 | 완료 |
| 12b | review 이전: `ReviewReader`·`ReviewWriter`·`ReviewImageReader`·`ReviewImageWriter` 추출, Business 입출력을 원시값과 도메인 레코드(`ReviewItem`, `ReviewPage`, `StoredImage`)로, 패키지 이동 | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 13a | accommodation Repository 사용 정리: reservation(견적·체크아웃·재고 seed)·search·recentlyviewed의 accommodation Repository 사용을 `AccommodationReader`·`AccommodationAmenityReader`로, accommodation의 예약 조회를 `ReservationReader`로. 다른 도메인이 받는 projection(`InventorySeedTarget`, `RecentlyViewedAccommodationProjection`)은 repository 패키지 밖 도메인 루트로 | 동결 위반 감소, 쿼리·잠금 순서 불변 | 완료 |
| 13b | accommodation 순환 정리: accommodation이 소유한 포트(검색 갱신 요청, 재고 seed, 미래 예약 확인, 예약 가능일)를 search·reservation이 구현해 accommodation → reservation·search 간선 제거, 검색 문서 → 주소 응답 변환을 search로 | 동결 위반 감소, 트랜잭션 경계 불변 | 완료 |
| 13c | accommodation 엔티티 입력: 엔티티가 받던 요청 DTO를 도메인 입력 레코드(`AccommodationPatch`, `NewAddress`, `NewOccupancyPolicy`)로, `Address`는 `GeocodeResult` 대신 좌표를 받음 | 동결 위반 감소, 스냅샷 불변 | 완료 |
| 13d | accommodation 쓰기 계층화: `AccommodationWriter`·`AccommodationValidator`·`AccommodationLocationResolver`·`AccommodationImageReader`·`AccommodationImageWriter` 추출, 쓰기 Business 입출력을 `AccommodationUpdate`·숙소 ID·`StoredImage`로 | 호출 순서·트랜잭션 경계·스냅샷 불변 | 완료 |
| 13e | accommodation 상세 캐시 값: `AccommodationDetailSnapshot`을 도메인 루트로 옮기고 중첩 값을 도메인 레코드로. 바꾸기 전 캐시 JSON을 문자열로 고정한 테스트를 추가해 저장된 값과 호환 확인 | 캐시 JSON·스냅샷 불변 | 완료 |
| 13f | accommodation 조회 출력: 공개 상세·가용일·호스트 목록·호스트 상세의 Business 출력을 도메인 레코드로(repository 패키지 projection 포함) | 엄격 규칙 준비, 스냅샷 불변 | 완료 |
| 13g | accommodation 패키지 이동(cache 하위 패키지 포함) | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 14a | coupon 입력과 사용 처리: `Coupon`이 요청 DTO 대신 `NewCoupon`·`CouponPatch`를 받음, 관리 서비스 입력을 도메인 레코드로, 다른 도메인이 쓰는 `CouponUsageService`를 Implement `CouponUsageManager`로(트랜잭션 참여 방식 그대로) | 동결 위반 감소, 스냅샷 불변 | 완료 |
| 14b | coupon 계층화: 관리·재고 준비·조회의 Repository 사용을 `CouponReader`·`CouponWriter`로, 발급 시도 한 번의 데이터 작업을 `CouponIssuer`로(재시도와 시도별 트랜잭션은 Business에 그대로), 조회 출력을 도메인 레코드(`CouponCampaignPage`, `MemberCouponPage`)로 | `coupon.md`의 잠금·트랜잭션 계약 불변 | 완료 |
| 14c | coupon 패키지 이동 | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 15a | reservation 경계: payment·search의 reservation Repository 사용을 `ReservationReader`(잠금 조회, 활성 예약 구간)·`ReservationHistoryWriter`로, reservation·payment의 search 발행기 사용을 accommodation 포트 `AccommodationSearchRefreshRequester`로 | 동결 위반 감소, 잠금 순서 불변 | 완료 |
| 15b | 예약 취소 API가 결제 취소 Business(`PaymentCancellationCommandService`)를 직접 호출(`ReservationService`의 위임 제거), `ReservationQuote`가 요청 DTO 대신 `NewReservationQuote`를 받음 | 동결 위반 감소, 스냅샷 불변 | 완료 |
| 15c-1 | reservation 재고 계층: 여러 Business와 payment가 쓰는 `ReservationInventoryService`를 Implement `ReservationInventoryManager`로, 숙소 재고 seed 포트 구현을 `ReservationInventorySeeder`로, 과거 FREE 행 정리를 `ReservationInventoryManager`로(메서드별 `@Transactional` 그대로) | `reservation.md`·`payment.md`의 락 순서와 트랜잭션 경계 불변식 | 완료 |
| 15c-2 | 예약 조회: 게스트·호스트 목록과 상세를 `ReservationQueryService`로 옮기고 `ReservationReader`로 읽음, 출력을 도메인 레코드(`GuestReservationPage`, `GuestReservationDetail`, `HostReservationPage`, 호스트 상세 projection)로 | 스냅샷·쿼리 수 불변 | 완료 |
| 15c-3 | 체크아웃: `ReservationService`와 `ReservationTransactionService`를 `ReservationCheckoutService`로 합침, Repository 사용을 `ReservationReader`·`ReservationWriter`·`ReservationHistoryWriter`·`ReservationQuoteReader`로, 입력 `ReservationCheckout`·출력 `ReservationReady` | `reservation.md`·`payment.md`의 락 순서와 트랜잭션 경계 불변식 | 완료 |
| 15c-4 | 견적·홀드·만료 정리·견적 정리: Repository 사용을 `ReservationReader`·`ReservationHistoryWriter`(배치 기록 포함)·`ReservationQuoteWriter`로, 견적 입력 `NewReservationQuote`, 출력 `ReservationQuoteDetail`·`ReservationHoldRelease`·`ReservationPaymentAttempt` | `reservation.md`·`payment.md`의 락 순서와 트랜잭션 경계 불변식 | 완료 |
| 15d | reservation 패키지 이동: 도메인 루트·`entity`·`exception`·`service`·`command`·`scheduler`는 `core.domain.reservation`으로 합치고, `inventory`·`policy`·`idempotency`·`port`·`repository`·`config`는 하위 패키지로 둠 | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 16a | payment 결제사 연동 타입: 결제사 포트·명령·결과·실패 분류(`service.gateway`)를 `gateway` 패키지로, `PaymentExecutionMode`를 도메인 루트로 옮겨 엔티티의 `service` 패키지 의존을 없앰 | 동결 위반 감소, 스냅샷 불변 | 완료 |
| 16b | payment 워커·수동 처리 계층: Business가 부르던 트랜잭션 단계를 Implement로(`PaymentOperationLeaseService` → `PaymentOperationLeaseManager`, `PaymentOperationFinalizer`는 `@Component`로, `PaymentOperationManualReviewTransactionService` → `PaymentOperationManualReviewProcessor`). 메서드별 `@Transactional`, 실행 펜스, 임대는 그대로 | `payment.md`의 실행 펜스와 수동 처리 불변식 | 완료 |
| 16c-1 | payment 명령: 결제 승인·취소 요청, DLT 처리, 복구가 `PaymentReader`·`PaymentOperationReader`·`PaymentOperationWriter`를 씀. 입력 `PaymentConfirmation`·`PaymentCancellation`, 출력 `PaymentOperationReceipt`(상태 URL은 응답 DTO가 만듦) | `payment.md`의 실행 펜스와 수동 처리 불변식 | 완료 |
| 16c-2 | payment 조회: operation 상세, 결제 정보, 수동 검토 대기열이 `PaymentOperationReader`·`PaymentReader`·`PaymentTransactionReader`·`PaymentOperationManualReviewReader`를 씀. 출력 `PaymentOperationDetail`(회원의 다음 행동과 재조회 간격을 serverTime 기준으로 계산)·`PaymentDetail`·`PaymentOperationManualReviewQueue`. Business가 받는 projection 두 개를 `repository/projection`에서 도메인 루트로 | `payment.md`의 실행 펜스와 수동 처리 불변식 | 완료 |
| 16d | payment 패키지 이동: 도메인 루트·`entity`·`exception`·`service`·`scheduler`는 `core.domain.payment`로 합치고, `gateway`·`infrastructure`·`messaging`·`monitoring`·`repository`·`config`는 하위 패키지로 둠. `PaymentOperationExecutor` → `PaymentOperationExecutionService`(이름 규칙) | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 17a | settlement 이전: `SettlementReader`·`SettlementHistoryWriter`·`SettlementGenerator`(월 집계·적재. 월 단위 분산 락과 트랜잭션은 Business에 그대로) 추출, 출력을 도메인 레코드(`SettlementSummary`, `HostSettlementDetail`, `HostSettlementTotals`)로, Implement만 읽는 원장 집계 projection은 `repository/projection`으로, 패키지 이동 | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 17b | recentlyviewed 이전: Redis ZSET 기록 읽기·쓰기를 `RecentlyViewedReader`·`RecentlyViewedWriter`로, 출력을 도메인 레코드 `RecentlyViewedAccommodation` 목록으로, 응답 DTO를 `AccommodationResponse`에서 `RecentlyViewedResponse`로 옮김(스키마 이름 유지. accommodation 응답이 recentlyviewed 레코드를 알면 순환이 생기므로), 패키지 이동 | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 17c | search 이전: 검색 조건을 도메인 레코드 `AccommodationSearchCondition`으로(요청 DTO의 `toCondition`), Elasticsearch 질의 생성·실행을 `AccommodationDocumentReader`로, 색인 저장·삭제를 `AccommodationDocumentWriter`로, 출력을 `AccommodationSearchPage`로(페이지 계산 포함), 패키지 이동. AWS 성능 실험실 검색 프로브는 이동 전후 jar 모두에서 리더를 찾음 | `search.md`의 색인 불변식, 스냅샷 불변 | 완료 |
| 17d | common 이전: `context`·`persistence`(BaseEntity, 이력 기반)·`monitoring`·`validation`을 `core.support.*`로 | 엄격 규칙 통과, 스냅샷 불변 | 완료 |
| 17e | config 이전: 앱 설정을 `core.api.config`로(템플릿 위치), 숙소 상세 캐시 전용 Redis 설정은 `core.domain.accommodation.cache.config`로, `config.apidocs`는 `core.api.config.apidocs`로 | 스냅샷 불변 | 완료 |
| 17f | messaging 기술 인프라(`event`, `outbox`, `infrastructure.kafka`)를 `core.support.messaging`으로 | `messaging.md`의 outbox·토픽 불변식 | 완료 |
| 17g | 운영 알림(`messaging.alert`)을 도메인 `core.domain.operatoralert`로 | `messaging.md`의 알림 불변식 | 완료 |
| 17h | geo 포트·어댑터를 소비 도메인 `core.domain.accommodation.location`으로(`GeocodingGateway`, `GoogleGeocodingAdapter`), 도메인 결과에서 Google 타입 viewport 제거 | 위치 확인 동작 불변 | 완료 |
| 17i | Google Geocoding HTTP 전송을 `clients/client-google-geocoding`으로(client-toss-payments 방식). URL 조립·호출·응답 DTO는 모듈로, 키·활성화·`OK` 판정·실패 매핑은 `GoogleGeocodingAdapter`에 | CI scope, 모듈 테스트 | 완료 |
| 17j | 마무리: ArchUnit의 레거시 패키지 참조와 엄격 규칙의 `allowEmptyShould` 정리, 현재 상태·목표 구조·CLAUDE.md 갱신 | 동결 저장소 | 완료 |
| 18 | S3 전송을 `clients/client-aws-s3`로 분리(17i와 같은 방식). 업로드·삭제는 모듈의 `S3StorageClient`로, 쓰기 허용·키 이름·CloudFront URL 변환은 `S3ImageUploader`에 | 이미지 업로드·삭제 동작 불변 | 완료 |
| 이후 | Spring REST Docs 전환과 `tests/api-docs` 모듈. springdoc 스냅샷 태스크, `ApiDocumentationTest`, `api-docs.md`, CI scope를 대체한다 | 문서 산출물 경로를 CI·문서와 함께 바꾼다 | |
| 선택 | `core-enum`과 `storage/db-core`로 엔티티 분리. 분리 전에 목표를 먼저 정한다(아래 "엔티티 분리 방식") | 마이그레이션의 JAR 경로 계약 | |
| 보류 | 예외를 템플릿의 `ErrorType`으로 통합 | 위 "템플릿과 다르게 가는 부분"의 예외 항목 참고 | |

권장 순서는 S3 모듈 분리(PR 18) → REST Docs 전환(이후) → 필요가 생기면 엔티티 분리다. S3 분리는 작고 독립적이고, REST Docs 전환은 다른 항목과 서로 의존하지 않는다.
엔티티 분리는 가장 크고 경로 계약을 바꾸므로 목표가 생길 때 한다.

### 엔티티 분리 방식

- **엔티티를 한 모듈로 옮기기만 하는 경우**: 모든 엔티티가 `storage/db-core`에 함께 있으므로 `Payment.reservation`, `Reservation.accommodation` 같은 연관관계를 그대로 둘 수 있다.
  다만 엔티티의 상태 전이 로직도 함께 옮겨져 저장 모듈이 도메인 로직을 갖게 되므로, 템플릿의 의도(storage는 영속성만)와 맞지 않고 얻는 것이 적다.
- **다른 애그리거트를 ID로 참조하도록 바꿔야 하는 경우**: 아래 두 방식에서만 필요하다.
  - 템플릿처럼 엔티티(저장 모델)와 도메인 모델을 나눌 때. 순수 도메인 객체는 지연 로딩으로 다른 애그리거트를 따라갈 수 없으므로 ID를 들고, 필요하면 그 도메인의 Reader로 읽는다.
  - 도메인별로 모듈을 나눌 때(예: payment 모듈). 다른 모듈의 엔티티와 연관관계를 맺으면 그 모듈에 컴파일 의존이 생기므로 ID로 끊는다.
- 동결로 남긴 reservation ↔ payment 순환(위 "reservation의 경계")도 이 두 방식 중 하나를 택할 때 함께 정리한다.

### 도메인 이전 레시피 (wishlist 파일럿에서 확정, PR 7a~7c)

옮기는 순간부터 엄격 규칙이 적용된다. 그래서 경계와 내부 계층을 먼저 정리하고, 패키지 이동은 마지막에 한다.
작은 도메인은 세 단계를 한 PR로 합쳐도 된다. 큰 도메인은 wishlist처럼 나눈다.

**1단계: 경계 정리** (wishlist는 PR 7a)

1. 다른 도메인이 이 도메인의 Repository를 쓰던 곳을 이 도메인의 Reader로 바꾼다.
2. 이 도메인이 다른 도메인의 Repository를 쓰던 곳은 그 도메인의 Reader(`MemberReader.readActive` 등)로 바꾼다.
3. 순환이 생기면 필요한 쪽이 소유한 포트를 이 도메인의 Reader가 구현한다.
   다만 순환의 원인이 이 도메인에 있는, 다른 도메인에 속한 자식 엔티티라면 포트 대신 그 엔티티를 Repository가 있는 소유 도메인으로 옮긴다
   (예: PR 11의 `AccommodationImage`, `ReviewImage`). 클래스 이름이 같으면 테이블 이름과 JPQL은 바뀌지 않는다.
4. 동결 저장소에서 줄어든 위반을 함께 커밋한다.
5. 저장소에서 이 도메인의 클래스 이름이 들어간 위반을 찾는다(`grep <도메인> archunit_store/*`).
   위반 메시지에는 클래스 전체 이름이 들어가므로, 남은 채로 옮기면 기존 위반은 "해결"되고 같은 내용이 새 위반으로 잡혀 실패한다.
   다른 도메인이 이 도메인의 Repository를 쓰는 위반은 이동 전에 Reader로 바꾼다(예: PR 10b-1).
6. 동결 규칙은 `repository` 패키지에 있는 타입(Repository의 중첩 인터페이스, `repository/projection`)을 받아 쓰는 것도 Repository 사용으로 본다.
   다른 도메인에 돌려주는 projection은 도메인 루트 패키지로 옮기고 Reader가 돌려준다(예: PR 13a의 `InventorySeedTarget`).

**2단계: 내부 계층화** (wishlist는 PR 7b)

1. Service의 세부 구현을 Reader/Writer/Validator로 추출한다. Service에는 흐름만 남긴다.
   잠금 순서와 트랜잭션 경계는 그대로 둔다. 잠금을 잡는 조회는 Reader의 `...ForUpdate` 메서드로 옮기고, Business가 그 순서대로 호출한다.
2. Business 시그니처에서 Request/Response를 제거한다. 변환은 Controller와 DTO 팩토리가 맡는다.
   기준은 위의 "Business 입출력과 도메인 레코드"를 따른다. 서비스를 직접 호출하는 통합 테스트는 결과를 `XxxResponse.from`으로 바꿔 기존 JSON 계약 단언을 유지한다.
3. Business가 받는 조회 결과를 `repository` 패키지 밖의 도메인 레코드로 바꾼다. 응답 DTO의 `@QueryProjection`도 도메인 레코드로 옮긴다.

**3단계: 패키지 이동** (wishlist는 PR 7c)

1. `git mv`로 옮긴다.

   | 이전 위치 | 새 위치 |
   |---|---|
   | `domain.<도메인>.api` | `core.api.controller.v1.<도메인>` |
   | `domain.<도메인>.dto`의 요청 DTO | `core.api.controller.v1.<도메인>.request` |
   | `domain.<도메인>.dto`의 응답 DTO | `core.api.controller.v1.<도메인>.response` |
   | `domain.<도메인>.{entity,exception,service}`, 도메인 레코드 | `core.domain.<도메인>` (하위 패키지 없이) |
   | `domain.<도메인>.repository` | `core.domain.<도메인>.repository` (`querydsl`, `projection` 유지) |
   | `domain.<도메인>.scheduler` 등 진입점 | `core.domain.<도메인>` (Business만 호출) |
   | `domain.<도메인>.port` | `core.domain.<도메인>.port` (포트 관례 유지) |

2. 패키지 문자열을 더 구체적인 경로부터 바꾼다(`...api` → `...dto.XxxRequest` → `...dto.XxxResponse` → `...{entity,exception,service}` → 나머지).
   - 이 치환으로 JPQL `select new` FQCN과 QueryDSL `import static ...QXxx.*`도 함께 바뀐다.
   - 각 파일의 `package` 선언을 경로에 맞추고, 같은 패키지가 된 클래스의 import를 지운다.
   - 이전 구조의 파일은 import 순서를 통째로 정렬하지 않고, 바뀐 줄만 제자리에 옮긴다(diff 최소화).
3. 테스트를 옮긴다. 도메인·통합 테스트는 `core.domain.<도메인>`, 컨트롤러 테스트는 `core.api.controller.v1.<도메인>`.
4. 경로 참조를 고친다.
   - `.claude/rules`의 `paths`: `**/src/**/domain/<도메인>/**/*`는 `core/domain/<도메인>`도 잡지만 컨트롤러는 잡지 못한다. 규칙 파일이 있는 도메인이면 `**/src/**/controller/v1/<도메인>/**/*`를 추가한다.
   - `docs/`의 소스 링크.
   - `ApiDocumentationTest`의 컨트롤러 스캔은 `core.api.controller.v1`을 이미 포함한다.
   - 직렬화된 값에 클래스 이름이 들어가는지 확인한다. 들어가면 배포 시 이미 저장된 값을 읽지 못한다.
     현재 일반 Redis 템플릿(`GenericJackson2JsonRedisSerializer`, `@class` 저장)에는 세션의 `Long`·`Boolean`·`String`만 저장된다. 최근 본 숙소는 문자열 템플릿을 쓴다.
     숙소 상세 캐시는 `AccommodationDetailCacheValue` 타입을 지정해 읽으므로 클래스 이름을 저장하지 않는다.
5. 전체 빌드로 확인한다.
   - 전체 테스트와 `verifyOpenApi`(스냅샷 불변)
   - ArchUnit: 엄격 규칙이 이 도메인에 적용되고 통과하는지, 동결 저장소에 새 위반이 없는지
