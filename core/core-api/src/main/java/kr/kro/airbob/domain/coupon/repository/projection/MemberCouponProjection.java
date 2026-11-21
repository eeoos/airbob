package kr.kro.airbob.domain.coupon.repository.projection;

import java.time.LocalDateTime;

import com.querydsl.core.annotations.QueryProjection;

import kr.kro.airbob.domain.coupon.common.DiscountType;

public record MemberCouponProjection(
	Long memberCouponId,
	LocalDateTime createdAt,
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
	@QueryProjection
	public MemberCouponProjection {
	}
}
