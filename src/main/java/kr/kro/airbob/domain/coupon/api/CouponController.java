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
import kr.kro.airbob.domain.coupon.service.CouponLockIssueService;
import kr.kro.airbob.domain.coupon.service.CouponLuaIssueService;
import kr.kro.airbob.domain.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 사용자 API. 발급 가능한 쿠폰 조회 및 선착순 발급.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/coupons")
public class CouponController {

	private final CouponService couponService;
	private final CouponLockIssueService lockIssueService;
	private final CouponLuaIssueService luaIssueService;

	@GetMapping
	public ResponseEntity<ApiResponse<CouponResponse.CouponInfos>> findValidCoupons() {
		CouponResponse.CouponInfos coupons = couponService.findValidCoupons();
		return ResponseEntity.ok(ApiResponse.success(coupons));
	}

	@PostMapping("/{couponId}/issue/lock")
	public ResponseEntity<ApiResponse<Void>> issueCouponWithLock(@PathVariable Long couponId) {
		Long memberId = UserContext.get().id();
		lockIssueService.issue(couponId, memberId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
	}

	@PostMapping("/{couponId}/issue/lua")
	public ResponseEntity<ApiResponse<Void>> issueCouponWithLua(@PathVariable Long couponId) {
		Long memberId = UserContext.get().id();
		luaIssueService.issue(couponId, memberId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
	}
}
