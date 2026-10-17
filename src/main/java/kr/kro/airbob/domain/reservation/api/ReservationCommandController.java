package kr.kro.airbob.domain.reservation.api;

import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Cancellation;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.service.ReservationHoldCommandService;
import kr.kro.airbob.domain.reservation.service.ReservationQuoteService;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;

@Tag(name = "예약")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ReservationCommandController {

	private final ReservationQuoteService quoteService;
	private final ReservationService reservationService;
	private final ReservationHoldCommandService holdCommandService;

	@Operation(operationId = "Reservation.createQuote", summary = "예약 견적 생성",
		description = """
			로그인한 회원의 가격·쿠폰·숙박 조건을 견적으로 저장합니다. 숙소 현지 오늘부터 3개월 범위에서 체크아웃일이 체크인일보다 뒤여야 하며, 당일 예약은 숙소 체크인 시각 전까지만 가능합니다. 예시 날짜는 실행 시 예약 가능한 날짜로 바꿉니다.

			현재 재고를 조회하지만 잠그거나 확보하지 않으며 쿠폰도 사용 처리하지 않습니다. coupon_id는 보유 쿠폰의 캠페인 ID입니다. 성공 응답의 quote_uid를 체크아웃에 사용합니다.

			견적 자체의 시간 만료는 없지만 데이터 보관 정책에 따라 삭제될 수 있습니다. 체크아웃에서 가격·할인·숙소·재고를 다시 검사합니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 완료", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.MEMBER_NOT_FOUND,
		ErrorCode.ACCOMMODATION_NOT_FOUND,
		ErrorCode.INVALID_RESERVATION_DATE,
		ErrorCode.RESERVATION_OUTSIDE_BOOKING_WINDOW,
		ErrorCode.RESERVATION_LOCAL_TIME_INVALID,
		ErrorCode.RESERVATION_OCCUPANCY_EXCEEDED,
		ErrorCode.RESERVATION_CHECK_IN_CLOSED,
		ErrorCode.RESERVATION_CONFLICT,
		ErrorCode.RESERVATION_INVENTORY_NOT_READY,
		ErrorCode.MEMBER_COUPON_NOT_FOUND,
		ErrorCode.COUPON_ALREADY_USED,
		ErrorCode.COUPON_NOT_APPLICABLE
	})
	@PostMapping("/v1/reservation-quotes")
	public ResponseEntity<ApiResponse<ReservationResponse.Quote>> createQuote(
		@Valid @RequestBody ReservationRequest.Quote request,
		@CurrentMemberId Long memberId
	) {
		ReservationResponse.Quote response = quoteService.createQuote(request, memberId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	@Operation(operationId = "Reservation.checkout", summary = "견적으로 예약 생성",
		description = """
			quote_uid와 Idempotency-Key로 예약을 생성합니다. 숙소 ID·날짜·인원·쿠폰은 견적의 값을 사용하며 새 요청에서 다시 받지 않습니다. 같은 회원·키·요청으로 재시도하면 기존 예약의 현재 상태를 반환합니다. 새 예약이나 새 보유 시간을 만들지 않습니다.

			request_message는 최대 255자이며 생략과 null은 동일합니다. 빈 문자열과 공백은 그대로 보존되어 멱등성 비교에 포함됩니다. 같은 키로 요청 내용을 바꾸면 409/R016, 이미 사용한 견적을 새 키로 요청하면 409/R020입니다.

			유료 예약은 PAYMENT_PENDING으로 생성하며 기본 15분간 임시 확보합니다. 실제 기한은 hold_expires_at을 따릅니다. 0원 예약은 CONFIRMED로 확정되고 payment_required=false이므로 결제 단계를 건너뜁니다.

			가격·할인이 바뀌면 409/R019로 거절하며 새 견적이 필요합니다. 그 밖의 숙소·쿠폰·기간·재고 오류는 각 업무 코드로 반환합니다. 503/R025이면 Retry-After: 1 이후 같은 키와 요청으로 재시도합니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "재고 잠금 경합 또는 날짜 재고 준비 중",
		content = @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(ref = "#/components/schemas/ApiError")),
		headers = @Header(name = "Retry-After", description = "R025일 때만 반환하는 재시도 대기 초. 현재 1입니다.", schema = @Schema(type = "integer", example = "1")))
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.MEMBER_NOT_FOUND,
		ErrorCode.ACCOMMODATION_NOT_FOUND,
		ErrorCode.RESERVATION_QUOTE_NOT_FOUND,
		ErrorCode.RESERVATION_QUOTE_STALE,
		ErrorCode.RESERVATION_QUOTE_ALREADY_CHECKED_OUT,
		ErrorCode.RESERVATION_CHECKOUT_IDEMPOTENCY_CONFLICT,
		ErrorCode.INVALID_RESERVATION_DATE,
		ErrorCode.RESERVATION_OUTSIDE_BOOKING_WINDOW,
		ErrorCode.RESERVATION_LOCAL_TIME_INVALID,
		ErrorCode.RESERVATION_OCCUPANCY_EXCEEDED,
		ErrorCode.RESERVATION_CHECK_IN_CLOSED,
		ErrorCode.RESERVATION_CONFLICT,
		ErrorCode.RESERVATION_INVENTORY_BUSY,
		ErrorCode.RESERVATION_INVENTORY_NOT_READY,
		ErrorCode.MEMBER_COUPON_NOT_FOUND,
		ErrorCode.COUPON_ALREADY_USED,
		ErrorCode.COUPON_NOT_APPLICABLE
	})
	@PostMapping("/v1/reservations")
	public ResponseEntity<ApiResponse<ReservationResponse.Ready>> checkout(
		@Valid @RequestBody ReservationRequest.Checkout request,
		@Parameter(description = "회원별 요청 식별자. 동일 요청 재시도에는 같은 키를 사용합니다.",
			schema = @Schema(minLength = 8, maxLength = 128, pattern = "^[A-Za-z0-9._:-]+$", example = "checkout-00000001"))
		@RequestHeader(name = "Idempotency-Key") String idempotencyKey,
		@CurrentMemberId Long memberId
	) {
		ReservationResponse.Ready response = reservationService.createPendingReservation(
			request, memberId, idempotencyKey);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Operation(operationId = "Reservation.releaseHold", summary = "예약 보유 해제",
		description = """
			본인 예약의 PAYMENT_PENDING 상태를 EXPIRED로 바꾸고 남아 있는 임시 확보와 사용 쿠폰을 해제합니다. 결제 시도 ID를 발급받았어도 결제 확인 처리 전이라면 해제할 수 있습니다. 요청 본문은 없습니다.

			저장된 상태가 이미 EXPIRED이면 200과 released_now=false를 반환합니다. 만료 시각이 지났더라도 아직 PAYMENT_PENDING이면 이번 요청이 상태를 바꾸므로 released_now=true입니다. 결제 처리 중·확정·취소 상태에서는 409/R021입니다.

			해석할 수 없는 예약 UID, 없는 예약, 다른 회원의 예약은 모두 404/R001입니다. 응답은 Cache-Control: no-store이며 결제된 예약의 취소에는 취소 API를 사용합니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true,
		headers = @Header(name = "Cache-Control", description = "no-store", schema = @Schema(type = "string")))
	@ApiErrors({
		ErrorCode.RESERVATION_NOT_FOUND,
		ErrorCode.RESERVATION_HOLD_RELEASE_NOT_ALLOWED
	})
	@DeleteMapping("/v1/reservations/{reservationUid}/hold")
	public ResponseEntity<ApiResponse<ReservationResponse.HoldRelease>> releaseHold(
		@Parameter(description = "예약 생성 응답의 reservation_uid", example = "00000000-0000-0000-0000-000000000020")
		@PathVariable String reservationUid,
		@CurrentMemberId Long memberId
	) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(holdCommandService.releaseHold(reservationUid, memberId)));
	}

	@Operation(operationId = "Reservation.beginPaymentAttempt", summary = "결제 시도 토큰 발급",
		description = """
			본인의 유료 PAYMENT_PENDING 예약에서 결제창 진입에 필요한 payment_attempt_id를 발급합니다. 요청 본문은 없으며 order_id는 reservation_uid와 같습니다. 응답의 주문·금액·통화를 사용하고 결제 확인 요청에 시도 ID를 그대로 전달합니다.

			최초 발급에는 기본 90초 이상의 보유 시간이 남아 있어야 합니다. 이미 발급한 미사용 시도는 보유 만료 전까지 같은 ID로 반환하며, 시도를 재발급하거나 보유 시간을 연장하지 않습니다. 시간이 부족하거나 만료되면 409/R022, 0원 예약·사용한 시도·허용되지 않는 예약 상태는 409/R023입니다.

			해석할 수 없는 예약 UID, 없는 예약, 다른 회원의 예약은 모두 404/R001입니다. 응답은 Cache-Control: no-store입니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true,
		headers = @Header(name = "Cache-Control", description = "no-store", schema = @Schema(type = "string")))
	@ApiErrors({
		ErrorCode.RESERVATION_NOT_FOUND,
		ErrorCode.RESERVATION_PAYMENT_ATTEMPT_TOO_LATE,
		ErrorCode.RESERVATION_PAYMENT_ATTEMPT_NOT_ALLOWED
	})
	@PostMapping("/v1/reservations/{reservationUid}/payment-attempts")
	public ResponseEntity<ApiResponse<ReservationResponse.PaymentAttemptReady>> beginPaymentAttempt(
		@Parameter(description = "예약 생성 응답의 reservation_uid", example = "00000000-0000-0000-0000-000000000020")
		@PathVariable String reservationUid,
		@CurrentMemberId Long memberId
	) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(holdCommandService.beginPaymentAttempt(reservationUid, memberId)));
	}

	@Operation(operationId = "Reservation.cancelReservation", summary = "예약 취소 요청",
		description = """
			본인 예약을 취소합니다. cancel_reason은 공백만 있는 값을 제외한 최대 200자로 필수입니다. 유료 예약은 전액 취소만 지원하며 cancel_amount는 생략·null 또는 현재 결제 잔액 전액입니다. 0원 예약은 금액을 생략하거나 null로 보냅니다.

			새 취소는 체크인 시각 전까지만 가능합니다. 유료 예약은 CONFIRMED 또는 CANCELLATION_FAILED에서 취소를 접수합니다. 이미 취소 중이거나 취소된 예약은 동일 사유와 금액으로 요청하면 기존 작업을 반환하며, 내용이 다르면 409/P006입니다. 0원 예약은 즉시 취소되고 반복 요청도 성공합니다.

			응답은 모두 202와 {"success":true}입니다. 유료 취소는 Location 헤더의 작업 조회 URL에서 결과를 확인하고, 0원 취소는 Location 없이 완료되므로 예약 상세를 확인합니다.

			다른 회원의 예약은 403/P004, 없는 예약은 404/R001, 해석할 수 없는 예약 UID는 400/C001입니다. 결제 전 보유 해제는 DELETE /reservations/{reservationUid}/hold를 사용합니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "요청 접수", useReturnTypeSchema = true,
		headers = @Header(name = "Location", description = "비동기 취소 작업이 있을 때 상태 조회 URL", schema = @Schema(type = "string")))
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.RESERVATION_NOT_FOUND,
		ErrorCode.PAYMENT_ACCESS_DENIED,
		ErrorCode.PAYMENT_NOT_FOUND,
		ErrorCode.CANNOT_CANCEL_RESERVATION,
		ErrorCode.RESERVATION_CANCELLATION_DEADLINE_PASSED,
		ErrorCode.PAYMENT_OPERATION_CONFLICT
	})
	@PostMapping("/v1/reservations/{reservationUid}")
	public ResponseEntity<ApiResponse<Void>> cancelReservation(
		@Parameter(description = "예약 생성 응답의 reservation_uid", example = "00000000-0000-0000-0000-000000000020")
		@PathVariable String reservationUid,
		@Valid @RequestBody PaymentRequest.Cancel request,
		@CurrentMemberId Long memberId) {
		Cancellation response = reservationService.cancelReservation(reservationUid, request, memberId);
		ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.accepted();
		if (response.statusUrl() != null) {
			responseBuilder.location(URI.create(response.statusUrl()));
		}
		return responseBuilder.body(ApiResponse.success());
	}
}
