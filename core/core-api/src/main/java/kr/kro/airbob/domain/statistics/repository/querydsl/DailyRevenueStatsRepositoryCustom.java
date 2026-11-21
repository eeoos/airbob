package kr.kro.airbob.domain.statistics.repository.querydsl;

import java.time.LocalDate;
import java.util.List;

import kr.kro.airbob.domain.statistics.dto.RevenueStatsResponse;

public interface DailyRevenueStatsRepositoryCustom {

	// 사전집계 테이블을 일자별로 롤업(after). naive(UNION, native)와 달리 QueryDSL로 표현 가능.
	List<RevenueStatsResponse.DailyRevenue> findDailyRevenueFromStats(LocalDate from, LocalDate to);
}
