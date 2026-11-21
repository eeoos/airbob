package kr.kro.airbob.domain.reservation.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ReservationGuestController implements ReservationGuestApi {

	private final ReservationService reservationService;

	@Override
	public ResponseEntity<ApiResponse<ReservationResponse.GuestDetail>> getGuestReservationDetail(
		String reservationUid,
		Long memberId) {
		ReservationResponse.GuestDetail response = reservationService.findMyReservationDetail(reservationUid, memberId);
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<ReservationResponse.GuestReservationInfos>> getGuestReservations(
		CursorRequest.CursorPageRequest request,
		ReservationFilterType filterType,
		Long memberId) {
		ReservationResponse.GuestReservationInfos response = reservationService.findMyReservations(memberId,
			request, filterType);
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
