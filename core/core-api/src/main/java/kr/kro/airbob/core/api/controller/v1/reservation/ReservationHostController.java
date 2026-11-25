package kr.kro.airbob.core.api.controller.v1.reservation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.core.api.controller.v1.reservation.response.ReservationResponse;
import kr.kro.airbob.core.domain.reservation.ReservationFilterType;
import kr.kro.airbob.core.domain.reservation.ReservationQueryService;
import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ReservationHostController implements ReservationHostApi {

	private final ReservationQueryService queryService;

	@Override
	public ResponseEntity<ApiResponse<ReservationResponse.HostReservationInfos>> getHostReservations(
		CursorRequest.CursorPageRequest cursorRequest,
		ReservationFilterType filterType,
		Long hostId) {

		ReservationResponse.HostReservationInfos response = ReservationResponse.HostReservationInfos.from(
			queryService.getHostReservations(hostId, cursorRequest, filterType));

		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<ReservationResponse.HostDetail>> getHostReservationDetail(
		String reservationUid,
		Long hostId) {

		ReservationResponse.HostDetail response = ReservationResponse.HostDetail.from(
			queryService.getHostReservationDetail(reservationUid, hostId));

		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
