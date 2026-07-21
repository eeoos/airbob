package kr.kro.airbob.domain.reservation.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

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
	@DisplayName("스케줄 실행은 cleanup service에 한 번 위임한다")
	void delegatesToCleanupService() {
		given(cleanupService.cleanupExpiredPendingReservations()).willReturn(3);
		ReservationScheduler scheduler = new ReservationScheduler(cleanupService);

		scheduler.cleanupExpiredPendingReservation();

		then(cleanupService).should().cleanupExpiredPendingReservations();
		then(cleanupService).shouldHaveNoMoreInteractions();
	}
}
