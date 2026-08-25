package kr.kro.airbob.domain.reservation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, ReservationRepositoryCustom{
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select reservation from Reservation reservation where reservation.id = :reservationId")
	Optional<Reservation> findByIdWithLock(@Param("reservationId") Long reservationId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<Reservation> findAllByStatusAndExpiresAtLessThanEqual(ReservationStatus status, Instant expiresAt);

	@Query(value = """
		select * from reservation
		where status = 'PAYMENT_PENDING'
		  and expires_at <= :cutoff
		order by expires_at, id
		limit :batchSize
		for update skip locked
		""", nativeQuery = true)
	List<Reservation> findExpiredPendingBatchForCleanup(
		@Param("cutoff") Instant cutoff,
		@Param("batchSize") int batchSize
	);

	Optional<Reservation> findByReservationUid(UUID reservationUid);

	@Query("""
		select reservation
		from Reservation reservation
		join fetch reservation.accommodation
		join fetch reservation.guest
		where reservation.id = :reservationId
		  and reservation.guest.id = :guestId
		""")
	Optional<Reservation> findCheckoutReplayByIdAndGuestId(
		@Param("reservationId") Long reservationId,
		@Param("guestId") Long guestId
	);

	@Query("select reservation.accommodation.id from Reservation reservation where reservation.reservationUid = :reservationUid")
	Optional<Long> findAccommodationIdByReservationUid(@Param("reservationUid") UUID reservationUid);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select reservation from Reservation reservation where reservation.reservationUid = :reservationUid")
	Optional<Reservation> findByReservationUidWithLock(@Param("reservationUid") UUID reservationUid);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select reservation
		from Reservation reservation
		where reservation.reservationUid = :reservationUid
		  and reservation.guest.id = :guestId
		""")
	Optional<Reservation> findByReservationUidAndGuestIdWithLock(
		@Param("reservationUid") UUID reservationUid,
		@Param("guestId") Long guestId
	);

	boolean existsByReservationCode(String reservationCode);
}
