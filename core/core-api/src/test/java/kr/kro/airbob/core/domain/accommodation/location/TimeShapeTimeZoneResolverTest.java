package kr.kro.airbob.core.domain.accommodation.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.iakovlev.timeshape.TimeZoneEngine;

class TimeShapeTimeZoneResolverTest {

	private final TimeShapeTimeZoneResolver resolver = new TimeShapeTimeZoneResolver();

	@Test
	@DisplayName("서울 좌표를 Asia/Seoul로 해석한다")
	void resolvesSeoulCoordinatesToAsiaSeoul() {
		assertThat(resolver.resolve(37.5665, 126.9780))
			.contains(ZoneId.of("Asia/Seoul"));
	}

	@Test
	@DisplayName("뉴욕 좌표를 America/New_York으로 해석한다")
	void resolvesNewYorkCoordinatesToAmericaNewYork() {
		assertThat(resolver.resolve(40.7128, -74.0060))
			.contains(ZoneId.of("America/New_York"));
	}

	@Test
	@DisplayName("새로 분리된 Coyhaique 영역을 누락하지 않는다")
	void resolvesNewlySplitCoyhaiqueZone() {
		assertThat(resolver.resolve(-45.5712, -72.0683))
			.contains(ZoneId.of("America/Coyhaique"));
	}

	@Test
	@DisplayName("Nevada 파일의 경계 좌표도 제공된 좌표의 Phoenix 시간대로 해석한다")
	void resolvesNevadaBoundaryCoordinateToPhoenix() {
		assertThat(resolver.resolve(35.12159, -114.59891))
			.contains(ZoneId.of("America/Phoenix"));
	}

	@Test
	@DisplayName("범위를 벗어난 위도는 빈 결과를 반환한다")
	void returnsEmptyForLatitudeOutOfRange() {
		assertThat(resolver.resolve(91.0, 0.0)).isEmpty();
	}

	@Test
	@DisplayName("NaN 좌표는 빈 결과를 반환한다")
	void returnsEmptyForNanCoordinates() {
		assertThat(resolver.resolve(Double.NaN, 126.9780)).isEmpty();
		assertThat(resolver.resolve(37.5665, Double.NaN)).isEmpty();
	}

	@Test
	@DisplayName("NaN 좌표는 시간대 엔진을 호출하지 않는다")
	void skipsTimeZoneEngineForNanCoordinates() {
		TimeZoneEngine engine = mock(TimeZoneEngine.class);
		given(engine.query(anyDouble(), anyDouble()))
			.willThrow(new AssertionError("유효하지 않은 좌표로 엔진을 호출하면 안 된다"));
		TimeShapeTimeZoneResolver guardedResolver = new TimeShapeTimeZoneResolver(engine);

		assertThat(guardedResolver.resolve(Double.NaN, 126.9780)).isEmpty();
		assertThat(guardedResolver.resolve(37.5665, Double.NaN)).isEmpty();
	}

	@Test
	@DisplayName("무한대 좌표는 빈 결과를 반환한다")
	void returnsEmptyForInfiniteCoordinates() {
		assertThat(resolver.resolve(Double.POSITIVE_INFINITY, 126.9780)).isEmpty();
		assertThat(resolver.resolve(Double.NEGATIVE_INFINITY, 126.9780)).isEmpty();
		assertThat(resolver.resolve(37.5665, Double.POSITIVE_INFINITY)).isEmpty();
		assertThat(resolver.resolve(37.5665, Double.NEGATIVE_INFINITY)).isEmpty();
	}

	@Test
	@DisplayName("범위를 벗어난 경도는 빈 결과를 반환한다")
	void returnsEmptyForLongitudeOutOfRange() {
		assertThat(resolver.resolve(0.0, 181.0)).isEmpty();
		assertThat(resolver.resolve(0.0, -181.0)).isEmpty();
	}
}
