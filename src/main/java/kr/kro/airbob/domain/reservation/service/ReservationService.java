package kr.kro.airbob.domain.reservation.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.redisson.api.RLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

			holdService.holdDates(request.accommodationId(), request.checkInDate(), request.checkOutDate());

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
		Reservation reservation;
		try {
			reservation = transactionService.confirmReservation(event.reservationUid());
		} catch (Exception e) {
			log.error("[예약 확정 실패] 예약 ID: {} 상태 변경 중 오류 발생. 보상 트랜잭션 시작", event.reservationUid(), e);
			outboxEventPublisher.save(
				EventType.RESERVATION_CONFIRMATION_FAILED,
				new ReservationEvent.ReservationConfirmationFailedEvent(event.reservationUid(),
					"Reservation confirmation failed due to: " + e.getMessage())
			);
			return;
		}

		// db 트랜잭션 성공하면 Redis 홀드 제거
		if (reservation != null) {
			removeReservationHold(reservation);
		}

	}

	@Transactional
	public void confirmReservation(String reservationUid) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		log.info("[결제 성공 확인]: 예약 ID {} 상태 변경(CONFIRM)", reservation.getId());
		reservation.confirm();

		// Redis 홀드 제거
		holdService.removeHold(
			reservation.getAccommodation().getId(),
			reservation.getCheckIn().toLocalDate(),
			reservation.getCheckOut().toLocalDate()
		);

		// Elasticsearch 색인을 위한 이벤트 발행
		outboxEventPublisher.save(
			EventType.RESERVATION_CHANGED,
			new AccommodationIndexingEvents.ReservationChangedEvent(
				reservation.getAccommodation().getAccommodationUid().toString()
			)
		);
	}

	public void handlePaymentFailed(PaymentEvent.PaymentFailedEvent event) {

		Reservation reservation;
		try {
			reservation = transactionService.expireReservation(event.reservationUid(), event.reason());
		} catch (ReservationStateChangeException e) {
			log.error("[예약 만료 처리 최종 실패] Reservation UID: {}. 수동 확인 필요", event.reservationUid(), e);
			return;
		}

		// db 트랜잭션 성공하면 Redis 홀드 제거
		if (reservation != null) {
			removeReservationHold(reservation);
		}
	}

	@Transactional
	public Reservation expireReservation(String reservationUid, String reason) {
		try {
			Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
				.orElseThrow(ReservationNotFoundException::new);

			if (reservation.getStatus() == ReservationStatus.EXPIRED) {
				log.info("[결제 실패 확인] 이미 만료된 예약입니다: {}", reservation.getId());
				return null;
			}

			log.warn("[결제 실패 확인]: 예약 ID {} 상태 변경(EXPIRED) 사유: {}", reservation.getId(), reason);
			reservation.expire();
			return reservation;
		} catch (Exception e) {
			log.error("[예약 만료 처리 실패] Reservation UID: {} 처리 중 DB 오류 발생", reservationUid, e);
			throw new ReservationStateChangeException();
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
			log.error("[Redis 홀드 제거 실패] Reservation UID: {}의 홀드 제거 중 오류 발생. TTL에 의해 자동 제거", reservation.getReservationUid(), e);
		}
	}


}
