package kr.kro.airbob.domain.payment.service;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolution;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionAction;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.repository.PaymentOperationResolutionRepository;
import kr.kro.airbob.messaging.alert.application.OperatorAlertOutboxPublisher;
import kr.kro.airbob.messaging.alert.application.OperatorAlertRequest;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentOperationManualResolutionRecorder {

	private final PaymentOperationResolutionRepository resolutionRepository;
	private final OperatorAlertOutboxPublisher alertPublisher;

	public PaymentOperationManualResolutionRecorder(
		PaymentOperationResolutionRepository resolutionRepository,
		OperatorAlertOutboxPublisher alertPublisher
	) {
		this.resolutionRepository = resolutionRepository;
		this.alertPublisher = alertPublisher;
	}

	public void recordAdmin(
		PaymentOperation operation,
		Long actorMemberId,
		PaymentOperationResolutionAction action,
		String reason,
		String evidenceReference,
		PaymentOperationStatus previousStatus,
		PaymentOperationStatus resultStatus,
		Instant recordedAt
	) {
		resolutionRepository.save(PaymentOperationResolution.recordAdmin(
			operation,
			actorMemberId,
			action,
			reason,
			evidenceReference,
			previousStatus,
			resultStatus,
			recordedAt
		));
		appendAlert(operation, action);
	}

	public void recordSystem(
		PaymentOperation operation,
		PaymentOperationResolutionAction action,
		String reason,
		PaymentOperationStatus previousStatus,
		PaymentOperationStatus resultStatus,
		Instant recordedAt
	) {
		resolutionRepository.save(PaymentOperationResolution.recordSystem(
			operation,
			action,
			reason,
			null,
			previousStatus,
			resultStatus,
			recordedAt
		));
		appendAlert(operation, action);
	}

	private void appendAlert(PaymentOperation operation, PaymentOperationResolutionAction action) {
		OperatorAlertRequest request = switch (action) {
			case RECONCILIATION_REQUESTED -> OperatorAlertRequest.paymentReconciliationRequested(
				operation.getOperationUid(), operation.getDispatchGeneration());
			case RECONCILIATION_APPLIED -> OperatorAlertRequest.paymentReconciliationApplied(
				operation.getOperationUid(), operation.getDispatchGeneration());
			case RECONCILIATION_DECLINED -> OperatorAlertRequest.paymentReconciliationDeclined(
				operation.getOperationUid(), operation.getDispatchGeneration());
			case RECONCILIATION_RETURNED_TO_REVIEW ->
				OperatorAlertRequest.paymentReconciliationReturnedToReview(
					operation.getOperationUid(), operation.getDispatchGeneration());
			case MARKED_NOT_PAID -> OperatorAlertRequest.paymentMarkedNotPaid(
				operation.getOperationUid(), operation.getDispatchGeneration());
		};
		alertPublisher.append(request);
	}
}
