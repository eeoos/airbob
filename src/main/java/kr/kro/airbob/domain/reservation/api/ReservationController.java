package kr.kro.airbob.domain.reservation.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
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
		@Valid @RequestBody ReservationRequest.Create request) {
		Long memberId = UserContext.get().id();
		ReservationResponse.Ready response = reservationService.createPendingReservation(request, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@PostMapping("/v1/reservations/{reservationUid}")
	public ResponseEntity<ApiResponse<Void>> cancelReservation(
		@PathVariable String reservationUid,
		@Valid @RequestBody PaymentRequest.Cancel request) {
		Long memberId = UserContext.get().id();
		reservationService.cancelReservation(reservationUid, request, memberId);
		return ResponseEntity.accepted().body(ApiResponse.success());
	}

	@GetMapping("/v1/profile/guest/reservations/{reservationUid}")
	public ResponseEntity<ApiResponse<ReservationResponse.DetailInfo>> getMyReservationDetail(@PathVariable String reservationUid) {
		Long memberId = UserContext.get().id();
		ReservationResponse.DetailInfo response = reservationService.findMyReservationDetail(reservationUid, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/v1/profile/guest/reservations")
	public ResponseEntity<ApiResponse<ReservationResponse.MyReservationInfos>> getMyReservations(
		@CursorParam CursorRequest.CursorPageRequest request,
		@RequestParam(defaultValue = "UPCOMING") ReservationFilterType filterType) {
		Long memberId = UserContext.get().id();
		ReservationResponse.MyReservationInfos response = reservationService.findMyReservations(memberId,
			request, filterType);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/v1/profile/host/reservations")
	public ResponseEntity<ApiResponse<ReservationResponse.HostReservationInfos>> getHostReservations(
		@CursorParam CursorRequest.CursorPageRequest cursorRequest,
		@RequestParam(defaultValue = "UPCOMING") ReservationFilterType filterType) {

		Long hostId = UserContext.get().id();
		ReservationResponse.HostReservationInfos response = reservationService.findHostReservations(hostId, cursorRequest, filterType);

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/v1/profile/host/reservations/{reservationUid}")
	public ResponseEntity<ApiResponse<ReservationResponse.HostDetailInfo>> getHostReservationDetail(@PathVariable String reservationUid) {

		Long hostId = UserContext.get().id();
		ReservationResponse.HostDetailInfo response = reservationService.findHostReservationDetail(reservationUid, hostId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
