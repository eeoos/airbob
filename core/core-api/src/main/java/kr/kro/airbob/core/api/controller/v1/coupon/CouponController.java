package kr.kro.airbob.core.api.controller.v1.coupon;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.core.api.controller.v1.coupon.response.CouponResponse;
import kr.kro.airbob.core.domain.coupon.CouponQueryService;
import kr.kro.airbob.core.domain.coupon.CouponSkipLockedIssueService;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CouponController implements CouponApi {

	private final CouponQueryService couponQueryService;
	private final CouponSkipLockedIssueService issueService;

	@Override
	public ResponseEntity<ApiResponse<CouponResponse.CouponInfos>> getCouponCampaigns(
		CursorRequest.CouponCampaignCursorPageRequest request) {
		CouponResponse.CouponInfos coupons = CouponResponse.CouponInfos
			.from(couponQueryService.getCouponCampaigns(request));
		return ResponseEntity.ok(ApiResponse.success(coupons));
	}

	@Override
	public ResponseEntity<ApiResponse<CouponResponse.MemberCouponInfos>> getMyCoupons(
		Long memberId,
		CursorRequest.CursorPageRequest request) {
		CouponResponse.MemberCouponInfos coupons = CouponResponse.MemberCouponInfos.from(
			couponQueryService.getMyCoupons(memberId, request));
		return ResponseEntity.ok(ApiResponse.success(coupons));
	}

	@Override
	public ResponseEntity<ApiResponse<Void>> issueCoupon(
		Long couponId,
		Long memberId) {
		issueService.issue(couponId, memberId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
	}
}
