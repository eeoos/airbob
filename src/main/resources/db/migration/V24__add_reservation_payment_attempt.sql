ALTER TABLE reservation
    ADD COLUMN payment_attempt_required BOOLEAN NOT NULL DEFAULT FALSE AFTER expires_at,
    ADD COLUMN payment_attempt_uid BINARY(16) NULL AFTER payment_attempt_required,
    ADD COLUMN payment_attempt_started_at DATETIME(6) NULL AFTER payment_attempt_uid,
    ADD COLUMN payment_attempt_consumed_at DATETIME(6) NULL AFTER payment_attempt_started_at,
    ADD CONSTRAINT uk_reservation_payment_attempt_uid UNIQUE (payment_attempt_uid),
    ADD CONSTRAINT chk_reservation_payment_attempt CHECK (
        (
            payment_attempt_required = FALSE
            AND payment_attempt_uid IS NULL
            AND payment_attempt_started_at IS NULL
            AND payment_attempt_consumed_at IS NULL
        )
        OR
        (
            payment_attempt_required = TRUE
            AND (
                (
                    payment_attempt_uid IS NULL
                    AND payment_attempt_started_at IS NULL
                    AND payment_attempt_consumed_at IS NULL
                )
                OR
                (
                    payment_attempt_uid IS NOT NULL
                    AND payment_attempt_started_at IS NOT NULL
                    AND (
                        payment_attempt_consumed_at IS NULL
                        OR payment_attempt_consumed_at >= payment_attempt_started_at
                    )
                )
            )
        )
    );
