# 코딩 컨벤션

airbob 코드의 규칙을 영역별로 모은 문서다. 규칙마다 그것을 지키게 하는 장치를 하나씩 정해 둔다.
기계로 확인할 수 있는 규칙은 기계가 강제하고, 사람과 AI 리뷰는 기계가 보지 못하는 부분만 본다.

| 영역 | 기준 | 강제하는 장치 |
|---|---|---|
| 포매팅·import | 아래 [포매팅](#포매팅) | 편집 훅의 자동 정리, Stop 훅과 CI의 `spotlessCheck` |
| 계층·의존 | [코드 구조](../architecture/code-structure.md) | ArchUnit(`core/core-api/src/test/java/kr/kro/airbob/architecture`) |
| 코드 규칙 | 아래 [코드 규칙](#코드-규칙) | ArchUnit `CodingRulesTest`, 테스트의 단언 API 제한은 Spotless |
| 이름 | 아래 [이름](#이름) | ArchUnit `NamingRulesTest`·`TestNamingRulesTest`, 테스트 메서드 이름은 Spotless, 나머지는 리뷰 |

## 포매팅

### 기준

[네이버 Java 코딩 컨벤션](https://naver.github.io/hackday-conventions-java/)을 따른다.

- 들여쓰기는 탭이고, 탭 하나는 4칸이다. 줄을 이어 쓸 때는 한 단계 더 들여쓴다.
- 한 줄은 120자를 넘지 않게 한다. 포매터가 줄을 바꾸지만 문자열 리터럴은 나누지 못하므로 120자가 넘는 줄이 남을 수 있다. 줄 길이 자체는 검사하지 않는다.
- import 순서는 static → `java` → `javax` → `org` → `net` → `com` → 그 외이고, 그룹 사이에 빈 줄을 둔다. 쓰지 않는 import는 지운다.
- 와일드카드 import(`*`)는 static import에서만 쓴다. 일반 import는 클래스 이름을 하나씩 쓴다.

포매터 설정은 `config/formatter/naver-eclipse-formatter.xml`이다.

- 네이버 저장소의 `rule-config/naver-eclipse-formatter.xml`을 그대로 옮겼다. 원본은 [커밋 `ad868727`](https://github.com/naver/hackday-conventions-java/blob/ad868727813252198d577c152071f190b774a87d/rule-config/naver-eclipse-formatter.xml)(2020-07-24)이고, 출처 주석만 붙였다.
- 원본 저장소에는 라이선스 표기가 없다.
- 옵션을 바꾸지 않고 원본 그대로 쓰기로 했다(2026-11-25). 지금 코드와 모양이 다른 곳도 네이버 설정에 맞췄다. 예를 들면 여러 줄로 쓴 파라미터 목록의 닫는 괄호를 앞 줄 끝에 둔다.
- 옵션을 바꾸려면 전체 코드를 다시 포매팅하고 이 문서를 함께 고친다.

### Spotless

[Spotless](https://github.com/diffplug/spotless) Gradle 플러그인이 위 기준을 적용한다. 설정은 루트 `build.gradle`에 있다.

| 명령 | 하는 일 |
|---|---|
| `./gradlew spotlessApply` | 모든 모듈의 Java 파일을 포매팅한다 |
| `./gradlew spotlessCheck` | 포매팅이 다르거나, static이 아닌 와일드카드 import, 아래 [코드 규칙](#코드-규칙)에서 막는 단언 클래스, [이름](#이름)에서 막는 테스트 메서드 이름이 있으면 실패한다. `check`에 포함되어 `./gradlew build`가 함께 실행한다 |

`spotlessApply`는 static이 아닌 와일드카드 import를 고치지 못한다. 쓰는 클래스를 하나씩 import하도록 직접 바꾼다.

- 대상은 각 모듈의 `src/*/java/**/*.java`다. QueryDSL Q클래스 같은 생성 코드(`build/`)는 빼고 본다.
- 주석 내용은 다시 줄바꿈하지 않는다. Javadoc, 블록 주석, 한 줄 주석 모두 들여쓰기만 맞춘다.
- 포매터가 만든 모양이 읽기 어렵다면 그 구간만 `// @formatter:off`와 `// @formatter:on`으로 감쌀 수 있다. 꼭 필요한 곳에만 쓴다.

### 자동으로 적용되는 곳

| 시점 | 장치 | 동작 |
|---|---|---|
| Claude Code가 Java 파일을 편집한 직후 | `.claude/hooks/compile-on-edit.sh` | 그 파일만 `spotlessApply`로 포매팅한 뒤 소스셋을 컴파일한다. 이 모드는 와일드카드 import 같은 lint를 검사하지 않는다 |
| Claude Code가 응답을 마치기 전 | `.claude/hooks/test-before-stop.sh` | 바뀐 모듈의 `spotlessCheck`를 테스트와 함께 실행한다. 포매팅 설정(루트 `build.gradle`, `gradle/libs.versions.toml`, `config/formatter/`)이 바뀌면 모든 모듈을 검사한다 |
| PR CI | `ci.yml`의 app 잡(`./gradlew build`), API 문서만 바뀐 PR은 API documentation 잡 | `spotlessCheck`가 실패하면 필수 검사 `CI result`가 실패한다 |

### 사람이 편집할 때

- `.editorconfig`가 인코딩, 줄 끝, 들여쓰기 문자를 맞춘다. IntelliJ는 이 파일을 자동으로 읽는다.
- IntelliJ의 포매터는 Eclipse 포매터와 결과가 같다는 보장이 없다. 기준은 Spotless 결과다. 두 방법 중 하나를 쓴다.
  - 커밋 전에 `./gradlew spotlessApply`를 실행한다.
  - IntelliJ에 [Adapter for Eclipse Code Formatter](https://plugins.jetbrains.com/plugin/6546) 플러그인을 설치하고, 위 XML과 같은 import 순서를 지정한다.

### 전체 포매팅 커밋과 blame

- 기존 코드는 2026-11-25에 포매팅만 담은 커밋 `1f871dd7` 하나로 맞췄다. 652개 파일이 바뀌었다.
- 디버그 정보 없이(`-g:none`) 컴파일한 클래스 파일 1,484개가 이 커밋 전후로 같다. 그래서 동작은 바뀌지 않았다.
- 이 커밋은 `.git-blame-ignore-revs`에 있다. GitHub의 blame 화면은 자동으로 건너뛴다. 로컬에서는 한 번 설정한다.
  ```bash
  git config blame.ignoreRevsFile .git-blame-ignore-revs
  ```
- 이 커밋 전에 갈라진 브랜치는 main을 합칠 때 충돌할 수 있다. 자기 브랜치의 변경을 기준으로 충돌을 풀고 `./gradlew spotlessApply`를 실행한다.

## 코드 규칙

main 코드 규칙은 ArchUnit `CodingRulesTest`(`core/core-api/src/test/java/kr/kro/airbob/architecture`)가, 테스트의 단언 API 제한은 Spotless(`forbidRegex`)가 강제한다. 위반하면 `./gradlew build`가 실패한다.

| 금지 | 대상 | 대신 쓸 것 |
|---|---|---|
| 필드 주입. `@Autowired`, `@Inject`, `@Resource`, `@Value`가 붙은 필드 | main | 생성자 파라미터(`final` 필드 + `@RequiredArgsConstructor`). 설정값은 생성자나 `@Bean` 메서드 파라미터에 `@Value`를 붙인다 |
| 표준 출력. `System.out`, `System.err`, `printStackTrace()` | main | `@Slf4j` 로거 |
| 일반 예외 직접 생성. `Throwable`, `Exception`, `RuntimeException` | main | 구체적인 예외. 보통 `ErrorCode`를 가진 `BaseException`의 하위 예외 |
| `java.util.logging` | main | SLF4J(`@Slf4j`) |
| `Clock` 빈 없이 현재 시각 읽기. java.time의 `now()`·`now(ZoneId)`, `System.currentTimeMillis()`, `ClockConfiguration` 밖에서 시스템 시계 만들기(`Clock.systemUTC()`·`systemDefaultZone()`·`system(ZoneId)`, `Clock.tickSeconds`·`tickMinutes`·`tickMillis`, `InstantSource.system()`) | main | `Clock` 빈을 주입받아 `clock.instant()`, `clock.millis()`, `LocalDate.now(clock)`를 쓴다. 저장할 `LocalDateTime`은 JPA 감사 시각처럼 `LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)`로 만든다. 주입받은 시계를 감싸는 `Clock.tick(clock, …)`, `Clock.offset(clock, …)`은 쓸 수 있다. 경과 시간은 `System.nanoTime()`으로 잰다 |
| 단언 클래스. JUnit `org.junit.jupiter.api.Assertions`, JUnit 4 `org.junit.Assert`, JUnit 3 `junit.framework.Assert`, Hamcrest `org.hamcrest.MatcherAssert` | 모든 모듈의 test | AssertJ의 `assertThat`, `assertThatThrownBy`. MockMvc `jsonPath`에 넘기는 Hamcrest `Matchers`는 쓸 수 있다 |

- **Clock 규칙의 이유.** 운영의 `Clock` 빈은 `Clock.systemUTC()`(`ClockConfiguration`)다. 테스트는 이 빈을 고정 시각으로 바꿔 시간을 제어한다. 그래서 시각을 직접 읽는 코드는 테스트에서 시간을 제어할 수 없다. 쿠폰 발급처럼 DB 시각을 쓰는 곳은 이 규칙과 무관하다(`.claude/rules/coupon.md`). 옛 날짜 API(`new Date()`, `Calendar.getInstance()`)는 이 규칙이 다루지 않는다. main에서 쓰지 않으며 새 코드에도 쓰지 않는다.
- **테스트 코드는 대부분 대상이 아니다.** 테스트의 `@Autowired` 필드는 스프링 테스트의 관행이고, 동시성 테스트는 결과를 표준 출력으로 남긴다.
- **규칙의 시험.** `CodingRulesFixtureTest`는 규칙마다 위반 예시 클래스를 두고, 규칙이 그 위반을 실제로 잡는지 확인한다.
  - 이 프로젝트의 ArchUnit 설정(`resolveMissingDependenciesFromClassPath=false`)은 JDK와 라이브러리 클래스를 가져오지 않는다. 그래서 그 타입은 이름으로만 안다.
  - 이름만으로 판단할 수 없는 규칙은 아무것도 잡지 못한 채 통과할 수 있다. 실제로 ArchUnit 기본 규칙은 `IllegalStateException` 같은 하위 클래스에서 부른 `printStackTrace()`와, 필드 없이 쓰는 `Logger.getGlobal()`을 놓쳤다.
  - 기본 규칙은 직접 호출만 본다. `orElseGet(Instant::now)`, `orElseThrow(RuntimeException::new)` 같은 메서드·생성자 참조도 잡도록 조건을 더했다. 픽스처는 호출과 참조를 모두 담는다.
- **테스트 단언을 Spotless로 막는 이유.** ArchUnit 규칙은 core-api 테스트 클래스만 가져와서 클라이언트 모듈의 테스트를 보지 못한다. API 문서 테스트만 바뀐 PR은 CI에서 테스트 대신 `spotlessCheck`와 `verifyOpenApi`만 돈다. Spotless는 모든 모듈에 적용되고 두 경우 모두 실행된다. JUnit 4와 Hamcrest는 Testcontainers와 `spring-boot-starter-test`를 통해 테스트 클래스패스에 있어서 함께 막는다.
- **도입할 때 고친 위반(2026-11-25).**
  - 필드 주입 8개: `ElasticsearchConfig`, `RedisConfig`(지금은 `ElasticsearchConfiguration`, `RedisConfiguration`), `S3ImageUploader`, `SettlementGenerator`의 `@Value` 필드
  - `Clock` 없이 시각을 읽은 5곳: `WishlistRepositoryImpl`, `SessionRedisRepository`, `RecentlyViewedWriter`
  - JUnit `Assertions`를 쓴 테스트 3개 파일
- **넣지 않은 ArchUnit 기본 규칙.**
  - JodaTime 금지: 의존성이 없다.
  - 옛 날짜·시간 클래스 금지: JDBC에 값을 넘기는 `java.sql.Timestamp`·`Date` 사용이 정당하다.
  - 사용 중단 API 금지: 라이브러리 클래스를 가져오지 않아 JDK API의 사용 중단 표시를 볼 수 없다.
- **실행.** `./gradlew :core:core-api:test --tests 'kr.kro.airbob.architecture*'`로 몇 초 안에 끝난다. Stop 훅은 core-api의 main 코드가 바뀌면 이 테스트를 실행한다.

## 이름

기본 이름은 [네이버 Java 코딩 컨벤션](https://naver.github.io/hackday-conventions-java/)의 "이름" 장(2.1~2.13)을 따른다. 역할 이름은 [코드 구조](../architecture/code-structure.md#계층과-의존-규칙)의 계층 위에 정했다.

- 규칙은 2026-11-26에 정했다.
- 기존 이름은 규칙을 넣기 전에 먼저 맞췄다: main은 #228, 테스트는 #229.
- 그래서 위반을 동결하지 않고 모두 엄격 규칙으로 둔다.

### 기본 이름

| 규칙 | 예 | 강제 |
|---|---|---|
| 식별자는 영문·숫자·`_`만 쓴다. 한국어 발음을 영문으로 옮겨 적지 않는다(2.1·2.2) | 테스트의 한국어 설명은 `@DisplayName("결제키가 200자를 넘으면 거부한다")`에 쓴다 | 테스트 메서드 이름은 Spotless, 그 밖은 리뷰 |
| 약어도 첫 글자만 대문자로 쓴다. 대문자로 쓰는 약어 목록은 두지 않는다(2.3) | `HttpApiUrl`, `mysqlContainer`(`HTTPApiURL`, `mySQLContainer`가 아님) | ArchUnit: 클래스·메서드·필드 이름에 대문자 3개 이상 연속 금지. 대문자로 쓴 상수 이름(`MAX_URL_LENGTH`)은 빼고 본다. `userID`처럼 2글자 약어는 리뷰 |
| 패키지는 소문자, 클래스는 대문자로 시작하는 카멜 표기, 메서드·변수는 소문자로 시작하는 카멜 표기, 상수는 대문자와 `_`(2.4·2.5·2.9·2.11·2.12) | `PaymentStatus`, `readActive`, `MAX_PAGE_NUMBER` | 대문자로 시작하는 `void` 메서드 이름은 Spotless. 그 밖은 리뷰(도입할 때 위반 0) |
| 테스트 메서드에는 `_`를 쓸 수 있다. Spring Data 파생 쿼리에는 쓰지 않는다(2.9) | `existsByWishlistMemberIdAndAccommodationId` | 리뷰 |
| 클래스는 명사, 인터페이스는 명사나 형용사로 짓는다(2.6·2.7) | | 리뷰 |
| 메서드는 동사나 전치사로 시작한다(2.10) | `readActive`, `toCondition`, `withTicker` | 역할별 동사는 아래 표 |
| 1글자 이름은 반복문 변수나 람다 파라미터처럼 잠깐 쓰는 변수에만 쓴다(2.13) | | 리뷰 |
| 테스트 클래스는 `Test`로 끝난다. `@Nested` 클래스도 같다(2.8) | `ReservationTest`, `StatusTransitionTest` | ArchUnit `TestNamingRulesTest` |

### 역할 이름

| 대상 | 규칙 | 강제 |
|---|---|---|
| Reader 메서드(public이거나 다른 main 클래스가 부르는 것) | `read`는 반드시 있는 단건(없으면 예외)과 목록, `find`는 `Optional`만. 그 밖에는 `is`·`exists`·`has`(boolean), `count`, `search`를 쓴다 | ArchUnit |
| `@Service`·`@RestController`·Swagger 인터페이스(`@Tag`)의 public 메서드 | 조회는 `get`으로 시작한다. 검색도 같다(`getSearchResults`). `find`·`read`·`search`·`load`·`fetch`·`retrieve`·`query`·`select`·`lookup`으로 시작하지 않는다 | ArchUnit |
| Writer 메서드 | 뜻으로 짝을 맞춘다. `create`·`delete`는 엔티티 자체를 만들고 지운다. `add`·`remove`는 다른 엔티티의 컬렉션에 넣고 뺀다. `update`·`change`는 값을 바꾸고, `record`는 이력을 남긴다 | 리뷰 |
| `core.domain`의 `@Component` | 역할을 드러내는 명사로 끝난다(`Reader`, `Writer`, `Validator`, `Issuer` …). `Helper`·`Util`·`Utils`·`Common`·`Misc`·`Etc`로 끝나지 않는다 | ArchUnit |
| main의 `@Configuration`, 테스트의 `@TestConfiguration` | `*Configuration`. 최소 스프링 컨텍스트를 띄우는 테스트용 앱 루트(`@Configuration` `Application`)는 대상이 아니다 | ArchUnit |
| 모든 클래스 | `Dto`로 끝나지 않는다. 역할로 짓는다(`Request`, `Response`, `Condition` …) | ArchUnit |
| `@RestController`, Swagger 인터페이스 | `*Controller`, `*Api` | ArchUnit |
| `@Scheduled` 메서드가 있는 클래스, `@KafkaListener` | `*Scheduler`, `*Listener` | ArchUnit |
| 예외 | `*Exception`. 익명 클래스는 뺀다 | ArchUnit |
| `request`·`response` 패키지의 최상위 클래스 | `*Request`, `*Response` | ArchUnit |
| 통합 이벤트(`IntegrationEvent` 구현) | `<주어><과거분사>V<버전>`(`AccommodationSearchRefreshRequestedV1`) | 버전 접미사는 ArchUnit, 과거분사는 리뷰 |
| 도메인 입출력 레코드 | 생성은 `NewXxx`, 부분 수정은 `XxxPatch`([코드 구조](../architecture/code-structure.md#business-입출력과-도메인-레코드)) | 리뷰 |

- **Reader 동사를 결과로 나누는 이유.** 이름만 보고 값이 없을 때를 안다. `read`는 예외를 던지고, `find`는 빈 `Optional`을 돌려준다. Spring Data의 `findById`와도 맞는다. Service와 Controller는 조회 동사를 `get` 하나로 둔다. 호출하는 쪽이 Reader의 구분을 알 필요가 없어서다.
- **Reader 검사 범위.** public 메서드와, 다른 main 클래스가 부르는 메서드를 본다. 그래서 package-private이어도 Service가 부르면 대상이다. 테스트에서만 부르는 메서드는 Reader의 조회 어휘가 아니어서 뺀다. 예: 검색 쿼리를 만드는 `AccommodationDocumentReader.buildQuery`, 테스트용 팩토리 `CommonCodeReader.withTicker`.
- **테스트 메서드 이름을 Spotless로 막는 이유.** [코드 규칙](#코드-규칙)의 단언 제한과 같다. 모든 모듈에 적용되고, API 문서 테스트만 바뀐 PR에서도 돈다. 정규식은 `void` 메서드 선언만 본다. 테스트 메서드는 모두 `void`다. 주석에 `void 한글이름(` 같은 글을 쓰면 걸리니 문구를 바꾼다.
- **규칙의 시험.** `NamingRulesFixtureTest`는 자체 조건을 가진 규칙마다 위반 예시와 허용 예시를 둔다. Reader 동사, 조회 동사, 막연한 접미사, 진입점, 예외, 통합 이벤트, 약어, 테스트 클래스가 대상이다.
  - 예외 규칙은 앱의 예외가 JDK 예외를 상속하는지 따라가야 한다. ArchUnit 설정이 JDK 클래스를 가져오지 않으므로, 앱 클래스가 끝나는 곳의 라이브러리 타입은 테스트 클래스패스에서 읽어 판단한다. 그래서 `IllegalStateException`을 바로 상속한 예외도 잡는다.
  - 나머지 규칙은 이름 접미사만 본다. 모든 규칙은 적어도 한 클래스에 적용되어야 통과하므로, 대상을 고르는 조건이 비면 실패한다.
- **도입할 때 확인한 것(2026-11-26).** 이름을 바꾸기 전 코드(#227 머지 시점 `5dedd289`)에 이 규칙들을 돌렸다. #228·#229에서 고친 위반이 그대로 잡혔다.
  - ArchUnit
    - Reader 동사 12
    - 조회 동사 35
    - `*Config` 11
    - `Dto` 2
    - 테스트 클래스 이름 1
    - 테스트 설정 이름 15
    - 약어 30(`mySQLContainer` 13개 파일 포함)
  - Spotless: 테스트 메서드 이름 78(한글 76, 대문자로 시작 2)
- **실행.** `./gradlew :core:core-api:test --tests 'kr.kro.airbob.architecture*'`로 코드 규칙과 함께 몇 초 안에 끝난다. 테스트 메서드 이름은 `./gradlew spotlessCheck`가 본다.
