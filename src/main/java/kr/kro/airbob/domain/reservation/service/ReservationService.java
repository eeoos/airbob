package kr.kro.airbob.domain.reservation.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.Member;
import kr.kro.airbob.domain.member.MemberRepository;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.payment.event.PaymentEvent;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationLockException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationStateChangeException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.search.event.AccommodationIndexingEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

	private final ReservationRepository reservationRepository;
	private final MemberRepository memberRepository;
	private final AccommodationRepository accommodationRepository;

	private final OutboxEventPublisher outboxEventPublisher;
	private final ReservationHoldService holdService;
	private final ReservationLockManager lockManager;
	private final ReservationTransactionService transactionService;

	@Transactional
	public ReservationResponse.Ready createPendingReservation(Long memberId, ReservationRequest.Create request) {

		if (holdService.isAnyDateHeld(request.accommodationId(), request.checkInDate(), request.checkOutDate())) {
			throw new ReservationLockException();
		}

		List<String> lockKeys = DateLockKeyGenerator.generateLockKeys(request.accommodationId(), request.checkInDate(),
			request.checkOutDate());
		RLock lock = lockManager.acquireLocks(lockKeys);

		try{
			log.info("분산 락 획득 성공 (락 키: {})", lockKeys);

			// -- 락 획득 성공 --
			Member guest = memberRepository.findById(memberId).orElseThrow(MemberNotFoundException::new);
			Accommodation accommodation = accommodationRepository.findById(request.accommodationId())
				.orElseThrow(AccommodationNotFoundException::new);

			LocalDateTime checkInDateTime = request.checkInDate().atTime(accommodation.getCheckInTime());
			LocalDateTime checkOutDateTime = request.checkOutDate().atTime(accommodation.getCheckOutTime());

			if (reservationRepository.existsConflictingReservation(request.accommodationId(), checkInDateTime, checkOutDateTime)) {
				throw new ReservationConflictException();
			}

			Reservation pendingReservation = Reservation.createPendingReservation(accommodation, guest, request);
			reservationRepository.save(pendingReservation);

			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					log.info("DB 트랜잭션 커밋 완료 (예약 PENDING). ReservationUID: {} Redis 홀드 생성 시작", pendingReservation.getReservationUid());
					try {
						holdService.holdDates(request.accommodationId(), request.checkInDate(), request.checkOutDate());
					} catch (Exception e) {
						log.error("[CRITICAL] PENDING 예약 생성 후 Redis 홀드 실패. 데이터 불일치 발생 ReservationUID: {}",
							pendingReservation.getReservationUid(), e);
					}
				}
			});

			log.info("예약 ID {} (UID: {}) PENDING 상태로 생성", pendingReservation.getId(), pendingReservation.getReservationUid());
			return ReservationResponse.Ready.from(pendingReservation);
		} finally {
			lockManager.releaseLocks(lock);
		}
	}

	@Transactional
	public void cancelReservation(String reservationUid, PaymentRequest.Cancel request) {

		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		log.info("[예약 취소]: Reservation UID {}", reservationUid);

		reservation.cancel();

		outboxEventPublisher.save(
			EventType.RESERVATION_CANCELLED,
			new ReservationEvent.ReservationCancelledEvent(
				reservationUid,
				request.cancelReason(),
				request.cancelAmount()
			)
		);

		log.info("[예약 취소 완료]: Reservation UID {} 상태 변경 및 이벤트 발행 완료", reservationUid);
	}

	public void handlePaymentSucceeded(PaymentEvent.PaymentSucceededEvent event) {

		Reservation reservation = transactionService.confirmReservation(event.reservationUid());

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				log.info("DB 트랜잭션 커밋 완료. Reservation UID: {}. Redis 홀드 제거 시작", event.reservationUid());
				try {
					if (reservation != null) {
						removeReservationHold(reservation);
					}
				} catch (Exception e) {
					log.error("Redis 홀드 제거 실패(DB 커밋은 완료) ReservationUID: {}", event.reservationUid(), e);
				}
			}
		});
	}

	public void handlePaymentFailed(PaymentEvent.PaymentFailedEvent event) {

		Reservation reservation = transactionService.expireReservation(event.reservationUid(), event.reason());

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				log.info("DB 트랜잭션 커밋 완료 (예약 만료) ReservationUID: {} Redis 홀드 제거 시작", event.reservationUid());
				try {
					if (reservation != null) {
						removeReservationHold(reservation);
					}
				} catch (Exception e) {
					log.error("예약 만료 후 Redis 홀드 제거 실패(DB 커밋은 완료) ReservationUID: {}", event.reservationUid(), e);
				}
			}
		});
	}

	private void removeReservationHold(Reservation reservation) {
		holdService.removeHold(
			reservation.getAccommodation().getId(),
			reservation.getCheckIn().toLocalDate(),
			reservation.getCheckOut().toLocalDate());
	}
}
