package kr.kro.airbob.domain.payment.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.payment.service.PaymentOperationAlertService;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewNotice;
import kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryService;
import kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryService.RecoveryBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentOperationRecoveryScheduler {
	private final PaymentOperationRecoveryService recoveryService;
	private final PaymentOperationAlertService alertService;

	@Scheduled(fixedDelayString = "${payment.operation.scheduler-delay:10s}")
	public void recoverPaymentOperations() {
		RecoveryBatch batch = recoveryService.recoverDue();
		batch.manualReviews().forEach(this::alertManualReview);
	}

	private void alertManualReview(PaymentOperationManualReviewNotice notice) {
		try {
			alertService.alertManualReview(notice);
		} catch (RuntimeException alertFailure) {
			log.error(
				"payment-operation manual-review alert failed. operationUid={}",
				notice.operationUid()
			);
		}
	}
}
