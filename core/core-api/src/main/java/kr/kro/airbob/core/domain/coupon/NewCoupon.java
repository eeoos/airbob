package kr.kro.airbob.core.domain.coupon;

import java.time.Instant;

/** 새 쿠폰 캠페인. 시각은 API가 받은 Instant이고 엔티티가 UTC로 바꿔 저장한다. */
public record NewCoupon(
	String name,
	String description,
	DiscountType discountType,
	Integer discountValue,
	Integer minPaymentPrice,
	Integer maxDiscountAmount,
	Instant issueStartAt,
	Instant issueEndAt,
	Instant usableFrom,
	Instant usableUntil,
	Boolean isActive,
	Integer totalQuantity) {
}
