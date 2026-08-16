package kr.kro.airbob.domain.coupon.dto;

import java.time.LocalDateTime;
import java.util.List;

import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.common.MemberCouponStatus;
import kr.kro.airbob.domain.coupon.entity.Coupon;
import kr.kro.airbob.domain.coupon.entity.MemberCoupon;
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
		LocalDateTime issueStartAt,
		LocalDateTime issueEndAt,
		LocalDateTime usableFrom,
		LocalDateTime usableUntil,
		Integer totalQuantity,
		Integer issuedQuantity,
		CouponIssuanceStatus issuanceStatus
	) {
		public static CouponInfo of(Coupon coupon, LocalDateTime now) {
			return new CouponInfo(
				coupon.getId(),
				coupon.getName(),
				coupon.getDescription(),
				coupon.getDiscountType(),
				coupon.getDiscountValue(),
				coupon.getMinPaymentPrice(),
				coupon.getMaxDiscountAmount(),
				coupon.getIssueStartAt(),
				coupon.getIssueEndAt(),
				coupon.getUsableFrom(),
				coupon.getUsableUntil(),
				coupon.getTotalQuantity(),
				coupon.getIssuedQuantity(),
				coupon.issuanceStatus(now));
		}
	}

	public record CouponInfos(
		List<CouponInfo> infos
	) {

	}

	public record MemberCouponInfo(
		Long couponId,
		String name,
		String description,
		DiscountType discountType,
		Integer discountValue,
		Integer minPaymentPrice,
		Integer maxDiscountAmount,
		LocalDateTime usableFrom,
		LocalDateTime usableUntil,
		MemberCouponStatus status
	) {
		public static MemberCouponInfo of(MemberCoupon memberCoupon, LocalDateTime now) {
			Coupon coupon = memberCoupon.getCoupon();
			return new MemberCouponInfo(
				coupon.getId(),
				coupon.getName(),
				coupon.getDescription(),
				coupon.getDiscountType(),
				coupon.getDiscountValue(),
				coupon.getMinPaymentPrice(),
				coupon.getMaxDiscountAmount(),
				coupon.getUsableFrom(),
				coupon.getUsableUntil(),
				memberCoupon.status(now));
		}
	}

	public record MemberCouponInfos(
		List<MemberCouponInfo> infos
	) {

	}
}
