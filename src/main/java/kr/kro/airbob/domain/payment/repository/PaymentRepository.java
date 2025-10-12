package kr.kro.airbob.domain.payment.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import kr.kro.airbob.domain.payment.entity.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
	Optional<Payment> findByReservationReservationUid(UUID reservationUid);

	Optional<Payment> findByOrderId(String orderId);

	Optional<Payment> findByPaymentKey(String paymentKey);

	@Query("SELECT p.reservation.guest.id FROM Payment p WHERE p.paymentKey = :paymentKey")
	Optional<Long> findGuestIdByPaymentKey(@Param("paymentKey") String paymentKey);

	@Query("SELECT p.reservation.guest.id FROM Payment p WHERE p.orderId = :orderId")
	Optional<Long> findGuestIdByOrderId(@Param("orderId") String orderId);
}
