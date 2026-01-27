package kr.kro.airbob.domain.payment.repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.payment.entity.PaymentAttempt;

public interface PaymentAttemptRepository  extends JpaRepository<PaymentAttempt, Long> {
	List<PaymentAttempt> findByOrderIdOrderByCreatedAtDesc(String orderId);
}
