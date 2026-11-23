package kr.kro.airbob.domain.coupon;

import java.time.Instant;

import kr.kro.airbob.domain.coupon.common.DiscountType;

/** 쿠폰 캠페인 부분 수정. null 필드는 유지한다. 시각은 엔티티가 UTC로 바꿔 저장한다. */
public record CouponPatch(
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
	Integer totalQuantity
) {
}
