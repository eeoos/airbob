---
paths:
  - "src/**/domain/accommodation/cache/**/*"
  - "src/**/config/AccommodationDetailRedis*"
---

# Accommodation detail cache

- Cache code lives under `domain/accommodation/cache/`: core classes at its root, plus `config/`,
  `invalidation/`, `messaging/{event,kafka,outbox}/`, `monitoring/`, and `redis/`. The dedicated
  Redis connection is configured in `config/AccommodationDetailRedisConfig` and
  `config/AccommodationDetailRedisProperties`.
- General Redis and the dedicated accommodation-cache Redis remain separate dependencies.
- Durable invalidation uses the domain-owned canonical messaging adapter and
  `ACCOMMODATION_CACHE.events`. Do not recreate root outbox or Kafka-consumer packages.
- A loader registers a load permit before reading MySQL. `AccommodationDetailRedisStore` compares
  the permit and writes the value in one Lua script, and invalidation deletes the value and permit
  together, so a load that started before an invalidation cannot repopulate stale data. Keep the
  compare and write in one script.
