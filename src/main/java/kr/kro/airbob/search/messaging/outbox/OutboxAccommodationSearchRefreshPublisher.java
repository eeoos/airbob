package kr.kro.airbob.search.messaging.outbox;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import kr.kro.airbob.messaging.outbox.OutboxWriter;
import kr.kro.airbob.search.messaging.AccommodationSearchRefreshPublisher;
import kr.kro.airbob.search.messaging.event.AccommodationSearchRefreshRequestedV1;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OutboxAccommodationSearchRefreshPublisher
	implements AccommodationSearchRefreshPublisher {

	private final OutboxWriter outboxWriter;

	@Override
	public void requestRefresh(UUID accommodationUid) {
		outboxWriter.append(new AccommodationSearchRefreshRequestedV1(
			Objects.requireNonNull(accommodationUid, "accommodationUid must not be null")));
	}
}
