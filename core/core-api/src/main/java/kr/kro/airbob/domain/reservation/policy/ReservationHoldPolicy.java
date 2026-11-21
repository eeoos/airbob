package kr.kro.airbob.domain.reservation.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ReservationHoldPolicy {

	public static final Duration DEFAULT_DURATION = Duration.ofMinutes(15);

	private final Duration duration;

	public ReservationHoldPolicy(Duration duration) {
		if (duration == null || duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException("reservation hold duration must be positive");
		}
		this.duration = duration;
	}

	public static ReservationHoldPolicy defaultPolicy() {
		return new ReservationHoldPolicy(DEFAULT_DURATION);
	}

	public Instant expiresAtFrom(Instant holdStartedAt) {
		return Objects.requireNonNull(holdStartedAt, "holdStartedAt must not be null")
			.plus(duration);
	}
}
