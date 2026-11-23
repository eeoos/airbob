package kr.kro.airbob.domain.reservation.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.core.support.response.ApiResponse;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import kr.kro.airbob.domain.reservation.service.ReservationQueryService;
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
