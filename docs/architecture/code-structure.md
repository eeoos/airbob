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

core-api의 `application.yaml`이 `spring.config.import`로 `monitoring.yml`과 `logging.yml`을 불러온다.
readiness 그룹 구성(`accommodationInventory` 포함)처럼 애플리케이션 health indicator에 묶인 설정은
core-api의 `application*.yaml`에 남는다. core-api 코드가 직접 쓰는 `spring-boot-actuator`(HealthIndicator)와
`micrometer-core`는 core-api가 직접 선언한다.

client 모듈은 스프링 빈을 직접 등록하지 않고 전송 객체와 팩토리만 제공한다. 설정 프로퍼티(환경변수 계약)와
빈 등록, 외부 응답을 도메인 의미로 바꾸는 일은 core-api가 맡는다. 예를 들어 운영 알림의
`operator-alert.slack.*` 프로퍼티, 미설정 판정, 메시지 구성, `OperatorAlertDelivery*Exception` 매핑은
core-api의 `messaging/alert/infrastructure/slack`에 남는다.
결제도 마찬가지로 `payment.toss.*` 프로퍼티, 활성화 여부, provider 코드 분류(승인·거절·재시도·결과 불명·수동 검토),
응답 상관 검증, 도메인 값 정규화는 core-api의 `TossPaymentsAdapter`에 남는다.

공통 패키지 중 템플릿 위치로 옮긴 것은 다음과 같다.

| 이전 위치 | 현재 위치 |
|---|---|
| `common.exception`(`BaseException`, `ErrorCode`, `InvalidInputException`, `AdminAccessDeniedException`) | `core.support.error` |
| `common.dto`(`ApiResponse`, `ErrorResponse`) | `core.support.response` |
| `cursor.*` | `core.support.cursor.*` |
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

템플릿 구조로 옮긴 도메인은 wishlist(PR 7c), commoncode(PR 8), statistics(PR 9), member(PR 10b-2), auth(PR 10c)다. 다른 도메인을 옮길 때 wishlist를 기준으로 삼는다.

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

`common`의 나머지(`context`, `domain`, `history`, `monitoring`, `validation`)와 `config`, `geo`, `search`, `messaging`,
그리고 위 도메인들을 뺀 각 도메인 패키지(`kr.kro.airbob.domain.<도메인>/{api,service,repository,entity,dto}`)는 아직 이전 구조다.

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
├─ clients/client-slack, clients/client-toss-payments   외부 HTTP 전송 계층만 담당
├─ tests/api-docs       Spring REST Docs 전환 시 생성
└─ (선택) core/core-enum, storage/db-core   엔티티를 분리하는 단계에서 생성
```

- 외부 시스템의 응답을 어떻게 해석하는지는 core-api가 맡는다. 예를 들어 결제 결과 분류와 운영 알림 재시도 의미가 여기에 해당한다. client 모듈은 HTTP 요청과 응답 매핑만 맡는다.
- JPA 엔티티는 상태 전이 로직을 가진 도메인 모델이다. 그래서 당분간 core-api에 둔다.

### core-api 패키지

```
kr.kro.airbob
├─ core.api.controller                     전역 예외 처리, 공통 웹 설정
├─ core.api.controller.v1.<도메인>         Controller, Swagger/문서 인터페이스
│   ├─ request/                            요청 DTO (toXxx()로 도메인 입력을 만든다)
│   └─ response/                           응답 DTO (from(도메인 결과))
├─ core.domain.<도메인>                    @Service(Business), @Component(Implement),
│   │                                      엔티티, 도메인 enum·예외·record
│   └─ repository/                         Spring Data, QueryDSL, projection (Data Access)
└─ core.support.{error,response,cursor}    BaseException·ErrorCode, ApiResponse, 커서 페이징
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
- **다른 도메인이 쓰는 기능**: Service가 아니라 Reader/Writer에 둔다. Service는 Controller용 흐름만 갖는다.
  예: 숙소의 코드 검증은 `CommonCodeService`가 아니라 `CommonCodeReader.isValidCode`를 쓴다. 이전 구조 도메인의 Service도 마찬가지다.
- **값 정규화 규칙**: 코드 대문자화처럼 저장 값의 규칙은 엔티티 정적 메서드에 둔다(예: `CommonCode.normalizeCode`). 요청 DTO의 공백 제거는 검증 전에 필요하므로 그대로 둔다.
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

