package kr.kro.airbob.domain.coupon.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.coupon.dto.CouponRequest;
import kr.kro.airbob.domain.coupon.service.CouponService;
import kr.kro.airbob.domain.coupon.service.CouponStockPreparationService;
import lombok.RequiredArgsConstructor;

/**
 * 쿠폰 관리 API (ADMIN 전용 — AdminAuthInterceptor 가 /api/v1/admin/** 보호).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class CouponAdminController {

	private final CouponService couponService;
	private final CouponStockPreparationService preparationService;

	@PostMapping("/v1/admin/coupons")
	public ResponseEntity<ApiResponse<Void>> createCoupon(@RequestBody CouponRequest.Create request) {
		couponService.createCoupon(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
	}

	@PatchMapping("/v1/admin/coupons/{couponId}")
	public ResponseEntity<ApiResponse<Void>> updateCoupon(
		@RequestBody CouponRequest.Update request, @PathVariable Long couponId) {
		couponService.updateCoupon(request, couponId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@DeleteMapping("/v1/admin/coupons/{couponId}")
	public ResponseEntity<ApiResponse<Void>> deleteCoupon(@PathVariable Long couponId) {
		couponService.deleteCoupon(couponId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@PostMapping("/v1/admin/coupons/{couponId}/stock/prepare")
	public ResponseEntity<ApiResponse<Void>> prepareStock(@PathVariable Long couponId) {
		preparationService.prepare(couponId);
		return ResponseEntity.ok(ApiResponse.success());
	}
}
