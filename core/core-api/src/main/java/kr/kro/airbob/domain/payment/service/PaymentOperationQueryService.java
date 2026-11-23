package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.PaymentOperationDetail;
import kr.kro.airbob.domain.payment.PaymentOperationDetailRow;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentOperationQueryService {

	private final PaymentOperationReader operationReader;
	private final Clock clock;

	@Transactional(readOnly = true)
	public PaymentOperationDetail find(UUID operationUid, Long memberId) {
		PaymentOperationDetailRow operation = operationReader.readDetail(operationUid);
		if (!Objects.equals(operation.requesterMemberId(), memberId)) {
			throw new PaymentAccessDeniedException();
		}
		return PaymentOperationDetail.of(operation, clock.instant());
	}
}
