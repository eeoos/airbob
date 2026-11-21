package kr.kro.airbob.domain.payment.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Accepted;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.service.PaymentOperationCommandService;
import kr.kro.airbob.domain.payment.service.PaymentQueryService;
import lombok.RequiredArgsConstructor;

@Tag(name = "결제")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class PaymentController {

	private final PaymentOperationCommandService paymentOperationCommandService;
	private final PaymentQueryService paymentQueryService;

	@Operation(operationId = "Payment.confirmPayment", summary = "결제 확인 요청",
		description = """
			예약 게스트가 결제 시도 발급 응답의 payment_attempt_id와 결제창에서 받은 payment_key·order_id·amount를 전달합니다.
			order_id는 예약 UID이며 서버가 반환한 문자열과 금액을 그대로 사용합니다. 0원 예약에는 호출하지 않습니다.

			최초 요청은 보유 기한이 남은 PAYMENT_PENDING 예약만 접수합니다. 202는 처리 접수이며 결제 완료를 보장하지 않습니다.
			응답의 상대 경로 status_url을 같은 API 서버에서 조회해 결과를 확인합니다.

			같은 예약·시도 ID·결제 키·금액의 재요청은 기존 작업의 현재 상태를 반환하며, 이미 사용한 시도나 보유 기한이 지난 후에도 재조회할 수 있습니다.
			다른 결제 키는 409/P006, 예약과 다른 금액·주문 문자열은 400/C001, 발급된 시도 ID 불일치는 409/R024입니다.
			기존 작업이 없는 상태에서 보유 기한이 지났거나 결제 대기 상태가 아니면 409/R011입니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "요청 접수", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.RESERVATION_NOT_FOUND,
		ErrorCode.PAYMENT_ACCESS_DENIED,
		ErrorCode.RESERVATION_PAYMENT_ATTEMPT_INVALID,
		ErrorCode.CANNOT_CONFIRM_EXPIRED_RESERVATION,
		ErrorCode.PAYMENT_OPERATION_CONFLICT
	})
	@PostMapping("/v1/payments/confirm")
	public ResponseEntity<ApiResponse<Accepted>> confirmPayment(
		@Valid @RequestBody PaymentRequest.Confirm request,
		@CurrentMemberId Long memberId
	) {
		return ResponseEntity.accepted()
			.body(ApiResponse.success(paymentOperationCommandService.requestConfirmation(request, memberId)));
	}

	@Operation(operationId = "Payment.getPaymentByPaymentKey", summary = "결제 키로 결제 조회",
		description = """
			예약 게스트 본인의 저장된 결제와 성공한 취소 이력을 결제 키로 조회합니다. 호스트·관리자 역할만으로 다른 게스트의 결제를 조회할 수 없습니다.
			결제 승인 반영 전 또는 0원 예약처럼 결제 기록이 없으면 404/P001, 다른 게스트의 결제이면 403/P004입니다.
			처리 중인 승인·취소의 진행 상황은 payment-operations의 상태 조회 API를 사용합니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.PAYMENT_NOT_FOUND,
		ErrorCode.PAYMENT_ACCESS_DENIED
	})
	@GetMapping("/v1/payments/{paymentKey}")
	public ResponseEntity<ApiResponse<PaymentResponse.PaymentInfo>> getPaymentByPaymentKey(
		@Parameter(description = "결제창에서 받은 결제 키", example = "example-payment-key")
		@PathVariable String paymentKey,
		@CurrentMemberId Long memberId) {
		PaymentResponse.PaymentInfo response = paymentQueryService.findPaymentByPaymentKey(paymentKey, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
	@Operation(operationId = "Payment.getPaymentByOrderId", summary = "주문 ID로 결제 조회",
		description = """
			예약 게스트 본인의 저장된 결제와 성공한 취소 이력을 order_id로 조회합니다. 현재 주문 ID는 예약 UID이며 시도 ID·작업 ID와 다릅니다.
			취소 이력은 원장 생성 시각 오름차순이고 이력이 없으면 빈 배열입니다. 호스트·관리자 역할만으로 다른 게스트의 결제를 조회할 수 없습니다.
			결제 기록이 없거나 주문 문자열이 일치하지 않으면 404/P001, 다른 게스트의 결제이면 403/P004입니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.PAYMENT_NOT_FOUND,
		ErrorCode.PAYMENT_ACCESS_DENIED
	})
	@GetMapping("/v1/payments/orders/{orderId}")
	public ResponseEntity<ApiResponse<PaymentResponse.PaymentInfo>> getPaymentByOrderId(
		@Parameter(description = "결제 시도 발급 응답의 order_id(예약 UID)", example = "00000000-0000-0000-0000-000000000020")
		@PathVariable String orderId,
		@CurrentMemberId Long memberId) {
		PaymentResponse.PaymentInfo response = paymentQueryService.findPaymentByOrderId(orderId, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
