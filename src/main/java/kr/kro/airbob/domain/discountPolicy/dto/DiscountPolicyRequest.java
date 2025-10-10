package kr.kro.airbob.domain.discountPolicy.dto;

import java.time.LocalDateTime;

import kr.kro.airbob.domain.discountPolicy.common.DiscountType;
import kr.kro.airbob.domain.discountPolicy.common.PromotionType;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DiscountPolicyRequest {

	public record Create(
		String name,
		Double discountRate,
		String description,
		DiscountType discountType,
		PromotionType promotionType,
		Integer minPaymentPrice,
		Integer maxApplyPrice,
		LocalDateTime startDate,
		LocalDateTime endDate,
		Boolean isActive
	) {

	}

	public record Update(
		String name,
		Double discountRate,
		String description,
		DiscountType discountType,
		PromotionType promotionType,
		Integer minPaymentPrice,
		Integer maxApplyPrice,
		LocalDateTime startDate,
		LocalDateTime endDate,
		Boolean isActive
	) {

	}
}
