package kr.kro.airbob.domain.payment.service;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Detail;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import kr.kro.airbob.domain.payment.repository.projection.PaymentOperationDetailRow;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentOperationQueryService {

	private final PaymentOperationRepository repository;
	private final Clock clock;

	@Transactional(readOnly = true)
	public Detail find(UUID operationUid, Long memberId) {
		PaymentOperationDetailRow operation = repository.findDetailByOperationUid(operationUid)
			.orElseThrow(PaymentOperationNotFoundException::new);
		if (!Objects.equals(operation.requesterMemberId(), memberId)) {
			throw new PaymentAccessDeniedException();
		}
		return Detail.from(operation, clock.instant());
	}
}
