package kr.kro.airbob.domain.payment.service;

import java.time.Duration;
import java.util.Objects;

public final class PaymentRetryBackoff {
	private final Duration initial;
	private final Duration max;

	public PaymentRetryBackoff(Duration initial, Duration max) {
		this.initial = requirePositive(initial, "initial");
		this.max = requirePositive(max, "max");
		if (initial.compareTo(max) > 0) {
			throw new IllegalArgumentException("initial must not exceed max");
		}
	}

	public Duration forAttempt(int attemptCount) {
		long multiplier = 1L << Math.max(0, Math.min(attemptCount - 1, 20));
		try {
			Duration candidate = initial.multipliedBy(multiplier);
			return candidate.compareTo(max) > 0 ? max : candidate;
		} catch (ArithmeticException ignored) {
			return max;
		}
	}

	private static Duration requirePositive(Duration value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return value;
	}
}
