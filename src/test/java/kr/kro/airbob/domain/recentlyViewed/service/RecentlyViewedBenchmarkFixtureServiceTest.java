package kr.kro.airbob.domain.recentlyViewed.service;

import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("최근 본 숙소 벤치마크 fixture 서비스 테스트")
class RecentlyViewedBenchmarkFixtureServiceTest {

	@Mock
	private RecentlyViewedService recentlyViewedService;

	private RecentlyViewedBenchmarkFixtureService fixtureService;

	@BeforeEach
	void setUp() {
		fixtureService = new RecentlyViewedBenchmarkFixtureService(recentlyViewedService);
	}

	@Test
	@DisplayName("토큰 검증을 마친 회원은 자신의 최근 본 숙소 fixture를 교체한다")
	void authenticatedMemberCanReplaceOwnFixture() {
		List<Long> accommodationIds = List.of(251L, 252L);

		fixtureService.replaceFixture(7L, accommodationIds);

		verify(recentlyViewedService).replaceRecentlyViewed(7L, accommodationIds);
	}
}
