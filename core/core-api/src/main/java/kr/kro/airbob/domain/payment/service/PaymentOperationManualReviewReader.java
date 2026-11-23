package kr.kro.airbob.domain.payment.service;

import java.util.List;

import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.PaymentOperationManualReviewQueueItem;
import kr.kro.airbob.domain.payment.repository.PaymentOperationManualReviewQueryRepository;
import lombok.RequiredArgsConstructor;

/** 수동 확인 대기열 조회 구현 컴포넌트. */
@Component
@RequiredArgsConstructor
public class PaymentOperationManualReviewReader {

	private final PaymentOperationManualReviewQueryRepository queryRepository;

	/** 수동 확인 진입 시각·내부 ID 오름차순으로 최대 size건을 읽는다. */
	public List<PaymentOperationManualReviewQueueItem> readOldest(int size) {
		return queryRepository.findOldest(size);
	}
}
