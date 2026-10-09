package kr.kro.airbob.messaging.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import kr.kro.airbob.domain.accommodation.cache.messaging.kafka.AccommodationDetailCacheInvalidationKafkaListener;
import kr.kro.airbob.domain.accommodation.cache.messaging.kafka.AccommodationDetailCacheKafkaConsumerConfiguration;
import kr.kro.airbob.domain.accommodation.cache.messaging.kafka.AccommodationDetailCacheKafkaRetryPublisherConfiguration;
import kr.kro.airbob.domain.payment.messaging.kafka.PaymentOperationExecutionListener;
import kr.kro.airbob.domain.payment.messaging.kafka.PaymentOperationKafkaConsumerConfiguration;
import kr.kro.airbob.domain.payment.messaging.kafka.PaymentOperationKafkaRetryPublisherConfiguration;
import kr.kro.airbob.messaging.alert.infrastructure.kafka.OperatorAlertKafkaConsumerConfiguration;
import kr.kro.airbob.messaging.alert.infrastructure.kafka.OperatorAlertKafkaListener;
import kr.kro.airbob.messaging.alert.infrastructure.kafka.OperatorAlertKafkaPublisherConfiguration;
import kr.kro.airbob.search.messaging.kafka.AccommodationSearchKafkaConsumerConfiguration;
import kr.kro.airbob.search.messaging.kafka.AccommodationSearchKafkaRetryPublisherConfiguration;
import kr.kro.airbob.search.messaging.kafka.AccommodationSearchRefreshListener;

@DisplayName("도메인 Kafka 프로파일 경계 계약")
class DomainKafkaProfileContractTest {

	private static final List<Class<?>> DOMAIN_KAFKA_BEAN_TYPES = List.of(
		MessagingKafkaConfiguration.class,
		PaymentOperationExecutionListener.class,
		PaymentOperationKafkaConsumerConfiguration.class,
		PaymentOperationKafkaRetryPublisherConfiguration.class,
		AccommodationSearchRefreshListener.class,
		AccommodationSearchKafkaConsumerConfiguration.class,
		AccommodationSearchKafkaRetryPublisherConfiguration.class,
		OperatorAlertKafkaListener.class,
		OperatorAlertKafkaConsumerConfiguration.class,
		OperatorAlertKafkaPublisherConfiguration.class,
		AccommodationDetailCacheInvalidationKafkaListener.class,
		AccommodationDetailCacheKafkaConsumerConfiguration.class,
		AccommodationDetailCacheKafkaRetryPublisherConfiguration.class
	);

	@ParameterizedTest
	@ValueSource(strings = {"traffic-benchmark", "cache-benchmark"})
	void excludesEveryDomainKafkaBeanFromReadOnlyBenchmarks(String profile) {
		new ApplicationContextRunner()
			.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
			.withUserConfiguration(DOMAIN_KAFKA_BEAN_TYPES.toArray(Class<?>[]::new))
			.withPropertyValues(
				"spring.kafka.listener.auto-startup=true",
				"operator-alert.kafka.auto-startup=true",
				"accommodation.indexing.kafka.auto-startup=true",
				"accommodation.detail-cache.invalidation.kafka.auto-startup=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(DOMAIN_KAFKA_BEAN_TYPES).allSatisfy(beanType ->
					assertThat(context).doesNotHaveBean(beanType));
			});
	}
}
