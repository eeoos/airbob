package kr.kro.airbob.domain.statistics.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.statistics.dto.RevenueStatsResponse;
import kr.kro.airbob.domain.statistics.repository.DailyRevenueStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RevenueStatsService {

	public static final String SOURCE_RAW = "raw";
	public static final String SOURCE_STATS = "stats";

	private final DailyRevenueStatsRepository repository;

	// 단일 일자 재집계 (DELETE 후 INSERT...SELECT → 멱등)
	@Transactional
	public void recompute(LocalDate date) {
		repository.deleteByStatDate(date);
		repository.aggregateForDate(date);
	}

	// [from, to] 구간 백필 (날짜별 재집계 반복)
	@Transactional
	public void backfill(LocalDate from, LocalDate to) {
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			repository.deleteByStatDate(d);
			repository.aggregateForDate(d);
		}
	}

	// 일자별 매출 조회. source=raw 면 원장 직접 집계(before), 그 외엔 사전집계 테이블(after).
	@Transactional(readOnly = true)
	public RevenueStatsResponse.DailyRevenues getDailyRevenue(LocalDate from, LocalDate to, String source) {
		if (SOURCE_RAW.equalsIgnoreCase(source)) {
			// before: 원장 직접 집계(native, UNION)
			List<RevenueStatsResponse.DailyRevenue> items = repository.findDailyRevenueFromLedgerNaive(from, to).stream()
				.map(RevenueStatsResponse.DailyRevenue::from)
				.toList();
			return new RevenueStatsResponse.DailyRevenues(from, to, SOURCE_RAW, items);
		}

		// after: 사전집계 테이블 일자별 롤업(QueryDSL)
		List<RevenueStatsResponse.DailyRevenue> items = repository.findDailyRevenueFromStats(from, to);
		return new RevenueStatsResponse.DailyRevenues(from, to, SOURCE_STATS, items);
	}
}
