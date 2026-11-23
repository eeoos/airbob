package kr.kro.airbob.core.domain.search.messaging.outbox;

import java.util.UUID;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.search.messaging.AccommodationSearchRefreshPublisher;
import kr.kro.airbob.core.domain.search.messaging.event.AccommodationSearchRefreshRequestedV1;
import kr.kro.airbob.messaging.outbox.application.OutboxWriter;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OutboxAccommodationSearchRefreshPublisher
	implements AccommodationSearchRefreshPublisher {

	private final OutboxWriter outboxWriter;

	@Override
	public void requestRefresh(UUID accommodationUid) {
		outboxWriter.append(new AccommodationSearchRefreshRequestedV1(accommodationUid));
	}
}
