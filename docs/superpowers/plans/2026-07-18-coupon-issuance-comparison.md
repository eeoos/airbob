# Coupon Lock/Lua Issuance Comparison Implementation Plan

> **For Codex:** Execute this plan task by task with test-first changes. Keep the two issuance paths structurally independent so the lock path can be deleted after measurement.

**Goal:** Complete a synchronous first-come coupon feature that exposes separate Redisson-lock and Redis-Lua APIs, preserves database consistency for handled failures, and provides a reproducible k6 comparison environment.

**Architecture:** Both public APIs return `201 Created` only after the MySQL transaction commits. The lock path serializes the database validation/insert transaction with Redisson. The Lua path atomically validates Redis time, campaign state, duplicate membership, and stock before persisting the approved issue to MySQL; a conditional Lua compensation restores Redis only when the member was actually approved. Redis preparation is an admin-only, one-time operation before issuance opens.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA, MySQL 8/Flyway, Redisson/Redis 7 Lua, Micrometer/Prometheus, JUnit 5, Testcontainers, k6.

---

## Task 1: Split issuance and usage periods

**Files:**

- Create: `src/main/resources/db/migration/V14__split_coupon_issue_and_usage_periods.sql`
- Create: `src/test/java/kr/kro/airbob/migration/CouponPeriodMigrationIntegrationTest.java`
- Modify: `src/main/java/kr/kro/airbob/domain/coupon/entity/Coupon.java`
- Modify: `src/main/java/kr/kro/airbob/domain/coupon/dto/CouponRequest.java`
- Modify: `src/main/java/kr/kro/airbob/domain/coupon/dto/CouponResponse.java`
- Modify: `src/test/java/kr/kro/airbob/domain/coupon/entity/CouponTest.java`
- Modify: coupon fixtures under `src/test/java/kr/kro/airbob/domain/coupon/`

1. Add failing entity boundary tests proving issuance and usage periods are independent and both use `[start, end)` semantics.
2. Add a failing Flyway integration test that migrates a pre-V14 coupon row and verifies `start_date/end_date` become `usable_from/usable_until` while their values are also copied into new non-null `issue_start_at/issue_end_at` columns.
3. Implement V14 and replace the two entity/DTO fields with `issueStartAt`, `issueEndAt`, `usableFrom`, and `usableUntil`.
4. Implement `isIssueOpen(now)`, `isIssuable(now)`, and `isUsable(now)` using start-inclusive/end-exclusive checks.
5. Update existing test fixtures and run the focused migration/entity tests.
6. Commit as a cohesive period-model change.

## Task 2: Implement the Redis Lua contract

**Files:**

- Create: `src/main/resources/lua/coupon_prepare.lua`
- Modify: `src/main/resources/lua/coupon_issue.lua`
- Create: `src/main/resources/lua/coupon_compensate.lua`
- Create: `src/main/java/kr/kro/airbob/domain/coupon/service/CouponRedisStockManager.java`
- Create: `src/main/java/kr/kro/airbob/domain/coupon/service/CouponRedisIssueResult.java`
- Create: `src/test/java/kr/kro/airbob/domain/coupon/CouponRedisStockManagerIntegrationTest.java`

1. Add Testcontainers-backed failing tests for one-time preparation, Redis hash-tagged keys, Redis-time boundaries, inactive/unprepared/duplicate/sold-out results, and shared expiration.
2. Add a failing test that calls compensation twice and proves stock is restored exactly once.
3. Implement preparation with `coupon:{id}:meta` and `coupon:{id}:issued`. The meta hash stores `stock`, `issueStartAt`, `issueEndAt`, `active`, and `expiresAt`; preparation refuses to overwrite either existing key.
4. Implement issue Lua return codes: remaining stock `>= 0`, sold out `-1`, duplicate `-2`, before start `-3`, ended `-4`, unprepared `-5`, inactive `-6`. Use Redis `TIME`, not the application clock.
5. Implement compensation as `SREM` followed by `HINCRBY stock 1` only when `SREM` returns one. Distinguish compensated, no-op, and missing-meta outcomes.
6. Load all scripts once at bean initialization and expose typed Java results instead of raw codes or key strings.
7. Run the focused Redis integration tests and commit.

## Task 3: Add safe stock preparation and immutable campaign controls

**Files:**

- Create: `src/main/java/kr/kro/airbob/domain/coupon/service/CouponStockPreparationService.java`
- Modify: `src/main/java/kr/kro/airbob/domain/coupon/service/CouponService.java`
- Modify: `src/main/java/kr/kro/airbob/domain/coupon/api/CouponAdminController.java`
- Modify: `src/main/java/kr/kro/airbob/common/exception/ErrorCode.java`
- Create/modify: coupon exceptions under `src/main/java/kr/kro/airbob/domain/coupon/exception/`
- Create: `src/test/java/kr/kro/airbob/domain/coupon/CouponStockPreparationServiceTest.java`
- Create: `src/test/java/kr/kro/airbob/domain/coupon/api/CouponAdminControllerTest.java`

1. Add failing service tests for rejecting unlimited, inactive, already-started, DB-issued, or Redis-prepared campaigns; also verify a valid untouched campaign is prepared once.
2. Add failing tests proving prepared campaigns cannot change `totalQuantity`, `issueStartAt`, `issueEndAt`, or `isActive`, and cannot be deactivated, while presentation/usage-period fields remain editable.
3. Implement stock preparation from a transactionally read DB snapshot. Require finite positive stock, active state, current time before `issueStartAt`, `issuedQuantity == 0`, and `member_coupon` count zero.
4. Store epoch milliseconds using an explicit `Asia/Seoul` zone so AWS host timezone does not change the campaign instant. Retain Redis metadata until seven days after issuance ends.
5. Add specific errors: already prepared/configuration conflict as `409`, Redis unprepared as `503`, and lock timeout as `503`.
6. Add `POST /api/v1/admin/coupons/{couponId}/stock/prepare`. Preserve the existing admin controller convention requested by the user: class mapping `/api`, method mappings beginning `/v1/admin/coupons`.
7. Run focused service/controller tests and commit.

