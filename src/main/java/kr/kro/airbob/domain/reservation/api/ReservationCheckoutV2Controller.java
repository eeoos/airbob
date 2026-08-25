package kr.kro.airbob.domain.reservation.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.service.ReservationQuoteService;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2")
public class ReservationCheckoutV2Controller {

	private final ReservationQuoteService quoteService;
	private final ReservationService reservationService;

	@PostMapping("/reservation-quotes")
	public ResponseEntity<ApiResponse<ReservationResponse.Quote>> createQuote(
		@Valid @RequestBody ReservationRequest.Quote request,
		@CurrentMemberId Long memberId
	) {
		ReservationResponse.Quote response = quoteService.createQuote(request, memberId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	@PostMapping("/reservations")
	public ResponseEntity<ApiResponse<ReservationResponse.Ready>> checkout(
		@Valid @RequestBody ReservationRequest.Checkout request,
		@RequestHeader(name = "Idempotency-Key") String idempotencyKey,
		@CurrentMemberId Long memberId
	) {
		ReservationResponse.Ready response = reservationService.createPendingReservation(
			request, memberId, idempotencyKey);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
