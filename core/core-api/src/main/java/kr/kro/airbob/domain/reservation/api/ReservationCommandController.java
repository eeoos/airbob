package kr.kro.airbob.domain.reservation.api;

import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.core.support.response.ApiResponse;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Cancellation;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.service.PaymentCancellationCommandService;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.service.ReservationCheckoutService;
import kr.kro.airbob.domain.reservation.service.ReservationHoldCommandService;
import kr.kro.airbob.domain.reservation.service.ReservationQuoteService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ReservationCommandController implements ReservationCommandApi {

	private final ReservationQuoteService quoteService;
	private final ReservationCheckoutService checkoutService;
	private final ReservationHoldCommandService holdCommandService;
	private final PaymentCancellationCommandService cancellationCommandService;

	@Override
	public ResponseEntity<ApiResponse<ReservationResponse.Quote>> createQuote(
		ReservationRequest.Quote request,
		Long memberId
	) {
		ReservationResponse.Quote response = quoteService.createQuote(request, memberId);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<ReservationResponse.Ready>> checkout(
		ReservationRequest.Checkout request,
		String idempotencyKey,
		Long memberId
	) {
		ReservationResponse.Ready response = ReservationResponse.Ready.from(
			checkoutService.checkout(request.toCheckout(), memberId, idempotencyKey));
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<ReservationResponse.HoldRelease>> releaseHold(
		String reservationUid,
		Long memberId
	) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(holdCommandService.releaseHold(reservationUid, memberId)));
	}

	@Override
	public ResponseEntity<ApiResponse<ReservationResponse.PaymentAttemptReady>> beginPaymentAttempt(
		String reservationUid,
		Long memberId
	) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(holdCommandService.beginPaymentAttempt(reservationUid, memberId)));
	}

	@Override
	public ResponseEntity<ApiResponse<Void>> cancelReservation(
		String reservationUid,
		PaymentRequest.Cancel request,
		Long memberId) {
		Cancellation response = cancellationCommandService.requestCancellation(reservationUid, request, memberId);
		ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.accepted();
		if (response.statusUrl() != null) {
			responseBuilder.location(URI.create(response.statusUrl()));
		}
		return responseBuilder.body(ApiResponse.success());
	}
}
