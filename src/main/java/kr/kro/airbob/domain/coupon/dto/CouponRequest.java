package kr.kro.airbob.domain.coupon.dto;

import java.time.LocalDateTime;

import kr.kro.airbob.domain.coupon.common.DiscountType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CouponRequest {

	public record Create(
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
		Boolean isActive,
		Integer totalQuantity
	) {

	}

	public record Update(
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
		Boolean isActive,
		Integer totalQuantity
	) {

	}
}
