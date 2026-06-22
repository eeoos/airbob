package kr.kro.airbob.domain.coupon.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 사용자 API. 발급 가능한 쿠폰 조회 및 발급(추후 추가).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/coupons")
public class CouponController {

	private final CouponService couponService;

	@GetMapping
	public ResponseEntity<ApiResponse<CouponResponse.CouponInfos>> findValidCoupons() {
		CouponResponse.CouponInfos coupons = couponService.findValidCoupons();
		return ResponseEntity.ok(ApiResponse.success(coupons));
	}
}
