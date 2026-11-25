package kr.kro.airbob.core.domain.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.core.domain.payment.PaymentOperationResolution;

public interface PaymentOperationResolutionRepository extends JpaRepository<PaymentOperationResolution, Long> {}
