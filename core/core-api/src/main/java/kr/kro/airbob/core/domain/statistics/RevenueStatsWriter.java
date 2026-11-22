package kr.kro.airbob.core.domain.statistics;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.statistics.repository.DailyRevenueStatsRepository;
import lombok.RequiredArgsConstructor;

/** 결제 거래 원장에서 일자별 사전 집계를 다시 만든다. */
@Component
@RequiredArgsConstructor
public class RevenueStatsWriter {

	private final DailyRevenueStatsRepository repository;

	/** 날짜의 기존 집계 행을 지우고 다시 적재한다(DELETE 후 INSERT...SELECT → 멱등). 거래가 없으면 행도 남지 않는다. */
	public void recompute(LocalDate date) {
		repository.deleteByStatDate(date);
		repository.aggregateForDate(date);
	}
}
