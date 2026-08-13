package kr.kro.airbob.domain.payment.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import kr.kro.airbob.domain.payment.entity.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
	Optional<Payment> findByReservationReservationUid(UUID reservationUid);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select payment from Payment payment where payment.reservation.reservationUid = :reservationUid")
	Optional<Payment> findByReservationReservationUidWithLock(@Param("reservationUid") UUID reservationUid);

	Optional<Payment> findByOrderId(String orderId);

	Optional<Payment> findByPaymentKey(String paymentKey);
}
