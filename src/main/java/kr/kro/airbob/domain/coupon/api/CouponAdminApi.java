package kr.kro.airbob.domain.coupon.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.coupon.dto.CouponRequest;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;

/**
 * 쿠폰 관리 API (ADMIN 전용 — AdminAuthInterceptor 가 /api/v1/admin/** 보호).
 */
@Tag(name = "쿠폰 관리")
@RequestMapping("/api")
public interface CouponAdminApi {

	@Operation(operationId = "CouponAdmin.changeQuantity", summary = "쿠폰 수량 변경",
		description = """
			수량을 양의 정수인 목표값으로 변경합니다. 발급 시작 전이며 발급 내역이 없는 캠페인만 가능하고, 같은 수량을 다시 보내도 이 조건을 확인합니다.
			미준비 상태는 전체 수량만 변경하며, 준비된 상태는 발급용 재고도 목표 수량에 맞춰 추가·삭제합니다. 발급 시작 후의 증감·재입고는 허용하지 않습니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.COUPON_NOT_FOUND,
		ErrorCode.COUPON_INVALID_CONFIGURATION,
		ErrorCode.COUPON_CONFIGURATION_LOCKED,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@PatchMapping("/v1/admin/coupons/{couponId}/quantity")
	ResponseEntity<ApiResponse<Void>> changeQuantity(@Parameter(description = "생성 응답의 data.id 또는 캠페인 목록의 id", example = "101") @PathVariable Long couponId,
		@Valid @RequestBody CouponRequest.Quantity request);

	@Operation(operationId = "CouponAdmin.createCoupon", summary = "쿠폰 생성",
		description = """
			미래에 발급을 시작하는 캠페인을 생성하고 data.id를 반환합니다. 이 ID로 재고 준비·수정·삭제 API를 호출합니다.
			생성만으로 재고가 준비되지는 않으며, 활성 캠페인을 발급 시작 전에 stock/prepare로 준비해야 사용자 목록에 표시됩니다.

			이름은 공백만으로 지정할 수 없고 이름·설명은 최대 255자입니다. 전체 수량과 할인 값은 양수이며 정률 할인은 1~100입니다.
			발급 시작 < 발급 종료, 사용 시작 < 사용 종료, 사용 종료 >= 발급 종료를 만족해야 합니다.
			시각은 Z 또는 +09:00처럼 오프셋이 있는 ISO 8601 문자열입니다. UTC 기준 1000~9999년 범위이며 마이크로초로 반올림한 값으로 기간을 검증·저장합니다.
			형식 오류는 400/C001, 누락·길이·수량·할인·기간 등 설정 오류는 400/CP013입니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 완료", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.COUPON_INVALID_CONFIGURATION
	})
	@PostMapping("/v1/admin/coupons")
	ResponseEntity<ApiResponse<CouponResponse.Create>> createCoupon(@RequestBody CouponRequest.Create request);

	@Operation(operationId = "CouponAdmin.updateCoupon", summary = "쿠폰 설정 수정",
		description = """
			부분 수정이며 생략과 null은 모두 기존 값 유지입니다. null로 필드를 비울 수 없고 설명은 빈 문자열로 바꿀 수 있습니다.
			이름·설명·할인 조건·사용 기간은 발급 시작 후에도 수정할 수 있으며, 이미 발급한 쿠폰도 현재 캠페인의 조건을 읽습니다.

			발급 시작·종료 시각, 활성 여부, 전체 수량의 실제 값 변경은 발급 시작 전이며 발급 내역이 없을 때만 허용합니다. 위 조건을 벗어나면 409/CP014입니다.
			issue_start_at을 명시하면 같은 값이어도 현재보다 미래여야 하므로, 시작 후 일반 정보만 수정할 때는 생략합니다.
			병합한 최종 설정에 생성과 같은 할인·길이·기간 검증을 적용합니다. 준비된 쿠폰의 수량이 바뀌면 재고도 함께 조정합니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.COUPON_NOT_FOUND,
		ErrorCode.COUPON_INVALID_CONFIGURATION,
		ErrorCode.COUPON_CONFIGURATION_LOCKED,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@PatchMapping("/v1/admin/coupons/{couponId}")
	ResponseEntity<ApiResponse<Void>> updateCoupon(
		@RequestBody CouponRequest.Update request, @Parameter(description = "생성 응답의 data.id 또는 캠페인 목록의 id", example = "101") @PathVariable Long couponId);

	@Operation(operationId = "CouponAdmin.deleteCoupon", summary = "쿠폰 삭제",
		description = "발급 시작 전이며 발급 내역이 없는 캠페인을 삭제하고 준비된 미발급 재고도 정리합니다. 시작 이후·발급 내역 존재는 409/CP014입니다. 삭제 후 재요청은 404/CP001입니다. 본문 없이 호출합니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.COUPON_NOT_FOUND,
		ErrorCode.COUPON_CONFIGURATION_LOCKED,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@DeleteMapping("/v1/admin/coupons/{couponId}")
	ResponseEntity<ApiResponse<Void>> deleteCoupon(@Parameter(description = "생성 응답의 data.id 또는 캠페인 목록의 id", example = "101") @PathVariable Long couponId);

	@Operation(operationId = "CouponAdmin.prepareStock", summary = "쿠폰 재고 준비",
		description = """
			활성 상태이고 발급 시작 전이며 발급 내역이 없는 캠페인의 전체 수량만큼 재고를 준비합니다. 생성 응답의 data.id를 couponId로 사용하며 본문은 없습니다.
			이미 준비했으면 409/CP009이며 재호출로 소비된 재고를 채우지 않습니다. 미준비 상태에서 비활성·시작 시각 도달·발급 내역 존재는 409/CP010입니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.COUPON_NOT_FOUND,
		ErrorCode.COUPON_ALREADY_PREPARED,
		ErrorCode.COUPON_STOCK_PREPARATION_NOT_ALLOWED,
		ErrorCode.COUPON_INVALID_CONFIGURATION,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@PostMapping("/v1/admin/coupons/{couponId}/stock/prepare")
	ResponseEntity<ApiResponse<Void>> prepareStock(@Parameter(description = "생성 응답의 data.id 또는 캠페인 목록의 id", example = "101") @PathVariable Long couponId);
}
