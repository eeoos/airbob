package kr.kro.airbob.domain.payment.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminResponse.ManualReviewItem;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminResponse.ManualReviewQueue;
import kr.kro.airbob.domain.payment.repository.PaymentOperationManualReviewQueryRepository;
import kr.kro.airbob.domain.payment.repository.projection.PaymentOperationManualReviewQueueItem;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentOperationManualReviewQueryService {

	public static final int MAX_LIMIT = 100;

	private final PaymentOperationManualReviewQueryRepository repository;

	@Transactional(readOnly = true)
	public ManualReviewQueue findManualReviewQueue(int limit) {
		validateLimit(limit);
		List<PaymentOperationManualReviewQueueItem> fetched = repository.findOldest(limit + 1);
		boolean hasMore = fetched.size() > limit;
		List<ManualReviewItem> items = fetched.stream()
			.limit(limit)
			.map(ManualReviewItem::from)
			.toList();
		return new ManualReviewQueue(items, hasMore);
	}

	private void validateLimit(int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new InvalidInputException("limit must be between 1 and " + MAX_LIMIT);
		}
	}
}
