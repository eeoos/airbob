package kr.kro.airbob.domain.payment.api;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments")
public class PaymentController {

	private final ApplicationEventPublisher eventPublisher;

	@PostMapping("/confirm")
	public ResponseEntity<Void> confirmPayment(@Valid @RequestBody PaymentRequest.Confirm request) {
		eventPublisher.publishEvent(
			new ReservationEvent.ReservationPendingEvent(
				request.amount().intValue(),
				request.paymentKey(),
				request.orderId()
			)
		);

		return ResponseEntity.accepted().build();
	}
}
