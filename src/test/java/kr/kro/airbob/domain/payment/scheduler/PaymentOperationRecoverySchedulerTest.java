package kr.kro.airbob.domain.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import kr.kro.airbob.domain.payment.service.PaymentOperationAlertService;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewNotice;
import kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryService;
import kr.kro.airbob.domain.payment.service.PaymentOperationRecoveryService.RecoveryBatch;
import kr.kro.airbob.outbox.SlackNotificationService;

@ExtendWith(MockitoExtension.class)
class PaymentOperationRecoverySchedulerTest {

	private static final UUID FIRST_UID =
		UUID.fromString("61518730-ec87-4710-91cc-0d93675c2bc4");
	private static final UUID SECOND_UID =
		UUID.fromString("c2d0a00b-f353-409d-8cd8-72e3a484d19d");

	@Mock private PaymentOperationRecoveryService recoveryService;
	@Mock private PaymentOperationAlertService alertService;

	private PaymentOperationRecoveryScheduler scheduler;

	@BeforeEach
	void setUp() {
		scheduler = new PaymentOperationRecoveryScheduler(recoveryService, alertService);
	}

	@Test
	void delegatesOneRecoveryBatchAndAlertsEveryManualReviewAfterward() {
		PaymentOperationManualReviewNotice first = new PaymentOperationManualReviewNotice(FIRST_UID);
		PaymentOperationManualReviewNotice second = new PaymentOperationManualReviewNotice(SECOND_UID);
		given(recoveryService.recoverDue()).willReturn(new RecoveryBatch(3, List.of(first, second)));

		scheduler.recoverPaymentOperations();

		then(recoveryService).should().recoverDue();
		then(alertService).should().alertManualReview(first);
		then(alertService).should().alertManualReview(second);
	}

	@Test
	void alertFailureCannotFailRecoveryOrPreventRemainingSanitizedNotices() {
		PaymentOperationManualReviewNotice first = new PaymentOperationManualReviewNotice(FIRST_UID);
		PaymentOperationManualReviewNotice second = new PaymentOperationManualReviewNotice(SECOND_UID);
		given(recoveryService.recoverDue()).willReturn(new RecoveryBatch(0, List.of(first, second)));
		willThrow(new IllegalStateException("slack unavailable paymentKey=sensitive"))
			.given(alertService).alertManualReview(first);
		Logger logger = (Logger)LoggerFactory.getLogger(PaymentOperationRecoveryScheduler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			assertThatCode(scheduler::recoverPaymentOperations).doesNotThrowAnyException();
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}

		then(alertService).should().alertManualReview(second);
		assertThat(appender.list).singleElement().satisfies(event -> {
			assertThat(event.getFormattedMessage())
				.contains(FIRST_UID.toString())
				.doesNotContain("paymentKey", "sensitive", "slack unavailable");
			assertThat(event.getThrowableProxy()).isNull();
		});
	}

	@Test
	void usesTheConfiguredTenSecondFixedDelayContract() throws NoSuchMethodException {
		Method method = PaymentOperationRecoveryScheduler.class.getMethod("recoverPaymentOperations");

		Scheduled scheduled = method.getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.fixedDelayString())
			.isEqualTo("${payment.operation.scheduler-delay:10s}");
	}

	@Test
	void manualReviewAlertContainsOnlyTheOperationRecoveryIdentifier() {
		SlackNotificationService slackNotificationService =
			org.mockito.Mockito.mock(SlackNotificationService.class);
		PaymentOperationAlertService service = new PaymentOperationAlertService(slackNotificationService);

		service.alertManualReview(new PaymentOperationManualReviewNotice(FIRST_UID));

		ArgumentCaptor<String> alert = ArgumentCaptor.forClass(String.class);
		then(slackNotificationService).should().sendAlert(alert.capture());
		assertThat(alert.getValue())
			.contains("PAYMENT_EXECUTION_REQUESTED_V1", FIRST_UID.toString())
			.doesNotContain(
				"paymentKey", "providerIdempotencyKey", "failureCode", "failureMessage",
				"card", "account");
	}
}
