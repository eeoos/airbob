package kr.kro.airbob.geo.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import net.iakovlev.timeshape.TimeZoneEngine;

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

	@Test
	void NaN_좌표는_빈_결과를_반환한다() {
		assertThat(resolver.resolve(Double.NaN, 126.9780)).isEmpty();
		assertThat(resolver.resolve(37.5665, Double.NaN)).isEmpty();
	}

	@Test
	void NaN_좌표는_시간대_엔진을_호출하지_않는다() {
		TimeZoneEngine engine = mock(TimeZoneEngine.class);
		when(engine.query(anyDouble(), anyDouble()))
			.thenThrow(new AssertionError("유효하지 않은 좌표로 엔진을 호출하면 안 된다"));
		TimeShapeTimeZoneResolver guardedResolver = new TimeShapeTimeZoneResolver(engine);

		assertThat(guardedResolver.resolve(Double.NaN, 126.9780)).isEmpty();
		assertThat(guardedResolver.resolve(37.5665, Double.NaN)).isEmpty();
	}

	@Test
	void 무한대_좌표는_빈_결과를_반환한다() {
		assertThat(resolver.resolve(Double.POSITIVE_INFINITY, 126.9780)).isEmpty();
		assertThat(resolver.resolve(Double.NEGATIVE_INFINITY, 126.9780)).isEmpty();
		assertThat(resolver.resolve(37.5665, Double.POSITIVE_INFINITY)).isEmpty();
		assertThat(resolver.resolve(37.5665, Double.NEGATIVE_INFINITY)).isEmpty();
	}

	@Test
	void 범위를_벗어난_경도는_빈_결과를_반환한다() {
		assertThat(resolver.resolve(0.0, 181.0)).isEmpty();
		assertThat(resolver.resolve(0.0, -181.0)).isEmpty();
	}
}
