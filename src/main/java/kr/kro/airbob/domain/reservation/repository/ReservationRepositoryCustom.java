package kr.kro.airbob.domain.reservation.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.domain.reservation.dto.ReservationDateRange;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationFilterType;

public interface ReservationRepositoryCustom {
	/**
	 * Caller must hold the accommodation row lock for the inventory decision.
	 */
	boolean existsConflictingReservation(
		Long accommodationId,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		Instant now
	);

	/**
	 * Caller must hold the accommodation row lock for the inventory decision.
	 */
	boolean existsConflictingReservationExcluding(
		Long accommodationId,
		Long excludedReservationId,
		LocalDate checkInDate,
		LocalDate checkOutDate,
		Instant now
	);

	boolean existsFutureInventoryReservation(Long accommodationId, Instant now);

	boolean existsCompletedReservationByGuest(Long accommodationId, Long memberId);

	boolean existsPastCompletedReservationByGuest(Long accommodationId, Long memberId, Instant now);

	List<ReservationDateRange> findActiveReservationRangesByAccommodationId(
		Long accommodationId,
		LocalDate windowStartInclusive,
		LocalDate windowEndExclusive
	);

	List<ReservationDateRange> findUnavailableReservationRangesByAccommodationId(
		Long accommodationId,
		LocalDate windowStartInclusive,
		LocalDate windowEndExclusive,
		Instant now
	);

	List<ReservationDateRange> findActiveReservationRangesByAccommodationUid(
		UUID accommodationUid,
		LocalDate windowStartInclusive,
		LocalDate windowEndExclusive
	);

	Slice<Reservation> findMyReservationsByGuestIdWithCursor(
		Long guestId,
		Long lastId,
		LocalDateTime lastCreatedAt,
		ReservationFilterType filterType,
		Instant now,
		Pageable pageable
	);

	Slice<Reservation> findHostReservationsByHostIdWithCursor(
		Long hostId,
		Long lastId,
		LocalDateTime lastCreatedAt,
		ReservationFilterType filterType,
		Instant now,
		Pageable pageable
	);

	Optional<Reservation> findReservationDetailByUidAndGuestId(UUID reservationUid, Long guestId);

	Optional<Reservation> findHostReservationDetailByUidAndHostId(UUID reservationUid, Long hostId);
}
