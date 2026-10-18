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
public class ReservationHostController implements ReservationHostApi {

	private final ReservationService reservationService;

	@Override
	public ResponseEntity<ApiResponse<ReservationResponse.HostReservationInfos>> getHostReservations(
		CursorRequest.CursorPageRequest cursorRequest,
		ReservationFilterType filterType,
		Long hostId) {

		ReservationResponse.HostReservationInfos response = reservationService.findHostReservations(hostId, cursorRequest, filterType);

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<ReservationResponse.HostDetail>> getHostReservationDetail(
		String reservationUid,
		Long hostId) {

		ReservationResponse.HostDetail response = reservationService.findHostReservationDetail(reservationUid, hostId);

		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
