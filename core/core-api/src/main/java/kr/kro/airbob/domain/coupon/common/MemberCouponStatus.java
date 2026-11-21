package kr.kro.airbob.domain.coupon.common;

import java.time.LocalDateTime;

public enum MemberCouponStatus {
	UPCOMING,
	AVAILABLE,
	UNAVAILABLE,
	USED,
	EXPIRED;

	public static MemberCouponStatus resolve(boolean used, Boolean active, LocalDateTime usableFrom,
		LocalDateTime usableUntil, LocalDateTime now) {
		if (used) {
			return USED;
		}
		if (!now.isBefore(usableUntil)) {
			return EXPIRED;
		}
		if (!Boolean.TRUE.equals(active)) {
			return UNAVAILABLE;
		}
		if (now.isBefore(usableFrom)) {
			return UPCOMING;
		}
		return AVAILABLE;
	}
}
