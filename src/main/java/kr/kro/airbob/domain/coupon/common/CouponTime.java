package kr.kro.airbob.domain.coupon.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import kr.kro.airbob.domain.coupon.exception.CouponInvalidConfigurationException;

/** Bridges explicit API instants and UTC DATETIME(6) values without the host time zone. */
public final class CouponTime {

	private static final Instant MIN_DATABASE_INSTANT = Instant.parse("1000-01-01T00:00:00Z");
	private static final Instant MAX_DATABASE_INSTANT_EXCLUSIVE = Instant.parse("+10000-01-01T00:00:00Z");

	private CouponTime() { }

	public static LocalDateTime toUtc(Instant value) {
		if (value == null) return null;
		if (value.isBefore(MIN_DATABASE_INSTANT) || !value.isBefore(MAX_DATABASE_INSTANT_EXCLUSIVE)) {
			throw new CouponInvalidConfigurationException();
		}
		// Match DATETIME(6) rounding before comparing periods or an already stored policy.
		Instant rounded = value.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
		if (!rounded.isBefore(MAX_DATABASE_INSTANT_EXCLUSIVE)) {
			throw new CouponInvalidConfigurationException();
		}
		return LocalDateTime.ofInstant(rounded, ZoneOffset.UTC);
	}

	public static Instant toInstant(LocalDateTime utc) {
		return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
	}
}
