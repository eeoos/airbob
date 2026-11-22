package kr.kro.airbob.core.domain.statistics;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.statistics.repository.DailyRevenueStatsRepository;
import lombok.RequiredArgsConstructor;

/** 사전 집계 테이블(daily_revenue_stats)만 읽는다. 원장을 실시간으로 조회하지 않는다. */
@Component
@RequiredArgsConstructor
public class RevenueStatsReader {

	private final DailyRevenueStatsRepository repository;

	/** [from, to] 구간의 일자별 합계를 날짜 오름차순으로. 집계 행이 없는 날짜는 빠진다. */
	public List<DailyRevenueTotal> readDailyTotals(LocalDate from, LocalDate to) {
		return repository.findDailyRevenueFromStats(from, to);
	}
}
