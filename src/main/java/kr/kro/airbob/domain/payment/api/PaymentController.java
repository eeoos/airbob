package kr.kro.airbob.domain.payment.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.service.PaymentService;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")
public class PaymentController {

	private final PaymentService paymentService;

	@PostMapping("/confirm")
	public ResponseEntity<Void> confirmPayment(@Valid @RequestBody PaymentRequest.Confirm request) {

		paymentService.requestPaymentConfirmation(request);
		return ResponseEntity.accepted().build();
	}

	@GetMapping("/{paymentKey}")
	public ResponseEntity<PaymentResponse.PaymentInfo> getPaymentByPaymentKey(@PathVariable String paymentKey) {
		PaymentResponse.PaymentInfo response = paymentService.findPaymentByPaymentKey(paymentKey);
		return ResponseEntity.ok(response);
	}
	@GetMapping("/orders/{orderId}")
	public ResponseEntity<PaymentResponse.PaymentInfo> getPaymentByOrderId(@PathVariable String orderId) {
		PaymentResponse.PaymentInfo response = paymentService.findPaymentByOrderId(orderId);
		return ResponseEntity.ok(response);
	}
}
