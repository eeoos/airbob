package kr.kro.airbob.domain.payment.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;

public interface PaymentOperationRepository extends JpaRepository<PaymentOperation, Long> {

	Optional<PaymentOperation> findByOperationUid(UUID operationUid);

	Optional<PaymentOperation> findByDeduplicationKey(String deduplicationKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select po from PaymentOperation po where po.operationUid = :operationUid")
	Optional<PaymentOperation> findByOperationUidWithLock(@Param("operationUid") UUID operationUid);
}