- **`LayeredArchitectureTest`(엄격)**: 새 패키지(`kr.kro.airbob.core..`)에 규칙 1~4와 이름 규칙을 적용한다.
  `core.support`가 도메인·Presentation·설정에 의존하지 않는지도 확인한다.
  `core.domain.<도메인>`은 엔티티와 Service·Reader가 한 패키지에 있어서, 동결 테스트가 패키지 이름(`dto`·`api`·`service`)으로는 규칙 7을 검사하지 못한다.
  그래서 엄격 테스트가 엔티티의 `@Service`·`@Component` 의존을 따로 막는다.
  `@Scheduled`·`@KafkaListener` 메서드를 가진 컴포넌트는 진입점으로 보고 Implement 규칙(4)에서 빼며, 대신 `core..`의 어떤 클래스도 진입점에 의존하지 못하게 한다.
  아직 클래스가 없는 패키지의 규칙은 통과하고(`allowEmptyShould`), 도메인을 옮기는 순간부터 적용된다.
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

새 위반을 저장소에 직접 추가해서 테스트를 통과시키지 않는다. 의도적으로 기준선을 다시 잡아야 하면
`archunit.properties`에 `freeze.refreeze=true`를 잠시 두고 한 번 실행한 뒤 되돌리고, 이유를 PR에 남긴다.
테스트는 저장소 루트를 작업 디렉터리로 실행해야 저장소 경로를 찾는다. Gradle로 실행하면 이 조건이 맞춰진다.

### 템플릿과 다르게 가는 부분

- **엔티티를 core-api의 도메인 모델로 유지한다.** 템플릿은 엔티티를 storage 모듈에 두고 별도 도메인 모델로 변환한다. airbob은 엔티티의 상태 전이 로직, 도메인 간 연관관계, `FOR UPDATE NOWAIT` 쿼리가 많아서 이 분리를 선택 단계로 미룬다.
- **버전은 `gradle/libs.versions.toml`로 관리한다.** `gradle.properties`는 git에 올리지 않는 로컬 JDK 설정 파일이기 때문이다.
- **HTTP 클라이언트는 Feign 대신 기존 RestClient를 유지한다.**
- **spring-javaformat, Spring Boot 4/Java 25 업그레이드, 테스트 태그 체계는 이 계획에 포함하지 않는다.**
- **API 응답 포맷(`ApiResponse`)과 OpenAPI 스냅샷은 구조 변경 중에 바꾸지 않는다.**

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
| 11~ | 도메인별 이전(PR 하나에 도메인 하나). 순서: image, review, accommodation, coupon → reservation, payment → settlement, search, messaging, 나머지 공통 패키지 | `reservation.md`·`payment.md`의 락 순서와 트랜잭션 경계 불변식 | |
| 이후 | Spring REST Docs 전환과 `tests/api-docs` 모듈. springdoc 스냅샷 태스크, `ApiDocumentationTest`, `api-docs.md`, CI scope를 대체한다 | 문서 산출물 경로를 CI·문서와 함께 바꾼다 | |
| 선택 | `core-enum`과 `storage/db-core`로 엔티티 분리(다른 애그리거트는 ID로 참조), geocoding·S3 모듈 분리, 예외를 `ErrorType`으로 통합 | 마이그레이션의 JAR 경로 계약 | |

### 도메인 이전 레시피 (wishlist 파일럿에서 확정, PR 7a~7c)

옮기는 순간부터 엄격 규칙이 적용된다. 그래서 경계와 내부 계층을 먼저 정리하고, 패키지 이동은 마지막에 한다.
작은 도메인은 세 단계를 한 PR로 합쳐도 된다. 큰 도메인은 wishlist처럼 나눈다.

**1단계: 경계 정리** (wishlist는 PR 7a)

1. 다른 도메인이 이 도메인의 Repository를 쓰던 곳을 이 도메인의 Reader로 바꾼다.
2. 이 도메인이 다른 도메인의 Repository를 쓰던 곳은 그 도메인의 Reader(`MemberReader.readActive` 등)로 바꾼다.
3. 순환이 생기면 필요한 쪽이 소유한 포트를 이 도메인의 Reader가 구현한다.
4. 동결 저장소에서 줄어든 위반을 함께 커밋한다.
5. 저장소에서 이 도메인의 클래스 이름이 들어간 위반을 찾는다(`grep <도메인> archunit_store/*`).
   위반 메시지에는 클래스 전체 이름이 들어가므로, 남은 채로 옮기면 기존 위반은 "해결"되고 같은 내용이 새 위반으로 잡혀 실패한다.
   다른 도메인이 이 도메인의 Repository를 쓰는 위반은 이동 전에 Reader로 바꾼다(예: PR 10b-1).

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
5. 전체 빌드로 확인한다.
   - 전체 테스트와 `verifyOpenApi`(스냅샷 불변)
   - ArchUnit: 엄격 규칙이 이 도메인에 적용되고 통과하는지, 동결 저장소에 새 위반이 없는지
