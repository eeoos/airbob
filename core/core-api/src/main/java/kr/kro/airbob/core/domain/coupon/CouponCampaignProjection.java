package kr.kro.airbob.core.domain.coupon;

import java.time.LocalDateTime;

import com.querydsl.core.annotations.QueryProjection;

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
	boolean hasRemainingStock
) {
	@QueryProjection
	public CouponCampaignProjection {
	}
}
