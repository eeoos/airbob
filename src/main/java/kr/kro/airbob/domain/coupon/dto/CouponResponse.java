package kr.kro.airbob.domain.coupon.dto;

import java.time.LocalDateTime;
import java.util.List;

import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CouponResponse {

	public record CouponInfo(
		Long id,
		String name,
		String description,
		DiscountType discountType,
		Integer discountValue,
		Integer minPaymentPrice,
		Integer maxDiscountAmount,
		LocalDateTime startDate,
		LocalDateTime endDate,
		Integer totalQuantity,
		Integer issuedQuantity
	) {
		public static CouponInfo of(Coupon coupon) {
			return new CouponInfo(
				coupon.getId(),
				coupon.getName(),
				coupon.getDescription(),
				coupon.getDiscountType(),
				coupon.getDiscountValue(),
				coupon.getMinPaymentPrice(),
				coupon.getMaxDiscountAmount(),
				coupon.getStartDate(),
				coupon.getEndDate(),
				coupon.getTotalQuantity(),
				coupon.getIssuedQuantity());
		}
	}

	public record CouponInfos(
		List<CouponInfo> infos
	) {

	}
}
