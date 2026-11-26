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
- Name a `@Service` query with `get`, even when its first word would be another verb such as `show` or
  `browse` (the tests only catch the common ones).
- Name domain input records `NewXxx` for creation and `XxxPatch` for a partial update that keeps null fields;
  see `docs/architecture/code-structure.md` ("Business 입출력과 도메인 레코드").
