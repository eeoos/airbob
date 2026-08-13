package kr.kro.airbob.domain.accommodation.cache;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("숙소 상세 캐시 설정 테스트")
class AccommodationDetailCachePropertiesTest {

	@Test
	@DisplayName("TTL과 대기 시간이 0 이하이면 시작 시 설정을 거부한다")
	void rejectsNonPositiveTtlAndLockWait() {
		assertThatThrownBy(() -> properties(Duration.ZERO, Duration.ofSeconds(2)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(Duration.ofMinutes(10), Duration.ZERO))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AccommodationDetailCacheProperties(
			Duration.ofMinutes(10), Duration.ofMinutes(2),
			Duration.ofSeconds(45), Duration.ofSeconds(15),
			Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ZERO,
			Duration.ofSeconds(1), Duration.ofSeconds(1)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private AccommodationDetailCacheProperties properties(Duration ttl, Duration lockWait) {
		return new AccommodationDetailCacheProperties(
			ttl,
			Duration.ofMinutes(2),
			Duration.ofSeconds(45),
			Duration.ofSeconds(15),
			lockWait,
			Duration.ofSeconds(5),
			Duration.ofSeconds(30),
			Duration.ofSeconds(1),
			Duration.ofSeconds(1));
	}
}
