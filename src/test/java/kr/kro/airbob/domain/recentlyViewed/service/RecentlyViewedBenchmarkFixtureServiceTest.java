package kr.kro.airbob.domain.recentlyViewed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("최근 본 숙소 벤치마크 fixture 서비스 테스트")
class RecentlyViewedBenchmarkFixtureServiceTest {

	@Mock
	private RecentlyViewedService recentlyViewedService;
	@Mock
	private NamedParameterJdbcTemplate jdbcTemplate;

	private RecentlyViewedBenchmarkFixtureService fixtureService;

	@BeforeEach
	void setUp() {
		fixtureService = new RecentlyViewedBenchmarkFixtureService(recentlyViewedService, jdbcTemplate);
	}

	@Test
	@DisplayName("토큰 검증을 마친 회원은 자신의 최근 본 숙소 fixture를 교체한다")
	void authenticatedMemberCanReplaceOwnFixture() {
		List<Long> accommodationIds = List.of(251L, 252L);
		when(jdbcTemplate.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(2L);

		fixtureService.replaceFixture(7L, accommodationIds);

		verify(recentlyViewedService).replaceRecentlyViewed(7L, accommodationIds);
	}

	@Test
	@DisplayName("N+1을 재현할 수 없는 데이터는 기존 최근 본 기록을 지우기 전에 거절한다")
	void invalidDatasetDoesNotReplaceHistory() {
		when(jdbcTemplate.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(1L);

		assertThatThrownBy(() -> fixtureService.replaceFixture(7L, List.of(251L, 252L)))
			.isInstanceOfSatisfying(BaseException.class, exception ->
				assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.BENCHMARK_RECENTLY_VIEWED_FIXTURE_INVALID));
		verifyNoInteractions(recentlyViewedService);
	}
}
