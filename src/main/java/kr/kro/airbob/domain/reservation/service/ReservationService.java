package kr.kro.airbob.domain.reservation.service;

import java.util.List;

import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
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

	public ReservationResponse.Ready createPendingReservation(ReservationRequest.Create request) {

		Long memberId = getMemberId();
		String changedBy = "USER_ID:" + memberId;

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
				changedBy,
				"사용자 예약 생성"
			);

			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					log.info("DB 트랜잭션 커밋 완료. ReservationUID: {}. Redis 홀드 생성 시작.", pendingReservation.getReservationUid());
					try {
						holdService.holdDates(request.accommodationId(), request.checkInDate(), request.checkOutDate());
					} catch (Exception e) {
						log.error("[CRITICAL] PENDING 예약 생성 후 Redis 홀드 실패. ReservationUID: {}", pendingReservation.getReservationUid(), e);
					}
				}
			});

			return ReservationResponse.Ready.from(pendingReservation);
		} finally {
			lockManager.releaseLocks(lock);
		}
	}

	public void cancelReservation(String reservationUid, PaymentRequest.Cancel request) {

		Long memberId = UserContext.get().id();
		String changedBy = "USER_ID:" + memberId;

		transactionService.cancelReservationInTx(reservationUid, request, changedBy);
	}

	public void handlePaymentSucceeded(PaymentEvent.PaymentSucceededEvent event) {

		Reservation reservation = transactionService.confirmReservationInTx(event.reservationUid());

		if (reservation != null) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					log.info("DB 트랜잭션 커밋 완료. Reservation UID: {}. Redis 홀드 제거 시작.", event.reservationUid());
					removeReservationHold(reservation);
				}
			});
		}
	}

	public void handlePaymentFailed(PaymentEvent.PaymentFailedEvent event) {

		Reservation reservation = transactionService.expireReservationInTx(event.reservationUid(), event.reason());

		if (reservation != null) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					log.info("DB 트랜잭션 커밋 완료. ReservationUID: {}. Redis 홀드 제거 시작.", event.reservationUid());
					removeReservationHold(reservation);
				}
			});
		}
	}

	private void removeReservationHold(Reservation reservation) {
		try {
			holdService.removeHold(
				reservation.getAccommodation().getId(),
				reservation.getCheckIn().toLocalDate(),
				reservation.getCheckOut().toLocalDate()
			);
		} catch (Exception e) {
			log.error("Redis 홀드 제거 실패 (DB 커밋은 완료). ReservationUID: {}", reservation.getReservationUid(), e);
		}
	}

	private Long getMemberId() {
		return UserContext.get().id();
	}
}
