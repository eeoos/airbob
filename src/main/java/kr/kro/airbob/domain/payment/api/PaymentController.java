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
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.service.PaymentService;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class PaymentController {

	private final OutboxEventPublisher outboxEventPublisher;
	private final PaymentService paymentService;

	@PostMapping("/v1/payments/confirm")
	public ResponseEntity<ApiResponse<Void>> confirmPayment(@Valid @RequestBody PaymentRequest.Confirm request) {
		outboxEventPublisher.save(
			EventType.PAYMENT_CONFIRM_REQUESTED,
			request
		);
		return ResponseEntity.accepted().body(ApiResponse.success());
	}

	@GetMapping("/v1/payments/{paymentKey}")
	public ResponseEntity<ApiResponse<PaymentResponse.PaymentInfo>> getPaymentByPaymentKey(@PathVariable String paymentKey) {
		PaymentResponse.PaymentInfo response = paymentService.findPaymentByPaymentKey(paymentKey);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
	@GetMapping("/v1/payments/orders/{orderId}")
	public ResponseEntity<ApiResponse<PaymentResponse.PaymentInfo>> getPaymentByOrderId(@PathVariable String orderId) {
		PaymentResponse.PaymentInfo response = paymentService.findPaymentByOrderId(orderId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
