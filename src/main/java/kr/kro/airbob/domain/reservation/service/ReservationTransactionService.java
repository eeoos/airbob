package kr.kro.airbob.domain.reservation.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
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
public class ReservationTransactionService {

	private final ReservationRepository reservationRepository;
	private final OutboxEventPublisher outboxEventPublisher;

	@Transactional
	public Reservation confirmReservation(String reservationUid) {
		Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
			.orElseThrow(ReservationNotFoundException::new);

		log.info("[결제 성공 확인]: 예약 ID {} 상태 변경(CONFIRMED)", reservation.getId());
		reservation.confirm();

		outboxEventPublisher.save(
			EventType.RESERVATION_CHANGED,
			new AccommodationIndexingEvents.ReservationChangedEvent(
				reservation.getAccommodation().getAccommodationUid().toString()
			)
		);
		return reservation;
	}

	@Transactional
	public Reservation expireReservation(String reservationUid, String reason) {
		try {
			Reservation reservation = reservationRepository.findByReservationUid(UUID.fromString(reservationUid))
				.orElseThrow(ReservationNotFoundException::new);

			if (reservation.getStatus() == ReservationStatus.EXPIRED) {
				log.info("[결제 실패 확인] 이미 만료 처리된 예약: {}", reservation.getId());
				return null;
			}

			log.warn("[결제 실패 확인] 예약 ID {} 상태 변경(EXPIRED) 사유: {}", reservation.getId(), reason);
			reservation.expire();
			return reservation;
		} catch (Exception e) {
			log.error("[예약 만료 처리 실패]: Reservation UID: {} 처리 중 DB 오류 발생", reservationUid, e);
			throw new ReservationStateChangeException();
		}
	}
}
