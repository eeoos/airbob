package kr.kro.airbob.domain.payment.repository;

import java.time.Instant;
import java.util.List;
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

	@Query(value = """
		select * from payment_operation
		where last_enqueued_at <= :staleBefore
		  and (
		    status = 'READY'
		    or (status in ('RETRY_WAIT', 'OUTCOME_UNKNOWN') and next_attempt_at <= :now)
		    or (status = 'EXECUTING' and lease_expires_at <= :now)
		  )
		order by coalesce(next_attempt_at, lease_expires_at, created_at), id
		limit :batchSize
		for update skip locked
		""", nativeQuery = true)
	List<PaymentOperation> findRecoverableForUpdate(
		@Param("now") Instant now,
		@Param("staleBefore") Instant staleBefore,
		@Param("batchSize") int batchSize);
}
