package kr.kro.airbob.domain.payment.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.service.PaymentQueryService;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Accepted;
import kr.kro.airbob.domain.payment.service.PaymentOperationCommandService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class PaymentController {

	private final PaymentOperationCommandService paymentOperationCommandService;
	private final PaymentQueryService paymentQueryService;

	@PostMapping("/v1/payments/confirm")
	public ResponseEntity<ApiResponse<Accepted>> confirmPayment(
		@Valid @RequestBody PaymentRequest.Confirm request,
		@CurrentMemberId Long memberId
	) {
		return ResponseEntity.accepted()
			.body(ApiResponse.success(paymentOperationCommandService.requestConfirmation(request, memberId)));
	}

	@GetMapping("/v1/payments/{paymentKey}")
	public ResponseEntity<ApiResponse<PaymentResponse.PaymentInfo>> getPaymentByPaymentKey(
		@PathVariable String paymentKey,
		@CurrentMemberId Long memberId) {
		PaymentResponse.PaymentInfo response = paymentQueryService.findPaymentByPaymentKey(paymentKey, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
	@GetMapping("/v1/payments/orders/{orderId}")
	public ResponseEntity<ApiResponse<PaymentResponse.PaymentInfo>> getPaymentByOrderId(
		@PathVariable String orderId,
		@CurrentMemberId Long memberId) {
		PaymentResponse.PaymentInfo response = paymentQueryService.findPaymentByOrderId(orderId, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
