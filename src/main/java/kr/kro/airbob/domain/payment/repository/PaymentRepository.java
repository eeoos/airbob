package kr.kro.airbob.domain.payment.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.payment.entity.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
	Optional<Payment> findByReservationReservationUid(UUID reservationUid);

	Optional<Payment> findByOrderId(String orderId);

	Optional<Payment> findByPaymentKey(String paymentKey);
}
