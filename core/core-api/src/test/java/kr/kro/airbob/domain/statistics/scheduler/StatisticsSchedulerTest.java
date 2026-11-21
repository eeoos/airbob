package kr.kro.airbob.domain.statistics.scheduler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import kr.kro.airbob.domain.statistics.service.RevenueStatsService;

@ExtendWith(MockitoExtension.class)
@DisplayName("일일 매출 집계 스케줄러 테스트")
class StatisticsSchedulerTest {

	@Mock
	private RevenueStatsService revenueStatsService;

	@Test
	@DisplayName("UTC 날짜 경계를 기준으로 전일을 집계한다")
	void aggregatesPreviousUtcDate() {
		Clock clock = Clock.fixed(Instant.parse("2099-01-01T00:30:00Z"), ZoneOffset.UTC);
		StatisticsScheduler scheduler = new StatisticsScheduler(revenueStatsService, clock);

		scheduler.aggregateDailyRevenue();

		verify(revenueStatsService).recompute(LocalDate.of(2098, 12, 31));
	}

	@Test
	@DisplayName("스케줄 실행 시간대를 UTC로 고정한다")
	void schedulesInUtc() throws NoSuchMethodException {
		Method method = StatisticsScheduler.class.getMethod("aggregateDailyRevenue");
		Scheduled scheduled = method.getAnnotation(Scheduled.class);

		assertThat(scheduled.zone()).isEqualTo("UTC");
	}
}
