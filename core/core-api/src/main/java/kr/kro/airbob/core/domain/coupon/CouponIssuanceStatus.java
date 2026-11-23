package kr.kro.airbob.core.domain.coupon;

import java.time.LocalDateTime;

public enum CouponIssuanceStatus {
	UPCOMING,
	OPEN,
	SOLD_OUT;

	public static CouponIssuanceStatus resolve(LocalDateTime issueStartAt, Integer totalQuantity,
		boolean hasRemainingStock, LocalDateTime now) {
		if (now.isBefore(issueStartAt)) {
			return UPCOMING;
		}
		if (totalQuantity != null && !hasRemainingStock) {
			return SOLD_OUT;
		}
		return OPEN;
	}
}
