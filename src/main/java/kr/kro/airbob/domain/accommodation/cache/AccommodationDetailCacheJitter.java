package kr.kro.airbob.domain.accommodation.cache;

import java.util.concurrent.ThreadLocalRandom;

public class AccommodationDetailCacheJitter {

	public long nextMillis(long boundExclusive) {
		return ThreadLocalRandom.current().nextLong(boundExclusive);
	}
}
