package kr.kro.airbob.domain.recentlyViewed.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

@DisplayName("최근 본 숙소 벤치마크 fixture 요청 검증 테스트")
class RecentlyViewedBenchmarkRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("1개에서 100개의 서로 다른 양수 숙소 ID만 허용한다")
	void validatesFixtureAccommodationIds() {
		List<Long> oneHundredIds = LongStream.rangeClosed(1, 100).boxed().toList();

		assertThat(validator.validate(new RecentlyViewedBenchmarkRequest.Replace(List.of(251L)))).isEmpty();
		assertThat(validator.validate(new RecentlyViewedBenchmarkRequest.Replace(oneHundredIds))).isEmpty();
		assertThat(validator.validate(new RecentlyViewedBenchmarkRequest.Replace(List.of()))).isNotEmpty();
		assertThat(validator.validate(new RecentlyViewedBenchmarkRequest.Replace(
			LongStream.rangeClosed(1, 101).boxed().toList()
		))).isNotEmpty();
		assertThat(validator.validate(new RecentlyViewedBenchmarkRequest.Replace(List.of(0L)))).isNotEmpty();
		assertThat(validator.validate(new RecentlyViewedBenchmarkRequest.Replace(List.of(251L, 251L)))).isNotEmpty();
	}
}
