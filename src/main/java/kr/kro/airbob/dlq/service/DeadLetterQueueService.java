package kr.kro.airbob.dlq.service;

import static kr.kro.airbob.dlq.entity.FailedIndexingEvent.EventStatus.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.dlq.entity.FailedIndexingEvent;
import kr.kro.airbob.dlq.repository.FailedEventRepository;
import kr.kro.airbob.dlq.reprocessor.AccommodationEventReprocessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class DeadLetterQueueService {

	private final FailedEventRepository failedEventRepository;
	private final AccommodationEventReprocessor reprocessor;
	private final SlackNotificationService slackService;
	private final ObjectMapper objectMapper;

	private static final int MAX_RETRY_COUNT = 5;

	@Transactional
	public void saveFailedEvent(String eventType, Object eventData, Exception exception) {
		String errorMessage = extractErrorMessage(exception);

		try {
			String serializedEventData = objectMapper.writeValueAsString(eventData);
			FailedIndexingEvent failedEvent =
				FailedIndexingEvent.create(eventType, serializedEventData, errorMessage);

			failedEventRepository.save(failedEvent);

			log.warn("이벤트 처리 실패로 DLQ에 저장: eventType={}, error={}",
				eventType, errorMessage);
		} catch (Exception e) {
			log.error("이벤트 직렬화 실패: eventType={}, error={}", eventType, e.getMessage(), e);
			throw new IllegalArgumentException("이벤트 데이터 직렬화 실패", e);
		}
	}

	@Scheduled(fixedDelay = 300000) // 5분
	@Transactional
	public void retryFailedEvents() {
		LocalDateTime now = LocalDateTime.now();
		List<FailedIndexingEvent> eventsToRetry =
			failedEventRepository.findEventsReadyForRetry(now, MAX_RETRY_COUNT);

		if (eventsToRetry.isEmpty()) {
			return;
		}

		for (FailedIndexingEvent failedEvent : eventsToRetry) {
			try {
				failedEvent.updateStatus(RETRYING);
				failedEventRepository.save(failedEvent);

				boolean success = reprocessEvent(failedEvent);

				if (success) {
					failedEvent.updateStatus(PROCESSED);
					failedEventRepository.save(failedEvent);
				} else {
					handleRetryFailure(failedEvent);
				}
			} catch (Exception e) {
				log.error("이벤트 재처리 중 예외 발생: id={}", failedEvent.getId(), e);
				handleRetryFailure(failedEvent);
			}
		}
	}


	@Scheduled(fixedDelay = 3600000)
	@Transactional
	public void processDeadLetters() {
		List<FailedIndexingEvent> deadLetterCandidates =
			failedEventRepository.findDeadLetterCandidates(MAX_RETRY_COUNT);

		for (FailedIndexingEvent event : deadLetterCandidates) {

			event.markAsDeadLetter();
			failedEventRepository.save(event);

			sendDeadLetterAlert(event);

			log.error("이벤트가 Dead Letter로 이동: id={}, eventType={}, retryCount={}",
				event.getId(), event.getEventType(), event.getRetryCount());
		}
	}

	private void sendDeadLetterAlert(FailedIndexingEvent event) {
		String message = String.format("""
		🚨 *Dead Letter Alert* 🚨
		• Event Type: `%s`
		• Event ID: `%d`
		• Retry Count: `%d`
		• First Failed: `%s`
		• Error: ```%s```""",
			event.getEventType(),
			event.getId(),
			event.getRetryCount(),
			event.getFailedAt(),
			event.getErrorMessage()
		);

		// slackService.sendAlert(message);
		// TODO: 디스코드로 변경 필요
	}

	private void handleRetryFailure(FailedIndexingEvent failedEvent) {
		failedEvent.incrementRetryCount();
		failedEvent.updateStatus(FAILED);
		failedEvent.updateErrorMessage(failedEvent.getErrorMessage() +
			"\n[재시도 " + failedEvent.getRetryCount() + "회 실패 at " +
			LocalDateTime.now() + "]");
		failedEventRepository.save(failedEvent);
	}

	private boolean reprocessEvent(FailedIndexingEvent failedEvent) {
		String eventType = failedEvent.getEventType();
		String eventData = failedEvent.getEventData();

		return reprocessor.reprocess(eventType, eventData);
	}

	private String extractErrorMessage(Exception e) {
		if(e == null) return "Unknown error";

		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		return sw.toString();
	}
}
