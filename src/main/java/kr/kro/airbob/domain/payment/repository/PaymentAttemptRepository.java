package kr.kro.airbob.domain.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.payment.entity.PaymentAttempt;

public interface PaymentAttemptRepository  extends JpaRepository<PaymentAttempt, Long> {
}
