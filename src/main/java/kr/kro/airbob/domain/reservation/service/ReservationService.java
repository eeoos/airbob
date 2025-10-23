package kr.kro.airbob.domain.reservation.service;

import java.util.List;

import org.redisson.api.RLock;
import org.springframework.stereotype.Service;

import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

	private final ReservationHoldService holdService;
	private final ReservationLockManager lockManager;

	private final ReservationTransactionService transactionService;

	public ReservationResponse.Ready createPendingReservation(ReservationRequest.Create request, Long memberId) {

		if (holdService.isAnyDateHeld(request.accommodationId(), request.checkInDate(), request.checkOutDate())) {
			throw new ReservationLockException();
		}

		List<String> lockKeys = DateLockKeyGenerator.generateLockKeys(request.accommodationId(), request.checkInDate(),
			request.checkOutDate());
		RLock lock = lockManager.acquireLocks(lockKeys);

		try{
			Reservation pendingReservation = transactionService.createPendingReservationInTx(
				request,
				memberId,
				"사용자 예약 생성"
			);

			holdService.holdDates(request.accommodationId(), request.checkInDate(), request.checkOutDate());

			return ReservationResponse.Ready.from(pendingReservation);
		} finally {
			lockManager.releaseLocks(lock);
		}
	}

	public void cancelReservation(String reservationUid, PaymentRequest.Cancel request, Long memberId) {
		transactionService.cancelReservationInTx(reservationUid, request, memberId);
	}

	public void confirmReservation(PaymentEvent.PaymentCompletedEvent event) {
		transactionService.confirmReservationInTx(event.reservationUid());
	}

	public void expireReservation(PaymentEvent.PaymentFailedEvent event) {
		transactionService.expireReservationInTx(event.reservationUid(), event.reason());
	}

	public void revertCancellation(ReservationEvent.ReservationCancellationRevertRequestedEvent event) {
		transactionService.revertCancellationInTx(event.reservationUid(), event.reason());
	}

	public ReservationResponse.MyReservationInfos findMyReservations(Long memberId, CursorRequest.CursorPageRequest cursorRequest) {
		return transactionService.findMyReservations(memberId, cursorRequest);
	}
}
