package kr.kro.airbob.domain.coupon.repository.projection;

import java.time.LocalDateTime;

import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.domain.coupon.common.CouponTime;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;

/** Candidate read model for the guarded campaign-query experiment. */
public record CouponCampaignAvailabilityProjection(
	Long id, String name, String description, DiscountType discountType, Integer discountValue,
	Integer minPaymentPrice, Integer maxDiscountAmount, LocalDateTime issueStartAt,
	LocalDateTime issueEndAt, LocalDateTime usableFrom, LocalDateTime usableUntil,
	Integer totalQuantity, boolean hasRemaining
) {
	public CouponResponse.CouponInfo toResponse(LocalDateTime now) {
		CouponIssuanceStatus status = now.isBefore(issueStartAt) ? CouponIssuanceStatus.UPCOMING
			: hasRemaining ? CouponIssuanceStatus.OPEN : CouponIssuanceStatus.SOLD_OUT;
		return new CouponResponse.CouponInfo(id, name, description, discountType, discountValue,
			minPaymentPrice, maxDiscountAmount, CouponTime.toInstant(issueStartAt), CouponTime.toInstant(issueEndAt),
			CouponTime.toInstant(usableFrom), CouponTime.toInstant(usableUntil), totalQuantity, status);
	}
}
