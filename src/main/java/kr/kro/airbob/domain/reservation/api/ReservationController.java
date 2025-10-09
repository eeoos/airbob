package kr.kro.airbob.domain.reservation.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/reservations")
public class ReservationController {

	private final ReservationService reservationService;

	@PostMapping
	public ResponseEntity<ReservationResponse.Ready> createReservation(
		HttpServletRequest request,
		@Valid @RequestBody ReservationRequest.Create requestDto) {

		Long memberId = (Long) request.getAttribute("memberId");

		ReservationResponse.Ready response = reservationService.createPendingReservation(memberId, requestDto);
		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/{reservationUid}")
	public ResponseEntity<Void> cancelReservation(
		@PathVariable String reservationUid,
		@Valid @RequestBody PaymentRequest.Cancel request
	) {
		reservationService.cancelReservation(reservationUid, request);
		return ResponseEntity.accepted().build();
	}
}
