package kr.kro.airbob.domain.statistics.dto;

import java.time.LocalDate;
import java.util.List;

public class RevenueStatsResponse {

	public record DailyRevenue(
		LocalDate date,
		long grossAmount,
		long refundAmount,
		long netAmount,
		long paymentCount,
		long refundCount
	) {
	}

	public record DailyRevenues(
		LocalDate from,
		LocalDate to,
		String source,   // stats
		List<DailyRevenue> items
	) {}
}
