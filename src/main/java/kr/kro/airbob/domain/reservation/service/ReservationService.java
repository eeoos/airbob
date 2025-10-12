package kr.kro.airbob.domain.reservation.service;

import java.util.List;

import org.redisson.api.RLock;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

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

	public ReservationResponse.Ready createPendingReservation(ReservationRequest.Create request, Long memberId) {

		String changedBy = "USER_ID:" + memberId;

		if (holdService.isAnyDateHeld(request.accommodationId(), request.checkInDate(), request.checkOutDate())) {
			throw new ReservationLockException();
		}

		List<String> lockKeys = DateLockKeyGenerator.generateLockKeys(request.accommodationId(), request.checkInDate(),
			request.checkOutDate());
		RLock lock = lockManager.acquireLocks(lockKeys);

		try{
			Runnable afterCommitTask = () -> {
				log.info("DB 트랜잭션 커밋 완료. Redis 홀드 생성 시작.");
				try {
					holdService.holdDates(
						request.accommodationId(), request.checkInDate(), request.checkOutDate());
				} catch (Exception e) {
					log.error("[CRITICAL] Redis 홀드 생성 실패. Reservation 생성은 완료됨.", e);
				}
			};

			Reservation pendingReservation = transactionService.createPendingReservationInTx(
				request,
				memberId,
				changedBy,
				"사용자 예약 생성",
				afterCommitTask
			);

			return ReservationResponse.Ready.from(pendingReservation);
		} finally {
			lockManager.releaseLocks(lock);
		}
	}

	public void cancelReservation(String reservationUid, PaymentRequest.Cancel request, Long memberId) {

		String changedBy = "USER_ID:" + memberId;

		transactionService.cancelReservationInTx(reservationUid, request, changedBy, memberId);
	}

	public void handlePaymentSucceeded(PaymentEvent.PaymentSucceededEvent event, Acknowledgment ack) {
		Runnable afterCommitTask = () -> {
			try {
				Reservation reservation = transactionService.findByReservationUidNullable(event.reservationUid());
				if (reservation != null) {
					removeReservationHold(reservation);
					log.info("[AFTER_COMMIT] Redis 홀드 제거 완료. Reservation UID={}", reservation.getReservationUid());
				} else {
					log.warn("[AFTER_COMMIT] Reservation UID={} 조회 실패. Redis 해제 스킵.", event.reservationUid());
				}
			} catch (Exception e) {
				log.error("[AFTER_COMMIT] Redis 홀드 제거 실패 (DB 커밋은 완료됨).", e);
			} finally {
				ack.acknowledge();
				log.info("[KAFKA-ACK] 예약 확정 성공. Offset 커밋 완료. UID={}", event.reservationUid());
			}
		};

		transactionService.confirmReservationInTx(event.reservationUid(), afterCommitTask);
	}

	public void handlePaymentFailed(PaymentEvent.PaymentFailedEvent event, Acknowledgment ack) {
		Runnable afterCommitTask = () -> {
			try {
				Reservation reservation = transactionService.findByReservationUidNullable(event.reservationUid());
				if (reservation != null) {
					removeReservationHold(reservation);
					log.info("[AFTER_COMMIT] Redis 홀드 제거 완료. Reservation UID={}", reservation.getReservationUid());
				} else {
					log.warn("[AFTER_COMMIT] Reservation UID={} 조회 실패. Redis 해제 스킵.", event.reservationUid());
				}
			} catch (Exception e) {
				log.error("[AFTER_COMMIT] Redis 홀드 제거 실패 (DB 커밋은 완료됨).", e);
			}finally {
				ack.acknowledge();
				log.info("[KAFKA-ACK] 예약 만료 성공. Offset 커밋 완료. UID={}", event.reservationUid());
			}
		};

		transactionService.expireReservationInTx(event.reservationUid(), event.reason(), afterCommitTask);
	}

	private void removeReservationHold(Reservation reservation) {
		holdService.removeHold(
			reservation.getAccommodation().getId(),
			reservation.getCheckIn().toLocalDate(),
			reservation.getCheckOut().toLocalDate()
		);
	}
}
