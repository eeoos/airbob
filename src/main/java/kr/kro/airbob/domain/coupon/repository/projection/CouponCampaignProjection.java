package kr.kro.airbob.domain.coupon.repository.projection;

import java.time.LocalDateTime;

import kr.kro.airbob.domain.coupon.common.DiscountType;

public record CouponCampaignProjection(
	Long id,
	String name,
	String description,
	DiscountType discountType,
	Integer discountValue,
	Integer minPaymentPrice,
	Integer maxDiscountAmount,
	LocalDateTime issueStartAt,
	LocalDateTime issueEndAt,
	LocalDateTime usableFrom,
	LocalDateTime usableUntil,
	Integer totalQuantity,
	Integer issuedQuantity
) {
}
