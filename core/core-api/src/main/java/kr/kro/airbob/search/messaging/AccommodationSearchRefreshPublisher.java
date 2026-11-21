package kr.kro.airbob.search.messaging;

import java.util.UUID;

public interface AccommodationSearchRefreshPublisher {

	void requestRefresh(UUID accommodationUid);
}
