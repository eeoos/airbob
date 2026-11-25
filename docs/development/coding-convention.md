# 코딩 컨벤션

airbob 코드의 규칙을 영역별로 모은 문서다. 규칙마다 그것을 지키게 하는 장치를 하나씩 정해 둔다.
기계로 확인할 수 있는 규칙은 기계가 강제하고, 사람과 AI 리뷰는 기계가 보지 못하는 부분만 본다.

| 영역 | 기준 | 강제하는 장치 |
|---|---|---|
| 포매팅·import | 아래 [포매팅](#포매팅) | 편집 훅의 자동 정리, Stop 훅과 CI의 `spotlessCheck` |
| 계층·의존 | [코드 구조](../architecture/code-structure.md) | ArchUnit(`core/core-api/src/test/java/kr/kro/airbob/architecture`) |
| 코드 규칙 | 아래 [코드 규칙](#코드-규칙) | ArchUnit `CodingRulesTest`, 테스트의 JUnit `Assertions` 금지는 Spotless |

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
| `./gradlew spotlessCheck` | 포매팅이 다르거나, static이 아닌 와일드카드 import나 JUnit `Assertions`가 있으면 실패한다. `check`에 포함되어 `./gradlew build`가 함께 실행한다 |

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

main 코드 규칙은 ArchUnit `CodingRulesTest`(`core/core-api/src/test/java/kr/kro/airbob/architecture`)가, 테스트의 JUnit `Assertions` 금지는 Spotless(`forbidRegex`)가 강제한다. 위반하면 `./gradlew build`가 실패한다.

| 금지 | 대상 | 대신 쓸 것 |
|---|---|---|
| 필드 주입. `@Autowired`, `@Inject`, `@Resource`, `@Value`가 붙은 필드 | main | 생성자 파라미터(`final` 필드 + `@RequiredArgsConstructor`). 설정값은 생성자나 `@Bean` 메서드 파라미터에 `@Value`를 붙인다 |
| 표준 출력. `System.out`, `System.err`, `printStackTrace()` | main | `@Slf4j` 로거 |
| 일반 예외 직접 생성. `Throwable`, `Exception`, `RuntimeException` | main | 구체적인 예외. 보통 `ErrorCode`를 가진 `BaseException`의 하위 예외 |
| `java.util.logging` | main | SLF4J(`@Slf4j`) |
| `Clock` 빈 없이 현재 시각 읽기. java.time의 `now()`·`now(ZoneId)`, `System.currentTimeMillis()` | main | `Clock` 빈을 주입받아 `clock.instant()`, `clock.millis()`, `LocalDate.now(clock)`를 쓴다. 저장할 `LocalDateTime`은 JPA 감사 시각처럼 `LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)`로 만든다. 경과 시간은 `System.nanoTime()`으로 잰다 |
| JUnit `Assertions` | 모든 모듈의 test | AssertJ의 `assertThat`, `assertThatThrownBy` |

- **Clock 규칙의 이유.** 운영의 `Clock` 빈은 `Clock.systemUTC()`(`ClockConfig`)다. 테스트는 이 빈을 고정 시각으로 바꿔 시간을 제어한다. 그래서 시각을 직접 읽는 코드는 테스트에서 시간을 제어할 수 없다. 쿠폰 발급처럼 DB 시각을 쓰는 곳은 이 규칙과 무관하다(`.claude/rules/coupon.md`).
- **테스트 코드는 대부분 대상이 아니다.** 테스트의 `@Autowired` 필드는 스프링 테스트의 관행이고, 동시성 테스트는 결과를 표준 출력으로 남긴다.
- **규칙의 시험.** `CodingRulesFixtureTest`는 규칙마다 위반 예시 클래스를 두고, 규칙이 그 위반을 실제로 잡는지 확인한다.
  - 이 프로젝트의 ArchUnit 설정(`resolveMissingDependenciesFromClassPath=false`)은 JDK와 라이브러리 클래스를 가져오지 않는다. 그래서 그 타입은 이름으로만 안다.
  - 이름만으로 판단할 수 없는 규칙은 아무것도 잡지 못한 채 통과할 수 있다. 실제로 ArchUnit 기본 규칙은 `IllegalStateException` 같은 하위 클래스에서 부른 `printStackTrace()`와, 필드 없이 쓰는 `Logger.getGlobal()`을 놓쳤다.
  - 기본 규칙은 직접 호출만 본다. `orElseGet(Instant::now)`, `orElseThrow(RuntimeException::new)` 같은 메서드·생성자 참조도 잡도록 조건을 더했다. 픽스처는 호출과 참조를 모두 담는다.
- **JUnit `Assertions`를 Spotless로 막는 이유.** ArchUnit 규칙은 core-api 테스트 클래스만 가져와서 클라이언트 모듈의 테스트를 보지 못한다. API 문서 테스트만 바뀐 PR은 CI에서 테스트 대신 `spotlessCheck`와 `verifyOpenApi`만 돈다. Spotless는 모든 모듈에 적용되고 두 경우 모두 실행된다.
- **도입할 때 고친 위반(2026-11-25).**
  - 필드 주입 8개: `ElasticsearchConfig`, `RedisConfig`, `S3ImageUploader`, `SettlementGenerator`의 `@Value` 필드
  - `Clock` 없이 시각을 읽은 5곳: `WishlistRepositoryImpl`, `SessionRedisRepository`, `RecentlyViewedWriter`
  - JUnit `Assertions`를 쓴 테스트 3개 파일
- **넣지 않은 ArchUnit 기본 규칙.**
  - JodaTime 금지: 의존성이 없다.
  - 옛 날짜·시간 클래스 금지: JDBC에 값을 넘기는 `java.sql.Timestamp`·`Date` 사용이 정당하다.
  - 사용 중단 API 금지: 라이브러리 클래스를 가져오지 않아 JDK API의 사용 중단 표시를 볼 수 없다.
- **실행.** `./gradlew :core:core-api:test --tests 'kr.kro.airbob.architecture*'`로 몇 초 안에 끝난다. Stop 훅은 core-api의 main 코드가 바뀌면 이 테스트를 실행한다.
