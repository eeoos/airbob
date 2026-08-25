package kr.kro.airbob.domain.reservation.api;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Cancellation;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class ReservationController {

	private final ReservationService reservationService;

	@PostMapping("/v1/reservations")
	public ResponseEntity<ApiResponse<ReservationResponse.Ready>> createReservation(
		@Valid @RequestBody ReservationRequest.Create request,
		@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
		@CurrentMemberId Long memberId) {
		ReservationResponse.Ready response = reservationService.createPendingReservation(
			request, memberId, idempotencyKey);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@PostMapping("/v1/reservations/{reservationUid}")
	public ResponseEntity<ApiResponse<Void>> cancelReservation(
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

	@GetMapping("/v1/profile/guest/reservations/{reservationUid}")
	public ResponseEntity<ApiResponse<ReservationResponse.GuestDetail>> getGuestReservationDetail(
		@PathVariable String reservationUid,
		@CurrentMemberId Long memberId) {
		ReservationResponse.GuestDetail response = reservationService.findMyReservationDetail(reservationUid, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/v1/profile/guest/reservations")
	public ResponseEntity<ApiResponse<ReservationResponse.GuestReservationInfos>> getGuestReservations(
		@CursorParam CursorRequest.CursorPageRequest request,
		@RequestParam(required = false) ReservationFilterType filterType,
		@CurrentMemberId Long memberId) {
		ReservationResponse.GuestReservationInfos response = reservationService.findMyReservations(memberId,
			request, filterType);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/v1/profile/host/reservations")
	public ResponseEntity<ApiResponse<ReservationResponse.HostReservationInfos>> getHostReservations(
		@CursorParam CursorRequest.CursorPageRequest cursorRequest,
		@RequestParam(required = false) ReservationFilterType filterType,
		@CurrentMemberId Long hostId) {

		ReservationResponse.HostReservationInfos response = reservationService.findHostReservations(hostId, cursorRequest, filterType);

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/v1/profile/host/reservations/{reservationUid}")
	public ResponseEntity<ApiResponse<ReservationResponse.HostDetail>> getHostReservationDetail(
		@PathVariable String reservationUid,
		@CurrentMemberId Long hostId) {

		ReservationResponse.HostDetail response = reservationService.findHostReservationDetail(reservationUid, hostId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
