package kr.kro.airbob.domain.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.payment.entity.Payment;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
}
