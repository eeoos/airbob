---
paths:
  - "**/src/**/domain/reservation/**/*"
  - "**/src/**/domain/payment/**/*"
  - "**/src/**/domain/accommodation/**/*"
  - "**/src/**/controller/v1/accommodation/**/*"
  - "**/src/**/controller/v1/reservation/**/*"
---

# Reservation inventory

MySQL is the only reservation authority. There is no external distributed reservation lock or
best-effort Redis hold.

## Date ownership

- `accommodation_inventory_day` owns one row for every night in `[checkIn, checkOut)`. Its primary
  key is `(accommodation_id, stay_date)`.
- Inventory states are `FREE`, `HOLD(owner, expiresAt)`, and `OCCUPIED(owner)`. Reservation status
  remains the business state; the inventory table is the exclusive date-ownership model.
- The database enforces ownership. The composite foreign key `(reservation_id, accommodation_id)`
  references the V25 `UNIQUE(id, accommodation_id)` on `reservation`, and
  `chk_inventory_day_state_owner` ties state to owner and hold expiry (V26).
- Startup seeds and verifies every published accommodation before readiness opens. Publish and
  timezone changes seed in the business transaction (`AccommodationCommandService`); rolling
  maintenance inserts only missing days.
- A checkout whose nights lack inventory rows fails closed with `R026` (inventory not ready). For an
  existing reservation, missing coverage is an invariant violation instead.

## Quote and checkout

- The public write contract is V1-only: create a quote, checkout it with an `Idempotency-Key`, then
  issue a payment-attempt token before confirmation. Do not restore direct-create or reservation V2
  compatibility paths.
- Quote creation never locks or consumes inventory. The authoritative checkout revalidates quote,
  price, coupon, accommodation, and inventory before creating the 15-minute hold.
- Quotes have no elapsed-time validity deadline or `quote_expires_at` response field. Keep live
  checkout revalidation, one-use attachment, and idempotent replay. Quote data retention (30 days
  by default, based on `created_at`) remains separate from checkout validity and HOLD expiry.
- Checkout takes a short published-accommodation `FOR SHARE NOWAIT` snapshot, then locks only the
  requested date rows in primary-key order with `FOR UPDATE NOWAIT` under `READ_COMMITTED`.
- Coupon use runs inside the same checkout transaction. The coupon is restored when the hold is
  released or expires.
- `R025` means retryable `NOWAIT` lock contention. `R002` means a requested night is `OCCUPIED` or
  held by another unexpired `HOLD`; quote creation can return it too.

## Transitions and cleanup

- Every existing-reservation transition locks reservation before its date rows. Payment entry moves
  owned `HOLD` rows to `OCCUPIED`. Definitive confirmation decline, successful cancellation, eligible
  mark-not-paid, and a guest releasing their own hold (`ReservationHoldCommandService`) release them.
  Retry, unknown outcome, manual review, cancellation pending, and cancellation failure retain
  ownership.
- Expired cleanup releases only `HOLD` rows still owned by that reservation. A newer checkout may
  atomically reclaim an expired hold, and stale cleanup must never clear the new owner.

## Host reservation list

- Host reservation lists select cursor keys with ownership, status, checkout, and cursor filters
  before `size + 1`, then join display fields in one HQL-derived-table SQL statement. Preserve
  current accommodation ownership, outer ordering, and the nonnull guest foreign-key contract.
  V33 covers the candidate scan with `(accommodation_id, created_at DESC, id DESC, status, check_out_at)`.
