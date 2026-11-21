package kr.kro.airbob.search.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.argThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.messaging.outbox.application.OutboxWriter;
import kr.kro.airbob.search.messaging.event.AccommodationSearchRefreshRequestedV1;
import kr.kro.airbob.search.messaging.outbox.OutboxAccommodationSearchRefreshPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("숙소 검색 refresh 발행 계약")
class AccommodationSearchRefreshPublisherTest {

	private static final UUID ACCOMMODATION_UID =
		UUID.fromString("109cc081-b87d-4502-9a5e-7d7b65993056");

	@Mock private OutboxWriter outboxWriter;

	@Test
	@DisplayName("도메인은 협소한 포트로 단일 refresh 이벤트만 outbox에 추가한다")
	void appendsOneVersionedRefreshEvent() {
		AccommodationSearchRefreshPublisher publisher =
			new OutboxAccommodationSearchRefreshPublisher(outboxWriter);

		publisher.requestRefresh(ACCOMMODATION_UID);

		then(outboxWriter).should().append(argThat(event -> {
			AccommodationSearchRefreshRequestedV1 refresh =
				(AccommodationSearchRefreshRequestedV1) event;
			assertThat(refresh.accommodationUid()).isEqualTo(ACCOMMODATION_UID);
			assertThat(refresh.aggregateId()).isEqualTo(ACCOMMODATION_UID.toString());
			assertThat(refresh.partitionKey()).isEqualTo(ACCOMMODATION_UID.toString());
			assertThat(refresh.descriptor().destination()).isEqualTo("ACCOMMODATION_INDEX.events");
			assertThat(refresh.descriptor().aggregateType()).isEqualTo("ACCOMMODATION");
			assertThat(refresh.descriptor().eventType())
				.isEqualTo("ACCOMMODATION_SEARCH_REFRESH_REQUESTED");
			assertThat(refresh.descriptor().eventVersion()).isEqualTo("1");
			return true;
		}));
	}
}
