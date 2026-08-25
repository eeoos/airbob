package kr.kro.airbob.domain.reservation.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import kr.kro.airbob.domain.reservation.service.ExpiredReservationCleanupService;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationScheduler 테스트")
class ReservationSchedulerTest {

	@Mock
	private ExpiredReservationCleanupService cleanupService;

	@Test
	@DisplayName("스케줄 실행은 빈 batch가 나올 때까지 짧은 cleanup 트랜잭션을 반복한다")
	void drainsUntilAnEmptyBatch() {
		given(cleanupService.cleanupExpiredPendingReservations()).willReturn(100, 100, 37, 0);
		ReservationScheduler scheduler = new ReservationScheduler(cleanupService, 10);

		scheduler.cleanupExpiredPendingReservation();

		then(cleanupService).should(times(4)).cleanupExpiredPendingReservations();
		then(cleanupService).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("계속 full batch여도 한 번의 스케줄 실행은 설정된 횟수에서 멈춘다")
	void stopsAtTheConfiguredBatchLimit() {
		given(cleanupService.cleanupExpiredPendingReservations()).willReturn(100);
		ReservationScheduler scheduler = new ReservationScheduler(cleanupService, 3);

		scheduler.cleanupExpiredPendingReservation();

		then(cleanupService).should(times(3)).cleanupExpiredPendingReservations();
		then(cleanupService).shouldHaveNoMoreInteractions();
	}
}
