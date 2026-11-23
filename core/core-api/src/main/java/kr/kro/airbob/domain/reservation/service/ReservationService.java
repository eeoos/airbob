package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;

import org.springframework.stereotype.Service;

import kr.kro.airbob.core.support.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReservationService {

	private final ReservationTransactionService transactionService;
	private final Clock clock;

	public ReservationResponse.Ready createPendingReservation(
		ReservationRequest.Checkout request,
		Long memberId,
		String idempotencyKey
	) {
		Reservation reservation = transactionService.createPendingReservationInTx(
			request,
			memberId,
			idempotencyKey,
			"견적 기반 예약 생성"
		);
		return ReservationResponse.Ready.from(reservation, clock.instant());
	}

	public ReservationResponse.GuestReservationInfos findMyReservations(Long memberId, CursorRequest.CursorPageRequest cursorRequest, ReservationFilterType filterType) {
		return transactionService.findMyReservations(memberId, cursorRequest, filterType);
	}

	public ReservationResponse.GuestDetail findMyReservationDetail(String reservationUidStr, Long memberId) {
		return transactionService.findMyReservationDetail(reservationUidStr, memberId);
	}

	public ReservationResponse.HostReservationInfos findHostReservations(Long hostId, CursorRequest.CursorPageRequest cursorRequest, ReservationFilterType filterType) {
		return transactionService.findHostReservations(hostId, cursorRequest, filterType);
	}

	public ReservationResponse.HostDetail findHostReservationDetail(String reservationUid, Long hostId) {
		return transactionService.findHostReservationDetail(reservationUid, hostId);
	}
}
