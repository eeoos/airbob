package kr.kro.airbob.domain.reservation.scheduler;

import static kr.kro.airbob.config.SchedulingConfig.RESERVATION_QUOTE_CLEANUP_TASK_SCHEDULER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import kr.kro.airbob.domain.reservation.service.ReservationQuoteCleanupService;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationQuoteCleanupScheduler 테스트")
class ReservationQuoteCleanupSchedulerTest {

	@Mock
	private ReservationQuoteCleanupService cleanupService;

	@Test
	@DisplayName("빈 batch가 나올 때까지 짧은 quote cleanup 트랜잭션을 반복한다")
	void drainsUntilAnEmptyBatch() {
		given(cleanupService.cleanupOneBatch()).willReturn(100, 100, 37, 0);
		ReservationQuoteCleanupScheduler scheduler = new ReservationQuoteCleanupScheduler(cleanupService, 10);

		scheduler.cleanupExpiredQuotes();

		then(cleanupService).should(times(4)).cleanupOneBatch();
		then(cleanupService).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("계속 full batch여도 한 번의 스케줄 실행은 설정된 최대 batch 수에서 멈춘다")
	void stopsAtTheConfiguredBatchLimit() {
		given(cleanupService.cleanupOneBatch()).willReturn(100);
		ReservationQuoteCleanupScheduler scheduler = new ReservationQuoteCleanupScheduler(cleanupService, 3);

		scheduler.cleanupExpiredQuotes();

		then(cleanupService).should(times(3)).cleanupOneBatch();
		then(cleanupService).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("quote cleanup은 hold 만료 cleanup과 분리된 task scheduler에서 실행된다")
	void usesTheReservationQuoteCleanupTaskScheduler() throws NoSuchMethodException {
		Method cleanupMethod = ReservationQuoteCleanupScheduler.class.getMethod("cleanupExpiredQuotes");
		Scheduled scheduled = cleanupMethod.getAnnotation(Scheduled.class);

		assertThat(scheduled).isNotNull();
		assertThat(scheduled.scheduler()).isEqualTo(RESERVATION_QUOTE_CLEANUP_TASK_SCHEDULER);
	}
}
