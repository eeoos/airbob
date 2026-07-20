package kr.kro.airbob.domain.coupon.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.service.CouponLuaIssueService;
import kr.kro.airbob.domain.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 사용자 API. 발급 가능한 쿠폰 조회 및 선착순 발급.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class CouponController {

	private final CouponService couponService;
	private final CouponLuaIssueService luaIssueService;

	@GetMapping("/v1/coupons")
	public ResponseEntity<ApiResponse<CouponResponse.CouponInfos>> findValidCoupons() {
		CouponResponse.CouponInfos coupons = couponService.findValidCoupons();
		return ResponseEntity.ok(ApiResponse.success(coupons));
	}

	@PostMapping("/v1/coupons/{couponId}/issue")
	public ResponseEntity<ApiResponse<Void>> issueCoupon(@PathVariable Long couponId) {
		Long memberId = UserContext.get().id();
		luaIssueService.issue(couponId, memberId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
	}
}
