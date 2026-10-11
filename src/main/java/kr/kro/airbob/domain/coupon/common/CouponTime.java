package kr.kro.airbob.domain.coupon.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Bridges explicit API instants and UTC DATETIME(6) values without the host time zone. */
public final class CouponTime {

	private CouponTime() { }

	public static LocalDateTime toUtc(Instant value) {
		return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
	}

	public static Instant toInstant(LocalDateTime utc) {
		return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
	}
}
