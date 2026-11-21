---
paths:
  - "**/src/**/domain/accommodation/cache/**/*"
  - "**/src/**/config/AccommodationDetailRedis*"
---

# Accommodation detail cache

- General Redis and the dedicated accommodation-cache Redis remain separate dependencies.
- Durable invalidation uses the domain-owned canonical messaging adapter and
  `ACCOMMODATION_CACHE.events`. Do not recreate root outbox or Kafka-consumer packages.
- A loader registers a load permit before reading MySQL. `AccommodationDetailRedisStore` compares
  the permit and writes the value in one Lua script, and invalidation deletes the value and permit
  together, so a load that started before an invalidation cannot repopulate stale data. Keep the
  compare and write in one script.
