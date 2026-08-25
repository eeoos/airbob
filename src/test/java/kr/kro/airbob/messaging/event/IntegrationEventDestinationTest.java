package kr.kro.airbob.messaging.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import kr.kro.airbob.domain.payment.messaging.event.PaymentOperationExecutionRequestedV1;
import kr.kro.airbob.domain.accommodation.cache.messaging.event.AccommodationDetailCacheInvalidationRequestedV1;
import kr.kro.airbob.messaging.alert.event.OperatorAlertRequestedV1;
import kr.kro.airbob.search.messaging.event.AccommodationSearchRefreshRequestedV1;

class IntegrationEventDestinationTest {

	@Test
	void eventTopicsAndDescriptorsShareCanonicalDestinations() {
		assertThat(PaymentOperationExecutionRequestedV1.TOPIC)
			.isEqualTo(IntegrationEventDestination.PAYMENT_OPERATION.topic());
		assertThat(PaymentOperationExecutionRequestedV1.DESCRIPTOR.destination())
			.isEqualTo(IntegrationEventDestination.PAYMENT_OPERATION.topic());
		assertThat(AccommodationSearchRefreshRequestedV1.TOPIC)
			.isEqualTo(IntegrationEventDestination.ACCOMMODATION_INDEX.topic());
		assertThat(AccommodationSearchRefreshRequestedV1.DESCRIPTOR.destination())
			.isEqualTo(IntegrationEventDestination.ACCOMMODATION_INDEX.topic());
		assertThat(AccommodationDetailCacheInvalidationRequestedV1.TOPIC)
			.isEqualTo(IntegrationEventDestination.ACCOMMODATION_CACHE.topic());
		assertThat(AccommodationDetailCacheInvalidationRequestedV1.DESCRIPTOR.destination())
			.isEqualTo(IntegrationEventDestination.ACCOMMODATION_CACHE.topic());
		assertThat(OperatorAlertRequestedV1.TOPIC)
			.isEqualTo(IntegrationEventDestination.OPERATOR_ALERT.topic());
		assertThat(OperatorAlertRequestedV1.DESCRIPTOR.destination())
			.isEqualTo(IntegrationEventDestination.OPERATOR_ALERT.topic());
	}

	@Test
	void onlyDomainDltStreamsAreOperatorAlertSources() {
		assertThat(IntegrationEventDestination.isOperatorAlertDltSource(
			IntegrationEventDestination.PAYMENT_OPERATION.topic())).isTrue();
		assertThat(IntegrationEventDestination.isOperatorAlertDltSource(
			IntegrationEventDestination.ACCOMMODATION_INDEX.topic())).isTrue();
		assertThat(IntegrationEventDestination.isOperatorAlertDltSource(
			IntegrationEventDestination.ACCOMMODATION_CACHE.topic())).isTrue();
		assertThat(IntegrationEventDestination.isOperatorAlertDltSource(
			IntegrationEventDestination.OPERATOR_ALERT.topic())).isFalse();
		assertThat(IntegrationEventDestination.isOperatorAlertDltSource(
			"EVIL.events")).isFalse();
	}
}
