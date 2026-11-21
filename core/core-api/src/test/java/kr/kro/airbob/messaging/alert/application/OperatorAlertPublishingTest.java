package kr.kro.airbob.messaging.alert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.messaging.alert.event.OperatorAlertKind;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSourcePosition;
import kr.kro.airbob.messaging.alert.event.OperatorAlertSummaryCode;
import kr.kro.airbob.messaging.alert.infrastructure.outbox.MysqlOperatorAlertOutboxAppender;

@ExtendWith(MockitoExtension.class)
@DisplayName("operator alert outbox publisher")
class OperatorAlertPublishingTest {

	private static final UUID SUBJECT_UID =
		UUID.fromString("a02c5725-79dd-4511-bec2-cdc529a1d36e");
	private static final UUID OCCURRENCE_UID =
		UUID.fromString("e50bad19-a938-48ca-9c15-42633dc41f31");

	@Mock private OperatorAlertOutboxAppender outboxAppender;

	@Test
	void appendsInsideTheCallersExistingTransactionWithoutOpeningANewOne() throws Exception {
		org.mockito.BDDMockito.given(outboxAppender.appendIfAbsent(
			org.mockito.ArgumentMatchers.any())).willReturn(true);
		OperatorAlertOutboxPublisher publisher =
			new OperatorAlertOutboxPublisher(outboxAppender);
		OperatorAlertRequest request = request();

		OperatorAlertPublication publication = publisher.append(request);

		ArgumentCaptor<OperatorAlertRequestedV1> event =
			ArgumentCaptor.forClass(OperatorAlertRequestedV1.class);
		then(outboxAppender).should().appendIfAbsent(event.capture());
		assertThat(publication.appended()).isTrue();
		assertThat(publication.alertUid()).isEqualTo(event.getValue().alertUid());
		assertThat(OperatorAlertOutboxPublisher.class
			.getMethod("append", OperatorAlertRequest.class)
			.getAnnotation(Transactional.class)).isNull();
	}

	@Test
	void ignoresASequentialReplayWithTheSameDedupeIdentity() {
		OperatorAlertRequest request = request();
		OperatorAlertRequestedV1 expected = OperatorAlertRequestedV1.create(
			request.kind(), request.subjectUid(), request.summaryCode(),
			request.sourcePosition(), request.occurrenceUid());
		org.mockito.BDDMockito.given(outboxAppender.appendIfAbsent(
			org.mockito.ArgumentMatchers.any())).willReturn(false);
		OperatorAlertOutboxPublisher publisher =
			new OperatorAlertOutboxPublisher(outboxAppender);

		OperatorAlertPublication replay = publisher.append(request);

		assertThat(replay).isEqualTo(
			new OperatorAlertPublication(expected.alertUid(), false));
		then(outboxAppender).should().appendIfAbsent(
			org.mockito.ArgumentMatchers.argThat(event ->
				event.deduplicationKey().equals(expected.deduplicationKey())));
	}

	@Test
	void enqueueServiceStartsARequiredTransactionForKafkaDltCallers() throws Exception {
		Transactional transactional = OperatorAlertEnqueueService.class
			.getMethod("enqueue", OperatorAlertRequest.class)
			.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);

		Transactional appenderTransaction = MysqlOperatorAlertOutboxAppender.class
			.getMethod("appendIfAbsent", OperatorAlertRequestedV1.class)
			.getAnnotation(Transactional.class);
		assertThat(appenderTransaction).isNotNull();
		assertThat(appenderTransaction.propagation()).isEqualTo(Propagation.MANDATORY);
	}

	private OperatorAlertRequest request() {
		return new OperatorAlertRequest(
			OperatorAlertKind.PAYMENT_OPERATION_QUARANTINED,
			SUBJECT_UID,
			OperatorAlertSummaryCode.MESSAGE_PROCESSING_FAILED,
			new OperatorAlertSourcePosition("PAYMENT_OPERATION.events", 1, 7L),
			OCCURRENCE_UID
		);
	}
}
