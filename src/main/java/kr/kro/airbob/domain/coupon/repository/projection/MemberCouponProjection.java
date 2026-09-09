package kr.kro.airbob.domain.coupon.repository.projection;

import java.time.LocalDateTime;

import kr.kro.airbob.domain.coupon.common.DiscountType;

public record MemberCouponProjection(
	Long couponId,
	String name,
	String description,
	DiscountType discountType,
	Integer discountValue,
	Integer minPaymentPrice,
	Integer maxDiscountAmount,
	LocalDateTime usableFrom,
	LocalDateTime usableUntil,
	boolean used,
	Boolean active
) {
}
