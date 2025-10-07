package kr.kro.airbob.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import kr.kro.airbob.outbox.SlackNotificationService;
import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

	private final KafkaTemplate<String, Object> kafkaTemplate;
	private final SlackNotificationService slackNotificationService;

	@Bean
	public DefaultErrorHandler errorHandler() {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
			(consumerRecord, exception) -> {
				sendSlackAlert(consumerRecord, exception);
				return null;
			}
		);

		ExponentialBackOff backOff = new ExponentialBackOff(2000L, 2.0);
		backOff.setMaxInterval(10000L);
		backOff.setMaxElapsedTime(60000L);

		return new DefaultErrorHandler(recoverer, backOff);
	}

	private void sendSlackAlert(ConsumerRecord<?, ?> record, Exception exception) {
		String message = String.format(
			"""
			🚨 *Kafka DLQ Alert* 🚨
			메시지 처리에 최종 실패하여 DLQ로 이동합니다.

			• *Topic*: `%s`
			• *Partition*: `%d`
			• *Offset*: `%d`
			• *Key*: `%s`
			• *Exception*: `%s`
			• *Error Message*: ```%s```""",
			record.topic(),
			record.partition(),
			record.offset(),
			record.key(),
			exception.getClass().getSimpleName(),
			exception.getMessage()
		);
		slackNotificationService.sendAlert(message);
		// TODO: 디스코드로 변경 필요
	}
}
