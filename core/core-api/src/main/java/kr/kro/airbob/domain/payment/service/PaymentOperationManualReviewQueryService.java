package kr.kro.airbob.domain.payment.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.core.support.error.InvalidInputException;
import kr.kro.airbob.domain.payment.PaymentOperationManualReviewQueue;
import kr.kro.airbob.domain.payment.PaymentOperationManualReviewQueueItem;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentOperationManualReviewQueryService {

	public static final int MAX_LIMIT = 100;

	private final PaymentOperationManualReviewReader manualReviewReader;

	@Transactional(readOnly = true)
	public PaymentOperationManualReviewQueue findManualReviewQueue(int limit) {
		validateLimit(limit);
		List<PaymentOperationManualReviewQueueItem> fetched = manualReviewReader.readOldest(limit + 1);
		boolean hasMore = fetched.size() > limit;
		return new PaymentOperationManualReviewQueue(fetched.stream().limit(limit).toList(), hasMore);
	}

	private void validateLimit(int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new InvalidInputException("limit must be between 1 and " + MAX_LIMIT);
		}
	}
}
