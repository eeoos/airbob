package kr.kro.airbob.core.domain.payment;

import java.util.List;

/** 수동 확인 대기 operation 중 가장 오래된 묶음. hasMore는 요청한 수를 넘는 대기 작업이 있는지를 뜻한다. */
public record PaymentOperationManualReviewQueue(
	List<PaymentOperationManualReviewQueueItem> items,
	boolean hasMore
) {

	public PaymentOperationManualReviewQueue {
		items = List.copyOf(items);
	}
}
