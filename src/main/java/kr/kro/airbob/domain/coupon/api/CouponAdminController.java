package kr.kro.airbob.domain.coupon.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.coupon.dto.CouponRequest;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.service.CouponService;
import kr.kro.airbob.domain.coupon.service.CouponStockPreparationService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CouponAdminController implements CouponAdminApi {

	private final CouponService couponService;
	private final CouponStockPreparationService preparationService;

	@Override
	public ResponseEntity<ApiResponse<Void>> changeQuantity(Long couponId,
		CouponRequest.Quantity request) {
		couponService.changeQuantity(couponId, request.totalQuantity());
		return ResponseEntity.ok(ApiResponse.success());
	}

	@Override
	public ResponseEntity<ApiResponse<CouponResponse.Create>> createCoupon(CouponRequest.Create request) {
		Long id = couponService.createCoupon(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(new CouponResponse.Create(id)));
	}

	@Override
	public ResponseEntity<ApiResponse<Void>> updateCoupon(
		CouponRequest.Update request, Long couponId) {
		couponService.updateCoupon(request, couponId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@Override
	public ResponseEntity<ApiResponse<Void>> deleteCoupon(Long couponId) {
		couponService.deleteCoupon(couponId);
		return ResponseEntity.ok(ApiResponse.success());
	}

	@Override
	public ResponseEntity<ApiResponse<Void>> prepareStock(Long couponId) {
		preparationService.prepare(couponId);
		return ResponseEntity.ok(ApiResponse.success());
	}
}
