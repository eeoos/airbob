package kr.kro.airbob.domain.accommodation.cache.config;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 캐시 만료 시점을 분산하는 난수를 제공
 * 별도 타입으로 둬 TTL 계산을 결정적으로 테스트
 */
public class AccommodationDetailCacheJitter {

	public long nextMillis(long boundExclusive) {
		return ThreadLocalRandom.current().nextLong(boundExclusive);
	}
}
