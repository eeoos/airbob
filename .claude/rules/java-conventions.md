---
paths:
  - "**/src/**/*.java"
---

# Java conventions

The rules and their reasons are in `docs/development/coding-convention.md`. The build already fails on
formatting, the code rules (`CodingRulesTest`), and most naming rules (`NamingRulesTest`,
`TestNamingRulesTest`, and the Spotless lint on test method names). This file lists only what review checks.

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
- Name a query with `get` in a `@Service`, even when its first word would be another verb such as `show` or
  `browse`, and in a controller or Swagger interface mapped with `@RequestMapping(method = GET)`; the tests
  check `@GetMapping` methods and only the common other verbs.
- In test code too: no class ends with `Dto`, and a `@TestFactory` method that returns a value starts with a
  lowercase English word like any test method (`_` allowed); the Spotless lint reads `void` declarations only.
- A class that JUnit runs ends with `Test`, also where the test rule cannot judge it: a class that inherits an
  abstract parent's `@Nested` class, and the class around one marked with a composed `@Nested` annotation.
  A class JUnit does not run needs no suffix: a private class, an inner class without `@Nested`, or a class
  left with no test to run after overriding its inherited test methods without `@Test`.
- Name domain input records `NewXxx` for creation and `XxxPatch` for a partial update that keeps null fields;
  see `docs/architecture/code-structure.md` ("Business 입출력과 도메인 레코드").
