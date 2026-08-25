package kr.kro.airbob.domain.reservation.policy;

import java.time.Duration;
import java.time.Instant;

public class ReservationQuotePolicy {

	private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

	private final Duration ttl;

	public ReservationQuotePolicy(Duration ttl) {
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			throw new IllegalArgumentException("견적 유효기간은 0보다 커야 합니다.");
		}
		this.ttl = ttl;
	}

	public static ReservationQuotePolicy defaultPolicy() {
		return new ReservationQuotePolicy(DEFAULT_TTL);
	}

	public Instant expiresAtFrom(Instant quotedAt) {
		return quotedAt.plus(ttl);
	}

	public Duration ttl() {
		return ttl;
	}

}
