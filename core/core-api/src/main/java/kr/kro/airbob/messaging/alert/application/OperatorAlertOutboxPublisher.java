package kr.kro.airbob.messaging.alert.application;

import org.springframework.stereotype.Component;

import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;

@Component
public class OperatorAlertOutboxPublisher {

	private final OperatorAlertOutboxAppender outboxAppender;

	public OperatorAlertOutboxPublisher(OperatorAlertOutboxAppender outboxAppender) {
		this.outboxAppender = outboxAppender;
	}

	/** Joins the caller's transition transaction through the MANDATORY outbox appender. */
	public OperatorAlertPublication append(OperatorAlertRequest request) {
		OperatorAlertRequestedV1 event = createEvent(request);
		boolean appended = outboxAppender.appendIfAbsent(event);
		return new OperatorAlertPublication(event.alertUid(), appended);
	}

	private OperatorAlertRequestedV1 createEvent(OperatorAlertRequest request) {
		return OperatorAlertRequestedV1.create(
			request.kind(),
			request.subjectUid(),
			request.summaryCode(),
			request.sourcePosition(),
			request.occurrenceUid()
		);
	}
}
