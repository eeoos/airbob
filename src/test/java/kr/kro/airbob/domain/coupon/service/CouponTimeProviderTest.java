package kr.kro.airbob.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CouponTimeProviderTest {

	private final CouponTimeProvider timeProvider = new CouponTimeProvider();

	@Test
	@DisplayName("한국 로컬 쿠폰 시각을 호스트 시간대와 무관한 epoch millisecond로 변환한다")
	void convertsKoreanCampaignTimeToEpochMilliseconds() {
		LocalDateTime koreanTenAm = LocalDateTime.of(2026, 7, 18, 10, 0);

		assertThat(timeProvider.toEpochMilli(koreanTenAm))
			.isEqualTo(Instant.parse("2026-07-18T01:00:00Z").toEpochMilli());
	}
}
