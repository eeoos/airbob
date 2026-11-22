package kr.kro.airbob.search.messaging;

import java.util.UUID;

import kr.kro.airbob.domain.accommodation.port.AccommodationSearchRefreshRequester;

public interface AccommodationSearchRefreshPublisher extends AccommodationSearchRefreshRequester {

	@Override
	void requestRefresh(UUID accommodationUid);
}
