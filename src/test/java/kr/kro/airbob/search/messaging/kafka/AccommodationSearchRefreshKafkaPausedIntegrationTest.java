package kr.kro.airbob.search.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(AccommodationSearchRefreshKafkaIntegrationTest.KafkaTestConfiguration.class)
@TestPropertySource(properties = {
	"spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
	"spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
	"spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
	"spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
	"accommodation.indexing.kafka.group=accommodation-indexing-paused-group",
	"accommodation.indexing.kafka.attempts=2",
	"accommodation.indexing.kafka.backoff-ms=100",
	"accommodation.indexing.kafka.auto-startup=false"
})
@EmbeddedKafka(
	partitions = 1,
	topics = {
		AccommodationSearchRefreshKafkaIntegrationTest.INDEXING_TOPIC,
		AccommodationSearchRefreshKafkaIntegrationTest.INDEXING_RETRY_TOPIC,
		AccommodationSearchRefreshKafkaIntegrationTest.INDEXING_DLT_TOPIC
	},
	bootstrapServersProperty = "spring.kafka.bootstrap-servers",
	brokerProperties = "auto.create.topics.enable=false"
)
@DisplayName("숙소 색인 Kafka 재색인 중지 통합 테스트")
class AccommodationSearchRefreshKafkaPausedIntegrationTest {

	@Autowired private KafkaListenerEndpointRegistry registry;

	@Test
	@DisplayName("alias readiness가 false이면 main, retry, DLT listener가 모두 시작되지 않는다")
	void doesNotStartAnyIndexingListener() {
		assertThat(registry.getListenerContainers())
			.hasSize(3)
			.allSatisfy(container -> assertThat(container.isRunning()).isFalse());
	}
}
