---
paths:
  - "src/**/api/**/*"
  - "src/**/dto/**/*"
  - "src/**/apidocs/**/*"
  - "docs/api/**/*"
---

# API documentation

- Controller/DTO annotations and `config/apidocs/` are the source of the V1 OpenAPI contracts.
  Keep summaries, success statuses, headers, examples, `@ApiErrors`, and unique nested DTO schema
  names aligned with actual behavior. Do not infer authentication from `@CurrentMemberId` alone.
- Update `docs/api/` guides when authentication, pagination, time semantics, or workflows change.
  Use synthetic examples and keep API-facing payment states distinct from internal operation states.
- Run `./gradlew updateOpenApi verifyOpenApi` after API changes and include generated YAML snapshots
  in the same change. Never hand-edit `docs/api/openapi/*.yaml`; ordinary builds only verify them.
- Keep Swagger UI and live OpenAPI endpoints disabled when `test` is active unless documentation
  tests explicitly enable them. The shared documentation URL is
  `https://api.airbob.cloud/swagger-ui/index.html`.
