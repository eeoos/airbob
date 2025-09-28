package kr.kro.airbob.outbox;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.outbox.entity.Outbox;
import kr.kro.airbob.outbox.exception.OutboxEventPublishingException;
import kr.kro.airbob.outbox.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher {

	private final OutboxRepository outboxRepository;
	private final ObjectMapper objectMapper;

	public void save(EventType eventType, EventPayload payload) {
		try {
			String serializedPayload = objectMapper.writeValueAsString(payload);

			Outbox outboxEvent = Outbox.create(eventType.getAggregateType(), payload.getId(), eventType.name(),
				serializedPayload);

			outboxRepository.save(outboxEvent);
		} catch (Exception e) {
			log.error("Outbox 이벤트 저장 실패: eventType={}, aggregateId={}, error={}",
				eventType.name(), payload.getId(), e.getMessage());
			throw new OutboxEventPublishingException(e);
		}
	}
}
