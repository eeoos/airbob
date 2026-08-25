package kr.kro.airbob.domain.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.payment.entity.PaymentOperationResolution;

public interface PaymentOperationResolutionRepository extends JpaRepository<PaymentOperationResolution, Long> {
}
