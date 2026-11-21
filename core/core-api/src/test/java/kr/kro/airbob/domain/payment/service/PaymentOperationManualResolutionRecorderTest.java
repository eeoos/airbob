package kr.kro.airbob.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationResolutionAction;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.repository.PaymentOperationResolutionRepository;
import kr.kro.airbob.messaging.alert.application.OperatorAlertOutboxPublisher;

@SpringJUnitConfig(PaymentOperationManualResolutionRecorderTest.TestConfiguration.class)
class PaymentOperationManualResolutionRecorderTest {

	@Autowired private PaymentOperationManualResolutionRecorder recorder;
	@Autowired private PaymentOperationResolutionRepository repository;
	@Autowired private OperatorAlertOutboxPublisher alertPublisher;

	@Test
	void refusesToWriteAnyAuditOrAlertOutsideAnOwningTransaction() {
		PaymentOperation operation = PaymentOperation.builder()
			.operationUid(UUID.fromString("e5ce655a-84b2-4cc4-b97a-07ad1b8d2952"))
			.dispatchGeneration(3)
			.build();

		assertThatThrownBy(() -> recorder.recordSystem(
			operation,
			PaymentOperationResolutionAction.RECONCILIATION_RETURNED_TO_REVIEW,
			"RECONCILIATION_ATTEMPTS_EXHAUSTED",
			PaymentOperationStatus.EXECUTING,
			PaymentOperationStatus.MANUAL_REVIEW,
			Instant.parse("2026-08-17T00:00:00Z")))
			.isInstanceOf(IllegalTransactionStateException.class);

		verifyNoInteractions(repository, alertPublisher);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class TestConfiguration {

		@Bean
		PaymentOperationResolutionRepository repository() {
			return mock(PaymentOperationResolutionRepository.class);
		}

		@Bean
		OperatorAlertOutboxPublisher alertPublisher() {
			return mock(OperatorAlertOutboxPublisher.class);
		}

		@Bean
		PaymentOperationManualResolutionRecorder recorder(
			PaymentOperationResolutionRepository repository,
			OperatorAlertOutboxPublisher alertPublisher
		) {
			return new PaymentOperationManualResolutionRecorder(repository, alertPublisher);
		}

		@Bean
		PlatformTransactionManager transactionManager() {
			return new RecordingTransactionManager();
		}
	}

	private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
		}
	}
}
