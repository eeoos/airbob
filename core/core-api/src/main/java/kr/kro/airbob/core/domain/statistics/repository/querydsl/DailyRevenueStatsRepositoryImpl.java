package kr.kro.airbob.core.domain.statistics.repository.querydsl;

import static kr.kro.airbob.core.domain.statistics.QDailyRevenueStats.*;

import java.time.LocalDate;
import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import kr.kro.airbob.core.domain.statistics.DailyRevenueTotal;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DailyRevenueStatsRepositoryImpl implements DailyRevenueStatsRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	@Override
	public List<DailyRevenueTotal> findDailyRevenueFromStats(LocalDate from, LocalDate to) {
		return queryFactory
			.select(Projections.constructor(DailyRevenueTotal.class,
				dailyRevenueStats.statDate,
				dailyRevenueStats.grossAmount.sum(),
				dailyRevenueStats.refundAmount.sum(),
				dailyRevenueStats.netAmount.sum(),
				// paymentCount/refundCount 는 Integer -> SUM 결과는 BIGINT(Long) 이므로 longValue()로 캐스팅
				dailyRevenueStats.paymentCount.sum().longValue(),
				dailyRevenueStats.refundCount.sum().longValue()))
			.from(dailyRevenueStats)
			.where(dailyRevenueStats.statDate.between(from, to))
			.groupBy(dailyRevenueStats.statDate)
			.orderBy(dailyRevenueStats.statDate.asc())
			.fetch();
	}
}
