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

`common`의 나머지(`context`, `domain`, `history`, `monitoring`, `validation`)와 `config`, `geo`, `search`, `messaging`,
그리고 각 도메인 패키지(`kr.kro.airbob.domain.<도메인>/{api,service,repository,entity,dto}`)는 아직 이전 구조다.

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
| Data Access | `core.domain.<도메인>.repository` | 영속성. 같은 도메인의 Implement만 사용한다 |

1. **역참조 금지**: `core.domain`은 `core.api`에 의존하지 않는다. Business 메서드는 web Request/Response를 받지도, 반환하지도 않는다.
2. **Presentation은 Business만 호출한다**: Implement나 Repository를 직접 쓰지 않는다.
3. **Business 규칙**: 다른 Business를 부르지 않는다(같은 계층 참조 금지). Repository에도 직접 접근하지 않는다(계층 건너뛰기 금지).
4. **Implement 규칙**: Business에 의존하지 않는다.
5. **Repository 캡슐화**: 다른 도메인의 Repository를 주입하지 않는다. 그 도메인의 Reader/Writer를 쓴다.
6. **도메인 순환 금지**: 순환이 생기면 의존받는 쪽이 포트 인터페이스를 소유하고, 상대 도메인이 그 포트를 구현한다. 기존 예는 `member/port/SessionInvalidator`다.
7. **엔티티는 DTO와 service 패키지에 의존하지 않는다**: 상태로 결정되는 규칙은 엔티티 메서드에 둔다. 예: `Reservation.confirm()`.

### ArchUnit으로 규칙 강제

규칙은 `core/core-api/src/test/java/kr/kro/airbob/architecture`의 ArchUnit 테스트가 강제한다.
`./gradlew :core:core-api:test --tests 'kr.kro.airbob.architecture*'`로 몇 초 안에 실행된다.
Stop 훅도 core-api의 main 코드가 바뀌면 이 테스트를 함께 실행한다.

- **`LayeredArchitectureTest`(엄격)**: 새 패키지(`kr.kro.airbob.core..`)에 규칙 1~4와 이름 규칙을 적용한다.
  `core.support`가 도메인·Presentation·설정에 의존하지 않는지도 확인한다.
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
| 7 | wishlist 파일럿: Reader/Writer 추출, 입출력에서 DTO 제거, accommodation↔wishlist 순환을 포트로 해소. 도메인 이전 레시피 확정 | 동결 위반이 줄었는지 | |
| 8~ | 도메인별 이전(PR 하나에 도메인 하나). 순서: commoncode, statistics → member+auth → image, review, accommodation, coupon → reservation, payment → settlement, search, messaging, 나머지 공통 패키지 | `reservation.md`·`payment.md`의 락 순서와 트랜잭션 경계 불변식 | |
| 이후 | Spring REST Docs 전환과 `tests/api-docs` 모듈. springdoc 스냅샷 태스크, `ApiDocumentationTest`, `api-docs.md`, CI scope를 대체한다 | 문서 산출물 경로를 CI·문서와 함께 바꾼다 | |
| 선택 | `core-enum`과 `storage/db-core`로 엔티티 분리(다른 애그리거트는 ID로 참조), geocoding·S3 모듈 분리, 예외를 `ErrorType`으로 통합 | 마이그레이션의 JAR 경로 계약 | |

### 도메인 이전 레시피 (초안, PR 7에서 확정)

1. 도메인 클래스를 옮긴다.
   - Controller, 문서 인터페이스, DTO → `core.api.controller.v1.<도메인>(.request/.response)`
   - 엔티티, enum, 예외, 서비스 → `core.domain.<도메인>`
   - Repository → `core.domain.<도메인>.repository`
2. JPQL의 `select new kr.kro.airbob...` FQCN 문자열을 고친다. `./gradlew compileJava`로 Q-클래스를 다시 생성한다.
3. Service의 세부 구현을 Reader/Writer/Validator로 추출한다. Service에는 흐름만 남긴다.
4. Business 시그니처에서 Request/Response를 제거한다. 변환은 Controller와 DTO 팩토리가 맡는다.
5. 다른 도메인이 이 도메인의 Repository를 쓰던 곳을 이 도메인의 Reader로 바꾼다. 순환이 생기면 포트로 끊는다.
6. 테스트 패키지와 `ApiDocumentationTest`의 컴포넌트 스캔, `.claude/rules` 경로를 갱신한다.
7. 전체 테스트, `verifyOpenApi`(스냅샷 불변), ArchUnit(동결 위반 감소)을 확인한다.
