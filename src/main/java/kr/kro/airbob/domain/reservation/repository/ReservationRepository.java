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

	Optional<Reservation> findByReservationUid(UUID reservationUid);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select reservation from Reservation reservation where reservation.reservationUid = :reservationUid")
	Optional<Reservation> findByReservationUidWithLock(@Param("reservationUid") UUID reservationUid);

	boolean existsByReservationCode(String reservationCode);
}
