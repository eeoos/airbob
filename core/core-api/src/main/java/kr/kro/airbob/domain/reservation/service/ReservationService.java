package kr.kro.airbob.domain.reservation.service;

import java.time.Clock;

import org.springframework.stereotype.Service;

import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
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
}
