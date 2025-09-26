package kr.kro.airbob.domain.reservation.service;

import java.time.LocalDateTime;
import java.util.List;

import org.redisson.api.RLock;
import org.springframework.context.ApplicationEventPublisher;
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
	public ReservationResponse.Create createPendingReservation(Long memberId, ReservationRequest.Create request) {

		if (holdService.isAnyDateHeld(request.accommodationId(), request.checkInDate(), request.checkOutDate())) {
			throw new ReservationConflictException("현재 다른 사용자가 해당 날짜로 결제를 진행 중입니다. 잠시 후 다시 시도해주세요.");
		}

		List<String> lockKeys = DateLockKeyGenerator.generateLockKeys(request.accommodationId(), request.checkInDate(),
			request.checkOutDate());

		RLock lock = null;
		try{
			lock = lockManager.acquireLocks(lockKeys);

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
			Reservation savedReservation = reservationRepository.save(pendingReservation);

			holdService.holdDates(request.accommodationId(), request.checkInDate(), request.checkOutDate());

			eventPublisher.publishEvent(
				new ReservationEvent.ReservationPendingEvent(savedReservation.getId(), savedReservation.getTotalPrice())
			);

			return ReservationResponse.Create.from(savedReservation);
		} finally {
			lockManager.releaseLocks(lock);
		}
	}

	// @Async // 굳이?
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void handlePaymentSucceeded(PaymentEvent.PaymentSucceededEvent event) {
		Reservation reservation = reservationRepository.findById(event.reservationId())
			.orElseThrow(ReservationNotFoundException::new);
		log.info("예약ID {} 상태 변경 시도", reservation.getId());
		reservation.confirm();
		log.info("예약ID {} 예약 완료", reservation.getId());
		// es 색인 이벤트 발행
		eventPublisher.publishEvent(new AccommodationIndexingEvents.ReservationChangedEvent(reservation.getAccommodation().getId()));
	}


	// todo: 결제 실패 보상 트랜잭션 구현
}
