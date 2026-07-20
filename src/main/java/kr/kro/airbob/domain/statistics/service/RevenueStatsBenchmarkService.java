package kr.kro.airbob.domain.statistics.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.kro.airbob.domain.statistics.dto.RevenueStatsResponse;
import kr.kro.airbob.domain.statistics.repository.DailyRevenueStatsRepository;
import lombok.RequiredArgsConstructor;

@Service
@Profile("read-model-benchmark")
@RequiredArgsConstructor
public class RevenueStatsBenchmarkService {

	private static final String SOURCE_RAW = "raw";

	private final DailyRevenueStatsRepository repository;

	@Transactional(readOnly = true)
	public RevenueStatsResponse.DailyRevenues getDailyRevenueBefore(LocalDate from, LocalDate to) {
		List<RevenueStatsResponse.DailyRevenue> items = repository
			.findDailyRevenueFromLedgerNaive(from, to)
			.stream()
			.map(RevenueStatsResponse.DailyRevenue::from)
			.toList();
		return new RevenueStatsResponse.DailyRevenues(from, to, SOURCE_RAW, items);
	}
}
