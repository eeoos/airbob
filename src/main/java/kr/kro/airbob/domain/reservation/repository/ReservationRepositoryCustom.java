package kr.kro.airbob.domain.reservation.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import kr.kro.airbob.domain.reservation.entity.Reservation;

public interface ReservationRepositoryCustom {
	boolean existsConflictingReservation(Long accommodationId, LocalDateTime checkIn, LocalDateTime checkOut);

	boolean existsCompletedReservationByGuest(Long accommodationId, Long memberId);

	List<Reservation> findFutureCompletedReservations(UUID accommodationUid);
}
