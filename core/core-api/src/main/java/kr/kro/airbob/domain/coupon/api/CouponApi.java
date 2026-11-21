package kr.kro.airbob.domain.coupon.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;

/**
 * 쿠폰 사용자 API. 캠페인·보유 쿠폰 조회 및 선착순 발급.
 */
@Tag(name = "쿠폰")
@RequestMapping("/api")
public interface CouponApi {

	@Operation(operationId = "Coupon.findCouponCampaigns", summary = "쿠폰 캠페인 조회",
		description = """
			활성 상태이고 재고 준비를 완료했으며 발급 종료 시각 전인 캠페인만 조회합니다. 발급 시작 전·품절·이미 본인이 발급받은 캠페인도 포함됩니다.
			발급 시작 시각·캠페인 ID 내림차순이며 cursor와 size(기본 20, 1~50)로 다음 페이지를 조회합니다.

			issuance_status는 시작 전 UPCOMING이 우선이고, 시작 후 유한 수량의 잔여 재고가 없으면 SOLD_OUT, 그 외 OPEN입니다.
			과거 total_quantity=null은 발급 시작 후 OPEN으로 읽지만 새 무제한 쿠폰을 만들거나 발급할 수 있다는 의미는 아닙니다.
			전체 수량은 잔여 수량이 아닙니다. 발급 시 조건을 다시 확인하므로 목록의 OPEN이 발급 성공을 보장하지 않습니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@GetMapping("/v1/coupons")
	ResponseEntity<ApiResponse<CouponResponse.CouponInfos>> findCouponCampaigns(
		@CursorParam CursorRequest.CouponCampaignCursorPageRequest request
	);

	@Operation(operationId = "Coupon.findMyCoupons", summary = "내 쿠폰 조회",
		description = """
			현재 회원이 발급받은 쿠폰을 발급 내역 생성 시각·보유 내역 ID 내림차순으로 조회합니다. 사용 완료·만료·비활성 쿠폰도 포함됩니다.
			cursor와 size(기본 20, 1~50)를 사용하며 캠페인 목록의 커서와 바꾸어 쓰지 않습니다. 응답의 coupon_id는 보유 내역 ID가 아닌 캠페인 ID로, 예약 견적에 사용합니다.

			사용 상태의 우선순위는 USED, EXPIRED, UNAVAILABLE, UPCOMING, AVAILABLE입니다.
			발급 기간 종료나 캠페인 품절은 이미 보유한 쿠폰의 사용을 막지 않습니다. AVAILABLE도 예약 금액·최소 금액 등 적용 조건을 보장하지 않으므로 견적에서 다시 검증합니다.
			체크아웃에 적용한 쿠폰은 결제 완료 전부터 USED이며, 예약 해제·취소로 복원된 뒤에는 현재 사용 기간과 활성 상태에 따라 다시 표시됩니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@GetMapping("/v1/members/me/coupons")
	ResponseEntity<ApiResponse<CouponResponse.MemberCouponInfos>> findMyCoupons(
		@CurrentMemberId Long memberId,
		@CursorParam CursorRequest.CursorPageRequest request
	);

	@Operation(operationId = "Coupon.issueCoupon", summary = "쿠폰 발급",
		description = """
			현재 회원에게 캠페인 쿠폰을 한 번 발급합니다. 본문·회원 ID 입력은 필요하지 않습니다. 성공은 201과 {"success":true}이며 보유 쿠폰 목록에서 결과를 확인합니다.
			활성·양수 유한 수량·발급 기간(시작 포함, 종료 제외)·재고 준비 여부를 다시 확인합니다. 조건 미충족은 409/CP005, 발급 기간 안의 미준비 재고는 503/CP011입니다.

			이미 발급받았다면 409/CP003이며 쿠폰을 사용하거나 복원해도 다시 발급받을 수 없습니다. 기간 종료 등으로 발급 조건을 벗어났다면 중복 확인보다 CP005가 먼저 반환될 수 있습니다.
			409/CP002는 품절, 503/CP012는 재시도 가능한 경합입니다. CP012에는 Retry-After 헤더가 없으므로 지연을 두고 제한적으로 재시도합니다.
			응답 유실 후 재요청에서 CP003을 받으면 보유 목록을 조회해 첫 요청의 성공 여부를 확인합니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 완료", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.COUPON_NOT_FOUND,
		ErrorCode.COUPON_SOLD_OUT,
		ErrorCode.COUPON_ALREADY_ISSUED,
		ErrorCode.COUPON_NOT_ISSUABLE,
		ErrorCode.COUPON_STOCK_NOT_PREPARED,
		ErrorCode.COUPON_ISSUE_CONTENDED,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@PostMapping("/v1/coupons/{couponId}/issue")
	ResponseEntity<ApiResponse<Void>> issueCoupon(
		@Parameter(description = "캠페인 목록의 id", example = "101")
		@PathVariable Long couponId,
		@CurrentMemberId Long memberId);
}
