package kr.kro.airbob.domain.payment.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse.Detail;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationNotFoundException;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentOperationQueryService {

	private final PaymentOperationRepository repository;

	@Transactional(readOnly = true)
	public Detail find(UUID operationUid, Long memberId) {
		PaymentOperation operation = repository.findByOperationUid(operationUid)
			.orElseThrow(PaymentOperationNotFoundException::new);
		if (!operation.isRequestedBy(memberId)) {
			throw new PaymentAccessDeniedException();
		}
		return Detail.from(operation);
	}
}
