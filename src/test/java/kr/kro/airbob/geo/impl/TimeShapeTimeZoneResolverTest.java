package kr.kro.airbob.geo.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class TimeShapeTimeZoneResolverTest {

	private final TimeShapeTimeZoneResolver resolver = new TimeShapeTimeZoneResolver();

	@Test
	void 서울_좌표를_AsiaSeoul로_해석한다() {
		assertThat(resolver.resolve(37.5665, 126.9780))
			.contains(ZoneId.of("Asia/Seoul"));
	}

	@Test
	void 뉴욕_좌표를_AmericaNewYork로_해석한다() {
		assertThat(resolver.resolve(40.7128, -74.0060))
			.contains(ZoneId.of("America/New_York"));
	}

	@Test
	void 범위를_벗어난_위도는_빈_결과를_반환한다() {
		assertThat(resolver.resolve(91.0, 0.0)).isEmpty();
	}
}
