package kr.kro.airbob.messaging.alert.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatorAlertEnqueueService {

	private final OperatorAlertOutboxPublisher publisher;

	public OperatorAlertEnqueueService(OperatorAlertOutboxPublisher publisher) {
		this.publisher = publisher;
	}

	/** Transaction boundary intended for Kafka DLT handlers and other non-transactional callers. */
	@Transactional(propagation = Propagation.REQUIRED)
	public OperatorAlertPublication enqueue(OperatorAlertRequest request) {
		return publisher.append(request);
	}
}
