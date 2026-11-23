package kr.kro.airbob.core.domain.reservation.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import kr.kro.airbob.core.domain.reservation.GuestReservationDetailProjection;
import kr.kro.airbob.core.domain.reservation.GuestReservationListProjection;
import kr.kro.airbob.core.domain.reservation.HostReservationDetailProjection;
import kr.kro.airbob.core.domain.reservation.HostReservationListProjection;
import kr.kro.airbob.core.domain.reservation.Reservation;
import kr.kro.airbob.core.domain.reservation.ReservationDateRange;
import kr.kro.airbob.core.domain.reservation.ReservationFilterType;

public interface ReservationRepositoryCustom {
	boolean existsFutureInventoryReservation(Long accommodationId, Instant now);

	boolean existsCompletedReservationByGuest(Long accommodationId, Long memberId);

	boolean existsPastCompletedReservationByGuest(Long accommodationId, Long memberId, Instant now);

	List<ReservationDateRange> findActiveReservationRangesByAccommodationId(
		Long accommodationId,
		LocalDate windowStartInclusive,
		LocalDate windowEndExclusive
	);

	Slice<GuestReservationListProjection> findMyReservationsByGuestIdWithCursor(
		Long guestId,
		Long lastId,
		LocalDateTime lastCreatedAt,
		ReservationFilterType filterType,
		Instant now,
		Pageable pageable
	);

	Slice<HostReservationListProjection> findHostReservationsByHostIdWithCursor(
		Long hostId,
		Long lastId,
		LocalDateTime lastCreatedAt,
		ReservationFilterType filterType,
		Instant now,
		Pageable pageable
	);

	Optional<GuestReservationDetailProjection> findReservationDetailByUidAndGuestId(UUID reservationUid, Long guestId);

	Optional<HostReservationDetailProjection> findHostReservationDetailByUidAndHostId(UUID reservationUid, Long hostId);
}
