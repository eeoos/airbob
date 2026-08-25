package kr.kro.airbob.domain.reservation.api;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.service.ReservationHoldCommandService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/reservations")
public class ReservationHoldV2Controller {

	private final ReservationHoldCommandService holdCommandService;

	@DeleteMapping("/{reservationUid}/hold")
	public ResponseEntity<ApiResponse<ReservationResponse.HoldRelease>> releaseHold(
		@PathVariable String reservationUid,
		@CurrentMemberId Long memberId
	) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(holdCommandService.releaseHold(reservationUid, memberId)));
	}

	@PostMapping("/{reservationUid}/payment-attempts")
	public ResponseEntity<ApiResponse<ReservationResponse.PaymentAttemptReady>> beginPaymentAttempt(
		@PathVariable String reservationUid,
		@CurrentMemberId Long memberId
	) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(holdCommandService.beginPaymentAttempt(reservationUid, memberId)));
	}
}
