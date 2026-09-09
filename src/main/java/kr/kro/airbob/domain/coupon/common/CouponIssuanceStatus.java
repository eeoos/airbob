package kr.kro.airbob.domain.coupon.common;

import java.time.LocalDateTime;

public enum CouponIssuanceStatus {
	UPCOMING,
	OPEN,
	SOLD_OUT;

	public static CouponIssuanceStatus resolve(LocalDateTime issueStartAt, Integer totalQuantity,
		Integer issuedQuantity, LocalDateTime now) {
		if (now.isBefore(issueStartAt)) {
			return UPCOMING;
		}
		if (totalQuantity != null && issuedQuantity >= totalQuantity) {
			return SOLD_OUT;
		}
		return OPEN;
	}
}
