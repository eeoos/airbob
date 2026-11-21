package kr.kro.airbob.domain.reservation.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import kr.kro.airbob.domain.reservation.exception.ReservationPaymentAttemptTooLateException;

public final class ReservationPaymentAttemptPolicy {

	private final Duration minimumRemaining;

	public ReservationPaymentAttemptPolicy(Duration minimumRemaining) {
		if (minimumRemaining == null || minimumRemaining.isZero() || minimumRemaining.isNegative()) {
			throw new IllegalArgumentException("minimum payment-attempt remaining time must be positive");
		}
		this.minimumRemaining = minimumRemaining;
	}

	public void validateFirstIssue(Instant now, Instant expiresAt) {
		if (Duration.between(require(now), require(expiresAt)).compareTo(minimumRemaining) < 0) {
			throw new ReservationPaymentAttemptTooLateException();
		}
	}

	public void validateReplay(Instant now, Instant expiresAt) {
		if (!require(now).isBefore(require(expiresAt))) {
			throw new ReservationPaymentAttemptTooLateException();
		}
	}

	public long remainingSeconds(Instant now, Instant expiresAt) {
		return Math.max(0L, Duration.between(require(now), require(expiresAt)).getSeconds());
	}

	private Instant require(Instant value) {
		return Objects.requireNonNull(value);
	}
}
