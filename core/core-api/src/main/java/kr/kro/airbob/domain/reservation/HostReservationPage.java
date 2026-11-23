package kr.kro.airbob.domain.reservation;

import java.util.List;

import kr.kro.airbob.core.support.cursor.dto.CursorResponse;

/** 호스트 예약 목록 한 페이지. */
public record HostReservationPage(
	List<HostReservationListProjection> reservations,
	CursorResponse.PageInfo pageInfo
) {
}
