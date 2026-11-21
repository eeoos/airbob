package kr.kro.airbob.domain.payment.repository.query;

import java.util.List;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.repository.PaymentOperationManualReviewQueryRepository;
import kr.kro.airbob.domain.payment.repository.projection.PaymentOperationManualReviewQueueItem;

@Repository
public class JpaPaymentOperationManualReviewQueryRepository
	implements PaymentOperationManualReviewQueryRepository {

	private final EntityManager entityManager;

	public JpaPaymentOperationManualReviewQueryRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override
	@Transactional(readOnly = true)
	public List<PaymentOperationManualReviewQueueItem> findOldest(int requestedSize) {
		if (requestedSize <= 0 || requestedSize > MAX_FETCH_SIZE) {
			throw new IllegalArgumentException(
				"requestedSize must be between 1 and " + MAX_FETCH_SIZE);
		}
		return entityManager.createQuery("""
			select new kr.kro.airbob.domain.payment.repository.projection.PaymentOperationManualReviewQueueItem(
				po.operationUid,
				po.operationType,
				po.attemptCount,
				po.manualReviewCount,
				po.reviewRequiredAt,
				po.version,
				po.notPaidResolutionEligible
			)
			from PaymentOperation po
			where po.status = :status
			  and po.reviewRequiredAt is not null
			order by po.reviewRequiredAt asc, po.id asc
			""", PaymentOperationManualReviewQueueItem.class)
			.setParameter("status", PaymentOperationStatus.MANUAL_REVIEW)
			.setMaxResults(requestedSize)
			.getResultList();
	}
}
