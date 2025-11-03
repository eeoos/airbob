package kr.kro.airbob.domain.reservation.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, ReservationRepositoryCustom{

	List<Reservation> findAllByStatusAndExpiresAtBefore(ReservationStatus status, LocalDateTime expiresAt);
	Optional<Reservation> findByReservationUid(UUID reservationUid);

	@Query("SELECT r.guest.id FROM Reservation r WHERE r.reservationUid = :reservationUid")
	Optional<Long> findGuestIdByReservationUid(@Param("reservationUid") UUID reservationUid);

	boolean existsByConfirmationCode(String newCode);
}
