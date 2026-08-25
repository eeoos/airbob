package kr.kro.airbob.messaging.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;

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

	private static final String TRAFFIC_BENCHMARK_EXCLUSION = "!traffic-benchmark";
	private static final List<Class<?>> DOMAIN_KAFKA_BEAN_TYPES = List.of(
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

	@Test
	void excludesEveryDomainKafkaBeanFromTrafficBenchmark() {
		assertThat(DOMAIN_KAFKA_BEAN_TYPES).allSatisfy(beanType -> {
			Profile profile = AnnotatedElementUtils.findMergedAnnotation(beanType, Profile.class);

			assertThat(profile)
				.as("%s profile", beanType.getSimpleName())
				.isNotNull();
			assertThat(profile.value())
				.as("%s profile expressions", beanType.getSimpleName())
				.containsExactly(TRAFFIC_BENCHMARK_EXCLUSION);
		});
	}
}
