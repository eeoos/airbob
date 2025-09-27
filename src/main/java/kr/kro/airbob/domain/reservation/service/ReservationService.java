package kr.kro.airbob.domain.reservation.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.redisson.api.RLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
import kr.kro.airbob.domain.reservation.event.ReservationEvent;
import kr.kro.airbob.domain.reservation.exception.ReservationConflictException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.repository.ReservationRepository;
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

	private final ReservationHoldService holdService;
	private final ReservationLockManager lockManager;

	private final ApplicationEventPublisher eventPublisher;


	@Transactional
	public ReservationResponse.Ready createPendingReservation(Long memberId, ReservationRequest.Create request) {

		if (holdService.isAnyDateHeld(request.accommodationId(), request.checkInDate(), request.checkOutDate())) {
			throw new ReservationConflictException("현재 다른 사용자가 해당 날짜로 결제를 진행 중입니다. 잠시 후 다시 시도해주세요.");
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
				throw new ReservationConflictException("해당 날짜에 이미 예약이 확정되었거나 진행 중입니다.");
			}

			Reservation pendingReservation = Reservation.createPendingReservation(accommodation, guest, request);
			reservationRepository.save(pendingReservation);

			holdService.holdDates(request.accommodationId(), request.checkInDate(), request.checkOutDate());

			log.info("예약 ID {} (UID: {}) PENDING 상태로 생성 완료", pendingReservation.getId(), pendingReservation.getReservationUid());

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

		eventPublisher.publishEvent(
			new ReservationEvent.ReservationCancelledEvent(
				reservationUid,
				request.cancelReason(),
				request.cancelAmount()
			)
		);
		log.info("[예약 취소 완료]: Reservation UID {} 상태 변경 및 이벤트 발행 완료", reservationUid);
	}

	@Async
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	@TransactionalEventListener
	public void handlePaymentCancellationFailed(PaymentEvent.PaymentCancellationFailedEvent event) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(event.reservationUid()))
			.orElseThrow(ReservationNotFoundException::new);

		log.warn("[보상 트랜잭션]: 예약(UID: {})의 환불 실패, 예약 상태 CANCELLED -> CANCELLATION_FAILED 변경", event.reservationUid());
		reservation.failCancellation();

		// TODO: 슬랙 알림 발송
	}

	@Async
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	@TransactionalEventListener
	public void handlePaymentSucceeded(PaymentEvent.PaymentSucceededEvent event) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(event.reservationUid()))
			.orElseThrow(ReservationNotFoundException::new);

		log.info("[결제 성공 확인]: 예약 ID {} 상태 변경(CONFORM)", reservation.getId());
		reservation.confirm();

		holdService.removeHold(
			reservation.getAccommodation().getId(),
			reservation.getCheckIn().toLocalDate(),
			reservation.getCheckOut().toLocalDate()
		);

		eventPublisher.publishEvent(new AccommodationIndexingEvents.ReservationChangedEvent(reservation.getAccommodation().getId()));
	}

	@Async
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	@TransactionalEventListener
	public void handlePaymentFailed(PaymentEvent.PaymentFailedEvent event) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(event.reservationUid()))
			.orElseThrow(ReservationNotFoundException::new);

		log.warn("[결제 실패 확인]: 예약 ID {} 상태 변경(EXPIRED) 사유: {}", reservation.getId(), event.reason());
		reservation.expire();

		holdService.removeHold(
			reservation.getAccommodation().getId(),
			reservation.getCheckIn().toLocalDate(),
			reservation.getCheckOut().toLocalDate()
		);
	}

}
