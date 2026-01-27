# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Test Commands

```bash
# Build (skipping tests)
./gradlew build -x test

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "kr.kro.airbob.domain.reservation.ReservationConcurrencyTest"

# Run a single test method
./gradlew test --tests "kr.kro.airbob.domain.reservation.ReservationConcurrencyTest.동시에_300개_요청이_들어오면_1개만_예약_성공한다"

# Generate QueryDSL Q-classes (required after entity changes)
./gradlew compileJava

# Run the application (requires external services)
./gradlew bootRun

# Package for deployment
./gradlew packageZip
```

## Architecture Overview

This is an Airbnb clone (Airbob) built with Spring Boot 3.5.8, JDK 21, using event-driven architecture for reservation and payment processing.

### Package Structure

```
kr.kro.airbob/
├── common/          # Cross-cutting: BaseEntity, UserContext, ApiResponse, GlobalExceptionHandler
├── config/          # Spring configs: Redis, Elasticsearch, Kafka, QueryDSL, WebMvc
├── cursor/          # Cursor-based pagination (CursorParam annotation + resolver)
├── domain/          # Domain modules (see below)
├── geo/             # Geolocation utilities (Google Maps, IPInfo integration)
├── kafka/           # Kafka consumers and event translators
├── outbox/          # Transactional Outbox pattern for event publishing
└── search/          # Elasticsearch search service with dual-backend (ES + MySQL fallback)
```

### Domain Modules

Each domain follows layered structure: `api/` (controllers) → `service/` → `repository/` → `entity/`

- **reservation**: Core booking logic with Redisson distributed locks for concurrency control
- **payment**: Toss Payments integration with compensation service for failures
- **accommodation**: Listings with QueryDSL for complex queries, Elasticsearch indexing
- **member**: User management with session-based auth (Redis-backed)
- **review**: Review system with denormalized summary aggregation
- **wishlist**: User wishlists with cursor pagination
- **auth**: Session authentication filter and utilities

### Key Architectural Patterns

**Distributed Locking (Reservation)**
- Redisson `RedissonMultiLock` with Pub/Sub (not spin-lock) for lock acquisition
- Lock keys sorted alphabetically to prevent deadlocks: `accommodation:{id}:{date}`
- Located in `ReservationLockManager.java`

**Event-Driven Architecture**
- Kafka topics: `PAYMENT.events`, `RESERVATION.events`, `ACCOMMODATIONS.events`
- Transactional Outbox pattern via Debezium CDC for exactly-once delivery
- DLQ (`*.DLT`) with Slack notifications for failed messages

**Search**
- Elasticsearch 8.9 with Nori analyzer (Korean) + standard analyzer
- Dual endpoints: `/api/v1/search/accommodations` (ES) and `/api/v2/search/accommodations` (MySQL fallback)
- Event-driven index updates via `AccommodationIndexingConsumer`

### Database

- MySQL 8.0 with Flyway migrations in `src/main/resources/db/migration/`
- 33 migration versions (V1-V33)
- QueryDSL for type-safe queries (Q-classes generated at compile time)

### Testing

- Testcontainers for MySQL, Redis, Elasticsearch
- Concurrency tests validate distributed locking behavior
- Test profile disables Kafka

### External Integrations

- **Toss Payments**: Payment gateway (`domain/payment/service/gateway/`)
- **Google Maps API**: Geocoding (`geo/`)
- **AWS S3**: Image storage via Spring Cloud AWS
- **Slack Webhooks**: Error alerting for DLQ events

### Session Authentication

- Redis-backed sessions with `SessionAuthFilter`
- `UserContext` (ThreadLocal) holds request-scoped user info
- Public paths defined in filter: `/api/v1/auth/login`, `/api/v1/members`, search endpoints
