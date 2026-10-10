package kr.kro.airbob.domain.coupon.dto;

import java.time.Instant;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import kr.kro.airbob.domain.coupon.common.DiscountType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CouponRequest {
	public record Quantity(@NotNull @Positive Integer totalQuantity) { }

	public record Create(
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

	public record Update(
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
}
