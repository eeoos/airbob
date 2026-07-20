package kr.kro.airbob.domain.recentlyViewed.service;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("nplus1-benchmark")
public class RecentlyViewedBenchmarkFixtureService {

	private final RecentlyViewedService recentlyViewedService;

	public RecentlyViewedBenchmarkFixtureService(RecentlyViewedService recentlyViewedService) {
		this.recentlyViewedService = recentlyViewedService;
	}

	public void replaceFixture(Long memberId, List<Long> accommodationIds) {
		recentlyViewedService.replaceRecentlyViewed(memberId, accommodationIds);
	}
}
