package kr.kro.airbob.domain.reservation.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;

public interface ReservationRepositoryCustom {
	boolean existsConflictingReservation(Long accommodationId, LocalDateTime checkIn, LocalDateTime checkOut);

	boolean existsCompletedReservationByGuest(Long accommodationId, Long memberId);

	boolean existsPastCompletedReservationByGuest(Long accommodationId, Long memberId);

	List<Reservation> findFutureCompletedReservations(UUID accommodationUid);

	Slice<Reservation> findMyReservationsByGuestIdWithCursor(
		Long guestId,
		Long lastId,
		LocalDateTime lastCreatedAt,
		ReservationFilterType filterType,
		Pageable pageable
	);

	Slice<Reservation> findHostReservationsByHostIdWithCursor(
		Long hostId,
		Long lastId,
		LocalDateTime lastCreatedAt,
		ReservationFilterType filterType,
		Pageable pageable
	);

	Optional<Reservation> findReservationDetailByUidAndGuestId(UUID reservationUid, Long guestId);

	Optional<Reservation> findHostReservationDetailByUidAndHostId(UUID reservationUid, Long hostId);
}
