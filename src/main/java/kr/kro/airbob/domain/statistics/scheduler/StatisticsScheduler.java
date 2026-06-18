package kr.kro.airbob.domain.statistics.scheduler;

import java.time.LocalDate;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.statistics.service.RevenueStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 일일 매출 사전집계 배치. 매일 새벽 전일자를 재집계(멱등).
@Slf4j
@Component
@RequiredArgsConstructor
public class StatisticsScheduler {

	private final RevenueStatsService revenueStatsService;

	@Scheduled(cron = "0 0 4 * * *")
	public void aggregateDailyRevenue() {
		LocalDate target = LocalDate.now().minusDays(1);
		log.info("일일 매출 사전집계 시작: stat_date={}", target);
		revenueStatsService.recompute(target);
		log.info("일일 매출 사전집계 완료: stat_date={}", target);
	}
}
