package kr.kro.airbob.domain.payment.service;

import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.repository.PaymentOperationRepository;
import lombok.RequiredArgsConstructor;

/** 결제 operation 저장 구현 컴포넌트. 호출한 쪽 트랜잭션 안에서 돈다. */
@Component
@RequiredArgsConstructor
public class PaymentOperationWriter {

	private final PaymentOperationRepository operationRepository;

	public void create(PaymentOperation operation) {
		operationRepository.save(operation);
	}
}
