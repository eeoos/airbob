package kr.kro.airbob.domain.payment.repository;

import java.util.List;

import kr.kro.airbob.domain.payment.repository.projection.PaymentOperationManualReviewQueueItem;

public interface PaymentOperationManualReviewQueryRepository {

	int MAX_FETCH_SIZE = 101;

	List<PaymentOperationManualReviewQueueItem> findOldest(int requestedSize);
}
