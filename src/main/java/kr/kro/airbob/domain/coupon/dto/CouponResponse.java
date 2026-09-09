package kr.kro.airbob.domain.coupon.dto;

import java.time.LocalDateTime;
import java.util.List;

import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.common.MemberCouponStatus;
import kr.kro.airbob.domain.coupon.repository.projection.CouponCampaignProjection;
import kr.kro.airbob.domain.coupon.repository.projection.MemberCouponProjection;
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
		public static CouponInfo of(CouponCampaignProjection coupon, LocalDateTime now) {
			return new CouponInfo(
				coupon.id(),
				coupon.name(),
				coupon.description(),
				coupon.discountType(),
				coupon.discountValue(),
				coupon.minPaymentPrice(),
				coupon.maxDiscountAmount(),
				coupon.issueStartAt(),
				coupon.issueEndAt(),
				coupon.usableFrom(),
				coupon.usableUntil(),
				coupon.totalQuantity(),
				coupon.issuedQuantity(),
				CouponIssuanceStatus.resolve(coupon.issueStartAt(), coupon.totalQuantity(), coupon.issuedQuantity(), now));
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
		public static MemberCouponInfo of(MemberCouponProjection coupon, LocalDateTime now) {
			return new MemberCouponInfo(
				coupon.couponId(),
				coupon.name(),
				coupon.description(),
				coupon.discountType(),
				coupon.discountValue(),
				coupon.minPaymentPrice(),
				coupon.maxDiscountAmount(),
				coupon.usableFrom(),
				coupon.usableUntil(),
				MemberCouponStatus.resolve(coupon.used(), coupon.active(), coupon.usableFrom(),
					coupon.usableUntil(), now));
		}
	}

	public record MemberCouponInfos(
		List<MemberCouponInfo> infos
	) {

	}
}
