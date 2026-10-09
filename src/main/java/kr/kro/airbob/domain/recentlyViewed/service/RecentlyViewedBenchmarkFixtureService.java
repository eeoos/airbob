package kr.kro.airbob.domain.recentlyViewed.service;

import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.ErrorCode;

@Service
@Profile("nplus1-benchmark")
public class RecentlyViewedBenchmarkFixtureService {

	private final RecentlyViewedService recentlyViewedService;
	private final NamedParameterJdbcTemplate jdbcTemplate;

	public RecentlyViewedBenchmarkFixtureService(RecentlyViewedService recentlyViewedService,
		NamedParameterJdbcTemplate jdbcTemplate) {
		this.recentlyViewedService = recentlyViewedService;
		this.jdbcTemplate = jdbcTemplate;
	}

	public void replaceFixture(Long memberId, List<Long> accommodationIds) {
		// 같은 주소를 공유하거나 주소가 없는 숙소는 주소 N회 조회 기준선을 만들 수 없다.
		// DB는 읽기만 하고, 검증이 끝난 뒤에만 해당 회원의 Redis 기록을 교체한다.
		Long distinctPublishedAddresses = jdbcTemplate.queryForObject("""
			SELECT COUNT(DISTINCT ad.id)
			FROM accommodation a
			JOIN address ad ON ad.id = a.address_id
			WHERE a.id IN (:ids) AND a.status = 'PUBLISHED'
			""", Map.of("ids", accommodationIds), Long.class);
		if (distinctPublishedAddresses == null || distinctPublishedAddresses != accommodationIds.size()) {
			throw new BaseException(ErrorCode.BENCHMARK_RECENTLY_VIEWED_FIXTURE_INVALID);
		}
		recentlyViewedService.replaceRecentlyViewed(memberId, accommodationIds);
	}
}
