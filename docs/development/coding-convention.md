# 코딩 컨벤션

airbob 코드의 규칙을 영역별로 모은 문서다. 규칙마다 그것을 지키게 하는 장치를 하나씩 정해 둔다.
기계로 확인할 수 있는 규칙은 기계가 강제하고, 사람과 AI 리뷰는 기계가 보지 못하는 부분만 본다.

| 영역 | 기준 | 강제하는 장치 |
|---|---|---|
| 포매팅·import | 아래 [포매팅](#포매팅) | 편집 훅의 자동 정리, Stop 훅과 CI의 `spotlessCheck` |
| 계층·의존 | [코드 구조](../architecture/code-structure.md) | ArchUnit(`core/core-api/src/test/java/kr/kro/airbob/architecture`) |
| 코드 규칙 | 아래 [코드 규칙](#코드-규칙) | ArchUnit `CodingRulesTest`, 테스트의 단언 API 제한은 Spotless |
| 이름 | 아래 [이름](#이름) | ArchUnit `NamingRulesTest`·`TestNamingRulesTest`, 테스트 메서드 이름은 Spotless, 나머지는 리뷰 |
| 설계 | 아래 [설계](#설계) | 엔티티의 public setter 금지는 ArchUnit `CodingRulesTest`, 나머지는 리뷰 |
| 테스트 | 아래 [테스트](#테스트) | 목 API 제한은 Spotless, 나머지는 리뷰 |
| 커밋 메시지 | 아래 [커밋 메시지](#커밋-메시지) | CI `scope` 잡의 `scripts/ci/commit_messages.py` |

## 규칙을 바꿀 때

규칙의 요약은 여러 곳에 있다. 에이전트와 리뷰어는 이 문서보다 요약을 읽기 때문이다. 예를 들어 Claude 리뷰는 리뷰 기준, `CLAUDE.md`, 바뀐 경로의 `.claude/rules`만 받는다. 규칙을 더하거나 바꾸거나 강제하는 방식을 바꾸면 아래 표에서 그 규칙을 적은 곳을 찾아 함께 고친다. 도입 PR들(#227~#233)의 Claude 리뷰 지적 25건 가운데 9건이, 이 요약들이 서로 다르거나 실제 규칙과 다른 것이었다.

| 고칠 곳 | 무엇을 |
|---|---|
| 이 문서 | 규칙, 이유, 도입할 때 관행(개수), 강제 방식, 맨 위 표의 "강제하는 장치" |
| 강제 장치 | Spotless는 루트 `build.gradle`, ArchUnit은 `architecture` 패키지의 규칙과 픽스처 테스트, 커밋 메시지는 `scripts/ci/commit_messages.py`와 그 테스트. 검사를 돌리는 범위를 바꾸면 Stop 훅(`.claude/hooks/test-before-stop.sh`)의 경로 조건과 CI의 검사 선택(`scripts/ci/scope.py`)도 본다 |
| `CLAUDE.md` | 기계가 막는 것의 요약. Spotless는 Commands, ArchUnit은 "Module and package structure", 커밋 메시지는 "Pull requests" |
| `.claude/rules/java-conventions.md` | 리뷰만 보는 Java 규칙. 기계 검사로 옮긴 규칙은 여기서 뺀다 |
| `docs/development/code-review.md` | "지적하지 않는 것"의 CI가 이미 강제하는 목록 |
| `docs/development/ai-change-verification.md` | 장치 표의 항목과 규칙 수 |
| `.claude/rules/deploy-ci.md`, `scripts/README.md` | 커밋 메시지 검사의 설명 |
| `docs/architecture/code-structure.md` | 계층 역할 이름과 도메인 레코드 이름(`NewXxx`, `XxxPatch`) |

## 포매팅

### 기준

[네이버 Java 코딩 컨벤션](https://naver.github.io/hackday-conventions-java/)을 따른다.

- 들여쓰기는 탭이고, 탭 하나는 4칸이다. 줄을 이어 쓸 때는 한 단계 더 들여쓴다.
- 한 줄은 120자를 넘지 않게 한다. 포매터가 줄을 바꾸지만 문자열 리터럴은 나누지 못하므로 120자가 넘는 줄이 남을 수 있다. 줄 길이 자체는 검사하지 않는다.
- import 순서는 static → `java` → `javax` → `org` → `net` → `com` → 그 외이고, 그룹 사이에 빈 줄을 둔다. 쓰지 않는 import는 지운다.
- 와일드카드 import(`*`)는 static import에서만 쓴다. 일반 import는 클래스 이름을 하나씩 쓴다.

포매터 설정은 `config/formatter/naver-eclipse-formatter.xml`이고, 이 설정을 읽는 Eclipse 포매터는 4.41로 고정한다(`gradle/libs.versions.toml`의 `eclipse-formatter`). 4.41은 Spotless 8.11.0의 기본값이다.

- 네이버 저장소의 `rule-config/naver-eclipse-formatter.xml`을 그대로 옮겼다. 원본은 [커밋 `ad868727`](https://github.com/naver/hackday-conventions-java/blob/ad868727813252198d577c152071f190b774a87d/rule-config/naver-eclipse-formatter.xml)(2020-07-24)이고, 출처 주석만 붙였다.
- 원본 저장소에는 라이선스 표기가 없다.
- 옵션을 바꾸지 않고 원본 그대로 쓰기로 했다(2026-11-25). 지금 코드와 모양이 다른 곳도 네이버 설정에 맞췄다. 예를 들면 여러 줄로 쓴 파라미터 목록의 닫는 괄호를 앞 줄 끝에 둔다.
- 옵션을 바꾸려면 전체 코드를 다시 포매팅하고 이 문서를 함께 고친다.
- Eclipse 버전을 고정한 이유: 버전을 비워 두면 Spotless의 기본값을 따르므로, 다른 이유로 Spotless를 올려도 포매터가 바뀌어 전체 파일이 `spotlessCheck`에서 실패할 수 있다. 포매터는 일부러 올릴 때만 바꾼다. 올릴 때는 버전을 바꾸고 `./gradlew spotlessApply`의 결과를 포매팅만 담은 커밋으로 따로 만든 뒤, 그 SHA를 `.git-blame-ignore-revs`에 더한다.

### Spotless

[Spotless](https://github.com/diffplug/spotless) Gradle 플러그인이 위 기준을 적용한다. 설정은 루트 `build.gradle`에 있다.

| 명령 | 하는 일 |
|---|---|
| `./gradlew spotlessApply` | 모든 모듈의 Java 파일을 포매팅한다 |
| `./gradlew spotlessCheck` | 포매팅이 다르거나, static이 아닌 와일드카드 import, 아래 [코드 규칙](#코드-규칙)에서 막는 단언 클래스, [이름](#이름)에서 막는 테스트 메서드 이름, [테스트](#테스트)에서 막는 목 API가 있으면 실패한다. `check`에 포함되어 `./gradlew build`가 함께 실행한다 |

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

### blame에서 숨기는 커밋

- 기존 코드는 2026-11-25에 포매팅만 담은 커밋 `1f871dd7` 하나로 맞췄다. 652개 파일이 바뀌었다.
- 디버그 정보 없이(`-g:none`) 컴파일한 클래스 파일 1,484개가 이 커밋 전후로 같다. 그래서 동작은 바뀌지 않았다.
- 이 커밋은 `.git-blame-ignore-revs`에 있다. 테스트의 목 호출을 BDDMockito로 바꾼 기계적 변환 커밋 `271d9d82`(70개 파일, [테스트](#테스트))도 함께 있다. GitHub의 blame 화면은 자동으로 건너뛴다. 로컬에서는 한 번 설정한다.
  ```bash
  git config blame.ignoreRevsFile .git-blame-ignore-revs
  ```
- 포매팅 커밋 `1f871dd7` 전에 갈라진 브랜치는 main을 합칠 때 충돌할 수 있다. 자기 브랜치의 변경을 기준으로 충돌을 풀고 `./gradlew spotlessApply`를 실행한다.
- 변환 커밋 `271d9d82` 전에 갈라진 브랜치에서 Mockito `when`·`verify`로 쓴 테스트는 `spotlessCheck`에서 실패한다. `spotlessApply`는 고치지 못하므로 BDDMockito로 직접 바꾼다.

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
| 엔티티(`@Entity`)와 엔티티가 물려받는 `@MappedSuperclass`의 public `set…` 메서드. Lombok `@Setter`·`@Data`가 만든 것도, 인자가 없는 것도 포함한다(상태 변경을 `set`으로 짓지 않는다). `@Embeddable` 값 타입은 대상이 아니다(도입할 때 0개) | main | 바뀌는 뜻을 이름에 담은 엔티티 메서드(`Reservation.confirm()`). [설계](#설계) 참고 |

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
- **실행.** `./gradlew :core:core-api:test --tests 'kr.kro.airbob.architecture*'`로 몇 초 안에 끝난다. Stop 훅은 core-api의 main이나 test, 클라이언트 모듈의 main 코드가 바뀌면 이 테스트를 실행한다.

## 이름

기본 이름은 [네이버 Java 코딩 컨벤션](https://naver.github.io/hackday-conventions-java/)의 "이름" 장(2.1~2.13)을 따른다. 역할 이름은 [코드 구조](../architecture/code-structure.md#계층과-의존-규칙)의 계층 위에 정했다.

- 규칙은 2026-11-26에 정했다.
- 기존 이름은 규칙을 넣기 전에 먼저 맞췄다: main은 #228, 테스트는 #229.
- 그래서 위반을 동결하지 않고 모두 엄격 규칙으로 둔다.

| 장치 | 보는 코드 |
|---|---|
| ArchUnit `NamingRulesTest` | 모든 모듈의 main(core-api와 클라이언트 모듈) |
| ArchUnit `TestNamingRulesTest` | core-api의 test. 클라이언트 모듈의 테스트는 보지 않는다 |
| Spotless 정규식 | 모든 모듈의 main과 test에서 `void` 메서드 선언 |

### 기본 이름

| 규칙 | 예 | 강제 |
|---|---|---|
| 식별자는 영문·숫자·`_`만 쓴다. 한국어 발음을 영문으로 옮겨 적지 않는다(2.1·2.2) | 테스트의 한국어 설명은 `@DisplayName("결제키가 200자를 넘으면 거부한다")`에 쓴다 | 테스트 메서드 이름은 Spotless, 그 밖은 리뷰 |
| 약어도 첫 글자만 대문자로 쓴다. 대문자로 쓰는 약어 목록은 두지 않는다(2.3) | `HttpApiUrl`, `mysqlContainer`(`HTTPApiURL`, `mySQLContainer`가 아님) | ArchUnit: 클래스·메서드·필드 이름에 대문자 3개 이상 연속 금지. `static final`이면서 대문자로 쓴 상수(`MAX_URL_LENGTH`, enum 상수)와 `static final long serialVersionUID`는 빼고 본다. `userID`처럼 2글자 약어는 리뷰 |
| 패키지는 소문자, 클래스는 대문자로 시작하는 카멜 표기, 메서드·변수는 소문자로 시작하는 카멜 표기, 상수는 대문자와 `_`(2.4·2.5·2.9·2.11·2.12) | `PaymentStatus`, `readActive`, `MAX_PAGE_NUMBER` | 대문자로 시작하는 `void` 메서드 이름은 Spotless. 그 밖은 리뷰(도입할 때 위반 0) |
| 테스트 메서드에는 `_`를 쓸 수 있다. Spring Data 파생 쿼리에는 쓰지 않는다(2.9) | `existsByWishlistMemberIdAndAccommodationId` | 리뷰 |
| 클래스는 명사, 인터페이스는 명사나 형용사로 짓는다(2.6·2.7) | | 리뷰 |
| 메서드는 동사나 전치사로 시작한다(2.10) | `readActive`, `toCondition`, `withTicker` | 역할별 동사는 아래 표 |
| 1글자 이름은 반복문 변수나 람다 파라미터처럼 잠깐 쓰는 변수에만 쓴다(2.13) | | 리뷰 |
| 테스트 클래스는 `Test`로 끝난다(2.8). 테스트 메서드(물려받은 것 포함)를 가진 구체 클래스, ArchUnit의 `@AnalyzeClasses` 클래스, `@Nested` 클래스를 감싸는 클래스가 대상이다. 테스트 메서드가 있으면 JUnit이 실행하지 않는 `private` 클래스나 `@Nested` 없는 내부 클래스도 대상이다. 추상 클래스와 인터페이스는 테스트 메서드가 있어도 그 때문에 대상이 되지는 않지만, `@Nested` 클래스를 감싸면 대상이다. 정적 중첩 테스트 클래스를 담기만 한 클래스는 대상이 아니다 | `ReservationTest`, `StatusTransitionTest` | ArchUnit `TestNamingRulesTest` |

### 역할 이름

| 대상 | 규칙 | 강제 |
|---|---|---|
| `core.domain`의 Reader 메서드(public이거나 다른 main 클래스가 부르는 것) | `read`는 반드시 있는 단건(없으면 예외)이나 목록을 돌려준다(`void` 아님). `find`는 `Optional`만 돌려준다. 그 밖에는 `is`·`exists`·`has`(boolean), `count`, `search`를 쓴다 | ArchUnit |
| `core.domain`의 `@Service`, `@RestController`, Swagger 인터페이스(`@Tag`)의 조회 | 조회는 `get`으로 시작한다. 검색도 같다(`getSearchResults`) | `@GetMapping` 메서드는 ArchUnit이 `get` 시작을 본다. 세 곳의 public 메서드가 `find`·`read`·`search`·`list`·`load`·`fetch`·`retrieve`·`query`·`select`·`lookup`으로 시작하는 것도 ArchUnit이 막는다. Service는 이름만으로 조회인지 알 수 없어, 그 밖의 동사로 지은 조회는 리뷰가 본다. `@GetMapping` 대신 `@RequestMapping(method = GET)`으로 매핑한 조회도 리뷰가 본다 |
| Writer 메서드 | 뜻으로 짝을 맞춘다. `create`·`delete`는 엔티티 자체를 만들고 지운다. `add`·`remove`는 다른 엔티티의 컬렉션에 넣고 뺀다. `update`·`change`는 값을 바꾸고, `record`는 이력을 남긴다 | 리뷰 |
| `core.domain`의 `@Component` | 역할을 드러내는 명사로 끝난다(`Reader`, `Writer`, `Validator`, `Issuer` …). `Helper`·`Util`·`Utils`·`Common`·`Misc`·`Etc`로 끝나지 않는다 | ArchUnit |
| main의 `@Configuration`, 테스트의 `@TestConfiguration` | `*Configuration`. 최소 스프링 컨텍스트를 띄우는 테스트용 앱 루트(`@Configuration` `Application`)는 대상이 아니다 | ArchUnit |
| 모든 클래스 | `Dto`로 끝나지 않는다. 역할로 짓는다(`Request`, `Response`, `Condition` …) | main은 ArchUnit, test는 리뷰 |
| `@RestController`, Swagger 인터페이스 | `*Controller`, `*Api` | ArchUnit |
| `@Scheduled` 메서드가 있는 클래스, `@KafkaListener` | `*Scheduler`, `*Listener`. 어노테이션을 여러 번 붙여도 같다 | ArchUnit |
| 예외(`Exception`의 하위 클래스) | `*Exception`. 익명 클래스는 뺀다. `Error`의 하위 클래스는 대상이 아니다 | ArchUnit |
| `request`·`response` 패키지의 최상위 클래스 | `*Request`, `*Response` | ArchUnit |
| 통합 이벤트(`IntegrationEvent` 구현) | `<주어><과거분사>V<버전>`(`AccommodationSearchRefreshRequestedV1`) | 버전 접미사는 ArchUnit, 과거분사는 리뷰 |
| 도메인 입출력 레코드 | 생성은 `NewXxx`, 부분 수정은 `XxxPatch`([코드 구조](../architecture/code-structure.md#business-입출력과-도메인-레코드)) | 리뷰 |

- **Reader 동사를 결과로 나누는 이유.** 이름만 보고 값이 없을 때를 안다. `read`는 예외를 던지고, `find`는 빈 `Optional`을 돌려준다. Spring Data의 `findById`와도 맞는다. Service와 Controller는 조회 동사를 `get` 하나로 둔다. 호출하는 쪽이 Reader의 구분을 알 필요가 없어서다.
- **Reader 검사 범위.** public 메서드와, 다른 main 클래스가 부르는 메서드를 본다. 그래서 package-private이어도 Service가 부르면 대상이다. 테스트에서만 부르는 메서드는 Reader의 조회 어휘가 아니어서 뺀다. 예: 검색 쿼리를 만드는 `AccommodationDocumentReader.buildQuery`, 테스트용 팩토리 `CommonCodeReader.withTicker`.
- **테스트 메서드 이름을 Spotless로 막는 이유.** [코드 규칙](#코드-규칙)의 단언 제한과 같다. 모든 모듈에 적용되고, API 문서 테스트만 바뀐 PR에서도 돈다.
  - 정규식은 `void` 메서드 선언만 본다. 값을 돌려주는 `@TestFactory` 메서드는 리뷰가 본다(도입할 때 0개).
  - 한글은 유니코드 한글 문자 전체(`\p{IsHangul}`)로 판별해 분해된 자모도 잡는다.
  - 주석에 `void 한글이름(` 같은 글을 쓰면 걸리니 문구를 바꾼다.
- **규칙의 시험.** `NamingRulesFixtureTest`는 자체 조건을 가진 규칙마다 위반 예시와 허용 예시를 둔다. Reader 동사, 조회 동사와 `@GetMapping`, 막연한 접미사, 진입점(반복 어노테이션 포함), 예외, 통합 이벤트, 약어, 테스트 클래스(물려받은 테스트, `@Nested`를 감싸는 클래스, 정적 중첩 테스트 클래스, 합성 어노테이션을 포함한 ArchUnit 테스트 클래스)가 대상이다.
  - 예외 규칙은 앱의 예외가 JDK 예외를 상속하는지 따라가야 한다. ArchUnit 설정이 JDK 클래스를 가져오지 않으므로, 앱 클래스가 끝나는 곳의 라이브러리 타입은 테스트 클래스패스에서 읽어 판단한다. 그래서 `IllegalStateException`을 바로 상속한 예외도 잡는다.
  - 테스트 클래스 규칙은 JUnit의 테스트 클래스 탐색을 그대로 따르지 않는다.
    - 더 엄격한 곳: 테스트 메서드(물려받은 것 포함)를 가진 구체 클래스는 JUnit이 실행하지 않아도 `Test`를 요구한다. 예: `private` 클래스, `@Nested` 없는 내부 클래스, 물려받은 테스트를 모두 어노테이션 없이 재정의한 클래스. 이런 클래스는 이름을 `Test`로 끝내거나, 테스트 메서드를 갖지 않게 구조를 바꾼다.
    - 놓치는 곳(리뷰가 본다, 도입할 때 모두 0개)
      - 추상 부모가 선언한 `@Nested` 클래스를 구체 자식이 물려받아 실행하는 구조: 자식 이름은 보지 않고, `@Nested`를 직접 감싼 부모 이름만 본다.
      - `@Nested`를 붙여 만든 합성 어노테이션: 감싸는 클래스를 보지 않는다.
      - `@Test`를 붙여 만든 합성 어노테이션만 쓰는 테스트 클래스: 테스트 메서드로 알아보지 못한다.
  - JUnit 탐색을 그대로 흉내 낼수록 경계 사례가 이어졌다. 그래서 이 저장소에서 쓰는 구조(최상위 테스트 클래스, `@Nested`, 인터페이스의 기본 테스트 메서드)까지만 맞췄다(2026-11-26 결정).
  - `@Scheduled`·`@KafkaListener`를 여러 번 붙이면 컨테이너 어노테이션(`@Schedules`·`@KafkaListeners`)으로 컴파일된다. 이름 규칙과 계층 규칙의 진입점 판별(`ArchitectureSlices`)은 둘 다 인식한다.
  - 나머지 규칙은 이름 접미사만 본다. 모든 규칙은 적어도 한 클래스에 적용되어야 통과하므로, 대상을 고르는 조건이 비면 실패한다.
- **도입할 때 확인한 것(2026-11-26).** 이름을 바꾸기 전 코드(#227 머지 시점 `5dedd289`)에 이 규칙들을 돌렸다. #228·#229에서 고친 위반이 그대로 잡혔다.
  - ArchUnit
    - Reader 동사 12
    - 조회 동사 35, `@GetMapping` 메서드 9
    - `*Config` 11
    - `Dto` 2
    - 테스트 클래스 이름 1
    - 테스트 설정 이름 15
    - 약어 30(`mySQLContainer` 13개 파일 포함)
  - Spotless: 테스트 메서드 이름 78(한글 76, 대문자로 시작 2)
- **실행.** `./gradlew :core:core-api:test --tests 'kr.kro.airbob.architecture*'`로 코드 규칙과 함께 몇 초 안에 끝난다. 테스트 메서드 이름은 `./gradlew spotlessCheck`가 본다.

## 설계

계층·의존 규칙과 Business 입출력은 [코드 구조](../architecture/code-structure.md#계층과-의존-규칙)에 있다. 이 절은 그 밖의 작성 규칙이다.

- 규칙은 2026-11-26에 지금 코드의 다수 관행을 재서 정했다. 관행이 갈린 항목은 사용자가 정했다.
- 기계가 검사하는 규칙을 빼면, 기존 코드는 고치지 않고 새로 쓰거나 고치는 코드부터 적용한다.

| 규칙 | 도입할 때 관행 | 강제 |
|---|---|---|
| 의존성은 생성자로 주입한다. `private final` 필드와 `@RequiredArgsConstructor`가 기본이다. `@Value` 파라미터나 생성 로직이 필요하면 생성자를 직접 쓴다 | 빈 클래스 214개 중 `@RequiredArgsConstructor` 123, 직접 쓴 생성자 57 | 필드 주입 금지는 ArchUnit([코드 규칙](#코드-규칙)), 나머지는 리뷰 |
| 로거는 `@Slf4j`로 만든다. `LoggerFactory`는 이름을 지정하는 로거(`airbob.sql.trace`)에만 쓴다 | `@Slf4j` 29, `LoggerFactory` 3 | 리뷰 |
| 엔티티는 `@Getter`와 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`를 쓴다. `@Setter`·`@Data`는 쓰지 않고, 상태는 바뀌는 뜻을 이름에 담은 엔티티 메서드로 바꾼다(`Reservation.confirm()`) | 엔티티 29개 모두 | public setter 금지는 ArchUnit, 나머지는 리뷰 |
| 요청·응답 DTO와, 도메인 입출력에 따로 만드는 타입은 record로 쓴다. 단순 값을 넘기거나 엔티티 ID를 돌려줄 때는 그 값을 그대로 쓴다([코드 구조](../architecture/code-structure.md#business-입출력과-도메인-레코드)) | 응답 중첩 타입 73개 모두, 요청 28개 중 24개 | 리뷰 |
| 정적 팩토리는 다른 타입 하나를 바꾸면 `from`, 값 여러 개를 모아 만들면 `of`로 짓는다 | 응답의 `from` 65, `of` 7 | 리뷰 |
| 예외는 세 갈래로 나눈다. 클라이언트에 오류 코드로 알리는 실패는 `BaseException` 하위 예외와 `ErrorCode`로 던진다. 외부 연동·인프라 실패는 뜻을 담은 `RuntimeException` 하위 예외로 던진다(클라이언트 모듈은 core의 예외를 모른다). 내부 불변식 위반은 `IllegalStateException`(또는 그 하위), 잘못된 인자는 `IllegalArgumentException`이다 | 예외 99개 중 `BaseException` 하위 85 | 일반 예외 생성 금지는 ArchUnit, 나머지는 리뷰 |
| `@Transactional`은 트랜잭션을 소유하는 메서드 단위로 붙인다. 보통 Business 메서드이고, 단계마다 트랜잭션을 여는 흐름에서는 그 단계의 Implement 메서드다(예: 임대 트랜잭션을 여는 `PaymentOperationLeaseManager.claim()`, [코드 구조의 payment 계층](../architecture/code-structure.md#payment의-계층-pr-16a16d)). DB를 읽는 조회는 `readOnly = true`로 둔다. 트랜잭션 밖에서 단계를 조율하는 메서드에는 붙이지 않는다. 예: 결제 실행 펜스를 잡고 단계를 조율하는 `PaymentOperationExecutionService.execute()`, 시도마다 새 트랜잭션을 여는 흐름([Business 입출력](../architecture/code-structure.md#business-입출력과-도메인-레코드)) | 메서드 단위 114, 클래스 단위 2. Service `get…` 29개 중 `readOnly` 26(나머지 3개는 ES·캐시 조회) | 리뷰 |
| `Optional`은 반환값과, "없을 수 있음"이 뜻인 record 컴포넌트에만 쓴다. 메서드 매개변수와 클래스 필드에는 쓰지 않는다 | 필드 0, 매개변수 1, record 컴포넌트 8 | 리뷰 |
| 주석은 한국어로 쓴다. 코드의 이름과 타입은 영어 그대로 적는다 | main 한국어 572·영어 121, test 영어 313·한국어 63(사용자 결정) | 리뷰 |
| 로그 메시지와 내부용 예외 메시지는 영어로 쓴다. 클라이언트에 가는 문구는 `ErrorCode`가 정한다 | 로그 한국어 47·영어 24, 예외 메시지 영어 132·한국어 39(사용자 결정) | 리뷰 |

## 테스트

통합 테스트의 기준(실제 MySQL, Embedded Kafka, `test` 프로필 격리)은 `CLAUDE.md`의 "Testing expectations"에 있다. 규칙을 정한 날짜와 적용 방식은 [설계](#설계)와 같다.

| 규칙 | 도입할 때 관행 | 강제 |
|---|---|---|
| 단언은 AssertJ로 한다 | | Spotless([코드 규칙](#코드-규칙)) |
| 목은 BDDMockito로 다룬다. 스텁은 `given(…).willReturn(…)`·`willThrow(…).given(mock)…`, 검증은 `then(mock).should()…`·`then(mock).shouldHaveNoInteractions()`, 순서 검증은 `then(mock).should(inOrder)…`로 쓴다 | 스텁 BDD 64·Mockito 37개 파일(사용자 결정). 기존 70개 파일을 바꿨다 | Mockito의 `when`·`verify`·`do…`·`verifyNo…Interactions` 호출은 Spotless(test 소스). `InOrder.verify`는 리뷰 |
| 스프링 테스트의 목 빈은 `@MockitoBean`·`@MockitoSpyBean`으로 만든다 | `@MockitoBean` 35, `@MockBean` 0 | `@MockBean`·`@SpyBean`은 Spotless |
| 새 테스트에는 동작을 설명하는 한국어 `@DisplayName`을 붙인다 | 테스트 메서드 1,623개 중 약 1,055개, 한국어 997·영어만 58(사용자 결정) | 리뷰 |
| 범위에 맞는 테스트를 쓴다. 단위는 `@ExtendWith(MockitoExtension.class)`, 쿼리는 `@DataJpaTest`, 여러 계층과 MySQL 계약은 `@SpringBootTest`와 Testcontainers로 한다 | `MockitoExtension` 76, `@DataJpaTest` 33, `@SpringBootTest` 26개 파일 | 리뷰 |
| 비동기 결과는 Awaitility로 기다린다. 고정 `Thread.sleep`으로 기다리지 않는다 | Awaitility 8, `Thread.sleep` 4개 파일(폴링 루프) | 리뷰 |
| `@Nested`와 given/when/then 주석은 선택이다 | `@Nested` 7, 주석 6개 파일 | — |

- **BDDMockito로 통일한 이유.** 테스트를 준비·실행·검증 순서로 읽히게 한다. 다수 관행이기도 했다. 기존 Mockito 코드는 이 규칙을 넣으며 함께 바꿨다(70개 파일, 동작 불변).
- **Spotless가 보는 것.** test 소스에서 접두어 없는 `when(`·`verify(`·`doThrow(` 같은 호출과 `Mockito.when(` 같은 호출을 막는다. 주석에 같은 글을 쓰면 걸리니 문구를 바꾼다.
  - `lenient().when(…)`은 막지 않는다. BDDMockito에는 lenient 스텁이 없다.
  - `inOrder.verify(…)`는 변수 이름을 알 수 없어 막지 못하므로 리뷰가 본다.

## 커밋 메시지

커밋 제목은 `<type>: <요약>` 한 줄로 쓴다. 2026-11-26에 정했다.

| type | 쓰는 곳 |
|---|---|
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작을 바꾸지 않는 구조 변경 |
| `perf` | 성능 개선 |
| `test` | 테스트만 바뀜 |
| `docs` | 문서만 바뀜 |
| `style` | 포매팅만 바뀜 |
| `build` | 빌드 설정과 의존성 |
| `ci` | CI 워크플로와 스크립트 |
| `chore` | 그 밖의 유지보수(도구 설정, 규칙 도입 등) |
| `revert` | 이전 커밋 되돌리기 |

- 요약은 한국어로, 무엇을 하는지 한 문장으로 쓴다. 클래스·파일 이름 같은 영어 식별자는 섞어도 된다. 예: `docs: CLAUDE.md에 행동 가이드라인 추가`
- 범위 표기(`fix(aws): …`)와 깨는 변경 표시(`feat!: …`)는 쓰지 않는다.
- `git revert`가 만드는 `Revert "…"` 제목은 `revert: …`로 고쳐 쓴다. `fixup!`·`squash!` 커밋은 PR을 열기 전에 합친다.
- 제목 다음 줄은 비운다. git은 빈 줄 없이 이어진 줄을 제목에 합쳐 보여 준다(`%s`, `git log --oneline`).
- 본문은 자유다. 검사하지 않는다.
- PR 제목도 같은 형식으로 쓴다(`pr-description` 스킬). PR 제목은 검사하지 않는다.

**강제.** CI `scope` 잡의 `scripts/ci/commit_messages.py`가 PR의 머지가 아닌 커밋(`base..head`) 제목(메시지의 첫 줄)을 검사한다. 형식, type 목록, 요약의 한글(완성형 1자 이상), 제목 다음 빈 줄을 본다. 위반이 있으면 `scope` 잡과 `CI result`가 실패하고, 빌드와 테스트도 돌지 않는다.
- 고치는 법: 해당 커밋의 제목을 고치고(`git rebase -i <base>`에서 `reword`) `git push --force-with-lease`로 다시 올린다.
- 로컬 확인: `BASE_SHA=origin/main python3 -B scripts/ci/commit_messages.py` (`HEAD_SHA`를 주지 않으면 `HEAD`)

**도입할 때 관행.** main의 첫 번째 부모 이력에서 두 부모가 있는 PR 머지 커밋 119개를 골라, 두 부모 사이(`<머지>^1..<머지>^2`)를 다시 검사했다. 머지 커밋 없이 들어간 PR은 빠진다. #160~#232(2026-10-11~11-26)의 73개는 모두 통과하고, 그 전의 #92~#159는 46개 중 41개가 실패한다. 대부분 AWS 실험 커밋의 범위 표기(`fix(aws): …`)와 영어 요약이다. 검사는 새 PR부터 적용하고 이력은 고치지 않는다.
