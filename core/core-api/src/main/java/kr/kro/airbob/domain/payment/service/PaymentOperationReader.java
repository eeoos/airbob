package kr.kro.airbob.domain.payment.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.PaymentOperationDetailRow;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.exception.PaymentOperationNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import lombok.RequiredArgsConstructor;

/** 결제 operation 조회 구현 컴포넌트. */
@Component
@RequiredArgsConstructor
public class PaymentOperationReader {

	private final PaymentOperationRepository operationRepository;

	public Optional<PaymentOperation> findByDeduplicationKey(String deduplicationKey) {
		return operationRepository.findByDeduplicationKey(deduplicationKey);
	}

	/** 예약의 가장 최근 취소 operation. 잠그지 않는다. */
	public Optional<PaymentOperation> findLatestCancellation(Long reservationId) {
		return operationRepository.findFirstByReservationIdAndOperationTypeOrderByIdDesc(
			reservationId, PaymentOperationType.CANCEL);
	}

	/** 회원 조회용 operation과 예약 상태를 한 쿼리로 읽는다. 없으면 {@link PaymentOperationNotFoundException}. */
	public PaymentOperationDetailRow readDetail(UUID operationUid) {
		return operationRepository.findDetailByOperationUid(operationUid)
			.orElseThrow(PaymentOperationNotFoundException::new);
	}

	public Optional<PaymentOperation> findForUpdate(UUID operationUid) {
		return operationRepository.findByOperationUidWithLock(operationUid);
	}

	/** 임대가 끝났거나 재시도 시각이 된 operation을 최대 batchSize건 잠가 읽는다. */
	public List<PaymentOperation> readRecoverableForUpdate(Instant now, int batchSize) {
		return operationRepository.findRecoverableForUpdate(now, batchSize);
	}
}
