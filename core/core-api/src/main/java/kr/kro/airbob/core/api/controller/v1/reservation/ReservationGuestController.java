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
public class ReservationGuestController implements ReservationGuestApi {

	private final ReservationQueryService queryService;

	@Override
	public ResponseEntity<ApiResponse<ReservationResponse.GuestDetail>> getGuestReservationDetail(
		String reservationUid,
		Long memberId) {
		ReservationResponse.GuestDetail response = ReservationResponse.GuestDetail.from(
			queryService.findMyReservationDetail(reservationUid, memberId));
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@Override
	public ResponseEntity<ApiResponse<ReservationResponse.GuestReservationInfos>> getGuestReservations(
		CursorRequest.CursorPageRequest request,
		ReservationFilterType filterType,
		Long memberId) {
		ReservationResponse.GuestReservationInfos response = ReservationResponse.GuestReservationInfos.from(
			queryService.findMyReservations(memberId, request, filterType));
		return ResponseEntity.ok(ApiResponse.success(response));
	}
}
