package kr.kro.airbob.domain.coupon.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.service.CouponQueryService;
import kr.kro.airbob.domain.coupon.service.CouponSkipLockedIssueService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CouponController implements CouponApi {

	private final CouponQueryService couponQueryService;
	private final CouponSkipLockedIssueService issueService;

	@Override
	public ResponseEntity<ApiResponse<CouponResponse.CouponInfos>> findCouponCampaigns(
		CursorRequest.CouponCampaignCursorPageRequest request
	) {
		CouponResponse.CouponInfos coupons = couponQueryService.findCouponCampaigns(request);
		return ResponseEntity.ok(ApiResponse.success(coupons));
	}

	@Override
	public ResponseEntity<ApiResponse<CouponResponse.MemberCouponInfos>> findMyCoupons(
		Long memberId,
		CursorRequest.CursorPageRequest request
	) {
		CouponResponse.MemberCouponInfos coupons = couponQueryService.findMyCoupons(memberId, request);
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
