package kr.kro.airbob.core.domain.statistics;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RevenueStatsService {

	private final RevenueStatsReader revenueStatsReader;
	private final RevenueStatsWriter revenueStatsWriter;

	// 단일 일자 재집계 (DELETE 후 INSERT...SELECT -> 멱등)
	@Transactional
	public void recompute(LocalDate date) {
		revenueStatsWriter.recompute(date);
	}

	// [from, to] 구간 백필 (날짜별 재집계 반복). 전체 구간이 한 트랜잭션이라 중간 실패 시 모두 롤백된다.
	@Transactional
	public void backfill(LocalDate from, LocalDate to) {
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			revenueStatsWriter.recompute(d);
		}
	}

	// 사전집계 테이블만 조회
	@Transactional(readOnly = true)
	public List<DailyRevenueTotal> getDailyRevenue(LocalDate from, LocalDate to) {
		return revenueStatsReader.readDailyTotals(from, to);
	}
}
