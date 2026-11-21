package kr.kro.airbob.domain.settlement.scheduler;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import kr.kro.airbob.domain.settlement.service.SettlementService;

@ExtendWith(MockitoExtension.class)
@DisplayName("월 정산 스케줄러 테스트")
class SettlementSchedulerTest {

	@Mock
	private SettlementService settlementService;

	@Test
	@DisplayName("UTC 월 경계를 기준으로 전월 정산을 생성한다")
	void generatesPreviousUtcMonth() {
		Clock clock = Clock.fixed(Instant.parse("2099-01-01T00:30:00Z"), ZoneOffset.UTC);
		SettlementScheduler scheduler = new SettlementScheduler(settlementService, clock);

		scheduler.generatePreviousMonthSettlement();

		verify(settlementService).generateMonth(YearMonth.of(2098, 12));
	}

	@Test
	@DisplayName("스케줄 실행 시간대를 UTC로 고정한다")
	void schedulesInUtc() throws NoSuchMethodException {
		Method method = SettlementScheduler.class.getMethod("generatePreviousMonthSettlement");
		Scheduled scheduled = method.getAnnotation(Scheduled.class);

		assertThat(scheduled.zone()).isEqualTo("UTC");
	}
}
