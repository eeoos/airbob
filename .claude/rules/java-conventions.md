---
paths:
  - "**/src/**/*.java"
---

# Java conventions

The rules and their reasons are in `docs/development/coding-convention.md`. The build already fails on
formatting, the code rules (`CodingRulesTest`), most naming rules (`NamingRulesTest`, `TestNamingRulesTest`,
and the Spotless lint on test method names), and the test mock APIs (Spotless). This file lists only what
review checks.

## Names

- Use English words, never romanized Korean. Put a Korean test description in `@DisplayName`.
- Capitalize only the first letter of an abbreviation, two-letter ones included: `userId`, `ioError`, not
  `userID`. Do not copy an enum constant into a method name (`returnsCardDescription`, not `CARD_description`).
- Name classes with nouns and interfaces with nouns or adjectives. Start methods with a verb or a
  preposition (`toCondition`, `withTicker`).
- Use one-letter names only for short-lived variables such as loop indexes and lambda parameters.
- Spring Data derived queries have no `_`: `existsByWishlistMemberIdAndAccommodationId`.
- Pair Writer verbs by meaning: `create`/`delete` the entity itself, `add`/`remove` an item in another
  entity's collection, `update`/`change` a value, `record` a history row.
- Name an integration event `<Subject><PastParticiple>V<n>`, such as `AccommodationSearchRefreshRequestedV1`.
- Name a query with `get` in a `core.domain` `@Service`, even when its first word would be another verb such
  as `show` or `browse`, and in any controller or Swagger interface, including one mapped with
  `@RequestMapping(method = GET)`; the tests check `@GetMapping` methods and only the common other verbs.
- In test code too: no class ends with `Dto`, and a `@TestFactory` method that returns a value starts with a
  lowercase English word like any test method (`_` allowed); the Spotless lint reads `void` declarations only.
- A class that JUnit runs ends with `Test`, also where the test rule cannot judge it: a class that inherits an
  abstract parent's `@Nested` class (the rule checks only the parent), the class around one marked with a
  composed `@Nested` annotation, and a class whose tests use only a composed `@Test` annotation.
  Elsewhere the rule is stricter than JUnit: a concrete class with test methods, inherited ones included,
  must end with `Test` even if JUnit skips it (a private class, an inner class without `@Nested`, a class whose
  inherited tests are all overridden without `@Test`); rename it or move the test methods out.
- Name domain input records `NewXxx` for creation and `XxxPatch` for a partial update that keeps null fields;
  see `docs/architecture/code-structure.md` ("Business 입출력과 도메인 레코드").

## Design

Apply these to code you write or change; existing code is not rewritten for them.

- Inject through the constructor: `private final` fields with `@RequiredArgsConstructor`, or a written
  constructor when it needs `@Value` parameters or logic.
- Log with `@Slf4j`; use `LoggerFactory` only for a named logger such as `airbob.sql.trace`.
- Entities use `@Getter` and `@NoArgsConstructor(access = AccessLevel.PROTECTED)`, never `@Setter` or
  `@Data`; change state through a method that names the change (`Reservation.confirm()`).
- Write request/response DTOs, and any type made for domain inputs or outputs, as records; pass simple
  values and return entity IDs as they are.
- Name a static factory `from` when it converts one other type and `of` when it builds from several values.
- Exceptions: a failure reported to clients with an error code is a `BaseException` subclass with an
  `ErrorCode`; an external or infrastructure failure is a `RuntimeException` subclass named for it (client
  modules do not know core exceptions); a broken invariant is `IllegalStateException` (or a subclass) and a
  bad argument is `IllegalArgumentException`.
- Put `@Transactional` on the method that owns the transaction, usually a business method and, in a flow
  that opens a transaction per step, that step's implement method (`PaymentOperationLeaseManager.claim()`);
  `readOnly = true` for queries that read the database. Do not put it on a method that coordinates steps
  outside a transaction, such as `PaymentOperationExecutionService.execute()` (the execution fence refuses an
  active transaction) or a flow that opens a new transaction per attempt.
- Use `Optional` for return values and for record components that may be absent, never for method
  parameters or class fields.
- Write comments in Korean, keeping code names and types as they are. Write log messages and internal
  exception messages in English; client-facing text comes from `ErrorCode`.

## Tests

- Mock with BDDMockito: `given(...).willReturn(...)`, `willThrow(...).given(mock)`, `then(mock).should()`,
  `then(mock).shouldHaveNoInteractions()`, and `then(mock).should(inOrder)` instead of `inOrder.verify(...)`.
- Give every new test a Korean `@DisplayName` that states the behavior.
- Pick the narrowest test: `@ExtendWith(MockitoExtension.class)` for units, `@DataJpaTest` for queries,
  `@SpringBootTest` with Testcontainers across layers and for MySQL contracts (see `CLAUDE.md`).
- Wait for asynchronous results with Awaitility, not a fixed `Thread.sleep`.