## Task 4: Split the lock and Lua issuance flows

**Files:**

- Create: `src/main/java/kr/kro/airbob/domain/coupon/service/CouponLockIssueService.java`
- Create: `src/main/java/kr/kro/airbob/domain/coupon/service/CouponLuaIssueService.java`
- Modify: `src/main/java/kr/kro/airbob/domain/coupon/service/CouponIssueTransactionService.java`
- Modify: `src/main/java/kr/kro/airbob/domain/coupon/service/CouponLockManager.java`
- Modify: `src/main/java/kr/kro/airbob/domain/coupon/api/CouponController.java`
- Delete: `src/main/java/kr/kro/airbob/domain/coupon/service/CouponIssueService.java`
- Create: `src/test/java/kr/kro/airbob/domain/coupon/service/CouponLockIssueServiceTest.java`
- Create: `src/test/java/kr/kro/airbob/domain/coupon/service/CouponLuaIssueServiceTest.java`
- Create: `src/test/java/kr/kro/airbob/domain/coupon/api/CouponControllerTest.java`

1. Add failing unit tests for lock acquisition → DB commit → unlock ordering and lock release on database failure.
2. Add failing unit tests mapping every Lua result to its domain exception, persisting only approvals, and invoking compensation on handled DB failures without masking the original exception.
3. Rename the database methods to `issueUnderLock` and `persistApprovedIssue`. Standardize validation order to active/issuance period, duplicate, then stock; keep `issued_quantity` increment and member-coupon insert in one transaction.
4. Implement two independent orchestration services without a strategy enum or shared strategy interface.
5. Expose only `POST /api/v1/coupons/{couponId}/issue/lock` and `POST /api/v1/coupons/{couponId}/issue/lua`. Both return 201 after the transactional service has returned.
6. Remove the combined service and its no-lock path.
7. Run focused unit/controller tests and commit.

## Task 5: Add bounded-cardinality issuance metrics

**Files:**

- Create: `src/main/java/kr/kro/airbob/domain/coupon/monitoring/CouponIssueMetricRecorder.java`
- Create: `src/main/java/kr/kro/airbob/domain/coupon/monitoring/MicrometerCouponIssueMetricRecorder.java`
- Modify: lock/Lua services and Redis/lock components from Tasks 2–4
- Create: `src/test/java/kr/kro/airbob/domain/coupon/monitoring/MicrometerCouponIssueMetricRecorderTest.java`

1. Add failing metric-recorder tests for total duration/result by `strategy`, lock wait/timeout, Lua duration/result, database outcome, and compensation outcome.
2. Implement timers/counters with only bounded tags (`lock|lua` and fixed result names). Never tag coupon/member IDs.
3. Record total issuance latency through DB commit and keep lock wait and Lua execution as separate measurements.
4. Run focused metrics tests and commit.

## Task 6: Replace probabilistic concurrency tests with invariants

**Files:**

- Modify: `src/test/java/kr/kro/airbob/domain/coupon/CouponConcurrencyTest.java`
- Add or split focused concurrency test classes when clarity improves

1. Delete the no-lock over-issuance test and all no-lock result accounting.
2. Test lock and Lua on separate coupon IDs with the same stock and distinct-member workload.
3. For each path, assert `member_coupon count == coupon.issued_quantity <= total_quantity` and no member receives more than one copy.
4. For Lua, prepare Redis first and also assert `Redis stock + DB issued count == total_quantity` after all requests finish.
5. Add same-member concurrent-request coverage for both paths.
6. Run the complete coupon test package and commit.

## Task 7: Add the AWS-ready k6 comparison harness

**Files:**

- Create: `load-test/k6/coupon-issuance-comparison.js`
- Create: `load-test/k6/lib/coupon-benchmark-fixture.js`
- Modify: `load-test/README.md`
- Create: `load-test/fixtures/coupon-sessions.example.json`

1. Parse a gitignored external session-ID fixture and require one distinct authenticated member session per active VU/request worker; never store passwords or real sessions in the repository.
2. Select only `VARIANT=lock|lua`, map that value to the corresponding URL, and require separate `COUPON_ID` values across recorded runs.
3. Use the same configurable constant-arrival-rate burst, warm-up, thresholds, and summary format for both variants.
4. Classify `201`, sold-out, duplicate, not-issuable, timeout/unprepared, and unexpected responses separately. Report RPS plus p50/p95/p99 and fail on unexpected responses or dropped iterations.
5. Document preparation order, separate coupon campaigns, session fixture format, warm-up, alternating run order, consistency queries, and result-file commands for later AWS execution.
6. Run k6 syntax/archive validation when k6 is installed; otherwise validate with the repository's available JavaScript tooling and record the limitation.
7. Commit the load-test harness.

## Task 8: Full verification and handoff

1. Run `./gradlew test --console=plain`.
2. Run `./gradlew build -x test --console=plain` if the full test task does not already compile/package every affected artifact.
3. Search for stale `/issue`, no-lock, atomic-counter, `startDate/endDate`, and deleted event-domain references.
4. Review the complete branch diff for accidental coupling, unbounded metric tags, unsafe Redis reset behavior, and unrelated user changes.
5. Confirm the worktree is clean and report branch names, commits, APIs, known Redis→DB crash gap, and exact verification results.
