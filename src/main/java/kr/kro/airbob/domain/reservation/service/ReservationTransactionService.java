package kr.kro.airbob.domain.reservation.service;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.repository.AccommodationRepository;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.payment.dto.PaymentRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationRequest;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.reservation.entity.ReservationStatusHistory;
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationAccessDeniedException;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
import kr.kro.airbob.domain.reservation.repository.ReservationStatusHistoryRepository;
import kr.kro.airbob.outbox.EventType;
import kr.kro.airbob.outbox.OutboxEventPublisher;
import kr.kro.airbob.search.event.AccommodationIndexingEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationTransactionService {

	private static final String KAFKA_CONSUMER = "SYSTEM:KAFKA_CONSUMER";

	private final OutboxEventPublisher outboxEventPublisher;

	private final MemberRepository memberRepository;
	private final ReservationRepository reservationRepository;
	private final AccommodationRepository accommodationRepository;
	private final ReservationStatusHistoryRepository historyRepository;

	@Transactional
	public Reservation createPendingReservationInTx(ReservationRequest.Create request, Long memberId, String changedBy,
		String reason) {

		Member guest = memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE).orElseThrow(MemberNotFoundException::new);
		Accommodation accommodation = accommodationRepository.findByIdAndStatus(request.accommodationId(), AccommodationStatus.PUBLISHED)
			.orElseThrow(AccommodationNotFoundException::new);

		LocalDateTime checkInDateTime = request.checkInDate().atTime(accommodation.getCheckInTime());
		LocalDateTime checkOutDateTime = request.checkOutDate().atTime(accommodation.getCheckOutTime());

		if (reservationRepository.existsConflictingReservation(request.accommodationId(), checkInDateTime, checkOutDateTime)) {
			throw new ReservationConflictException();
		}

		Reservation pendingReservation = Reservation.createPendingReservation(accommodation, guest, request);
		reservationRepository.save(pendingReservation);

		historyRepository.save(ReservationStatusHistory.builder()
			.reservation(pendingReservation)
			.previousStatus(null)
			.newStatus(ReservationStatus.PAYMENT_PENDING)
			.changedBy(changedBy)
			.reason(reason)
			.build());

		// SAGA 시작 이벤트 발행
		outboxEventPublisher.save(
			EventType.RESERVATION_PENDING,
			new ReservationEvent.ReservationPendingEvent(
				pendingReservation.getTotalPrice(),
				null, // 이 시점에는 paymentKey X
				pendingReservation.getReservationUid().toString()
			)
		);

		log.info("예약 ID {} (UID: {}) PENDING 상태로 DB 저장 완료", pendingReservation.getId(), pendingReservation.getReservationUid());
		return pendingReservation;
	}

	@Transactional
	public void cancelReservationInTx(String reservationUid, PaymentRequest.Cancel request, String changedBy, Long memberId) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		if (!reservation.getGuest().getId().equals(memberId)) {
			throw new ReservationAccessDeniedException();
		}

		ReservationStatus previousStatus = reservation.getStatus();
		reservation.cancel();

		historyRepository.save(ReservationStatusHistory.builder()
			.reservation(reservation)
			.previousStatus(previousStatus)
			.newStatus(ReservationStatus.CANCELLED)
			.changedBy(changedBy)
			.reason(request.cancelReason())
			.build());

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

	@Transactional
	public void confirmReservationInTx(String reservationUid) {

		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
			log.info("[결제 성공 확인] 이미 확정 처리된 예약: {}", reservation.getId());
			return;
		}

		ReservationStatus previousStatus = reservation.getStatus();
		reservation.confirm();

		historyRepository.save(ReservationStatusHistory.builder()
			.reservation(reservation)
			.previousStatus(previousStatus)
			.newStatus(ReservationStatus.CONFIRMED)
			.changedBy(KAFKA_CONSUMER)
			.reason("결제 성공")
			.build());

		outboxEventPublisher.save(
			EventType.RESERVATION_CONFIRMED,
			new ReservationEvent.ReservationConfirmedEvent(
				reservation.getAccommodation().getId(),
				reservation.getCheckIn().toLocalDate(),
				reservation.getCheckOut().toLocalDate()
			)
		);

		// es 색인
		outboxEventPublisher.save(
			EventType.RESERVATION_CHANGED,
			new AccommodationIndexingEvents.ReservationChangedEvent(
				reservation.getAccommodation().getAccommodationUid().toString()
			)
		);

		log.info("[결제 성공 확인]: 예약 ID {} 상태 변경(CONFIRMED) 및 이벤트 발행 완료", reservation.getId());
	}

	@Transactional
	public void expireReservationInTx(String reservationUid, String reason) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		if (reservation.getStatus() == ReservationStatus.EXPIRED) {
			log.info("[결제 실패 확인] 이미 만료 처리된 예약: {}", reservation.getId());
			return;
		}

		ReservationStatus previousStatus = reservation.getStatus();
		reservation.expire();

		historyRepository.save(ReservationStatusHistory.builder()
			.reservation(reservation)
			.previousStatus(previousStatus)
			.newStatus(ReservationStatus.EXPIRED)
			.changedBy(KAFKA_CONSUMER)
			.reason(reason)
			.build());

		outboxEventPublisher.save(
			EventType.RESERVATION_EXPIRED,
			new ReservationEvent.ReservationExpiredEvent(
				reservation.getAccommodation().getId(),
				reservation.getCheckIn().toLocalDate(),
				reservation.getCheckOut().toLocalDate()
			)
		);

		log.info("[결제 실패 확인] 예약 ID {} 상태 변경(EXPIRED) 사유: {}", reservation.getId(), reason);
	}

	@Transactional
	public void revertCancellationInTx(String reservationUid, String reason) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		// 이미 CANCELLATION_FAILED 상태라면 중복 처리 방지
		if (reservation.getStatus() == ReservationStatus.CANCELLATION_FAILED) {
			log.warn("[보상-SKIP] 이미 취소 실패 처리된 예약입니다. UID: {}", reservationUid);
			return;
		}

		ReservationStatus previousStatus = reservation.getStatus();
		reservation.failCancellation();

		historyRepository.save(ReservationStatusHistory.builder()
			.reservation(reservation)
			.previousStatus(previousStatus)
			.newStatus(ReservationStatus.CANCELLATION_FAILED)
			.changedBy(KAFKA_CONSUMER)
			.reason("결제 취소 실패 보상 트랜잭션: " + reason)
			.build()
		);

		log.info("[보상-SUCCESS] 예약 취소 실패 보상 처리 완료. 예약 상태 CANCELLATION_FAILED로 변경. UID: {}", reservationUid);
	}

	public Reservation findByReservationUidNullable(String reservationUid) {
		return reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElse(null);
	}
}
