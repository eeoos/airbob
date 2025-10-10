package kr.kro.airbob.domain.discountPolicy.dto;

import java.time.LocalDateTime;
import java.util.List;

import kr.kro.airbob.domain.discountPolicy.common.PromotionType;
import kr.kro.airbob.domain.discountPolicy.entity.DiscountPolicy;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class DiscountPolicyResponse {

	public record DiscountPolicyInfo(
		String name,
		Double discountRate,
		PromotionType promotionType,
		Integer minPaymentPrice,
		Integer maxApplyPrice,
		LocalDateTime startDate,
		LocalDateTime endDate
	) {
		public static DiscountPolicyInfo of(DiscountPolicy discountPolicy) {
			return new DiscountPolicyInfo(
				discountPolicy.getName(),
				discountPolicy.getDiscountRate(),
				discountPolicy.getPromotionType(),
				discountPolicy.getMinPaymentPrice(),
				discountPolicy.getMaxApplyPrice(),
				discountPolicy.getStartDate(),
				discountPolicy.getEndDate());
		}
	}

	public record DiscountPolicyInfos(
		List<DiscountPolicyInfo> infos
		) {

	}
}
