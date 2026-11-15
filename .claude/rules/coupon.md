---
paths:
  - "src/**/domain/coupon/**/*"
---

# Coupon issuance

Operations: `docs/operations/coupon-issuance-operations.md` and
`docs/operations/coupon-time-contract.md`.

## Quantity and time

- New coupons have positive finite quantities, validated in the application. Historical nullable
  quantity remains readable.
- Coupon business timestamps are stored/compared as UTC. API dates are `Instant` values with `Z`;
  the frontend formats them in `Asia/Seoul`.
- Issuance reads "now" from the database (`select utc_timestamp(6)`), not from the application clock.

## Issuance

- The only issuance endpoint is `/api/v1/coupons/{couponId}/issue`. There is no strategy lookup,
  conditional-UPDATE issuance, RC issuance, composite unit table, or experiment endpoint; do not
  reintroduce them.
- Issuance uses only surrogate-key `coupon_issue_unit` rows and `REPEATABLE_READ`. A unit DELETE and
  `member_coupon` INSERT commit in one transaction. Never update the shared coupon counter on a
  `SKIP LOCKED` path.
- Each attempt reads the coupon policy with `FOR SHARE NOWAIT`; admin changes lock it with
  `FOR UPDATE`.
- Empty `SKIP LOCKED` results require a fresh-snapshot check: `CP002` is sold out, `CP012` is
  retryable contention.
- MySQL errors 3572 (NOWAIT), 1213 (deadlock), and 1205 (lock wait timeout) are retried. By default
  issuance makes up to 3 attempts within 250 ms (`coupon.issue.max-attempts`,
  `coupon.issue.retry-budget-ms`), each in a new transaction with a 1-second timeout, then returns
  `CP012`.
- `UNIQUE(member_id, coupon_id)` and whole-transaction rollback prevent duplicates.
- Issuance unit reads and deletes use Spring Data JPA native queries; preparation retains bounded
  JDBC multi-row inserts.

## Stock preparation

- Before `issue_start_at` only, preparation, quantity changes, and discard lock the coupon first.
  After start, there are no quantity changes or restocking.
- Never regenerate consumed stock or auto-reseed an issued campaign. Never treat Redis timestamps as
  proof of MySQL stock.

## Lists and status

- Coupon campaign and owned-coupon lists use `@CursorParam` with a default size of 20 and a maximum
  of 50. Campaign order is `(issue_start_at, coupon.id)` descending; owned-coupon order is
  `(created_at, member_coupon.id)` descending.
- `member_coupon.created_at` is nonnull (V32). When repairing data, reconcile it from trusted
  issuance evidence; never invent dates.
- Campaign availability uses a correlated QueryDSL `EXISTS` on remaining `coupon_issue_unit` rows,
  without counting issued member coupons or locking stock. The issuance transaction revalidates
  availability.
- Issuance status keeps `UPCOMING` precedence, then `SOLD_OUT` only when `totalQuantity != null` and
  no stock remains, otherwise `OPEN`. Historical nullable quantity is a read compatibility rule.
