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
		public static DailyRevenue from(DailyRevenueRow row) {
			return new DailyRevenue(
				row.getStatDate(),
				nz(row.getGrossAmount()),
				nz(row.getRefundAmount()),
				nz(row.getNetAmount()),
				nz(row.getPaymentCount()),
				nz(row.getRefundCount())
			);
		}

		private static long nz(Long v) {
			return v == null ? 0L : v;
		}
	}

	public record DailyRevenues(
		LocalDate from,
		LocalDate to,
		String source,   // stats | raw
		List<DailyRevenue> items
	) {}
}
